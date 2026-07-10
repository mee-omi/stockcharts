package com.stockcharts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Binds the {@code stockcharts.*} settings from application.yml. */
@ConfigurationProperties(prefix = "stockcharts")
public class AppProperties {

    private String dataServiceUrl = "http://localhost:8000";
    private List<String> liveSymbols = List.of("RELIANCE", "TCS");
    private int backfillDays = 5;
    private final Screener screener = new Screener();

    /** Screener tuning (bounds the heavy "all NSE" scans). */
    public static class Screener {
        private int maxUniverse = 500;
        private int parallelism = 8;
        private int resultLimit = 200;

        public int getMaxUniverse() {
            return maxUniverse;
        }

        public void setMaxUniverse(int maxUniverse) {
            this.maxUniverse = maxUniverse;
        }

        public int getParallelism() {
            return parallelism;
        }

        public void setParallelism(int parallelism) {
            this.parallelism = parallelism;
        }

        public int getResultLimit() {
            return resultLimit;
        }

        public void setResultLimit(int resultLimit) {
            this.resultLimit = resultLimit;
        }
    }

    public Screener getScreener() {
        return screener;
    }

    public String getDataServiceUrl() {
        return dataServiceUrl;
    }

    public void setDataServiceUrl(String dataServiceUrl) {
        this.dataServiceUrl = dataServiceUrl;
    }

    public List<String> getLiveSymbols() {
        return liveSymbols;
    }

    public void setLiveSymbols(List<String> liveSymbols) {
        this.liveSymbols = liveSymbols;
    }

    public int getBackfillDays() {
        return backfillDays;
    }

    public void setBackfillDays(int backfillDays) {
        this.backfillDays = backfillDays;
    }
}
