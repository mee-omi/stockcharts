package com.stockcharts.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI stockChartsOpenApi() {
        return new OpenAPI().info(new Info()
                .title("StockCharts API")
                .version("1.0.0")
                .description("NSE technical charting: instruments, candles, and TA4J indicators."));
    }
}
