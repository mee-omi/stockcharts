package com.stockcharts.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the REST API. The app is served same-origin (nginx / the Vite proxy), so by default no
 * cross-origin callers are allowed; list extra origins in {@code stockcharts.allowed-origins}.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final AppProperties props;

    public CorsConfig(AppProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (props.getAllowedOrigins().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOrigins(props.getAllowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }
}
