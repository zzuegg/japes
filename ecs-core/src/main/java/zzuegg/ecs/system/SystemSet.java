package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SystemSet {
    String name();
    String stage() default "Update";
    String[] after() default {};
    String[] before() default {};
}
