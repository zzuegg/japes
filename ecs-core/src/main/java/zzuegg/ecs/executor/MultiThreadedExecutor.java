package zzuegg.ecs.executor;

import zzuegg.ecs.scheduler.ScheduleGraph;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
    public void execute(ScheduleGraph graph, Consumer<ScheduleGraph.SystemNode> runner) {
        graph.reset();

        while (!graph.isComplete()) {
            var ready = graph.readySystems();
            if (ready.isEmpty()) {
                throw new IllegalStateException("Deadlock: no systems ready but graph not complete");
            }

            if (ready.size() == 1) {
                runner.accept(ready.getFirst());
                graph.complete(ready.getFirst());
            } else {
                var phaser = new Phaser(ready.size());
                var failure = new AtomicReference<Throwable>();
                for (var node : ready) {
                    pool.execute(() -> {
                        try {
                            runner.accept(node);
                        } catch (Throwable t) {
                            // Keep the first failure; subsequent ones are suppressed
                            // onto it so we don't lose diagnostic data.
                            if (!failure.compareAndSet(null, t)) {
                                failure.get().addSuppressed(t);
                            }
                        } finally {
                            synchronized (graph) {
                                graph.complete(node);
                            }
                            phaser.arrive();
                        }
                    });
                }
                phaser.awaitAdvance(0);
                var t = failure.get();
                if (t != null) {
                    if (t instanceof RuntimeException re) throw re;
                    if (t instanceof Error err) throw err;
                    throw new RuntimeException(t);
                }
            }
        }
    }

    @Override
    public void shutdown() {
        if (ownsPool) {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(10, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                    // Best-effort: give cancelled tasks a chance to return.
                    pool.awaitTermination(1, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public ForkJoinPool pool() {
        return pool;
    }
}
