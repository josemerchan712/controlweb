package dev.josemariamerchan.controlweb.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the HTTP checker.
 *
 * @param allowPrivateHosts when {@code false} (the default, and the only safe value in production)
 *                          sites whose host resolves to a loopback, private, link-local or
 *                          otherwise non-public address are rejected. Only {@code dev} and
 *                          {@code test} profiles turn it on.
 */
@ConfigurationProperties("app.checker")
public record CheckerProperties(boolean allowPrivateHosts) {
}
