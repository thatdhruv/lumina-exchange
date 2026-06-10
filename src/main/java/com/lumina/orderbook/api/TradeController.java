package com.lumina.orderbook.api;

import com.lumina.orderbook.api.dto.TradeResponse;
import com.lumina.orderbook.service.TradeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TradeController {

    private final TradeQueryService tradeQueryService;

    public TradeController(TradeQueryService tradeQueryService) {
        this.tradeQueryService = tradeQueryService;
    }

    @GetMapping("/trades/{symbol}")
    public List<TradeResponse> getRecentTrades(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "50") int limit) {
        return tradeQueryService.getRecentTrades(symbol, limit);
    }
}
