package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(With.List.class)
public @interface With {
    Class<? extends Record> value();

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        With[] value();
    }
}
