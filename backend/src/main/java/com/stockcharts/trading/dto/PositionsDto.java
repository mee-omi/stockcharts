package com.stockcharts.trading.dto;

import java.util.List;

/** Net and day positions container. */
public record PositionsDto(List<PositionDto> net, List<PositionDto> day) {}
