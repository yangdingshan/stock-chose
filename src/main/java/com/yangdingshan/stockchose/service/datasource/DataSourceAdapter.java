package com.yangdingshan.stockchose.service.datasource;

import com.yangdingshan.stockchose.domain.StockRead;

import java.util.List;

public interface DataSourceAdapter {

    String getName();

    /**
     * Download all A-share stock data.
     * Returns empty list on failure (never null).
     */
    List<StockRead> downloadStockData();

    /**
     * Check if this data source is currently available.
     */
    boolean isAvailable();
}
