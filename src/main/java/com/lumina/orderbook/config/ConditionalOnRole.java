package com.lumina.orderbook.config;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Enables a bean only when {@code lumina.roles} contains the given role. One image runs as the
 * {@code api} gateway, the {@code engine}, the {@code projector}, or all three (the default).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Conditional(OnRoleCondition.class)
public @interface ConditionalOnRole {

    String value();
}
