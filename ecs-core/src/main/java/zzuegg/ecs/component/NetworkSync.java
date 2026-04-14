package zzuegg.ecs.component;

import java.lang.annotation.*;

/**
 * Marks a component record for network synchronization. Components with
 * this annotation are included when syncing state between server and clients.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface NetworkSync {}
