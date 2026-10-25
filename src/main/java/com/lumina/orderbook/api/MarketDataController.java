package com.lumina.orderbook.api;

import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.gateway.MarketDataHub;
import com.lumina.orderbook.gateway.OrderGateway;
import com.lumina.orderbook.instrument.Instrument;
import com.lumina.orderbook.instrument.Instruments;
import com.lumina.orderbook.view.BookView;
import com.lumina.orderbook.view.TradeView;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@ConditionalOnRole("api")
public class MarketDataController {

    private final OrderGateway gateway;
    private final MarketDataHub hub;
    private final Instruments instruments;

    public MarketDataController(OrderGateway gateway, MarketDataHub hub, Instruments instruments) {
        this.gateway = gateway;
        this.hub = hub;
        this.instruments = instruments;
    }

    @GetMapping("/instruments")
    public Collection<Instrument> instruments() {
        return instruments.all();
    }

    @GetMapping("/orderbook/{symbol}")
    public BookView orderBook(@PathVariable String symbol, @RequestParam(defaultValue = "10") int depth) {
        return gateway.orderBook(symbol, depth);
    }

    @GetMapping("/trades/{symbol}")
    public List<TradeView> trades(@PathVariable String symbol, @RequestParam(defaultValue = "50") int limit) {
        return gateway.recentTrades(symbol, limit);
    }

    /** Live feed: a {@code snapshot} on connect, then {@code book} and {@code trade} events. */
    @GetMapping(value = "/stream/{symbol}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String symbol) {
        return hub.subscribe(symbol);
    }
}
