package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.wire.EventBatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every gateway instance reads the whole event stream under its own throwaway consumer group, from
 * the tail. It uses the stream to answer commands it sent ({@link PendingCommands}) and to feed
 * live market data to its SSE subscribers ({@link MarketDataHub}).
 */
@Component
@ConditionalOnRole("api")
public class EventStreamListener {

    public static final String LISTENER_ID = "lumina-gateway";

    private static final Logger log = LoggerFactory.getLogger(EventStreamListener.class);

    private final PendingCommands pending;
    private final MarketDataHub hub;

    public EventStreamListener(PendingCommands pending, MarketDataHub hub) {
        this.pending = pending;
        this.hub = hub;
    }

    @KafkaListener(
            id = LISTENER_ID,
            idIsGroup = false,
            groupId = "lumina-gateway-${random.uuid}",
            topics = "${lumina.kafka.events-topic}",
            properties = "auto.offset.reset=latest")
    public void onBatch(byte[] payload) {
        try {
            EventBatch batch = EventBatch.parseFrom(payload);
            pending.complete(batch);
            hub.publish(batch);
        } catch (Exception e) {
            // A bad batch must not wedge the stream for every other caller on this instance.
            log.error("Dropping unreadable event batch", e);
        }
    }
}
