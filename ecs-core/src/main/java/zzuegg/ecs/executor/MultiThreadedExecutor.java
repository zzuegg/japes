package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Phaser;

public final class MultiThreadedExecutor implements Executor {

    private final ForkJoinPool pool;
    private final boolean ownsPool;

    public MultiThreadedExecutor(int parallelism) {
        this.pool = new ForkJoinPool(parallelism);
        this.ownsPool = true;
    }

    public MultiThreadedExecutor(ForkJoinPool pool) {
        this.pool = pool;
        this.ownsPool = false;
    }

    @Override
    public void execute(ScheduleGraph graph) {
        graph.reset();

        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Deadlock: no systems ready but graph not complete");
            }

            if (ready.size() == 1) {
                graph.complete(ready.getFirst());
            } else {
                var phaser = new Phaser(ready.size());
                for (var node : ready) {
                    pool.execute(() -> {
                        try {
                            // Actual invocation handled by World
                        } finally {
                            synchronized (graph) {
                                graph.complete(node);
                            }
                            phaser.arrive();
                        }
                    });
                }
                phaser.awaitAdvance(0);
            }
        }
    }

    @Override
    public void shutdown() {
        if (ownsPool) {
            pool.shutdown();
        }
    }

    public ForkJoinPool pool() {
        return pool;
    }
}
