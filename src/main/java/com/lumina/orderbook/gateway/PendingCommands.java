package com.lumina.orderbook.gateway;

import com.lumina.orderbook.config.ConditionalOnRole;
import com.lumina.orderbook.wire.EventBatch;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Correlates a command sent by this instance with the event batch the engine later emits for it. */
@Component
@ConditionalOnRole("api")
public class PendingCommands {

    private final ConcurrentMap<String, CompletableFuture<EventBatch>> waiting = new ConcurrentHashMap<>();

    public CompletableFuture<EventBatch> register(String commandId) {
        CompletableFuture<EventBatch> future = new CompletableFuture<>();
        waiting.put(commandId, future);
        return future;
    }

    public void complete(EventBatch batch) {
        CompletableFuture<EventBatch> future = waiting.get(batch.getCommandId());
        if (future != null) {
            future.complete(batch);
        }
    }

    public void fail(String commandId, Throwable error) {
        CompletableFuture<EventBatch> future = waiting.get(commandId);
        if (future != null) {
            future.completeExceptionally(error);
        }
    }

    public void forget(String commandId) {
        waiting.remove(commandId);
    }

    public int size() {
        return waiting.size();
    }
}
