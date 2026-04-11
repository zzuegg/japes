package zzuegg.ecs.system;

import java.lang.annotation.*;

/**
 * Method-level annotation declaring that a system wants to see
 * entities that participate in at least one pair of the given
 * relation type. The relation's archetype marker is added to the
 * system's required-components set, so the archetype filter narrows
 * to the markered subset — the body still walks the actual pair data
 * via a {@code PairReader<T>} service parameter.
 *
 * <p>The {@link #role} argument selects <i>which</i> side of the
 * relation the system iterates:
 * <ul>
 *   <li>{@link Role#SOURCE} — default. Filters to entities with at
 *       least one <i>outgoing</i> pair; pair with
 *       {@code reader.fromSource(self)}.</li>
 *   <li>{@link Role#TARGET} — filters to entities with at least one
 *       <i>incoming</i> pair; pair with
 *       {@code reader.withTarget(self)}. This is what lets a
 *       "who is hunting me?" system skip all the prey that aren't
 *       being hunted without the user writing a manual filter.</li>
 *   <li>{@link Role#EITHER} — no archetype narrowing; the annotation
 *       is informational only. Use when a system walks both
 *       directions or when you don't want the filter to exclude
 *       anything.</li>
 * </ul>
 *
 * <p>Repeatable: a system may declare multiple {@code @Pair} entries
 * to require several relation types or roles simultaneously.
 *
 * <p>Example:
 * <pre>{@code
 * @System
 * @Pair(Targeting.class)   // implicit role = SOURCE
 * void applyDamage(
 *     @Read Attacker attacker,
 *     Entity self,
 *     PairReader<Targeting> reader,
 *     Commands cmds
 * ) {
 *     for (var pair : reader.fromSource(self)) {
 *         cmds.addComponent(pair.target(), new IncomingDamage(pair.value().power()));
 *     }
 * }
 *
 * @System
 * @Pair(value = Targeting.class, role = Pair.Role.TARGET)
 * void awareness(
 *     @Read Health h,
 *     Entity self,
 *     PairReader<Targeting> reader
 * ) {
 *     // Only runs on entities that are currently being targeted,
 *     // so the body skips the "count incoming hunters" check for
 *     // every entity that has none.
 *     for (var pair : reader.withTarget(self)) { ... }
 * }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Pair.List.class)
public @interface Pair {
    /** The relation record type whose marker will be required. */
    Class<? extends Record> value();

    /**
     * Which side of the relation {@code self} should be on. See the
     * class-level javadoc for the full semantics. Defaults to
     * {@link Role#SOURCE} so that pre-existing {@code @Pair(T.class)}
     * declarations keep their original "has outgoing pairs" meaning.
     */
    Role role() default Role.SOURCE;

    /**
     * Which side of the pair an entity occupies in a query. Controls
     * which archetype marker the {@link Pair @Pair} annotation adds
     * to the system's required-components set.
     */
    enum Role {
        /** Filter to entities with >= 1 outgoing pair. Current default. */
        SOURCE,
        /** Filter to entities with >= 1 incoming pair (reverse-index hot path). */
        TARGET,
        /**
         * Don't narrow the archetype filter; the annotation is
         * informational only. Useful when a system walks pairs in
         * both directions or reads via an unannotated accompanying
         * query.
         */
        EITHER
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        Pair[] value();
    }
}
