package com.stockcharts.risk;

import com.stockcharts.journal.JournalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/risk")
@Tag(name = "Risk", description = "Order limits, daily loss limit and the kill switch")
public class RiskController {

    private final RiskService risk;
    private final JournalService journal;

    public RiskController(RiskService risk, JournalService journal) {
        this.risk = risk;
        this.journal = journal;
    }

    public record KillSwitchRequest(boolean enabled, boolean cancelOpenOrders) {
    }

    @GetMapping
    @Operation(summary = "Current limits and today's state (orders placed, P&L, loss limit reached)")
    public RiskService.RiskState state() {
        return risk.state(journal.activeBroker());
    }

    @PutMapping
    @Operation(summary = "Update the limits")
    public RiskService.RiskState update(@RequestBody RiskSettings settings) {
        risk.update(settings);
        return risk.state(journal.activeBroker());
    }

    @PostMapping("/kill-switch")
    @Operation(summary = "Turn the kill switch on/off; optionally cancel all open orders")
    public Map<String, Object> killSwitch(@RequestBody KillSwitchRequest req) {
        RiskService.KillSwitchResult r = risk.killSwitch(req.enabled(), req.cancelOpenOrders());
        return Map.of("state", risk.state(journal.activeBroker()), "cancelled", r.cancelled(), "errors", r.errors());
    }
}
