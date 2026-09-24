package com.stockcharts.alert;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByStatus(Alert.Status status);

    List<Alert> findAllByOrderByCreatedAtDesc();
}
