package com.lumina.orderbook.security;

import com.lumina.orderbook.config.LuminaProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps an API key to the account that owns it. Keys are held as SHA-256 digests, so the lookup
 * never compares the secret itself and a heap dump does not reveal configured keys.
 */
@Component
public class ApiKeyAuthenticator {

    private final Map<String, String> accountByDigest = new HashMap<>();

    public ApiKeyAuthenticator(LuminaProperties props) {
        for (LuminaProperties.ApiKey key : props.security().apiKeys()) {
            accountByDigest.put(digest(key.key()), key.account());
        }
    }

    public Optional<String> authenticate(String presentedKey) {
        if (presentedKey == null || presentedKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(accountByDigest.get(digest(presentedKey)));
    }

    private static String digest(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
