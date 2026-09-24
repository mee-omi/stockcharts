package com.stockcharts.journal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface JournalRepository extends JpaRepository<JournalEntry, Long> {

    List<JournalEntry> findAllByOrderByExecutedAtDescIdDesc();

    List<JournalEntry> findAllByOrderByExecutedAtAscIdAsc();

    boolean existsByBrokerAndOrderId(String broker, String orderId);
}
