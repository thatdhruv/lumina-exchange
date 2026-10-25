package com.lumina.orderbook.projection;

import com.google.protobuf.InvalidProtocolBufferException;
import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.engine.EngineEvent;
import com.lumina.orderbook.wire.EventBatch;
import com.lumina.orderbook.wire.WireCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the event stream into Postgres. Events can arrive more than once (engine replay, consumer
 * rebalance); an event is applied only if its sequence is beyond the symbol's cursor, so the
 * result is the same however many times a batch is delivered.
 */
@Component
@ConditionalOnRole("projector")
public class Projector {

    private static final Logger log = LoggerFactory.getLogger(Projector.class);

    private final ProjectionRepository repository;
    private final TransactionTemplate transaction;
    private final Counter applied;
    private final Counter duplicates;
    private final Counter gaps;

    public Projector(ProjectionRepository repository, TransactionTemplate transaction, MeterRegistry meters) {
        this.repository = repository;
        this.transaction = transaction;
        this.applied = Counter.builder("lumina.projector.events").tag("result", "applied").register(meters);
        this.duplicates = Counter.builder("lumina.projector.events").tag("result", "duplicate").register(meters);
        this.gaps = Counter.builder("lumina.projector.gaps").register(meters);
    }

    @KafkaListener(
            id = "lumina-projector",
            groupId = "lumina-projector",
            topics = "${lumina.kafka.events-topic}",
            batch = "true")
    public void onEvents(List<byte[]> payloads) throws InvalidProtocolBufferException {
        List<EventBatch> batches = new java.util.ArrayList<>(payloads.size());
        for (byte[] payload : payloads) {
            batches.add(EventBatch.parseFrom(payload));
        }
        transaction.executeWithoutResult(status -> project(batches));
    }

    void project(List<EventBatch> batches) {
        Map<String, Long> cursors = new HashMap<>();
        for (EventBatch batch : batches) {
            String symbol = batch.getSymbol();
            long cursor = cursors.computeIfAbsent(symbol, repository::cursor);
            for (EngineEvent event : WireCodec.events(batch)) {
                if (event.seq() <= cursor) {
                    duplicates.increment();
                    continue;
                }
                if (cursor > 0 && event.seq() != cursor + 1) {
                    gaps.increment();
                    log.warn("Sequence gap for {}: expected {} but got {}", symbol, cursor + 1, event.seq());
                }
                repository.apply(symbol, event);
                applied.increment();
                cursor = event.seq();
            }
            cursors.put(symbol, cursor);
        }
        cursors.forEach(repository::saveCursor);
    }
}
