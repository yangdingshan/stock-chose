package com.yangdingshan.stockchose.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockReport {
    private String filename;
    private String stockName;
    private String stockCode;
    private String industry;
    private String period;
    private LocalDate reportDate;
    private String dateFormatted;
    private LocalDateTime createdTime;
    private String reportType;
    private Integer score;
    private String title;
    private long size;
    private Double price;
    private Double fairValue;
    private Double safetyMargin;
    private String buyZone;
    private String addZone;
    private String reduceZone;
    private String stopLoss;
}