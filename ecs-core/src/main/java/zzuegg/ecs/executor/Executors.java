package zzuegg.ecs.executor;

import java.util.concurrent.ForkJoinPool;

public final class Executors {

    private Executors() {}

    public static Executor singleThreaded() {
        return new SingleThreadedExecutor();
    }

    public static Executor multiThreaded() {
        return new MultiThreadedExecutor(Runtime.getRuntime().availableProcessors());
    }

    public static Executor multiThreaded(ForkJoinPool pool) {
        return new MultiThreadedExecutor(pool);
    }

    public static Executor fixed(int threads) {
        return new MultiThreadedExecutor(threads);
    }
}
