package com.paisatrack.platform.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Serves the static web app from /app (classpath:static/app). The UI holds no secrets; it calls the API. */
@Configuration(proxyBeanMethods = false)
class WebUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/app/");
        registry.addRedirectViewController("/app", "/app/");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
        registry.addViewController("/favicon.ico").setViewName("forward:/favicon.svg");
    }
}
