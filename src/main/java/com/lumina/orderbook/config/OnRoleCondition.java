package com.lumina.orderbook.config;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.List;
import java.util.Map;

class OnRoleCondition implements Condition {

    private static final List<String> ALL = List.of("api", "engine", "projector");

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
        String wanted = (String) attributes.get("value");
        List<String> roles = Binder.get(context.getEnvironment())
                .bind("lumina.roles", Bindable.listOf(String.class))
                .orElse(ALL);
        return roles.contains(wanted);
    }
}
