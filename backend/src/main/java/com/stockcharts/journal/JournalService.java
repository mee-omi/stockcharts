package com.stockcharts.journal;

import com.stockcharts.trading.TradingClient;
import com.stockcharts.trading.dto.OrderDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Maintains the trade journal: fills are recorded when orders placed through the app complete,
 * and {@link #sync()} back-fills anything completed at the broker since (e.g. a limit order that
 * filled later, or a trade placed in the broker's own app).
 */
@Service
public class JournalService {

    private static final Logger log = LoggerFactory.getLogger(JournalService.class);

    private final JournalRepository repository;
    private final TradingClient trading;

    public JournalService(JournalRepository repository, TradingClient trading) {
        this.repository = repository;
        this.trading = trading;
    }

    public record ManualEntry(String symbol, String exchange, JournalEntry.Side side, String product,
                              Integer quantity, Double price, Long executedAt, String strategy, String notes) {
    }

    public record Annotation(String strategy, String notes) {
    }

    public List<JournalEntry> list() {
        return repository.findAllByOrderByExecutedAtDescIdDesc();
    }

    /**
     * Journal a completed order; a no-op for unfilled orders or ones already recorded. Not
     * transactional on purpose: a unique-key race must not poison a surrounding transaction.
     */
    public boolean recordIfFilled(OrderDto order, String broker, String tag, JournalEntry.Source source) {
        if (order == null || !"COMPLETE".equalsIgnoreCase(order.status()) || order.filledQuantity() <= 0
                || order.averagePrice() <= 0) {
            return false;
        }
        if (order.orderId() != null && repository.existsByBrokerAndOrderId(broker, order.orderId())) {
            return false;
        }
        JournalEntry.Side side;
        try {
            side = JournalEntry.Side.valueOf(order.transactionType().toUpperCase());
        } catch (Exception e) {
            return false;
        }
        Instant at = order.timestamp() > 0 ? Instant.ofEpochMilli(order.timestamp()) : Instant.now();
        try {
            repository.save(new JournalEntry(order.orderId(), broker, order.symbol().toUpperCase(),
                    order.exchange() == null ? "NSE" : order.exchange(), side,
                    order.product() == null ? "CNC" : order.product().toUpperCase(),
                    order.filledQuantity(), order.averagePrice(), at, tag, source));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false; // raced with a concurrent sync
        }
    }

    /** Pull the broker's order book and journal every completed order not yet recorded. */
    public int sync() {
        String broker = activeBroker();
        List<OrderDto> orders = trading.getOrders();
        int added = 0;
        if (orders != null) {
            for (OrderDto o : orders) {
                if (recordIfFilled(o, broker, null, JournalEntry.Source.SYNC)) {
                    added++;
                }
            }
        }
        log.info("Journal sync added {} trade(s) from broker {}", added, broker);
        return added;
    }

    public String activeBroker() {
        try {
            var info = trading.getBroker();
            return info != null && info.active() != null ? info.active() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Transactional
    public JournalEntry addManual(ManualEntry m) {
        if (m.symbol() == null || m.symbol().isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (m.side() == null) {
            throw new IllegalArgumentException("side (BUY/SELL) is required");
        }
        if (m.quantity() == null || m.quantity() < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        if (m.price() == null || !(m.price() > 0)) {
            throw new IllegalArgumentException("price must be positive");
        }
        String product = m.product() == null || m.product().isBlank() ? "CNC" : m.product().toUpperCase();
        if (!List.of("CNC", "MIS", "NRML").contains(product)) {
            throw new IllegalArgumentException("product must be CNC, MIS or NRML");
        }
        Instant at = m.executedAt() != null ? Instant.ofEpochMilli(m.executedAt()) : Instant.now();
        JournalEntry e = new JournalEntry(null, "manual", m.symbol().trim().toUpperCase(),
                m.exchange() == null || m.exchange().isBlank() ? "NSE" : m.exchange().toUpperCase(),
                m.side(), product, m.quantity(), m.price(), at, null, JournalEntry.Source.MANUAL);
        e.setStrategy(trim(m.strategy(), 64));
        e.setNotes(trim(m.notes(), 2000));
        return repository.save(e);
    }

    @Transactional
    public JournalEntry annotate(long id, Annotation a) {
        JournalEntry e = repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Journal entry not found: " + id));
        e.setStrategy(trim(a.strategy(), 64));
        e.setNotes(trim(a.notes(), 2000));
        return repository.save(e);
    }

    @Transactional
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new NoSuchElementException("Journal entry not found: " + id);
        }
        repository.deleteById(id);
    }

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
