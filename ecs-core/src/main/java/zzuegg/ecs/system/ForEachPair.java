package zzuegg.ecs.system;

import java.lang.annotation.*;

/**
 * Method-level annotation opting a system into per-pair iteration
 * for a relation type. Alternative to {@code @Pair(T.class)} that
 * flips the iteration model:
 *
 * <ul>
 *   <li>{@code @Pair} — system called <em>once per entity</em> that
 *       carries at least one pair; the user body walks the entity's
 *       pairs via {@code reader.fromSource(self)} /
 *       {@code reader.withTarget(self)}.</li>
 *   <li>{@code @ForEachPair} — system called <em>once per live
 *       pair</em>. No walker needed, no
 *       {@code reader.fromSource}/{@code withTarget} call. Parameters
 *       bind directly to source-side components (default), target-
 *       side components (opt-in with {@code @FromTarget}), the
 *       relation payload, and source/target entity ids.</li>
 * </ul>
 *
 * <p>The two are mutually exclusive on a single method — declaring
 * both on the same system is rejected at parse time.
 *
 * <p>Signature conventions for a
 * {@code @ForEachPair(Hunting.class)} method:
 * <pre>{@code
 * @System
 * @ForEachPair(Hunting.class)
 * void pursuit(
 *     @Read Position sourcePos,                // source's Position
 *     @Write Mut<Velocity> sourceVel,          // source's Velocity (writable)
 *     @FromTarget @Read Position targetPos,    // target's Position (read-only)
 *     Hunting hunting,                         // relation payload, type-matched
 *     Entity source,                           // source entity (default)
 *     @FromTarget Entity target,               // target entity (opt-in)
 *     ResMut<Counters> counters                // service param, unchanged
 * ) { ... }
 * }</pre>
 *
 * <p>Rules:
 * <ul>
 *   <li>{@code @Read}/{@code @Write} default to source-side.</li>
 *   <li>{@code @FromTarget} on a component or {@code Entity}
 *       parameter switches it to target-side.</li>
 *   <li>The relation payload binds by type match against the
 *       annotation's {@code value()}; no annotation needed.</li>
 *   <li>{@code @FromTarget @Write} is rejected for v1 — write
 *       conflicts between pairs sharing a target are ambiguous.</li>
 *   <li>Service params ({@code Commands}, {@code Res},
 *       {@code ResMut}, {@code ComponentReader}, {@code World})
 *       work unchanged.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ForEachPair {
    /** The relation record type that drives iteration. */
    Class<? extends Record> value();
}
