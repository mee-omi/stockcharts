package com.stockcharts;

import com.stockcharts.marketdata.MarketDataClient;
import com.stockcharts.trading.TradingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Applies the real Flyway migrations to PostgreSQL/TimescaleDB and has Hibernate validate every
 * entity against the resulting schema — the check the H2 tests (schema from entities) can't make.
 * Runs only when STOCKCHARTS_TEST_PG_URL is set (CI provides a database service).
 */
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true",
        "stockcharts.live-symbols=",
        "stockcharts.data-service-url=http://localhost:9",
        "stockcharts.alerts.initial-delay=PT1H",
})
@EnabledIfEnvironmentVariable(named = "STOCKCHARTS_TEST_PG_URL", matches = ".+")
class PostgresMigrationTest {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("STOCKCHARTS_TEST_PG_URL"));
        registry.add("spring.datasource.username", () -> env("STOCKCHARTS_TEST_PG_USER", "stockcharts"));
        registry.add("spring.datasource.password", () -> env("STOCKCHARTS_TEST_PG_PASSWORD", "stockcharts"));
    }

    private static String env(String name, String dflt) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? dflt : v;
    }

    @MockBean
    private MarketDataClient marketData;

    @MockBean
    private TradingClient tradingClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allMigrationsApplyAndEntitiesValidate() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank", String.class);
        assertTrue(versions.containsAll(List.of("1", "2", "3", "4", "5", "6", "7", "8")), versions.toString());
        Integer shedlock = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'shedlock'", Integer.class);
        assertEquals(1, shedlock);
    }
}
