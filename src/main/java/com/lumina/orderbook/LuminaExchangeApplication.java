package com.lumina.orderbook;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LuminaExchangeApplication {

    public static void main(String[] args) {
        SpringApplication.run(LuminaExchangeApplication.class, args);
    }
}
