package com.paisatrack.platform.logging;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** One line when the app is ready: profile, port, database host and whether API docs are on. No secrets. */
@Component
class StartupLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupLogger.class);

    @EventListener(ApplicationReadyEvent.class)
    void ready(ApplicationReadyEvent event) {
        Environment env = event.getApplicationContext().getEnvironment();
        String profiles = Arrays.toString(env.getActiveProfiles().length > 0 ? env.getActiveProfiles() : env.getDefaultProfiles());
        String port = env.getProperty("local.server.port", env.getProperty("server.port", "8080"));
        boolean docs = env.getProperty("springdoc.api-docs.enabled", Boolean.class, true);
        log.info("PaisaTrack ready profiles={} port={} database={} apiDocs={}",
                profiles, port, databaseHost(env.getProperty("spring.datasource.url", "")), docs ? "/swagger-ui.html" : "off");
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
}
