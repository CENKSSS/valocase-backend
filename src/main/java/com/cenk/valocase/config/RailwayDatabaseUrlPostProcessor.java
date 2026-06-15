package com.cenk.valocase.config;

import java.util.Collections;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Normalizes Railway's {@code DATABASE_URL} into a JDBC URL.
 *
 * Railway exposes the database URL in libpq form
 * ({@code postgresql://user:password@host:5432/database}), which the PostgreSQL
 * JDBC driver / Hikari cannot consume. This processor rewrites it to
 * {@code jdbc:postgresql://host:5432/database} and publishes it as
 * {@code spring.datasource.url} before the datasource is created. Credentials
 * are intentionally dropped from the URL — they are supplied separately via
 * {@code DATABASE_USERNAME} / {@code DATABASE_PASSWORD}.
 *
 * It is a no-op when {@code DATABASE_URL} is absent (local dev) or already a
 * {@code jdbc:} URL, so it changes nothing outside of Railway-style deployments.
 */
public class RailwayDatabaseUrlPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String raw = environment.getProperty("DATABASE_URL");
        if (raw == null) {
            return;
        }
        raw = raw.trim();
        if (raw.isEmpty() || raw.startsWith("jdbc:")) {
            return;
        }
        if (!(raw.startsWith("postgresql://") || raw.startsWith("postgres://"))) {
            return;
        }

        String afterScheme = raw.substring(raw.indexOf("://") + 3);
        // Drop any "user:password@" userinfo; credentials come from DATABASE_USERNAME/PASSWORD.
        int at = afterScheme.lastIndexOf('@');
        String hostAndDatabase = (at >= 0) ? afterScheme.substring(at + 1) : afterScheme;

        String jdbcUrl = "jdbc:postgresql://" + hostAndDatabase;

        environment.getPropertySources().addFirst(new MapPropertySource(
                "railwayDatabaseUrl",
                Collections.singletonMap("spring.datasource.url", jdbcUrl)));
    }
}
