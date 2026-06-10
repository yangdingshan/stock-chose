package com.yangdingshan.stockchose.service;

import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.domain.StockRead;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.service.datasource.AKShareAdapter;
import com.yangdingshan.stockchose.service.datasource.CacheAdapter;
import com.yangdingshan.stockchose.service.datasource.DataSourceAdapter;
import com.yangdingshan.stockchose.service.datasource.EastmoneyAdapter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataDownloadService {

    @Autowired
    private AKShareAdapter akshareAdapter;

    @Autowired
    private EastmoneyAdapter eastmoneyAdapter;

    @Autowired
    private CacheAdapter cacheAdapter;

    @Autowired
    private StockRepository stockRepository;

    private String lastDownloadSource;
    private String lastDownloadTime;
    private String lastErrorMessage;

    // Cache availability checks to avoid slow per-request probing
    private volatile Boolean cachedAkshareAvailable;
    private volatile Boolean cachedEastmoneyAvailable;
    private volatile Boolean cachedCacheAvailable;
    private volatile long availabilityCheckedAt;
    private static final long AVAILABILITY_CACHE_MS = 120_000;

    private boolean isAvailabilityCacheFresh() {
        return availabilityCheckedAt > 0
                && System.currentTimeMillis() - availabilityCheckedAt < AVAILABILITY_CACHE_MS;
    }

    private void refreshAvailabilityCache() {
        cachedAkshareAvailable = akshareAdapter.isAvailable();
        cachedEastmoneyAvailable = eastmoneyAdapter.isAvailable();
        cachedCacheAvailable = cacheAdapter.isAvailable();
        availabilityCheckedAt = System.currentTimeMillis();
    }

    private static final int MIN_STOCK_COUNT = 500;

    /**
     * Download stock data with automatic fallback: Eastmoney -> AKShare -> Cache.
     * Returns the data source name that succeeded, or null if all failed.
     */
    public String downloadStockData() {
        List<StockRead> stockList = null;
        String source = null;

        // Try primary: Eastmoney (fast, full 5000+ coverage)
        if (eastmoneyAdapter.isAvailable()) {
            log.info("尝试从东方财富下载股票数据...");
            stockList = eastmoneyAdapter.downloadStockData();
            if (stockList.size() >= MIN_STOCK_COUNT) {
                source = eastmoneyAdapter.getName();
                log.info("东方财富下载成功: {} 条", stockList.size());
            } else {
                log.warn("东方财富仅返回 {} 条，不足阈值 {}，将尝试其他数据源",
                        stockList.size(), MIN_STOCK_COUNT);
            }
        }

        // Enrich ROE from AKShare if both sources are available and Eastmoney has data
        if (stockList != null && !stockList.isEmpty() && akshareAdapter.isAvailable()) {
            log.info("尝试用 AKShare 补充 ROE 数据...");
            enrichROEFromAKShare(stockList);
        }

        // Try AKShare as primary if Eastmoney failed or returned too few
        if ((stockList == null || stockList.size() < MIN_STOCK_COUNT) && akshareAdapter.isAvailable()) {
            log.info("尝试从 AKShare 下载股票数据...");
            List<StockRead> akshareList = akshareAdapter.downloadStockData();
            if (akshareList.size() >= MIN_STOCK_COUNT || stockList == null
                    || akshareList.size() > stockList.size()) {
                stockList = akshareList;
                source = akshareAdapter.getName();
                log.info("AKShare下载成功: {} 条", stockList.size());
            }
        }

        // Fallback: local cache
        if ((stockList == null || stockList.isEmpty()) && cacheAdapter.isAvailable()) {
            log.info("在线数据源不可用，回退到本地缓存...");
            stockList = cacheAdapter.downloadStockData();
            if (!stockList.isEmpty()) {
                source = cacheAdapter.getName();
                log.info("本地缓存加载成功: {} 条", stockList.size());
            }
        }

        if (stockList == null || stockList.isEmpty()) {
            lastErrorMessage = "所有数据源均不可用，请稍后重试";
            log.error(lastErrorMessage);
            return null;
        }

        // Deduplicate
        List<StockRead> distinctStockList = new ArrayList<>(stockList.stream()
                .collect(Collectors.toMap(StockRead::getCode, s -> s, (existing, replacement) -> existing))
                .values());

        // Clear old data and save
        stockRepository.deleteAll();
        List<Stock> entities = new ArrayList<>();
        distinctStockList.forEach(sr -> {
            Stock s = new Stock();
            s.setCode(sr.getCode());
            s.setName(sr.getName());
            s.setIndustry(sr.getIndustry() != null ? sr.getIndustry() : "");
            s.setPrice(new BigDecimal(sr.getPrice()));
            s.setPe(Float.parseFloat(sr.getPe()));
            s.setPb(Float.parseFloat(sr.getPb()));
            s.setRoe(Float.parseFloat(sr.getRoe()));
            s.setIndexCount(0);
            s.setIndexCountRank(0);
            s.setStockMarket(getStockMarket(s.getCode()));
            s.setBuyTime(Instant.now());
            entities.add(s);
        });
        stockRepository.saveAll(entities);

        // Save to cache for future fallback
        cacheAdapter.saveToCache(distinctStockList);

        lastDownloadSource = source;
        lastDownloadTime = Instant.now().toString();
        lastErrorMessage = null;
        log.info("股票数据保存完成: {} 条，数据源: {}", entities.size(), source);
        return source;
    }

    /**
     * Use AKShare data to fill in missing ROE values from Eastmoney results.
     */
    private void enrichROEFromAKShare(List<StockRead> stockList) {
        List<StockRead> akshareList = akshareAdapter.downloadStockData();
        if (akshareList.isEmpty()) {
            log.info("AKShare 无数据，跳过 ROE 补充");
            return;
        }

        Map<String, StockRead> akshareMap = akshareList.stream()
                .collect(Collectors.toMap(StockRead::getCode, s -> s, (a, b) -> a));

        int enriched = 0;
        for (StockRead stock : stockList) {
            String code = stock.getCode();
            StockRead akStock = akshareMap.get(code);
            if (akStock == null) {
                continue;
            }
            // Fill ROE if Eastmoney result is missing or zero
            if ("0".equals(stock.getRoe()) || "0.0".equals(stock.getRoe())
                    || "0.00".equals(stock.getRoe())) {
                if (!"0".equals(akStock.getRoe()) && !"0.0".equals(akStock.getRoe())
                        && !"0.00".equals(akStock.getRoe())) {
                    stock.setRoe(akStock.getRoe());
                    enriched++;
                }
            }
            // Fill industry if Eastmoney result is empty
            if (stock.getIndustry() == null || stock.getIndustry().isEmpty()) {
                if (akStock.getIndustry() != null && !akStock.getIndustry().isEmpty()) {
                    stock.setIndustry(akStock.getIndustry());
                }
            }
        }
        log.info("AKShare 补充 ROE: {} 条，总计 AKShare 返回 {} 条", enriched, akshareList.size());
    }

    public String getLastDownloadSource() {
        return lastDownloadSource;
    }

    public String getLastDownloadTime() {
        return lastDownloadTime;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean isAKShareAvailable() {
        if (!isAvailabilityCacheFresh()) refreshAvailabilityCache();
        return cachedAkshareAvailable != null && cachedAkshareAvailable;
    }

    public boolean isEastmoneyAvailable() {
        if (!isAvailabilityCacheFresh()) refreshAvailabilityCache();
        return cachedEastmoneyAvailable != null && cachedEastmoneyAvailable;
    }

    public boolean isCacheAvailable() {
        if (!isAvailabilityCacheFresh()) refreshAvailabilityCache();
        return cachedCacheAvailable != null && cachedCacheAvailable;
    }

    public int getStockCount() {
        return (int) stockRepository.count();
    }

    private String getStockMarket(String code) {
        if (code.startsWith("300") || code.startsWith("301")) {
            return "创业板";
        } else if (code.startsWith("60")) {
            return "沪市A股";
        } else if (code.startsWith("900")) {
            return "沪市B股";
        } else if (code.startsWith("000")) {
            return "深市A股";
        } else if (code.startsWith("002")) {
            return "中小板";
        } else if (code.startsWith("200")) {
            return "深圳B股";
        } else if (code.startsWith("688")) {
            return "科创板";
        } else if (code.startsWith("836")) {
            return "新三板";
        } else {
            return "其他";
        }
    }
}
