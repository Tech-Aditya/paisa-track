package com.paisatrack.modules.currency;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * European Central Bank reference rates via Frankfurter (free, no key). Enabled with
 * {@code paisatrack.fx.provider=frankfurter}; off by default so the app never needs the network.
 */
@Component
@ConditionalOnProperty(name = "paisatrack.fx.provider", havingValue = "frankfurter")
class FrankfurterProvider implements ExchangeRateProvider {

    private final RestClient client;

    FrankfurterProvider(RestClient.Builder builder, @Value("${paisatrack.fx.frankfurter-url}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    @Override
    public String name() {
        return "FRANKFURTER";
    }

    @Override
    public Rates latest() {
        Response body = client.get().uri("/latest?base=EUR").retrieve().body(Response.class);
        if (body == null || body.rates() == null) {
            throw new IllegalStateException("Empty response from the rate provider");
        }
        Map<String, BigDecimal> rates = new LinkedHashMap<>(body.rates());
        return new Rates(body.base() == null ? "EUR" : body.base(), body.date(), rates);
    }

    record Response(String base, LocalDate date, Map<String, BigDecimal> rates) {
    }
}
