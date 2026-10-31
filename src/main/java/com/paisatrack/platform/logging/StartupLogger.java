package com.paisatrack.platform.logging;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Startup summary once the app is ready: name, version, profiles, where to open it (local,
 * network, web app, API docs, health, public address) and the database host. No secrets.
 */
@Component
class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent event) {
        log.info(summary(event.getApplicationContext().getEnvironment()));
    }

    @EventListener(ContextClosedEvent.class)
    void stopping() {
        log.info("PaisaTrack stopping");
    }

    static String summary(Environment env) {
        String name = env.getProperty("spring.application.name", "paisatrack");
        String version = env.getProperty("paisatrack.version", "dev");
        String[] active = env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles();
        String scheme = env.getProperty("server.ssl.enabled", Boolean.class, false) ? "https" : "http";
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        String context = trimSlash(env.getProperty("server.servlet.context-path", ""));
        String local = scheme + "://localhost:" + port + context;
        boolean docs = env.getProperty("springdoc.api-docs.enabled", Boolean.class, true);
        String publicUrl = trimSlash(env.getProperty("paisatrack.public-url", ""));

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Profile", String.join(", ", active)});
        rows.add(new String[] {"Local", local + "/"});
        networkAddress().ifPresent(ip -> rows.add(new String[] {"Network", scheme + "://" + ip + ":" + port + context + "/"}));
        rows.add(new String[] {"Web app", local + "/app/"});
        rows.add(new String[] {"API docs", docs ? local + "/swagger-ui.html" : "off"});
        rows.add(new String[] {"Health", local + "/actuator/health"});
        rows.add(new String[] {"Public", publicUrl.isEmpty() ? "not set (APP_PUBLIC_URL)" : publicUrl + "/app/"});
        rows.add(new String[] {"Database", databaseHost(env.getProperty("spring.datasource.url", ""))});

        StringBuilder out = new StringBuilder("PaisaTrack ready: ").append(name).append(' ').append(version);
        String rule = "-".repeat(60);
        out.append('\n').append(rule);
        for (String[] row : rows) {
            out.append(String.format("%n  %-10s %s", row[0], row[1]));
        }
        return out.append('\n').append(rule).toString();
    }

    /** "jdbc:postgresql://db:5432/paisatrack?user=x" becomes "db:5432/paisatrack". */
    static String databaseHost(String jdbcUrl) {
        int start = jdbcUrl.indexOf("//");
        if (start < 0) {
            return "unknown";
        }
        String rest = jdbcUrl.substring(start + 2);
        int query = rest.indexOf('?');
        rest = query >= 0 ? rest.substring(0, query) : rest;
        int at = rest.lastIndexOf('@');
        return at >= 0 ? rest.substring(at + 1) : rest;
    }

    private static Optional<String> networkAddress() {
        try {
            InetAddress address = InetAddress.getLocalHost();
            return address.isLoopbackAddress() ? Optional.empty() : Optional.of(address.getHostAddress());
        } catch (UnknownHostException e) {
            return Optional.empty();
        }
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
