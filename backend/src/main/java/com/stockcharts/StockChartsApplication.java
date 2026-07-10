package com.stockcharts;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class StockChartsApplication {
    public static void main(String[] args) {
        SpringApplication.run(StockChartsApplication.class, args);
    }
}
