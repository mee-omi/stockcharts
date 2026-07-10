package com.stockcharts.screener;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScreenerRepository extends JpaRepository<Screener, String> {
}
