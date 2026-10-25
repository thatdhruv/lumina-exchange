package com.lumina.orderbook.config;

import org.apache.kafka.common.config.TopicConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaAdmin.NewTopics;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaTopicsConfig {

    /**
     * The command log is the system of record, so it is never trimmed: the engine can always rebuild
     * state from the latest snapshot (or from scratch) by replaying it.
     */
    @Bean
    NewTopics exchangeTopics(LuminaProperties props) {
        LuminaProperties.Kafka k = props.kafka();
        return new NewTopics(
                TopicBuilder.name(k.commandsTopic())
                        .partitions(k.partitions())
                        .replicas(k.replicationFactor())
                        .config(TopicConfig.RETENTION_MS_CONFIG, "-1")
                        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(k.minInsyncReplicas()))
                        .build(),
                TopicBuilder.name(k.eventsTopic())
                        .partitions(k.partitions())
                        .replicas(k.replicationFactor())
                        .config(TopicConfig.RETENTION_MS_CONFIG, String.valueOf(7L * 24 * 3600 * 1000))
                        .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, String.valueOf(k.minInsyncReplicas()))
                        .build());
    }

    /** Never skip a batch silently: retry forever with a 1s pause (the projector is idempotent). */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        return new DefaultErrorHandler(new FixedBackOff(1000L, FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
