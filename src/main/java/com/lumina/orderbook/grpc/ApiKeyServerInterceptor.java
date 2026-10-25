package com.lumina.orderbook.grpc;

import com.lumina.orderbook.security.ApiKeyAuthenticator;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/** Resolves {@code x-api-key} metadata to an account; methods that need one check the context. */
@Component
@GlobalServerInterceptor
class ApiKeyServerInterceptor implements ServerInterceptor {

    static final Context.Key<String> ACCOUNT = Context.key("lumina-account");

    private static final Metadata.Key<String> API_KEY =
            Metadata.Key.of("x-api-key", Metadata.ASCII_STRING_MARSHALLER);

    private final ApiKeyAuthenticator authenticator;

    ApiKeyServerInterceptor(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        String account = authenticator.authenticate(headers.get(API_KEY)).orElse(null);
        return Contexts.interceptCall(Context.current().withValue(ACCOUNT, account), call, headers, next);
    }
}
