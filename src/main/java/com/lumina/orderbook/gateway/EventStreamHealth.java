package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.ConditionalOnRole;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * A gateway that has not joined the event stream yet would miss the answer to its first command,
 * so it reports not-ready until partitions are assigned.
 */
@Component("eventStream")
@ConditionalOnRole("api")
public class EventStreamHealth implements HealthIndicator {

    private final KafkaListenerEndpointRegistry registry;

    public EventStreamHealth(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Health health() {
        MessageListenerContainer container = registry.getListenerContainer(EventStreamListener.LISTENER_ID);
        if (container == null || !container.isRunning()) {
            return Health.down().withDetail("reason", "listener not running").build();
        }
        int assigned = container.getAssignedPartitions() == null ? 0 : container.getAssignedPartitions().size();
        return assigned > 0
                ? Health.up().withDetail("partitions", assigned).build()
                : Health.down().withDetail("reason", "no partitions assigned yet").build();
    }
}
