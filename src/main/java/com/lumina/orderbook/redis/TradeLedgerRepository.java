package com.lumina.orderbook.redis;

import com.lumina.orderbook.event.TradeExecutedEvent;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class TradeLedgerRepository {

    static final String TRADE_KEY_PREFIX = "trade:";
    static final String SYMBOL_INDEX_PREFIX = "trades:";

    private final StringRedisTemplate redisTemplate;

    public TradeLedgerRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void save(TradeExecutedEvent event) {
        String tradeKey = TRADE_KEY_PREFIX + event.id();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("id", event.id().toString());
        fields.put("buyOrderId", event.buyOrderId().toString());
        fields.put("sellOrderId", event.sellOrderId().toString());
        fields.put("symbol", event.symbol());
        fields.put("price", event.price().toPlainString());
        fields.put("quantity", event.quantity().toPlainString());
        fields.put("executedAt", event.executedAt().toString());

        redisTemplate.opsForHash().putAll(tradeKey, fields);
        redisTemplate.opsForZSet().add(
                SYMBOL_INDEX_PREFIX + event.symbol(),
                event.id().toString(),
                event.executedAt().toEpochMilli());
    }

    public List<Map<Object, Object>> findRecentBySymbol(String symbol, int limit) {
        Set<String> tradeIds = redisTemplate.opsForZSet()
                .reverseRange(SYMBOL_INDEX_PREFIX + symbol, 0, limit - 1L);
        if (tradeIds == null || tradeIds.isEmpty()) {
            return List.of();
        }

        List<Map<Object, Object>> trades = new ArrayList<>();
        for (String tradeId : tradeIds) {
            Map<Object, Object> trade = redisTemplate.opsForHash().entries(TRADE_KEY_PREFIX + tradeId);
            if (!trade.isEmpty()) {
                trades.add(trade);
            }
        }
        return trades;
    }
}
