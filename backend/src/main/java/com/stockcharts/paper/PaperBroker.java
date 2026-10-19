package com.stockcharts.paper;

import com.stockcharts.config.AppProperties;
import com.stockcharts.journal.ChargesModel;
import com.stockcharts.live.LiveDataService;
import com.stockcharts.live.PriceTick;
import com.stockcharts.market.NseCalendar;
import com.stockcharts.quote.PriceSource;
import com.stockcharts.trading.dto.FundsDto;
import com.stockcharts.trading.dto.HoldingDto;
import com.stockcharts.trading.dto.ModifyOrderRequest;
import com.stockcharts.trading.dto.OrderDto;
import com.stockcharts.trading.dto.PlaceOrderRequest;
import com.stockcharts.trading.dto.PositionDto;
import com.stockcharts.trading.dto.PositionsDto;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Paper trading that behaves like a real NSE account, persisted in the database:
 * <ul>
 *   <li>MARKET orders fill at the live price with adverse slippage; LIMIT, SL and SL-M orders wait
 *       and are checked on every tick (and every few seconds), filling when the price crosses.</li>
 *   <li>Every fill pays estimated brokerage and statutory charges ({@link ChargesModel}).</li>
 *   <li>Funds are checked before an order is accepted: delivery (CNC) buys need the full value,
 *       intraday (MIS) needs value / leverage; open orders block their margin.</li>
 *   <li>Outside the session, orders queue for the next open (like after-market orders). MIS
 *       positions are squared off at 15:20 IST, open day orders are cancelled at the close, and CNC
 *       buys become holdings on the next day.</li>
 * </ul>
 * All state changes run under one lock in a transaction, so ticks, schedules and API calls don't
 * race each other.
 */
@Service
public class PaperBroker {

    private static final Logger log = LoggerFactory.getLogger(PaperBroker.class);
    public static final String BROKER = "mock";
    static final String TOPIC = "/topic/paper";
    private static final Set<String> OPEN = Set.of(PaperOrder.Status.OPEN.name(), PaperOrder.Status.TRIGGER_PENDING.name());

    public record AccountInfo(double startingCash, double cash, double realizedToday, double chargesToday,
                              LocalDate tradingDay, double slippagePct, double misLeverage, String squareOffTime,
                              boolean marketHoursOnly, boolean marketOpen) {
    }

    private final PaperAccountRepository accounts;
    private final PaperOrderRepository orders;
    private final PaperPositionRepository positions;
    private final PaperHoldingRepository holdings;
    private final PriceSource prices;
    private final LiveDataService live;
    private final NseCalendar calendar;
    private final AppProperties.Paper cfg;
    private final ApplicationEventPublisher events;
    private final SimpMessagingTemplate messaging;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    /** Symbols with open orders, so ticks for other symbols skip the database entirely. */
    private final Set<String> watched = ConcurrentHashMap.newKeySet();

