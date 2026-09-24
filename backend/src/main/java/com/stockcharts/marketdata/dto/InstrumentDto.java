package com.stockcharts.marketdata.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Instrument as returned by the Python data service. */
public record InstrumentDto(
        String symbol,
        String name,
        @JsonProperty("instrument_token") long instrumentToken,
        String exchange,
        @JsonProperty("instrument_type") String instrumentType
) {
}
