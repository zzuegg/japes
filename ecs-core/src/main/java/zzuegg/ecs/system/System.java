package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface System {
    // Empty string is the "inherit from SystemSet, else Update" sentinel. Using
    // "Update" as the default made it impossible to tell whether the author
    // explicitly wanted to override a set's stage or just hadn't set one.
    String stage() default "";
    String[] after() default {};
    String[] before() default {};
}
