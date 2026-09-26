package com.stockcharts.paper;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/paper")
@Tag(name = "Paper trading", description = "The simulated account used when the broker is 'mock'")
public class PaperController {

    private final PaperBroker paper;

    public PaperController(PaperBroker paper) {
        this.paper = paper;
    }

    public record ResetRequest(Double startingCash) {
    }

    @GetMapping
    @Operation(summary = "Paper account: cash, today's realised P&L and charges, simulation settings")
    public PaperBroker.AccountInfo account() {
        return paper.account();
    }

    @PostMapping("/reset")
    @Operation(summary = "Delete all paper orders, positions and holdings and start with fresh cash")
    public PaperBroker.AccountInfo reset(@RequestBody(required = false) ResetRequest req) {
        return paper.reset(req == null ? null : req.startingCash());
    }
}
