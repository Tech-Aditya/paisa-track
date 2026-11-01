package com.paisatrack.modules.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.paisatrack.support.FinanceIntegrationTest;

/** A stand-in provider (no network) to test storing, crossing and overriding provider rates. */
class RateProviderIntegrationTest extends FinanceIntegrationTest {

    static final LocalDate DAY = LocalDate.now(ZoneId.of("Asia/Kolkata")).minusDays(1);

    @TestConfiguration
    static class StubProvider {
        @Bean
        ExchangeRateProvider stubRates() {
            return new ExchangeRateProvider() {
                @Override
                public String name() {
                    return "STUB";
                }

                @Override
                public Rates latest() {
                    return new Rates("EUR", DAY, Map.of("INR", new BigDecimal("90"), "USD", new BigDecimal("1.08"),
                            "XXX", new BigDecimal("1")));
                }
            };
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    /** Provider rates are shared by every user, so remove them to keep other tests independent. */
    @AfterEach
    void removeSharedRates() {
        jdbc.update("delete from exchange_rates where source = 'STUB'");
    }

    @Test
    void providerRatesAreStoredOnceAndCrossedThroughTheirBase() throws Exception {
        Session s = register(uniqueEmail());
        mockMvc.perform(authed(post("/api/v1/exchange-rates/refresh"), s.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("STUB"))
                .andExpect(jsonPath("$.data.stored").value(2));
        mockMvc.perform(authed(post("/api/v1/exchange-rates/refresh"), s.accessToken())).andExpect(status().isOk());
        String listed = mockMvc.perform(getAuthed("/api/v1/exchange-rates?currency=INR", s.accessToken()))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<?>) JsonPath.read(listed, "$.data[?(@.source == 'STUB')]")).hasSize(1);

        // USD -> INR through EUR: 90 / 1.08.
        mockMvc.perform(getAuthed("/api/v1/exchange-rates/convert?amount=108&from=USD&to=INR", s.accessToken()))
                .andExpect(jsonPath("$.data.convertedAmount").value(9000.00))
                .andExpect(jsonPath("$.data.source").value("STUB"))
                .andExpect(jsonPath("$.data.rateDate").value(DAY.toString()));

        // A manual rate on a later day wins.
        postAuthed("/api/v1/exchange-rates", s, body("base", "USD", "quote", "INR", "rate", "84")).andExpect(status().isCreated());
        mockMvc.perform(getAuthed("/api/v1/exchange-rates/convert?amount=100&from=USD&to=INR", s.accessToken()))
                .andExpect(jsonPath("$.data.convertedAmount").value(8400.00))
                .andExpect(jsonPath("$.data.source").value("MANUAL"));
        // Provider rates are shared; manual rates aren't.
        Session other = register(uniqueEmail());
        mockMvc.perform(getAuthed("/api/v1/exchange-rates/convert?amount=108&from=USD&to=INR", other.accessToken()))
                .andExpect(jsonPath("$.data.convertedAmount").value(9000.00));
    }
}
