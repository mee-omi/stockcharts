package com.stockcharts.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;


/** Reactive WebClient pointed at the Python market-data service. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class WebClientConfig {

    @Bean
    public WebClient marketDataWebClient(AppProperties props) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(props.getDataServiceTimeout());
        return WebClient.builder()
                .baseUrl(props.getDataServiceUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // Allow large historical payloads (default is 256 KiB).
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }
}
