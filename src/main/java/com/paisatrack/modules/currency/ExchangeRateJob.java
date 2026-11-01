package com.paisatrack.modules.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Fetches the provider's rates once a day, when a provider is configured. Safe to repeat. */
@Component
class ExchangeRateJob {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRateJob.class);

    private final CurrencyService service;

    ExchangeRateJob(CurrencyService service) {
        this.service = service;
    }

    @Scheduled(cron = "${paisatrack.fx.refresh-cron}")
    public void refresh() {
        if (!service.providerEnabled()) {
            return;
        }
        try {
            service.refresh();
        } catch (RuntimeException e) {
            log.warn("Job exchange-rates failed error={}", e.getClass().getSimpleName());
        }
    }
}
