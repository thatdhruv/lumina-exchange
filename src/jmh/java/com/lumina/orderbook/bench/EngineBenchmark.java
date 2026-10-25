package com.lumina.orderbook.bench;

import com.lumina.orderbook.engine.EngineCommand;
import com.lumina.orderbook.engine.MatchingEngine;
import com.lumina.orderbook.engine.OrderType;
import com.lumina.orderbook.engine.Side;
import com.lumina.orderbook.engine.TimeInForce;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Engine throughput and latency on a realistic mix: mostly passive limit orders around a moving
 * mid, a quarter marketable, and a third of the resting flow cancelled again.
 *
 * <pre>scripts/bench.sh [jmh args]</pre>
 */
@BenchmarkMode({Mode.Throughput, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 3)
@Fork(1)
public class EngineBenchmark {

    private static final String SYMBOL = "BTC-USD";

    @State(Scope.Thread)
    public static class Flow {
        MatchingEngine engine;
        SplittableRandom random;
        long mid;
        long nextId;
        final String[] accounts = new String[50];
        long[] restingIds;
        int restingCount;

        @Setup(Level.Iteration)
        public void setUp() {
            for (int i = 0; i < accounts.length; i++) {
                accounts[i] = "acct-" + i;
            }
            engine = new MatchingEngine();
            random = new SplittableRandom(42);
            mid = 5_000_000;
            nextId = 1;
            restingIds = new long[200_000];
            restingCount = 0;
            for (int i = 0; i < 20_000; i++) {
                engine.process(next());
            }
        }

        EngineCommand next() {
            if (restingCount > 1_000 && random.nextInt(3) == 0) {
                int idx = random.nextInt(restingCount);
                long id = restingIds[idx];
                restingIds[idx] = restingIds[--restingCount];
                return new EngineCommand.Cancel(SYMBOL, new UUID(0, id), accounts[(int) (id % 50)], 0);
            }
            mid += random.nextInt(-2, 3);
            Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
            boolean marketable = random.nextInt(4) == 0;
            long offset = marketable ? 20 : -random.nextInt(1, 200);
            long price = side == Side.BUY ? mid + offset : mid - offset;
            long id = nextId++;
            if (!marketable && restingCount < restingIds.length) {
                restingIds[restingCount++] = id;
            }
            return new EngineCommand.Place(SYMBOL, new UUID(0, id), accounts[(int) (id % 50)], side, OrderType.LIMIT,
                    TimeInForce.GTC, false, price, random.nextLong(1, 50), 0);
        }
    }

    @Benchmark
    public Object process(Flow flow) {
        return flow.engine.process(flow.next());
    }
}
