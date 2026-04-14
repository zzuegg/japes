package zzuegg.ecs.component;

import java.lang.annotation.*;

/**
 * Marks a component record for persistence. Components with this annotation
 * are included in save/load operations by default.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Persistent {}
