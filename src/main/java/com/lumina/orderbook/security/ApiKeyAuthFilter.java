package com.lumina.orderbook.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Authenticates {@code X-API-Key}; the account becomes the principal name. */
class ApiKeyAuthFilter extends OncePerRequestFilter {

    static final String HEADER = "X-API-Key";

    private final ApiKeyAuthenticator authenticator;

    ApiKeyAuthFilter(ApiKeyAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        authenticator.authenticate(request.getHeader(HEADER)).ifPresent(account ->
                SecurityContextHolder.getContext().setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(account, null,
                                AuthorityUtils.createAuthorityList("ROLE_TRADER"))));
        chain.doFilter(request, response);
    }
}
