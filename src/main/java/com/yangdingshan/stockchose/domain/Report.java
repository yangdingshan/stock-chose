package com.yangdingshan.stockchose.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Report {
    private String filename;
    private String title;
    private LocalDate date;
    private String dateFormatted;
    private List<String> tags;
    private String summary;
    private long size;
}
