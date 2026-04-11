# Events

Events are the framework's built-in cross-system mailbox. One system `send`s a record, another (or many) `read`s every record that was sent **during the previous tick**. Events decouple producers from consumers without a shared resource or a side-channel list.

## Declaring an event type

Event payloads are plain Java `record`s that extend nothing special — the type constraint is just `Record`, so every event is automatically immutable and value-like.

```java
public record DamageEvent(Entity target, float amount, DamageType kind) {}
public record LevelUp(Entity who, int newLevel) {}
```

Register each event type on the builder before the world is constructed. Unregistered types will throw `IllegalArgumentException` the first time a system tries to open a reader or writer.

```java
var world = World.builder()
    .addEvent(DamageEvent.class)
    .addEvent(LevelUp.class)
    .addSystem(CombatSystems.class)
    .addSystem(ProgressionSystems.class)
    .build();
```

`addEvent` takes any `Class<? extends Record>`, returns the builder for chaining, and can be called as many times as you like.

## Writers and readers as service parameters

Inside a system, you request an `EventWriter<E>` or `EventReader<E>` parameter and the framework resolves it for you — just like `Commands`, `Res`, or `Local`.

```java
class CombatSystems {

    @System
    void applyDamage(
            @Write Mut<Health> health,
            EventReader<DamageEvent> damageEvents) {
        for (var evt : damageEvents.read()) {
            if (!evt.target().equals(currentEntity())) continue;
            var cur = health.get();
            health.set(new Health(cur.hp() - evt.amount()));
        }
    }

    @System
    void emitOnDeath(@Read Health hp, Entity self, EventWriter<DeathEvent> deaths) {
        if (hp.hp() <= 0f) {
            deaths.send(new DeathEvent(self));
        }
    }
}
```

`EventReader<E>.read()` returns an unmodifiable `List<E>` — iterate it as many times as you like within the same tick. `EventWriter<E>.send(E)` is thread-safe (internally synchronised on the store) so parallel systems writing to the same event type do not corrupt each other.

!!! tip "No per-reader cursor"

    Every reader in a given tick sees the **same** list — there is no per-reader watermark or "unread" marker. If two systems read `DamageEvent`, both observe every event sent last tick. If you need at-most-once delivery, drain into a `Local<Set<Entity>>` keyed by entity id.

## Double-buffered semantics

This is the one rule worth internalising: **events sent in tick N become visible to readers in tick N+1**. The `EventStore` holds two buffers — a *write buffer* that `send` appends to, and a *read buffer* that `read` snapshots. At the very start of every tick, the world calls `eventRegistry.swapAll()`, which promotes every write buffer to the read buffer and installs a fresh empty write buffer:

```
tick N:   sender.send(evt)   → writeBuffer = [evt]
          reader.read()      → readBuffer  = []           (empty — was swapped at start of N)
tick N+1: swap at tick start → readBuffer  = [evt], writeBuffer = []
          reader.read()      → [evt]
tick N+2: swap again         → readBuffer  = [], writeBuffer = []
          reader.read()      → []                         (evt is gone forever)
```

Events that are never read expire at the next swap. If a system gated by `@RunIf` skips a tick, it will miss every event that was in flight for that single tick — plan accordingly, or buffer into a resource for critical events.

!!! warning "Never read events in the same tick they were sent"

    This is the most common mistake. A `postUpdate` system that sends a `LevelUp` will **not** see it in a `last` stage system running in the same tick. They always land one tick later, regardless of stage order.

## Pattern: damage pipeline

A typical flow uses events to keep the combat math in one system and the reactive bookkeeping in another.

```java
public record DamageEvent(Entity target, float amount) {}

@System
void rollDamage(
        @Read Attack attack,
        @Read Target target,
        EventWriter<DamageEvent> out) {
    out.send(new DamageEvent(target.entity(), attack.damage()));
}

@System(after = "rollDamage")
void applyDamage(
        @Write Mut<Health> hp,
        Entity self,
        EventReader<DamageEvent> in) {
    float incoming = 0f;
    for (var e : in.read()) {
        if (e.target().equals(self)) incoming += e.amount();
    }
    if (incoming > 0f) {
        var cur = hp.get();
        hp.set(new Health(cur.hp() - incoming));
    }
}
```

Because `applyDamage` reads from the previous tick's damage events, you effectively get a one-tick delay between the attack and the HP change. For most games that is invisible. If your gameplay needs same-frame application, fold the two systems into one and skip the event bus entirely.

## Pattern: level-up reactions

Events excel when many unrelated systems care about a single moment.

```java
public record LevelUp(Entity who, int newLevel) {}

@System
void checkLevelUp(@Write Mut<Experience> xp, Entity self, EventWriter<LevelUp> out) {
    var cur = xp.get();
    if (cur.current() >= cur.needed()) {
        out.send(new LevelUp(self, cur.level() + 1));
        xp.set(cur.levelUp());
    }
}

@System
void refillOnLevelUp(@Write Mut<Health> hp, Entity self, EventReader<LevelUp> ups) {
    for (var u : ups.read()) {
        if (u.who().equals(self)) hp.set(Health.full());
    }
}

@System
void playLevelUpVfx(EventReader<LevelUp> ups, ResMut<VfxQueue> vfx) {
    for (var u : ups.read()) vfx.get().enqueue(Vfx.LEVEL_UP, u.who());
}
```

Three consumers, one producer, zero coupling between consumer and producer. Add a fourth consumer tomorrow without touching `checkLevelUp`.

## Thread-safety notes

`EventWriter.send` and `EventStore.read` are both `synchronized`, so it is safe for multiple parallel systems to write to the same event type during a stage. Readers iterating a snapshot also hold a stable view — the list returned by `read()` is an unmodifiable copy of the backing array at the moment of the call. Subsequent `send`s from the same tick will not appear, because they go into the separate write buffer.

!!! tip "Events are ordered by send-time"

    Within a single tick, event order reflects the order `send` was called. Across parallel writers on the same event type, the order is the interleaving that the lock happened to produce — don't rely on it for correctness.

## What's next

- [Local system state](10-local-state.md) — persistent per-system scratch that survives across ticks.
- Related basics: [Commands](../basics/08-commands.md), [Resources](../basics/05-resources.md).
