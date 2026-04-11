package zzuegg.ecs.relation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Class-level marker for relation record types. Opts a record into
 * the non-fragmenting pair-storage backend and lets the registry
 * read per-type cleanup policy.
 *
 * <p>Records that carry this annotation must still be registered via
 * {@code World.setRelation} / {@code ComponentRegistry.registerRelation}
 * — the annotation is metadata only, not a replacement for explicit
 * registration.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Relation {

    /**
     * What happens to pairs of this relation type when their target
     * entity is despawned. See {@link CleanupPolicy}.
     */
    CleanupPolicy onTargetDespawn() default CleanupPolicy.RELEASE_TARGET;
}
