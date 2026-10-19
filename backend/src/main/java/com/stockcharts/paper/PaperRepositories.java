package com.stockcharts.paper;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

interface PaperAccountRepository extends JpaRepository<PaperAccount, Short> {
}

interface PaperOrderRepository extends JpaRepository<PaperOrder, String> {
    List<PaperOrder> findByStatusInOrderByPlacedAtAsc(Collection<String> statuses);

    List<PaperOrder> findByTradingDayOrStatusInOrderByPlacedAtDesc(LocalDate day, Collection<String> statuses);
}

interface PaperPositionRepository extends JpaRepository<PaperPosition, PaperPosition.Key> {
}

interface PaperHoldingRepository extends JpaRepository<PaperHolding, String> {
}
