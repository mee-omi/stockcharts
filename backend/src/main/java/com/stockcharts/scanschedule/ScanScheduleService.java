package com.stockcharts.scanschedule;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockcharts.alert.AlertNotifier;
import com.stockcharts.backtest.BacktestDtos.BacktestRequest;
import com.stockcharts.backtest.BacktestDtos.ScanRequest;
import com.stockcharts.backtest.BacktestDtos.ScanResult;
import com.stockcharts.backtest.BacktestDtos.ScanRow;
import com.stockcharts.backtest.BacktestService;
import com.stockcharts.market.NseCalendar;
import com.stockcharts.scanschedule.ScanScheduleDtos.RunDto;
import com.stockcharts.scanschedule.ScanScheduleDtos.RunRow;
import com.stockcharts.scanschedule.ScanScheduleDtos.ScheduleDto;
import com.stockcharts.scanschedule.ScanScheduleDtos.ScheduleRequest;
import io.micrometer.core.instrument.MeterRegistry;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Runs saved backtest scans on a schedule in Indian market time and notifies the matches.
 * <p>
 * A schedule fires either at fixed IST times ("15:35") or every N minutes during the NSE session,
 * and by default only on trading days. Each run re-scans with the latest data (a fixed date range
 * in the saved scan becomes a rolling window of the same length), keeps a short history, and
 * delivers the top matches in-app ({@code /topic/scans}), to Telegram and to the webhook. With
 * "only new", only stocks that were not in the previous successful run are notified.
 */
