package com.lumina.orderbook.instrument;

import com.lumina.orderbook.config.LuminaProperties;
import com.lumina.orderbook.gateway.GatewayException;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class Instruments {

    private final Map<String, Instrument> bySymbol = new LinkedHashMap<>();

    public Instruments(LuminaProperties properties) {
        for (LuminaProperties.InstrumentConfig c : properties.instruments()) {
            bySymbol.put(c.symbol(), new Instrument(c.symbol(), c.tickSize(), c.lotSize(), c.minQuantity(), c.maxQuantity()));
        }
    }

    public Instrument require(String symbol) {
        Instrument instrument = bySymbol.get(symbol);
        if (instrument == null) {
            throw GatewayException.invalid("UNKNOWN_SYMBOL", "unknown symbol " + symbol);
        }
        return instrument;
    }

    public Collection<Instrument> all() {
        return bySymbol.values();
    }
}
