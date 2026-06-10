package com.lumina.orderbook.kafka;

import com.lumina.orderbook.event.KafkaTopics;
import com.lumina.orderbook.event.TradeExecutedEvent;
import com.lumina.orderbook.redis.TradeLedgerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class TradeExecutedConsumer {

    private static final Logger log = LoggerFactory.getLogger(TradeExecutedConsumer.class);

    private final TradeLedgerRepository tradeLedgerRepository;

    public TradeExecutedConsumer(TradeLedgerRepository tradeLedgerRepository) {
        this.tradeLedgerRepository = tradeLedgerRepository;
    }

    @KafkaListener(
            topics = KafkaTopics.TRADES_EXECUTED,
            groupId = "lumina-trade-ledger",
            containerFactory = "kafkaListenerContainerFactory")
    public void consume(TradeExecutedEvent event) {
        log.debug("Persisting trade {} for symbol {}", event.id(), event.symbol());
        tradeLedgerRepository.save(event);
    }
}
