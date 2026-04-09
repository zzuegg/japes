package zzuegg.ecs.system;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(Without.List.class)
public @interface Without {
    Class<? extends Record> value();

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface List {
        Without[] value();
    }
}
