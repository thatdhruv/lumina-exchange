package com.lumina.orderbook.runtime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** Engine snapshots, one row per command-topic partition. */
@Repository
public class SnapshotStore {

    public record Stored(long kafkaOffset, byte[] payload) {
    }

    private final JdbcClient jdbc;

    public SnapshotStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Stored> load(int partition) {
        return jdbc.sql("SELECT kafka_offset, payload FROM engine_snapshots WHERE partition = :p")
                .param("p", partition)
                .query((rs, i) -> new Stored(rs.getLong("kafka_offset"), rs.getBytes("payload")))
                .optional();
    }

    /** Only ever moves forward, so a stale owner cannot overwrite a newer snapshot. */
    public void save(int partition, long kafkaOffset, byte[] payload) {
        jdbc.sql("""
                        INSERT INTO engine_snapshots (partition, kafka_offset, payload, taken_at)
                        VALUES (:p, :o, :payload, :now)
                        ON CONFLICT (partition) DO UPDATE
                           SET kafka_offset = EXCLUDED.kafka_offset,
                               payload = EXCLUDED.payload,
                               taken_at = EXCLUDED.taken_at
                         WHERE engine_snapshots.kafka_offset < EXCLUDED.kafka_offset
                        """)
                .param("p", partition)
                .param("o", kafkaOffset)
                .param("payload", payload)
                .param("now", Timestamp.from(Instant.now()))
                .update();
    }
}
