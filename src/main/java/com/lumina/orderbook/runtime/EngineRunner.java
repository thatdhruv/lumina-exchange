package com.lumina.orderbook.runtime;

import com.google.protobuf.InvalidProtocolBufferException;
import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.config.LuminaProperties;
import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.engine.MatchingEngine;
import com.lumina.orderbook.wire.WireCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerSeekAware;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Single writer for every command-topic partition assigned to this instance.
 *
 * <p>The command log is the source of truth. State is the latest snapshot plus a replay of the
 * log after it. Replay re-emits events that were already published; that is safe because the
 * engine is deterministic (same sequence numbers, same trade ids) and every consumer dedupes on
 * {@code (symbol, seq)}. Because of that, the consumer-group committed offset is irrelevant: the
 * resume position always comes from the snapshot.
 *
 * <p>Output is sent asynchronously per poll batch and flushed before the batch completes. If any
 * send fails, in-memory state has run ahead of the published events, so the partition is rebuilt
 * from its snapshot and replayed.
 */
@Component
@ConditionalOnRole("engine")
public class EngineRunner implements ConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(EngineRunner.class);

    private static final class Partition {
        final int id;
        final MatchingEngine engine;
        long lastOffset = -1;
        long snapshotOffset = -1;
        long lastSnapshotNanos = System.nanoTime();

        Partition(int id, MatchingEngine engine) {
            this.id = id;
            this.engine = engine;
        }
    }

    private final LuminaProperties props;
    private final SnapshotStore snapshots;
    private final KafkaTemplate<String, byte[]> kafka;
    private final Map<Integer, Partition> partitions = new ConcurrentHashMap<>();
    private final ThreadLocal<ConsumerSeekCallback> seekCallback = new ThreadLocal<>();

    private final Timer batchTimer;
    private final Timer snapshotTimer;
    private final Counter placed;
    private final Counter cancelled;
    private final Counter trades;
    private final Counter poison;
    private final Counter recoveries;

    public EngineRunner(LuminaProperties props,
                        SnapshotStore snapshots,
                        KafkaTemplate<String, byte[]> kafka,
                        MeterRegistry meters) {
        this.props = props;
        this.snapshots = snapshots;
        this.kafka = kafka;
        this.batchTimer = Timer.builder("lumina.engine.batch").description("Time to process one poll batch")
                .register(meters);
        this.snapshotTimer = Timer.builder("lumina.engine.snapshot").description("Time to persist a snapshot")
                .register(meters);
        this.placed = Counter.builder("lumina.engine.commands").tag("type", "place").register(meters);
        this.cancelled = Counter.builder("lumina.engine.commands").tag("type", "cancel").register(meters);
        this.trades = Counter.builder("lumina.engine.trades").register(meters);
        this.poison = Counter.builder("lumina.engine.poison").description("Undecodable commands skipped")
                .register(meters);
        this.recoveries = Counter.builder("lumina.engine.recoveries").description("Rebuilds after a failed batch")
                .register(meters);
        meters.gauge("lumina.engine.partitions", partitions, Map::size);
    }

    // ---- partition ownership -----------------------------------------------------------------

    @Override
    public void registerSeekCallback(ConsumerSeekCallback callback) {
        seekCallback.set(callback);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> assignments, ConsumerSeekCallback callback) {
        for (TopicPartition tp : assignments.keySet()) {
            load(tp, callback);
        }
    }

    @Override
    public void onPartitionsRevoked(Collection<TopicPartition> revoked) {
        for (TopicPartition tp : revoked) {
            Partition p = partitions.remove(tp.partition());
            if (p != null) {
                try {
                    kafka.flush();
                    snapshot(p);
                } catch (RuntimeException e) {
                    log.warn("Final snapshot of partition {} failed; the next owner will replay more", tp.partition(), e);
                }
            }
        }
    }

    private void load(TopicPartition tp, ConsumerSeekCallback callback) {
        Partition p = new Partition(tp.partition(), new MatchingEngine(props.engine().recentOrderIdCapacity()));
        var stored = snapshots.load(tp.partition());
        if (stored.isPresent()) {
            try {
                WireCodec.DecodedSnapshot snap = WireCodec.decodeSnapshot(stored.get().payload());
                p.engine.restore(snap.books());
                p.lastOffset = stored.get().kafkaOffset();
                p.snapshotOffset = p.lastOffset;
                callback.seek(tp.topic(), tp.partition(), p.lastOffset + 1);
                log.info("Partition {} restored from snapshot at offset {}", tp.partition(), p.lastOffset);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Corrupt snapshot for partition " + tp.partition(), e);
            }
        } else {
            callback.seekToBeginning(tp.topic(), tp.partition());
            log.info("Partition {} has no snapshot; replaying the command log from the start", tp.partition());
        }
        partitions.put(tp.partition(), p);
    }

    // ---- processing --------------------------------------------------------------------------

    @KafkaListener(
            id = "lumina-engine",
            groupId = "lumina-engine",
            topics = "${lumina.kafka.commands-topic}",
            batch = "true")
    public void onCommands(List<ConsumerRecord<String, byte[]>> records) {
        long started = System.nanoTime();
        Map<Integer, Long> lastOffsets = new HashMap<>();
        List<CompletableFuture<?>> sends = new ArrayList<>(records.size());
        try {
            for (ConsumerRecord<String, byte[]> record : records) {
                Partition p = partitions.get(record.partition());
                if (p == null) {
                    throw new IllegalStateException("record for unowned partition " + record.partition());
                }
                process(p, record, sends);
                lastOffsets.put(record.partition(), record.offset());
            }
            kafka.flush();
            for (CompletableFuture<?> send : sends) {
                send.get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            recoverFromSnapshot(records, e);
            return;
        }

        lastOffsets.forEach((partition, offset) -> partitions.get(partition).lastOffset = offset);
        for (Integer id : lastOffsets.keySet()) {
            maybeSnapshot(partitions.get(id));
        }
        batchTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
    }

    private void process(Partition p, ConsumerRecord<String, byte[]> record, List<CompletableFuture<?>> sends) {
        WireCodec.DecodedCommand decoded;
        try {
            decoded = WireCodec.decodeCommand(record.value());
        } catch (InvalidProtocolBufferException | IllegalArgumentException e) {
            poison.increment();
            log.error("Skipping undecodable command at {}-{}@{}", record.topic(), record.partition(), record.offset(), e);
            return;
        }
        List<EngineEvent> events = p.engine.process(decoded.command());
        switch (decoded.command()) {
            case com.lumina.orderbook.engine.EngineCommand.Place place -> placed.increment();
            case com.lumina.orderbook.engine.EngineCommand.Cancel cancel -> cancelled.increment();
        }
        for (EngineEvent event : events) {
            if (event instanceof EngineEvent.Trade) {
                trades.increment();
            }
        }
        byte[] payload = WireCodec.toBatch(decoded.commandId(), decoded.command().symbol(), events).toByteArray();
        sends.add(kafka.send(props.kafka().eventsTopic(), decoded.command().symbol(), payload));
    }

    private void recoverFromSnapshot(List<ConsumerRecord<String, byte[]>> records, Exception cause) {
        recoveries.increment();
        log.error("Batch failed; rebuilding affected partitions from their snapshots", cause);
        ConsumerSeekCallback callback = seekCallback.get();
        records.stream().map(ConsumerRecord::partition).distinct().forEach(partition -> {
            var tp = new TopicPartition(records.getFirst().topic(), partition);
            load(tp, callback);
        });
        try {
            Thread.sleep(Duration.ofSeconds(1));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void maybeSnapshot(Partition p) {
        long processedSince = p.lastOffset - p.snapshotOffset;
        if (processedSince <= 0) {
            return;
        }
        boolean enoughCommands = processedSince >= props.engine().snapshotEveryCommands();
        boolean stale = System.nanoTime() - p.lastSnapshotNanos >= props.engine().snapshotMaxInterval().toNanos();
        if (enoughCommands || stale) {
            snapshot(p);
        }
    }

    /** Callers guarantee all output up to {@code lastOffset} has been acknowledged by Kafka. */
    private void snapshot(Partition p) {
        if (p.lastOffset < 0 || p.lastOffset == p.snapshotOffset) {
            return;
        }
        long started = System.nanoTime();
        byte[] payload = WireCodec.encodeSnapshot(p.id, p.lastOffset, System.currentTimeMillis(), p.engine.snapshot());
        snapshots.save(p.id, p.lastOffset, payload);
        p.snapshotOffset = p.lastOffset;
        p.lastSnapshotNanos = System.nanoTime();
        snapshotTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        log.debug("Snapshot of partition {} at offset {} ({} bytes)", p.id, p.lastOffset, payload.length);
    }
}
