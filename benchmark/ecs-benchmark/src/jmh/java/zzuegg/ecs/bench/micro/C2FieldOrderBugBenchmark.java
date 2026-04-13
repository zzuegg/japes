package zzuegg.ecs.bench.micro;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * Minimal reproduction of a suspected C2 escape analysis bug where
 * record field ordering affects scalar replacement.
 *
 * <p>Both records have the exact same 4 field types (int, float, double, long)
 * but in different order. The computation is identical — just field access
 * and arithmetic. If EA works correctly, both should allocate 0 B/op.
 *
 * <p>Run:
 * <pre>
 * ./gradlew :benchmark:ecs-benchmark:jmhJar
 * java --enable-preview -jar benchmark/ecs-benchmark/build/libs/ecs-benchmark-*-jmh.jar \
 *   "C2FieldOrderBug" -f 1 -wi 10 -i 5 -w 2s -r 2s -prof gc
 * </pre>
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class C2FieldOrderBugBenchmark {

    // Same 4 types, different order
    public record MixedA(int i, float f, double d, long l) {}
    public record MixedB(double d, long l, int i, float f) {}
    // Control: all same type
    public record AllLong(long a, long b, long c, long d) {}
    // Another ordering
    public record MixedC(long l, double d, float f, int i) {}
    // Widths grouped: 32-bit first, then 64-bit
    public record MixedD(int i, float f, long l, double d) {}

    @Param({"10000"})
    int count;

    int[] data;

    @Setup
    public void setup() {
        data = new int[count];
        for (int i = 0; i < count; i++) data[i] = i;
    }

    @Benchmark
    public void mixedA_int_float_double_long(Blackhole bh) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            var r = new MixedA(data[i], data[i] * 1.1f, data[i] * 2.2, data[i] * 3L);
            sum += r.i() + (long) r.f() + (long) r.d() + r.l();
        }
        bh.consume(sum);
    }

    @Benchmark
    public void mixedB_double_long_int_float(Blackhole bh) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            var r = new MixedB(data[i] * 2.2, data[i] * 3L, data[i], data[i] * 1.1f);
            sum += (long) r.d() + r.l() + r.i() + (long) r.f();
        }
        bh.consume(sum);
    }

    @Benchmark
    public void mixedC_long_double_float_int(Blackhole bh) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            var r = new MixedC(data[i] * 3L, data[i] * 2.2, data[i] * 1.1f, data[i]);
            sum += r.l() + (long) r.d() + (long) r.f() + r.i();
        }
        bh.consume(sum);
    }

    @Benchmark
    public void mixedD_int_float_long_double(Blackhole bh) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            var r = new MixedD(data[i], data[i] * 1.1f, data[i] * 3L, data[i] * 2.2);
            sum += r.i() + (long) r.f() + r.l() + (long) r.d();
        }
        bh.consume(sum);
    }

    @Benchmark
    public void allLong_control(Blackhole bh) {
        long sum = 0;
        for (int i = 0; i < count; i++) {
            var r = new AllLong(data[i], data[i] * 2L, data[i] * 3L, data[i] * 4L);
            sum += r.a() + r.b() + r.c() + r.d();
        }
        bh.consume(sum);
    }
}