    public PaperBroker(PaperAccountRepository accounts, PaperOrderRepository orders, PaperPositionRepository positions,
                       PaperHoldingRepository holdings, PriceSource prices, LiveDataService live, NseCalendar calendar,
                       AppProperties props, ApplicationEventPublisher events, SimpMessagingTemplate messaging,
                       PlatformTransactionManager txManager, Clock clock) {
        this.accounts = accounts;
        this.orders = orders;
        this.positions = positions;
        this.holdings = holdings;
        this.prices = prices;
        this.live = live;
        this.calendar = calendar;
        this.cfg = props.getPaper();
        this.events = events;
        this.messaging = messaging;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    // ------------------------------------------------------------------ API

    public OrderDto placeOrder(PlaceOrderRequest req) {
        return locked(() -> {
            PaperAccount acct = settle();
            String symbol = req.symbol() == null ? "" : req.symbol().trim().toUpperCase(Locale.ROOT);
            String side = upper(req.transactionType());
            String type = upper(req.orderType());
            String product = upper(req.product()).isEmpty() ? "CNC" : upper(req.product());
            String exchange = upper(req.exchange()).isEmpty() ? "NSE" : upper(req.exchange());
            validate(symbol, side, type, product, req.quantity(), req.price(), req.triggerPrice());

            boolean open = marketOpen();
            PaperOrder o = new PaperOrder(newId(), symbol, exchange, side, type, product, req.quantity(),
                    positive(req.price()), positive(req.triggerPrice()), open ? today() : nextSessionDay(),
                    req.tag());
            if (type.startsWith("SL")) {
                o.status = PaperOrder.Status.TRIGGER_PENDING.name();
            }
            OptionalDouble ltp = prices.ltp(symbol);
            if (ltp.isEmpty()) {
                return reject(o, "No price available for " + symbol);
            }
            if (open && type.startsWith("SL") && triggerAlreadyHit(o, ltp.getAsDouble())) {
                return reject(o, "Trigger price must be " + (o.buy() ? "above" : "below")
                        + " the last price (₹" + round2(ltp.getAsDouble()) + ") for a stop-loss " + side);
            }
            String funds = reserveFunds(acct, o, ltp.getAsDouble());
            if (funds != null) {
                return reject(o, funds);
            }
            o.message = open ? null : "Queued: fills after the market opens";
            orders.save(o);
            watched.add(symbol);
            live.request(symbol);
            if (open) {
                evaluate(o, ltp.getAsDouble());
            }
            return o.toDto();
        });
    }

    public List<OrderDto> getOrders() {
        return locked(() -> {
            PaperAccount acct = settle();
            return orders.findByTradingDayOrStatusInOrderByPlacedAtDesc(acct.tradingDay, OPEN).stream()
                    .filter(o -> !o.tradingDay.isBefore(acct.tradingDay) || o.isOpen())
                    .map(PaperOrder::toDto).toList();
        });
    }

    public Map<String, Object> cancelOrder(String orderId) {
        return locked(() -> {
            settle();
            PaperOrder o = orders.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
            if (!o.isOpen()) {
                throw new IllegalArgumentException("Order " + orderId + " is " + o.status + " and cannot be cancelled");
            }
            close(o, PaperOrder.Status.CANCELLED, "Cancelled by user");
            return Map.of("order_id", orderId, "status", o.status);
        });
    }

    public OrderDto modifyOrder(String orderId, ModifyOrderRequest req) {
        return locked(() -> {
            PaperAccount acct = settle();
            PaperOrder o = orders.findById(orderId)
                    .orElseThrow(() -> new IllegalArgumentException("Order not found: " + orderId));
            if (!o.isOpen()) {
                throw new IllegalArgumentException("Order " + orderId + " is " + o.status + " and cannot be modified");
            }
            String type = req.orderType() == null || req.orderType().isBlank() ? o.orderType : upper(req.orderType());
            int qty = req.quantity() != null ? req.quantity() : o.quantity;
            Double price = req.price() != null ? positive(req.price()) : o.price;
            Double trigger = req.triggerPrice() != null ? positive(req.triggerPrice()) : o.triggerPrice;
            validate(o.symbol, o.side, type, o.product, qty, price, trigger);
            double ltp = prices.ltp(o.symbol).orElse(o.price != null ? o.price : 0);

            // Re-check funds with the new values, giving back what this order already holds.
            double previous = o.blockedMargin;
            o.blockedMargin = 0; // funds are derived from blocks, so this frees the old amount
            PaperOrder candidate = copyWith(o, type, qty, price, trigger);
            String funds = reserveFunds(acct, candidate, ltp);
            if (funds != null) {
                o.blockedMargin = previous;
                throw new IllegalArgumentException(funds);
            }
            if (!type.startsWith("SL")) {
                o.status = PaperOrder.Status.OPEN.name();
            } else if (!o.orderType.startsWith("SL")) {
                o.status = PaperOrder.Status.TRIGGER_PENDING.name(); // a limit order turned into a stop
            }
            o.orderType = type;
            o.quantity = qty;
            o.price = price;
            o.triggerPrice = trigger;
            o.blockedMargin = candidate.blockedMargin;
            o.updatedAt = now();
            orders.save(o);
            if (marketOpen() && ltp > 0) {
                evaluate(o, ltp);
            }
            return o.toDto();
        });
    }

    public PositionsDto getPositions() {
        return locked(() -> {
            settle();
            List<PositionDto> net = positions.findAll().stream()
                    .sorted(Comparator.comparing((PaperPosition p) -> p.symbol).thenComparing(p -> p.product))
                    .map(p -> {
                        double ltp = prices.ltp(p.symbol).orElse(p.averagePrice);
                        double pnl = p.realizedPnl + p.quantity * (ltp - p.averagePrice);
                        return new PositionDto(p.symbol, p.exchange, p.product, p.quantity, p.buyQuantity,
                                p.sellQuantity, round2(p.averagePrice), round2(ltp), round2(pnl), 0);
                    }).toList();
            return new PositionsDto(net, net);
        });
    }

    public List<HoldingDto> getHoldings() {
        return locked(() -> {
            settle();
            return holdings.findAll().stream()
                    .filter(h -> h.quantity > 0)
                    .sorted(Comparator.comparing(h -> h.symbol))
                    .map(h -> {
                        double ltp = prices.ltp(h.symbol).orElse(h.averagePrice);
                        double pnl = (ltp - h.averagePrice) * h.quantity;
                        double pct = h.averagePrice > 0 ? (ltp / h.averagePrice - 1) * 100 : 0;
                        return new HoldingDto(h.symbol, h.exchange, h.quantity, round2(h.averagePrice),
                                round2(ltp), round2(pnl), round2(pct));
                    }).toList();
        });
    }

    public FundsDto getFunds() {
        return locked(() -> {
            PaperAccount acct = settle();
            double used = usedMargin();
            double value = acct.cash;
            for (PaperPosition p : positions.findAll()) {
                double ltp = prices.ltp(p.symbol).orElse(p.averagePrice);
                // Intraday P&L is not yet in cash; delivery buys already left cash, so add their value.
                value += "MIS".equals(p.product) ? p.quantity * (ltp - p.averagePrice) : Math.max(0, p.quantity) * ltp;
            }
            for (PaperHolding h : holdings.findAll()) {
                value += h.quantity * prices.ltp(h.symbol).orElse(h.averagePrice);
            }
            return new FundsDto(round2(acct.cash), round2(used), round2(acct.cash - used), round2(value));
        });
    }

    public AccountInfo account() {
        return locked(() -> {
            PaperAccount acct = settle();
            List<PaperPosition> all = positions.findAll();
            double realized = all.stream().mapToDouble(p -> p.realizedPnl).sum();
            double charges = all.stream().mapToDouble(p -> p.charges).sum();
            return new AccountInfo(acct.startingCash, round2(acct.cash), round2(realized), round2(charges),
                    acct.tradingDay, cfg.getSlippagePct(), cfg.getMisLeverage(), cfg.getSquareOffTime().toString(),
                    cfg.isMarketHoursOnly(), marketOpen());
        });
    }

    /** Wipe orders, positions and holdings and start over with {@code startingCash} (default from config). */
    public AccountInfo reset(Double startingCash) {
        double cash = startingCash != null ? startingCash : cfg.getStartingCash();
        if (!(cash > 0) || cash > 1e12) {
            throw new IllegalArgumentException("Starting cash must be between ₹1 and ₹1,00,000 crore");
        }
        locked(() -> {
            orders.deleteAllInBatch();
            positions.deleteAllInBatch();
            holdings.deleteAllInBatch();
            accounts.deleteAllInBatch();
            accounts.save(new PaperAccount(cash, today()));
            watched.clear();
            return null;
        });
        log.info("Paper account reset with ₹{}", cash);
        push("reset", null);
        return account();
    }

    // ------------------------------------------------------------ live fills

    @EventListener
    public void onTick(PriceTick tick) {
        if (!watched.contains(tick.symbol()) || !marketOpen()) {
            return;
        }
        try {
            locked(() -> {
                for (PaperOrder o : orders.findByStatusInOrderByPlacedAtAsc(OPEN)) {
                    if (o.symbol.equals(tick.symbol()) && !o.tradingDay.isAfter(today())) {
                        evaluate(o, tick.ltp());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Paper fill check failed for {}: {}", tick.symbol(), e.getMessage());
        }
    }

    /** Safety net for feeds that tick rarely or not at all: check open orders against the best price. */
    @Scheduled(fixedDelayString = "${stockcharts.paper.poll-interval:PT5S}", initialDelayString = "PT20S")
    @SchedulerLock(name = "paperFills", lockAtMostFor = "PT1M")
    public void pollOpenOrders() {
        if (!marketOpen()) {
            return;
        }
        try {
            locked(() -> {
                settle();
                for (PaperOrder o : orders.findByStatusInOrderByPlacedAtAsc(OPEN)) {
                    watched.add(o.symbol);
                    if (o.tradingDay.isAfter(today())) {
                        continue;
                    }
                    live.request(o.symbol);
                    OptionalDouble ltp = prices.ltp(o.symbol);
                    if (ltp.isPresent()) {
                        evaluate(o, ltp.getAsDouble());
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Paper order poll failed: {}", e.getMessage());
        }
    }

    /** Session housekeeping: 15:20 intraday square-off and cancelling day orders at the close. */
    @Scheduled(cron = "${stockcharts.paper.session-cron:0 * * * * *}", zone = "Asia/Kolkata")
    @SchedulerLock(name = "paperSession", lockAtMostFor = "PT1M")
    public void sessionTick() {
        try {
            locked(() -> {
                settle();
                ZonedDateTime t = now().atZone(NseCalendar.IST);
                if (!calendar.isTradingDay(t.toLocalDate())) {
                    return null;
                }
                if (!t.toLocalTime().isBefore(cfg.getSquareOffTime())) {
                    squareOffIntraday("Auto square-off at " + cfg.getSquareOffTime() + " IST");
                }
                if (!t.toLocalTime().isBefore(NseCalendar.CLOSE)) {
                    for (PaperOrder o : orders.findByStatusInOrderByPlacedAtAsc(OPEN)) {
                        if (!o.tradingDay.isAfter(t.toLocalDate())) {
                            close(o, PaperOrder.Status.CANCELLED, "Cancelled at market close (day order)");
                        }
                    }
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Paper session housekeeping failed: {}", e.getMessage());
        }
    }

    // --------------------------------------------------------------- internals

    /**
     * Roll the account forward to today: anything left from an earlier day is squared off or
     * cancelled, CNC positions become holdings, and day positions start empty.
     */
    private PaperAccount settle() {
        PaperAccount acct = accounts.findById(PaperAccount.ID).orElseGet(() -> {
            log.info("Creating paper account with ₹{}", cfg.getStartingCash());
            return accounts.save(new PaperAccount(cfg.getStartingCash(), today()));
        });
        LocalDate today = today();
        if (!acct.tradingDay.isBefore(today)) {
            return acct;
        }
        squareOffIntraday("Squared off after the session (" + acct.tradingDay + ")");
        for (PaperOrder o : orders.findByStatusInOrderByPlacedAtAsc(OPEN)) {
            if (o.tradingDay.isBefore(today)) {
                close(o, PaperOrder.Status.CANCELLED, "Expired: day order from " + o.tradingDay);
            }
        }
        for (PaperPosition p : positions.findAll()) {
            if ("CNC".equals(p.product) && p.quantity > 0) {
                PaperHolding h = holdings.findById(p.symbol).orElseGet(() -> new PaperHolding(p.symbol, p.exchange));
                h.averagePrice = (h.averagePrice * h.quantity + p.averagePrice * p.quantity) / (h.quantity + p.quantity);
                h.quantity += p.quantity;
                h.updatedAt = now();
                holdings.save(h);
            }
        }
        positions.deleteAllInBatch();
        acct.tradingDay = today;
        acct.updatedAt = now();
        accounts.save(acct);
        return acct;
    }

    private void squareOffIntraday(String reason) {
        for (PaperPosition p : positions.findAll()) {
            if (!"MIS".equals(p.product) || p.quantity == 0) {
                continue;
            }
            for (PaperOrder o : orders.findByStatusInOrderByPlacedAtAsc(OPEN)) {
                if (o.symbol.equals(p.symbol) && "MIS".equals(o.product)) {
                    close(o, PaperOrder.Status.CANCELLED, reason);
                }
            }
            double ltp = prices.ltp(p.symbol).orElse(p.averagePrice);
            PaperOrder exit = new PaperOrder(newId(), p.symbol, p.exchange, p.quantity > 0 ? "SELL" : "BUY", "MARKET",
                    "MIS", Math.abs(p.quantity), null, null, today(), "auto-squareoff");
            orders.save(exit);
            fill(exit, withSlippage(exit.buy(), ltp));
            exit.message = reason;
            orders.save(exit);
        }
    }

    /** Fill {@code o} if {@code ltp} satisfies it (open orders only). */
    private void evaluate(PaperOrder o, double ltp) {
        if (!o.isOpen() || ltp <= 0) {
            return;
        }
        boolean buy = o.buy();
        switch (o.orderType) {
            case "MARKET" -> fill(o, withSlippage(buy, ltp));
            case "LIMIT" -> {
                if (buy ? ltp <= o.price : ltp >= o.price) {
                    fill(o, buy ? Math.min(ltp, o.price) : Math.max(ltp, o.price));
                }
            }
            case "SL-M" -> {
                if (triggerAlreadyHit(o, ltp)) {
                    fill(o, withSlippage(buy, ltp));
                }
            }
            case "SL" -> {
                if (PaperOrder.Status.TRIGGER_PENDING.name().equals(o.status)) {
                    if (!triggerAlreadyHit(o, ltp)) {
                        return;
                    }
                    o.status = PaperOrder.Status.OPEN.name(); // triggered: now a limit order
                    o.message = "Triggered at ₹" + round2(ltp);
                    o.updatedAt = now();
                    orders.save(o);
                }
                if (buy ? ltp <= o.price : ltp >= o.price) {
                    fill(o, buy ? Math.min(ltp, o.price) : Math.max(ltp, o.price));
                }
            }
            default -> close(o, PaperOrder.Status.REJECTED, "Unsupported order type " + o.orderType);
        }
    }

    private static boolean triggerAlreadyHit(PaperOrder o, double ltp) {
        return o.triggerPrice != null && (o.buy() ? ltp >= o.triggerPrice : ltp <= o.triggerPrice);
    }

    private void fill(PaperOrder o, double rawPrice) {
        double price = tick(rawPrice);
        int qty = o.quantity;
        boolean intraday = "MIS".equals(o.product);
        double value = qty * price;
        double charges = ChargesModel.leg(value, o.buy(), intraday);
        PaperAccount acct = accounts.findById(PaperAccount.ID).orElseThrow();
        PaperPosition pos = positions.findById(new PaperPosition.Key(o.symbol, o.product))
                .orElseGet(() -> new PaperPosition(o.symbol, o.product, o.exchange));

        if (intraday) {
            acct.cash += pos.apply(o.buy(), qty, price) - charges;
        } else if (o.buy()) {
            pos.apply(true, qty, price);
            acct.cash -= value + charges;
        } else {
            // Delivery sell: today's CNC buys first, then holdings.
            int fromDay = Math.min(Math.max(pos.quantity, 0), qty);
            if (fromDay > 0) {
                pos.apply(false, fromDay, price);
            }
            int fromHoldings = qty - fromDay;
            if (fromHoldings > 0) {
                PaperHolding h = holdings.findById(o.symbol).orElseThrow(
                        () -> new IllegalStateException("No holdings of " + o.symbol + " to sell"));
                pos.sellQuantity += fromHoldings;
                pos.sellValue += fromHoldings * price;
                pos.realizedPnl += (price - h.averagePrice) * fromHoldings;
                h.quantity -= fromHoldings;
                h.updatedAt = now();
                if (h.quantity <= 0) {
                    holdings.delete(h);
                } else {
                    holdings.save(h);
                }
            }
            acct.cash += value - charges;
        }
        pos.charges += charges;
        if (pos.quantity == 0 && pos.buyQuantity == 0 && pos.sellQuantity == 0) {
            positions.delete(pos);
        } else {
            positions.save(pos);
        }
        acct.updatedAt = now();
        accounts.save(acct);

        o.status = PaperOrder.Status.COMPLETE.name();
        o.filledQuantity = qty;
        o.averagePrice = price;
        o.charges = round2(charges);
        o.blockedMargin = 0;
        o.message = "Filled at ₹" + price + " (paper; charges ₹" + round2(charges) + ")";
        o.updatedAt = now();
        orders.save(o);
        refreshWatched();
        OrderDto dto = o.toDto();
        events.publishEvent(new PaperFillEvent(dto, o.tag));
        push("fill", dto);
    }

    private void close(PaperOrder o, PaperOrder.Status status, String message) {
        o.status = status.name();
        o.blockedMargin = 0;
        o.message = message;
        o.updatedAt = now();
        orders.save(o);
        refreshWatched();
        push(status.name().toLowerCase(Locale.ROOT), o.toDto());
    }

    private OrderDto reject(PaperOrder o, String message) {
        o.status = PaperOrder.Status.REJECTED.name();
        o.message = message;
        orders.save(o);
        push("rejected", o.toDto());
        return o.toDto();
    }

    /**
     * Block the funds {@code o} needs, or return why it can't be accepted. Orders that reduce an
     * existing position need nothing extra; delivery sells need the shares.
     */
    private String reserveFunds(PaperAccount acct, PaperOrder o, double ltp) {
        double ref = switch (o.orderType) {
            case "LIMIT", "SL" -> o.price != null ? o.price : ltp;
            case "SL-M" -> o.triggerPrice != null ? o.triggerPrice : ltp;
            default -> ltp;
        };
        double value = ref * o.quantity;
        PaperPosition pos = positions.findById(new PaperPosition.Key(o.symbol, o.product)).orElse(null);
        int net = pos == null ? 0 : pos.quantity;
        int pendingSameSide = orders.findByStatusInOrderByPlacedAtAsc(OPEN).stream()
                .filter(x -> x != o && !x.orderId.equals(o.orderId) && x.symbol.equals(o.symbol)
                        && x.product.equals(o.product) && x.side.equals(o.side))
                .mapToInt(x -> x.quantity).sum();

        if ("CNC".equals(o.product)) {
            if (!o.buy()) {
                int held = holdings.findById(o.symbol).map(h -> h.quantity).orElse(0);
                int available = held + Math.max(net, 0) - pendingSameSide;
                if (o.quantity > available) {
                    return "Insufficient holdings: " + o.symbol + " has " + Math.max(available, 0)
                            + " share(s) available to sell (delivery); use MIS to short intraday";
                }
                o.blockedMargin = 0;
                return null;
            }
            double need = value + ChargesModel.leg(value, true, false);
            return block(acct, o, need);
        }
        // MIS: the part that reduces an existing opposite position needs no margin.
        int reducing = (o.buy() && net < 0) || (!o.buy() && net > 0) ? Math.min(Math.abs(net), o.quantity) : 0;
        double opening = (o.quantity - reducing) * ref;
        double need = opening / cfg.getMisLeverage() + ChargesModel.leg(value, o.buy(), true);
        return block(acct, o, need);
    }

    private String block(PaperAccount acct, PaperOrder o, double need) {
        double available = acct.cash - usedMargin();
        if (need > available + 1e-6) {
            return "Insufficient funds: needs ₹" + round2(need) + ", available ₹" + round2(Math.max(available, 0));
        }
        o.blockedMargin = round2(need);
        return null;
    }

    /** Margin held by open MIS positions and by open orders. */
    private double usedMargin() {
        double used = 0;
        for (PaperPosition p : positions.findAll()) {
            if ("MIS".equals(p.product) && p.quantity != 0) {
                used += Math.abs(p.quantity) * p.averagePrice / cfg.getMisLeverage();
            }
        }
        for (PaperOrder o : orders.findByStatusInOrderByPlacedAtAsc(OPEN)) {
            used += o.blockedMargin;
        }
        return used;
    }

    private static void validate(String symbol, String side, String type, String product, int qty, Double price,
                                 Double trigger) {
        if (symbol.isEmpty()) {
            throw new IllegalArgumentException("symbol is required");
        }
        if (!side.equals("BUY") && !side.equals("SELL")) {
            throw new IllegalArgumentException("transaction_type must be BUY or SELL");
        }
        if (!Set.of("MARKET", "LIMIT", "SL", "SL-M").contains(type)) {
            throw new IllegalArgumentException("order_type must be MARKET, LIMIT, SL or SL-M");
        }
        if (!product.equals("CNC") && !product.equals("MIS")) {
            throw new IllegalArgumentException("Paper trading supports CNC (delivery) and MIS (intraday) equity orders");
        }
        if (qty < 1) {
            throw new IllegalArgumentException("quantity must be at least 1");
        }
        if ((type.equals("LIMIT") || type.equals("SL")) && (price == null || price <= 0)) {
            throw new IllegalArgumentException(type + " orders need a price");
        }
        if (type.startsWith("SL") && (trigger == null || trigger <= 0)) {
            throw new IllegalArgumentException(type + " orders need a trigger price");
        }
    }

    private PaperOrder copyWith(PaperOrder o, String type, int qty, Double price, Double trigger) {
        PaperOrder c = new PaperOrder(o.orderId, o.symbol, o.exchange, o.side, type, o.product, qty, price, trigger,
                o.tradingDay, o.tag);
        c.status = o.status;
        return c;
    }

    private void refreshWatched() {
        watched.clear();
        orders.findByStatusInOrderByPlacedAtAsc(OPEN).forEach(x -> watched.add(x.symbol));
    }

    private double withSlippage(boolean buy, double ltp) {
        double s = cfg.getSlippagePct() / 100.0;
        return buy ? ltp * (1 + s) : ltp * (1 - s);
    }

    private boolean marketOpen() {
        return !cfg.isMarketHoursOnly() || calendar.isOpen(now());
    }

    private Instant now() {
        return clock.instant();
    }

    private LocalDate today() {
        return now().atZone(NseCalendar.IST).toLocalDate();
    }

    /** The day of the session an order placed now would trade in. */
    private LocalDate nextSessionDay() {
        return calendar.nextOpen(now()).atZone(NseCalendar.IST).toLocalDate();
    }

    private void push(String type, OrderDto order) {
        try {
            java.util.Map<String, Object> msg = new java.util.HashMap<>();
            msg.put("type", type);
            msg.put("order", order);
            messaging.convertAndSend(TOPIC, msg);
        } catch (Exception e) {
            log.debug("Paper update not pushed: {}", e.getMessage());
        }
    }

    private <T> T locked(Supplier<T> work) {
        lock.lock();
        try {
            return tx.execute(status -> work.get());
        } finally {
            lock.unlock();
        }
    }

    /** NSE equity tick size is ₹0.05. */
    static double tick(double price) {
        return Math.round(price * 20.0) / 20.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static Double positive(Double v) {
        return v != null && v > 0 ? v : null;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static String newId() {
        return "P" + UUID.randomUUID().toString().replace("-", "").substring(0, 15).toUpperCase(Locale.ROOT);
    }
}