@Service
public class ScanScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScanScheduleService.class);
    public static final String TOPIC = "/topic/scans";
    static final int KEEP_RUNS = 30;
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE dd MMM, HH:mm").withZone(NseCalendar.IST);
    private static final TypeReference<List<RunRow>> ROWS = new TypeReference<>() { };

    private final ScanScheduleRepository schedules;
    private final ScanRunRepository runs;
    private final BacktestService backtest;
    private final NseCalendar calendar;
    private final AlertNotifier notifier;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry meters;

    public ScanScheduleService(ScanScheduleRepository schedules, ScanRunRepository runs, BacktestService backtest,
                               NseCalendar calendar, AlertNotifier notifier, ObjectMapper mapper, Clock clock,
                               MeterRegistry meters) {
        this.schedules = schedules;
        this.runs = runs;
        this.backtest = backtest;
        this.calendar = calendar;
        this.notifier = notifier;
        this.mapper = mapper;
        this.clock = clock;
        this.meters = meters;
    }

    // ------------------------------------------------------------------ CRUD

    public List<ScheduleDto> list() {
        return schedules.findAllByOrderByNameAsc().stream().map(this::toDto).toList();
    }

    public ScheduleDto get(long id) {
        return toDto(find(id));
    }

    @Transactional
    public ScheduleDto create(ScheduleRequest req) {
        ScanSchedule s = new ScanSchedule();
        apply(s, req, true);
        if (schedules.existsByNameIgnoreCase(s.name)) {
            throw new IllegalArgumentException("A schedule named '" + s.name + "' already exists");
        }
        s.nextRunAt = s.enabled ? nextRun(s, now()) : null;
        return toDto(schedules.save(s));
    }

    @Transactional
    public ScheduleDto update(long id, ScheduleRequest req) {
        ScanSchedule s = find(id);
        String oldName = s.name;
        apply(s, req, false);
        if (!s.name.equalsIgnoreCase(oldName) && schedules.existsByNameIgnoreCase(s.name)) {
            throw new IllegalArgumentException("A schedule named '" + s.name + "' already exists");
        }
        s.nextRunAt = s.enabled ? nextRun(s, now()) : null;
        return toDto(schedules.save(s));
    }

    @Transactional
    public void delete(long id) {
        ScanSchedule s = find(id);
        runs.deleteAllInBatch(runs.findByScheduleIdOrderByStartedAtDesc(id));
        schedules.delete(s);
    }

    public List<RunDto> history(long id) {
        ScanSchedule s = find(id);
        return runs.findTop30ByScheduleIdOrderByStartedAtDesc(id).stream().map(r -> runDto(r, s.name)).toList();
    }

    private void apply(ScanSchedule s, ScheduleRequest req, boolean creating) {
        if (req == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (req.name() != null || creating) {
            String name = req.name() == null ? "" : req.name().trim();
            if (name.isEmpty() || name.length() > 100) {
                throw new IllegalArgumentException("name is required (at most 100 characters)");
            }
            s.name = name;
        }
        if (req.scan() != null || creating) {
            backtest.validate(req.scan());
            try {
                s.requestJson = mapper.writeValueAsString(req.scan());
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid scan: " + e.getMessage());
            }
            if (s.requestJson.length() > 20_000) {
                throw new IllegalArgumentException("Scan definition is too large");
            }
        }
        if (req.times() != null || req.intervalMinutes() != null || creating) {
            boolean hasTimes = req.times() != null && !req.times().isBlank();
            boolean hasInterval = req.intervalMinutes() != null;
            if (hasTimes == hasInterval) {
                throw new IllegalArgumentException("Give either times (e.g. \"15:35\") or intervalMinutes, not both");
            }
            if (hasTimes) {
                s.runTimes = parseTimes(req.times()).stream().map(HM::format).collect(Collectors.joining(","));
                s.intervalMinutes = null;
            } else {
                int m = req.intervalMinutes();
                if (m < 5 || m > 375) {
                    throw new IllegalArgumentException("intervalMinutes must be between 5 and 375");
                }
                s.intervalMinutes = m;
                s.runTimes = null;
            }
        }
        if (req.tradingDaysOnly() != null) {
            s.tradingDaysOnly = req.tradingDaysOnly();
        }
        if (req.onlyNew() != null) {
            s.onlyNew = req.onlyNew();
        }
        if (req.topN() != null) {
            if (req.topN() < 1 || req.topN() > 50) {
                throw new IllegalArgumentException("topN must be between 1 and 50");
            }
            s.topN = req.topN();
        }
        if (req.notifyMatches() != null) {
            s.notify = req.notifyMatches();
        }
        if (req.enabled() != null) {
            s.enabled = req.enabled();
        }
    }

    static TreeSet<LocalTime> parseTimes(String raw) {
        TreeSet<LocalTime> out = new TreeSet<>();
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(LocalTime.parse(p.length() == 4 ? "0" + p : p, HM));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("Invalid time '" + p + "': use HH:mm in IST, e.g. 15:35");
            }
        }
        if (out.isEmpty() || out.size() > 24) {
            throw new IllegalArgumentException("Give between 1 and 24 run times");
        }
        return out;
    }

    // ------------------------------------------------------------- schedule

    /** The first run strictly after {@code after}, or null when none falls in the next 30 days. */
    Instant nextRun(ScanSchedule s, Instant after) {
        LocalDate day = after.atZone(NseCalendar.IST).toLocalDate();
        for (int i = 0; i < 30; i++, day = day.plusDays(1)) {
            if (s.intervalMinutes != null) {
                if (!calendar.isTradingDay(day)) {
                    continue;
                }
                Instant close = NseCalendar.sessionClose(day);
                for (Instant t = NseCalendar.sessionOpen(day).plusSeconds(s.intervalMinutes * 60L);
                     !t.isAfter(close); t = t.plusSeconds(s.intervalMinutes * 60L)) {
                    if (t.isAfter(after)) {
                        return t;
                    }
                }
            } else {
                if (s.tradingDaysOnly && !calendar.isTradingDay(day)) {
                    continue;
                }
                for (LocalTime time : parseTimes(s.runTimes)) {
                    Instant t = day.atTime(time).atZone(NseCalendar.IST).toInstant();
                    if (t.isAfter(after)) {
                        return t;
                    }
                }
            }
        }
        return null;
    }

    @Scheduled(fixedDelayString = "${stockcharts.scan-schedules.poll-interval:PT30S}", initialDelayString = "PT30S")
    @SchedulerLock(name = "scanSchedules", lockAtMostFor = "PT30M")
    public void runDue() {
        Instant now = now();
        for (ScanSchedule s : schedules.findByEnabledTrueAndNextRunAtLessThanEqual(now)) {
            try {
                run(s, false);
            } catch (Exception e) {
                log.warn("Scheduled scan '{}' failed: {}", s.name, e.getMessage());
            }
        }
    }

    /** Run a schedule now (outside its timetable); returns the recorded run. */
    public RunDto runNow(long id) {
        return run(find(id), true);
    }

    RunDto run(ScanSchedule s, boolean manual) {
        Instant started = now();
        ScanRun run = new ScanRun();
        run.scheduleId = s.id;
        run.startedAt = started;
        List<RunRow> rows = List.of();
        ScanResult result = null;
        try {
            ScanRequest scan = rolling(mapper.readValue(s.requestJson, ScanRequest.class), started);
            result = backtest.scan(scan);
            Set<String> previous = previousSymbols(s.id);
            rows = new ArrayList<>();
            for (ScanRow r : result.rows()) {
                rows.add(new RunRow(r.symbol(), r.name(), r.latest().signal().name(), r.latest().close(),
                        r.metrics().totalReturnPct(), r.metrics().winRatePct(), r.metrics().trades(),
                        r.metrics().profitFactor(), !previous.contains(r.symbol())));
            }
            run.status = "OK";
            run.matched = result.matched();
            run.newMatches = (int) rows.stream().filter(RunRow::isNew).count();
            List<RunRow> kept = rows.size() > 50 ? rows.subList(0, 50) : rows;
            run.rowsJson = mapper.writeValueAsString(kept);
            if (!result.errors().isEmpty()) {
                run.message = trim("Skipped: " + String.join("; ", result.errors()), 1000);
            }
        } catch (Exception e) {
            run.status = "FAILED";
            run.message = trim(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000);
        }
        run.durationMs = now().toEpochMilli() - started.toEpochMilli();
        run = runs.save(run);
        prune(s.id);

        s.lastRunAt = started;
        if (!manual || s.nextRunAt == null || !s.nextRunAt.isAfter(started)) {
            s.nextRunAt = s.enabled ? nextRun(s, started) : null;
        }
        schedules.save(s);
        meters.counter("stockcharts.scan_schedules.runs", "status", run.status).increment();

        RunDto dto = runDto(run, s.name);
        if (s.notify) {
            notifyRun(s, dto, result);
        }
        log.info("Scheduled scan '{}' {}: {} matched, {} new in {} ms", s.name, run.status, run.matched,
                run.newMatches, run.durationMs);
        return dto;
    }

    /** A saved date range becomes a rolling window of the same length ending now. */
    static ScanRequest rolling(ScanRequest scan, Instant now) {
        BacktestRequest b = scan.strategy();
        Long from = null;
        if (b.from() != null && b.to() != null && b.to() > b.from()) {
            from = now.toEpochMilli() - (b.to() - b.from());
        }
        BacktestRequest latest = new BacktestRequest(b.symbol(), b.timeframe(), from, null, b.entry(), b.exit(),
                b.direction(), b.stopLossPct(), b.takeProfitPct(), b.initialCapital(), b.positionSizePct(),
                b.commissionPct(), b.slippagePct());
        return new ScanRequest(scan.universe(), latest, scan.sortBy(), scan.minTrades(), scan.minWinRatePct(),
                scan.minProfitFactor(), scan.onlySignals(), scan.limit());
    }

    private Set<String> previousSymbols(long scheduleId) {
        return runs.findFirstByScheduleIdAndStatusOrderByStartedAtDesc(scheduleId, "OK")
                .map(r -> parseRows(r.rowsJson).stream().map(RunRow::symbol).collect(Collectors.toSet()))
                .orElseGet(HashSet::new);
    }

    private void prune(long scheduleId) {
        List<ScanRun> all = runs.findByScheduleIdOrderByStartedAtDesc(scheduleId);
        if (all.size() > KEEP_RUNS) {
            runs.deleteAllInBatch(all.subList(KEEP_RUNS, all.size()));
        }
    }

    private void notifyRun(ScanSchedule s, RunDto run, ScanResult result) {
        boolean failed = !"OK".equals(run.status());
        List<RunRow> candidates = s.onlyNew ? run.rows().stream().filter(RunRow::isNew).toList() : run.rows();
        if (!failed && candidates.isEmpty()) {
            return; // nothing (new) to report
        }
        List<RunRow> top = candidates.size() > s.topN ? candidates.subList(0, s.topN) : candidates;
        String text = failed ? "📊 Scan '" + s.name + "' failed: " + run.message() : message(s, run, top, result);
        Map<String, Object> webhook = new LinkedHashMap<>();
        webhook.put("type", "scan");
        webhook.put("schedule", s.name);
        webhook.put("run", new RunDto(run.id(), run.scheduleId(), run.scheduleName(), run.startedAt(),
                run.durationMs(), run.status(), run.matched(), run.newMatches(), top, run.message()));
        try {
            notifier.deliver(TOPIC, webhook.get("run"), text, webhook);
        } catch (Exception e) {
            log.warn("Scan notification for '{}' failed: {}", s.name, e.getMessage());
        }
    }

    static String message(ScanSchedule s, RunDto run, List<RunRow> top, ScanResult result) {
        StringBuilder sb = new StringBuilder("📊 Scan: ").append(s.name).append(" — ")
                .append(run.matched()).append(" match").append(run.matched() == 1 ? "" : "es");
        if (run.newMatches() > 0) {
            sb.append(" (").append(run.newMatches()).append(" new)");
        }
        if (result != null && result.dataAsOf() != null) {
            sb.append("\n").append(result.timeframe()).append(" data as of ")
                    .append(WHEN.format(Instant.ofEpochSecond(result.dataAsOf()))).append(" IST");
        }
        int i = 1;
        for (RunRow r : top) {
            sb.append("\n").append(i++).append(". ").append(r.symbol()).append(" ").append(r.signal())
                    .append(" @ ").append(String.format(java.util.Locale.ROOT, "%.2f", r.close()))
                    .append(" · ").append(String.format(java.util.Locale.ROOT, "%+.1f%%", r.totalReturnPct()))
                    .append(" · win ").append(String.format(java.util.Locale.ROOT, "%.0f%%", r.winRatePct()))
                    .append(" · ").append(r.trades()).append(" trades")
                    .append(r.isNew() ? " 🆕" : "");
        }
        return sb.toString();
    }

    // --------------------------------------------------------------- mapping

    private ScheduleDto toDto(ScanSchedule s) {
        ScanRequest scan;
        try {
            scan = mapper.readValue(s.requestJson, ScanRequest.class);
        } catch (Exception e) {
            scan = null;
        }
        RunDto last = runs.findTop30ByScheduleIdOrderByStartedAtDesc(s.id).stream().findFirst()
                .map(r -> runDto(r, s.name)).orElse(null);
        return new ScheduleDto(s.id, s.name, scan, s.runTimes, s.intervalMinutes, s.tradingDaysOnly, s.onlyNew,
                s.topN, s.notify, s.enabled, describe(s), millis(s.nextRunAt), millis(s.lastRunAt), last);
    }

    static String describe(ScanSchedule s) {
        if (s.intervalMinutes != null) {
            return "Every " + s.intervalMinutes + " min during market hours (09:15–15:30 IST, trading days)";
        }
        return "At " + s.runTimes.replace(",", ", ") + " IST " + (s.tradingDaysOnly ? "on trading days" : "every day");
    }

    private RunDto runDto(ScanRun r, String name) {
        return new RunDto(r.id, r.scheduleId, name, r.startedAt.toEpochMilli(), r.durationMs, r.status, r.matched,
                r.newMatches, parseRows(r.rowsJson), r.message);
    }

    private List<RunRow> parseRows(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, ROWS);
        } catch (Exception e) {
            return List.of();
        }
    }

    private ScanSchedule find(long id) {
        return schedules.findById(id).orElseThrow(() -> new NoSuchElementException("Scan schedule not found: " + id));
    }

    private Instant now() {
        return clock.instant();
    }

    private static Long millis(Instant t) {
        return t == null ? null : t.toEpochMilli();
    }

    private static String trim(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
