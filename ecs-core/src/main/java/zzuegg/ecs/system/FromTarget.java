package zzuegg.ecs.system;

import java.lang.annotation.*;

/**
 * Parameter-level opt-in marking a system input as target-side in a
 * {@code @ForEachPair} system. By default every parameter in a
 * per-pair iteration system binds against the source entity of
 * each pair; {@code @FromTarget} flips a parameter to resolve
 * against the target entity instead.
 *
 * <p>Applicable to:
 * <ul>
 *   <li>{@code @Read Component} — target-side read</li>
 *   <li>{@code Entity} — target entity id (bound per pair)</li>
 * </ul>
 *
 * <p>Not applicable to {@code @Write Mut<T>} — target-side writes
 * are forbidden in v1 because pairs sharing a target have ambiguous
 * write-conflict semantics (two predators hunting the same prey
 * each writing its Health). The parser rejects such declarations
 * at plan build.
 *
 * <p>Has no effect on regular {@code @System} or {@code @Pair}
 * systems (which don't distinguish source/target at the parameter
 * level). It's strictly an input to the {@code @ForEachPair}
 * dispatch path.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface FromTarget {
}
