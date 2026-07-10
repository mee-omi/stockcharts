package com.stockcharts.indicator;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomIndicatorRepository extends JpaRepository<CustomIndicator, String> {
}
