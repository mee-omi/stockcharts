package com.stockcharts.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Cluster mode ({@code stockcharts.cluster.enabled=true}): {@code @SchedulerLock}-annotated jobs
 * (instrument sync, alert evaluation) run on at most one replica at a time, coordinated through
 * the {@code shedlock} table. With cluster mode off the annotations are inert, which is what a
 * single instance (and the H2 dev/test profiles, which have no shedlock table) wants.
 */
@Configuration
@ConditionalOnProperty(prefix = "stockcharts.cluster", name = "enabled", havingValue = "true")
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
public class ClusterConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
