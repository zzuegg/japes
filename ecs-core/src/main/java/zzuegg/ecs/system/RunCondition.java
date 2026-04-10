package zzuegg.ecs.system;

import java.lang.annotation.*;

/**
 * Marks a no-arg boolean method on a system class as a named run-condition
 * referable from {@link RunIf}. Required so the framework does not have to
 * guess which helper methods are meant as run conditions — any unrelated
 * boolean getter would otherwise be registered (and could shadow a real
 * condition defined in a different system class).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RunCondition {
    /**
     * Optional name override. Defaults to the method name.
     */
    String value() default "";
}
