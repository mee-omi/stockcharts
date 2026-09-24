package com.stockcharts.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stockcharts.auth.SignedTokens;
import com.stockcharts.journal.JournalRepository;
import com.stockcharts.journal.PortfolioReport;
import com.stockcharts.quote.QuoteService;
import com.stockcharts.settings.AppSetting;
import com.stockcharts.settings.AppSettingRepository;
import com.stockcharts.trading.TradingClient;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PlaceOrderRequest;
import com.stockcharts.trading.dto.PositionDto;
import com.stockcharts.trading.dto.PositionsDto;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server-side order safeguards. Every order is assessed against the configured limits; orders that
 * reduce an existing position are always allowed (so the kill switch and the loss limit can never
 * trap you in a position). For live brokers each order also needs a confirmation token from
 * {@link #preview}: signed, bound to the exact order and broker, valid for 60 seconds, single use.
 */
@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);
    static final String SETTINGS_KEY = "risk.settings";
    static final String CONFIRM_PURPOSE = "order-confirm";
    static final Duration CONFIRM_TTL = Duration.ofSeconds(60);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record Assessment(String broker, boolean live, Double price, Double estimatedValue, boolean reducing,
                             int ordersToday, Double dayPnl, List<String> violations, List<String> warnings) {
    }

    public record Preview(Assessment assessment, boolean confirmationRequired, String confirmationToken,
                          Instant expiresAt) {
    }

    public record RiskState(RiskSettings settings, String broker, int ordersToday, Double dayPnl,
                            boolean lossLimitReached) {
    }

    private final AppSettingRepository settingsRepo;
    private final TradingClient trading;
    private final QuoteService quotes;
    private final JournalRepository journal;
    private final SignedTokens tokens;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;
    private final Cache<String, Boolean> usedTokens = Caffeine.newBuilder()
            .expireAfterWrite(CONFIRM_TTL.multipliedBy(2)).maximumSize(10_000).build();

    public RiskService(AppSettingRepository settingsRepo, TradingClient trading, QuoteService quotes,
                       JournalRepository journal, SignedTokens tokens, ObjectMapper mapper, MeterRegistry meters) {
        this.settingsRepo = settingsRepo;
        this.trading = trading;
        this.quotes = quotes;
        this.journal = journal;
        this.tokens = tokens;
        this.mapper = mapper;
        this.meters = meters;
    }

    // ---- settings ----

    public RiskSettings settings() {
        return settingsRepo.findById(SETTINGS_KEY).map(s -> {
            try {
                return mapper.readValue(s.getValue(), RiskSettings.class);
            } catch (Exception e) {
                log.warn("Stored risk settings unreadable; using defaults: {}", e.getMessage());
                return RiskSettings.defaults();
            }
        }).orElse(RiskSettings.defaults());
    }

    @Transactional
    public RiskSettings update(RiskSettings s) {
        requirePositive(s.maxQuantityPerOrder(), "maxQuantityPerOrder");
        requirePositive(s.maxOrderValue(), "maxOrderValue");
        requirePositive(s.maxOrdersPerDay(), "maxOrdersPerDay");
        requirePositive(s.maxDailyLoss(), "maxDailyLoss");
        try {
            String json = mapper.writeValueAsString(s);
            AppSetting row = settingsRepo.findById(SETTINGS_KEY).orElse(null);
            if (row == null) {
                settingsRepo.save(new AppSetting(SETTINGS_KEY, json));
            } else {
                row.setValue(json);
                settingsRepo.save(row);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not save risk settings", e);
        }
        log.info("Risk settings updated: {}", s);
        return s;
    }

    private static void requirePositive(Number n, String name) {
        if (n != null && n.doubleValue() <= 0) {
            throw new IllegalArgumentException(name + " must be positive (or empty to disable)");
        }
    }

    public record KillSwitchResult(List<String> cancelled, List<String> errors) {
    }

    /**
     * Turn the kill switch on/off; optionally cancel every open order. The switch is saved first,
     * so it holds even when the broker can't be reached to cancel orders (reported in errors).
     */
    public KillSwitchResult killSwitch(boolean on, boolean cancelOpenOrders) {
        update(settings().withKillSwitch(on));
        log.warn("Kill switch turned {}", on ? "ON" : "off");
        List<String> cancelled = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        if (on && cancelOpenOrders) {
            List<OrderDto> orders;
            try {
                orders = trading.getOrders();
            } catch (Exception e) {
                log.warn("Kill switch could not read the order book: {}", e.getMessage());
                errors.add("Could not read open orders from the broker — cancel them in the broker app");
                orders = List.of();
            }
            for (OrderDto o : orders == null ? List.<OrderDto>of() : orders) {
                String status = o.status() == null ? "" : o.status().toUpperCase(Locale.ROOT);
                if (status.equals("OPEN") || status.equals("PENDING") || status.contains("TRIGGER")) {
                    try {
                        trading.cancelOrder(o.orderId());
                        cancelled.add(o.orderId());
                    } catch (Exception e) {
                        log.warn("Kill switch could not cancel {}: {}", o.orderId(), e.getMessage());
                        errors.add("Could not cancel order " + o.orderId());
                    }
                }
            }
        }
        return new KillSwitchResult(cancelled, errors);
    }

    public RiskState state(String broker) {
        RiskSettings s = settings();
        Double pnl = dayPnl(broker, new ArrayList<>());
        boolean hit = s.maxDailyLoss() != null && pnl != null && pnl <= -s.maxDailyLoss();
        return new RiskState(s, broker, ordersToday(new ArrayList<>()), pnl, hit);
    }

    // ---- assessment ----

    public Assessment assess(PlaceOrderRequest req, String broker) {
        RiskSettings s = settings();
        boolean live = broker != null && !"mock".equalsIgnoreCase(broker);
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        boolean apply = live || s.applyToPaper();

        if (req.symbol() == null || req.symbol().isBlank() || req.quantity() < 1) {
            violations.add("Symbol and a quantity of at least 1 are required");
        }
        Double price = orderPrice(req, warnings);
        Double value = price == null ? null : round(price * req.quantity());
        boolean reducing = isReducing(req, warnings);
        int ordersToday = apply ? ordersToday(warnings) : 0;
        Double pnl = apply ? dayPnl(broker, warnings) : null;

        if (apply && !reducing) {
            if (s.killSwitch()) {
                violations.add("Kill switch is ON — only orders that reduce an open position are allowed");
            }
            if (s.maxQuantityPerOrder() != null && req.quantity() > s.maxQuantityPerOrder()) {
                violations.add("Quantity " + req.quantity() + " exceeds the limit of " + s.maxQuantityPerOrder());
            }
            if (s.maxOrderValue() != null) {
                if (value == null) {
                    violations.add("Order value can't be verified (no price available for " + req.symbol() + ")");
                } else if (value > s.maxOrderValue()) {
                    violations.add("Order value ₹" + value + " exceeds the limit of ₹" + s.maxOrderValue());
                }
            }
            if (s.maxOrdersPerDay() != null && ordersToday >= s.maxOrdersPerDay()) {
                violations.add("Daily order limit reached (" + ordersToday + "/" + s.maxOrdersPerDay() + ")");
            }
            if (s.maxDailyLoss() != null && pnl != null && pnl <= -s.maxDailyLoss()) {
                violations.add("Daily loss limit reached (P&L ₹" + round(pnl) + ", limit ₹" + s.maxDailyLoss()
                        + ") — only position-reducing orders are allowed");
            }
        }
        if (reducing) {
            warnings.add("Reduces an open position — allowed even when limits are hit");
        }
        return new Assessment(broker, live, price, value, reducing, ordersToday, pnl == null ? null : round(pnl),
                violations, warnings);
    }

    /** Assess and, when allowed, issue a single-use confirmation token for exactly this order. */
    public Preview preview(PlaceOrderRequest req, String broker) {
        Assessment a = assess(req, broker);
        if (!a.violations().isEmpty()) {
            return new Preview(a, false, null, null);
        }
        Instant exp = Instant.now().plus(CONFIRM_TTL);
        boolean required = a.live() && settings().requireConfirmation();
        return new Preview(a, required, tokens.sign(CONFIRM_PURPOSE, canonical(req, broker), exp), exp);
    }

    /** Throw unless the order passes the limits and (live brokers) carries a valid confirmation. */
    public Assessment enforce(PlaceOrderRequest req, String broker, String confirmation) {
        Assessment a = assess(req, broker);
        if (!a.violations().isEmpty()) {
            reject("limits", a.violations());
            throw new OrderRejectedException("Order blocked by risk limits", a.violations(), false);
        }
        if (a.live() && settings().requireConfirmation()) {
            String expected = canonical(req, broker);
            boolean valid = tokens.verify(CONFIRM_PURPOSE, confirmation).map(expected::equals).orElse(false);
            if (!valid) {
                reject("confirmation", List.of());
                throw new OrderRejectedException(
                        "Live orders need a confirmation: preview the order and confirm it within 60 seconds",
                        List.of(), true);
            }
            if (usedTokens.asMap().putIfAbsent(confirmation, Boolean.TRUE) != null) {
                reject("confirmation-reuse", List.of());
                throw new OrderRejectedException("This confirmation was already used — preview the order again",
                        List.of(), true);
            }
        }
        return a;
    }

    /** Modifications can raise exposure: apply the kill switch and per-order limits to the new values. */
    public void enforceModify(String symbol, Integer quantity, Double price, String broker) {
        RiskSettings s = settings();
        boolean live = broker != null && !"mock".equalsIgnoreCase(broker);
        if (!live && !s.applyToPaper()) {
            return;
        }
        List<String> v = new ArrayList<>();
        if (s.killSwitch()) {
            v.add("Kill switch is ON — order modifications are blocked (cancelling is allowed)");
        }
        if (quantity != null && s.maxQuantityPerOrder() != null && quantity > s.maxQuantityPerOrder()) {
            v.add("Quantity " + quantity + " exceeds the limit of " + s.maxQuantityPerOrder());
        }
        if (quantity != null && price != null && s.maxOrderValue() != null && quantity * price > s.maxOrderValue()) {
            v.add("Order value ₹" + round(quantity * price) + " exceeds the limit of ₹" + s.maxOrderValue());
        }
        if (!v.isEmpty()) {
            reject("modify", v);
            throw new OrderRejectedException("Modification blocked by risk limits", v, false);
        }
    }

    static String canonical(PlaceOrderRequest r, String broker) {
        return String.join("|", upper(r.symbol()), upper(r.exchange()), upper(r.transactionType()), upper(r.orderType()),
                upper(r.product()), String.valueOf(r.quantity()), String.valueOf(r.price()),
                String.valueOf(r.triggerPrice()), upper(broker));
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private void reject(String reason, List<String> violations) {
        meters.counter("stockcharts.orders.rejected", "reason", reason).increment();
        log.info("Order rejected ({}): {}", reason, violations);
    }

    // ---- inputs ----

    private Double orderPrice(PlaceOrderRequest req, List<String> warnings) {
        String type = upper(req.orderType());
        if ((type.equals("LIMIT") || type.equals("SL")) && req.price() != null && req.price() > 0) {
            return req.price();
        }
        if (type.equals("SL-M") && req.triggerPrice() != null && req.triggerPrice() > 0) {
            return req.triggerPrice();
        }
        try {
            List<QuoteService.Quote> q = quotes.quotes(List.of(req.symbol()));
            if (!q.isEmpty() && q.get(0).ltp() != null) {
                return q.get(0).ltp();
            }
        } catch (Exception e) {
            warnings.add("Last price unavailable: " + e.getMessage());
        }
        return null;
    }

    private boolean isReducing(PlaceOrderRequest req, List<String> warnings) {
        try {
            PositionsDto positions = trading.getPositions();
            if (positions == null || positions.net() == null) {
                return false;
            }
            int net = positions.net().stream()
                    .filter(p -> upper(p.symbol()).equals(upper(req.symbol())))
                    .filter(p -> req.product() == null || upper(p.product()).equals(upper(req.product())))
                    .mapToInt(PositionDto::quantity).sum();
            boolean buy = "BUY".equals(upper(req.transactionType()));
            return buy ? net < 0 && req.quantity() <= -net : net > 0 && req.quantity() <= net;
        } catch (Exception e) {
            warnings.add("Positions unavailable; treating the order as opening a position");
            return false;
        }
    }

    private int ordersToday(List<String> warnings) {
        try {
            List<OrderDto> orders = trading.getOrders();
            if (orders == null) {
                return 0;
            }
            long startOfDay = LocalDate.now(IST).atStartOfDay(IST).toInstant().toEpochMilli();
            return (int) orders.stream().filter(o -> o.timestamp() <= 0 || o.timestamp() >= startOfDay).count();
        } catch (Exception e) {
            warnings.add("Order book unavailable; daily order count not checked");
            return 0;
        }
    }

    /** Today's realised P&L from the journal (this broker) plus open P&L from broker positions. */
    private Double dayPnl(String broker, List<String> warnings) {
        double total = 0;
        Instant startOfDay = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        var fills = journal.findAllByOrderByExecutedAtAscIdAsc().stream()
                .filter(e -> broker == null || broker.equalsIgnoreCase(e.getBroker())).toList();
        total += PortfolioReport.build(fills, startOfDay, null).summary().netPnl();
        try {
            PositionsDto positions = trading.getPositions();
            if (positions != null && positions.net() != null) {
                total += positions.net().stream().mapToDouble(PositionDto::pnl).sum();
            }
        } catch (Exception e) {
            warnings.add("Open P&L unavailable; daily loss uses realised P&L only");
        }
        return total;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
