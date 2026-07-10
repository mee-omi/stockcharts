package com.stockcharts.instrument;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface InstrumentRepository extends JpaRepository<Instrument, String> {

    @Query("""
            SELECT i FROM Instrument i
            WHERE LOWER(i.symbol) LIKE LOWER(CONCAT('%', :q, '%'))
               OR LOWER(i.name)   LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY i.symbol
            """)
    List<Instrument> search(@Param("q") String query);
}
