package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface System {
    String stage() default "Update";
    String[] after() default {};
    String[] before() default {};
}
