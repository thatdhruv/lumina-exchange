package com.lumina.orderbook.service;

import com.lumina.orderbook.api.dto.TradeResponse;
import com.lumina.orderbook.redis.TradeLedgerRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TradeQueryService {

    private static final Logger log = LoggerFactory.getLogger(TradeQueryService.class);

    private final TradeLedgerRepository tradeLedgerRepository;

    public TradeQueryService(TradeLedgerRepository tradeLedgerRepository) {
        this.tradeLedgerRepository = tradeLedgerRepository;
    }

    @CircuitBreaker(name = "redis-ledger", fallbackMethod = "getRecentTradesFallback")
    public List<TradeResponse> getRecentTrades(String symbol, int limit) {
        List<Map<Object, Object>> entries = tradeLedgerRepository.findRecentBySymbol(symbol, limit);
        return entries.stream().map(TradeResponse::fromRedisHash).toList();
    }

    @SuppressWarnings("unused")
    private List<TradeResponse> getRecentTradesFallback(String symbol, int limit, Throwable throwable) {
        log.warn("Redis ledger unavailable for symbol {} — returning empty list: {}", symbol, throwable.getMessage());
        return List.of();
    }
}
