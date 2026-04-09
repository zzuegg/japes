package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Where.List.class)
public @interface Where {
    String value();

    @Target(ElementType.PARAMETER)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        Where[] value();
    }
}
