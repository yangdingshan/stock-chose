# StockChose Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Transform the CLI stock selection tool into a web app with Thymeleaf frontend, multi-source data download (Eastmoney -> cache fallback), and refactored service layer.

**Architecture:** Spring Boot 2.6.3 MVC with Thymeleaf server-side rendering. A `DataSourceAdapter` interface abstracts stock data fetching with Eastmoney as primary and local JSON cache as fallback. `StockService` is split into `StockRankService` (PE/PB/ROE ranking), `DataDownloadService` (download orchestration), and `IndexService` (CSI index processing). A single `StockController` serves four pages.

**Tech Stack:** Spring Boot 2.6.3, Java 8, Thymeleaf, Bootstrap 5, JPA/Hibernate, MySQL, EasyExcel, Hutool, Fastjson

---

### Task 1: Add web and Thymeleaf dependencies

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: Add `spring-boot-starter-web` and `spring-boot-starter-thymeleaf` to pom.xml**

Add inside `<dependencies>`, after the existing `spring-boot-starter-data-jpa` dependency:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>
```

- [ ] **Step 2: Verify build compiles**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add spring-boot-starter-web and thymeleaf dependencies

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: Create DataSourceAdapter interface and implementations

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/service/datasource/DataSourceAdapter.java`
- Create: `src/main/java/com/yangdingshan/stockchose/service/datasource/EastmoneyAdapter.java`
- Create: `src/main/java/com/yangdingshan/stockchose/service/datasource/CacheAdapter.java`
- Create: `src/main/java/com/yangdingshan/stockchose/domain/StockRead.java` (exists — verify path)

- [ ] **Step 1: Create DataSourceAdapter interface**

File: `src/main/java/com/yangdingshan/stockchose/service/datasource/DataSourceAdapter.java`

```java
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
```

- [ ] **Step 2: Create EastmoneyAdapter — extract download logic from StockService.downloadStockData()**

File: `src/main/java/com/yangdingshan/stockchose/service/datasource/EastmoneyAdapter.java`

```java
package com.yangdingshan.stockchose.service.datasource;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yangdingshan.stockchose.domain.StockRead;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EastmoneyAdapter implements DataSourceAdapter {

    @Override
    public String getName() {
        return "东方财富";
    }

    @Override
    public List<StockRead> downloadStockData() {
        List<StockRead> stockList = new ArrayList<>();
        int page = 1;
        int pageSize = 200;
        int consecutiveErrors = 0;
        final int MAX_CONSECUTIVE_ERRORS = 5;

        while (true) {
            String apiUrl = "http://push2.eastmoney.com/api/qt/clist/get";
            String params = String.format(
                    "pn=%d&pz=%d&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14,f2,f9,f23,f37",
                    page, pageSize);
            String fullUrl = apiUrl + "?" + params;

            String response = null;
            boolean requestSuccess = false;

            for (int retry = 0; retry < 3; retry++) {
                try {
                    response = HttpRequest.get(fullUrl)
                            .timeout(30_000)
                            .header("User-Agent",
                                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", "http://quote.eastmoney.com/")
                            .execute()
                            .body();

                    if (StrUtil.isNotBlank(response)) {
                        JSONObject jsonResponse = JSON.parseObject(response);
                        if (jsonResponse != null && jsonResponse.containsKey("data")) {
                            requestSuccess = true;
                            consecutiveErrors = 0;
                            break;
                        }
                    }
                } catch (Exception e) {
                    log.warn("第{}页第{}次请求失败: {}", page, retry + 1, e.getMessage());
                    try {
                        Thread.sleep(2000L * (retry + 1));
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            if (!requestSuccess || StrUtil.isBlank(response)) {
                consecutiveErrors++;
                log.error("第{}页获取失败，连续错误次数: {}/{}", page, consecutiveErrors, MAX_CONSECUTIVE_ERRORS);
                if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                    log.error("连续错误次数过多，结束下载。");
                    break;
                }
                try {
                    Thread.sleep(5000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            JSONObject jsonResponse = JSON.parseObject(response);
            JSONObject data = jsonResponse.getJSONObject("data");
            if (data == null) {
                break;
            }

            JSONArray diff = data.getJSONArray("diff");
            if (diff == null || diff.isEmpty()) {
                break;
            }

            log.info("正在处理第 {} 页，获取到 {} 条数据", page, diff.size());

            for (int i = 0; i < diff.size(); i++) {
                JSONObject stock = diff.getJSONObject(i);
                String code = stock.getString("f12");
                String name = stock.getString("f14");
                String priceStr = stock.getString("f2");
                String peStr = stock.getString("f9");
                String pbStr = stock.getString("f23");
                String roeStr = stock.getString("f37");

                if (!isNumber(priceStr) || !isNumber(peStr) || !isNumber(pbStr) || !isNumber(roeStr)) {
                    continue;
                }
                if (StrUtil.isBlank(name) || name.startsWith("*ST") || name.startsWith("ST")
                        || name.startsWith("PT") || name.startsWith("S")) {
                    continue;
                }
                if (Double.parseDouble(priceStr) <= 0 || Double.parseDouble(peStr) <= 0 ||
                        Double.parseDouble(pbStr) <= 0 || Double.parseDouble(roeStr) <= 0) {
                    continue;
                }

                StockRead stockRead = new StockRead();
                stockRead.setCode(code);
                stockRead.setName(name);
                stockRead.setPrice(priceStr);
                stockRead.setPe(peStr);
                stockRead.setPb(pbStr);
                stockRead.setRoe(roeStr);
                stockList.add(stockRead);
            }

            if (diff.size() < pageSize) {
                break;
            }

            try {
                Thread.sleep(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            page++;
        }

        log.info("东方财富下载完成，共获取有效股票数据: {} 条", stockList.size());
        return stockList;
    }

    @Override
    public boolean isAvailable() {
        try {
            String response = HttpRequest.get("http://push2.eastmoney.com/api/qt/clist/get?pn=1&pz=1&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14")
                    .timeout(10_000)
                    .execute()
                    .body();
            return StrUtil.isNotBlank(response) && JSON.parseObject(response).containsKey("data");
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isNumber(String str) {
        if (StrUtil.isBlank(str)) return false;
        try {
            Double.parseDouble(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
```

- [ ] **Step 3: Create CacheAdapter — local JSON file cache for fallback**

File: `src/main/java/com/yangdingshan/stockchose/service/datasource/CacheAdapter.java`

```java
package com.yangdingshan.stockchose.service.datasource;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yangdingshan.stockchose.domain.StockRead;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class CacheAdapter implements DataSourceAdapter {

    private static final String CACHE_FILE = "stock-cache.json";

    @Override
    public String getName() {
        return "本地缓存";
    }

    @Override
    public List<StockRead> downloadStockData() {
        List<StockRead> result = new ArrayList<>();
        Path cachePath = getCachePath();
        File file = cachePath.toFile();

        if (!file.exists()) {
            log.warn("本地缓存文件不存在: {}", cachePath);
            return result;
        }

        try {
            String json = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
            JSONArray array = JSON.parseArray(json);
            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                StockRead stockRead = new StockRead();
                stockRead.setCode(obj.getString("code"));
                stockRead.setName(obj.getString("name"));
                stockRead.setPrice(obj.getString("price"));
                stockRead.setPe(obj.getString("pe"));
                stockRead.setPb(obj.getString("pb"));
                stockRead.setRoe(obj.getString("roe"));
                result.add(stockRead);
            }
            log.info("从本地缓存加载 {} 条股票数据", result.size());
        } catch (IOException e) {
            log.error("读取本地缓存失败", e);
        }
        return result;
    }

    @Override
    public boolean isAvailable() {
        return getCachePath().toFile().exists();
    }

    /**
     * Save downloaded data to local cache (called after successful download from any source).
     */
    public void saveToCache(List<StockRead> stocks) {
        Path cachePath = getCachePath();
        JSONArray array = new JSONArray();
        for (StockRead s : stocks) {
            JSONObject obj = new JSONObject();
            obj.put("code", s.getCode());
            obj.put("name", s.getName());
            obj.put("price", s.getPrice());
            obj.put("pe", s.getPe());
            obj.put("pb", s.getPb());
            obj.put("roe", s.getRoe());
            array.add(obj);
        }
        try {
            Files.write(cachePath, array.toJSONString().getBytes(StandardCharsets.UTF_8));
            log.info("已保存 {} 条股票数据到本地缓存: {}", stocks.size(), cachePath);
        } catch (IOException e) {
            log.error("保存本地缓存失败", e);
        }
    }

    private Path getCachePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".stock-chose", CACHE_FILE);
    }
}
```

- [ ] **Step 4: Verify compilation**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/service/datasource/
git commit -m "feat: add DataSourceAdapter interface with Eastmoney and Cache implementations

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Create DataDownloadService — multi-source orchestration

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/service/DataDownloadService.java`

- [ ] **Step 1: Create DataDownloadService**

File: `src/main/java/com/yangdingshan/stockchose/service/DataDownloadService.java`

```java
package com.yangdingshan.stockchose.service;

import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.domain.StockRead;
import com.yangdingshan.stockchose.repository.StockRepository;
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
import java.util.stream.Collectors;

@Slf4j
@Service
public class DataDownloadService {

    @Autowired
    private EastmoneyAdapter eastmoneyAdapter;

    @Autowired
    private CacheAdapter cacheAdapter;

    @Autowired
    private StockRepository stockRepository;

    private String lastDownloadSource;
    private String lastDownloadTime;
    private String lastErrorMessage;

    /**
     * Download stock data with automatic fallback: Eastmoney -> Cache.
     * Returns the data source name that succeeded.
     */
    public String downloadStockData() {
        List<StockRead> stockList = null;
        String source = null;

        // Try primary: Eastmoney
        if (eastmoneyAdapter.isAvailable()) {
            log.info("尝试从东方财富下载股票数据...");
            stockList = eastmoneyAdapter.downloadStockData();
            if (!stockList.isEmpty()) {
                source = eastmoneyAdapter.getName();
                log.info("东方财富下载成功: {} 条", stockList.size());
            }
        }

        // Fallback: local cache
        if ((stockList == null || stockList.isEmpty()) && cacheAdapter.isAvailable()) {
            log.info("东方财富不可用，回退到本地缓存...");
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

    public String getLastDownloadSource() {
        return lastDownloadSource;
    }

    public String getLastDownloadTime() {
        return lastDownloadTime;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    public boolean isEastmoneyAvailable() {
        return eastmoneyAdapter.isAvailable();
    }

    public boolean isCacheAvailable() {
        return cacheAdapter.isAvailable();
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
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/service/DataDownloadService.java
git commit -m "feat: add DataDownloadService with multi-source fallback orchestration

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: Create StockRankService — extract ranking logic

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/service/StockRankService.java`

- [ ] **Step 1: Create StockRankService**

File: `src/main/java/com/yangdingshan/stockchose/service/StockRankService.java`

```java
package com.yangdingshan.stockchose.service;

import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.util.LambadaTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockRankService {

    private final StockRepository stockRepository;

    /**
     * Set PE, PB, and ROE ranks for all stocks.
     * PE: ascending (lower is better)
     * PB: ascending (lower is better)
     * ROE: descending (higher is better)
     */
    public void setPeRankAndRoeRank() {
        List<Stock> stocks = stockRepository.findAll();
        stocks.stream().sorted(Comparator.comparing(Stock::getPe))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPeRank));
        stocks.stream().sorted(Comparator.comparing(Stock::getPb))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPbRank));
        stocks.stream().sorted(Comparator.comparing(Stock::getRoe).reversed())
                .forEach(LambadaTools.forEachWithIndex(Stock::setRoeRank));
        stockRepository.saveAll(stocks);
        log.info("PE/PB/ROE排名已更新: {} 条", stocks.size());
    }

    /**
     * Compute composite scores: indexCountRank, peAndRoeCount, peAndRoeRank, indexPeRoeRank.
     * Must be called after setPeRankAndRoeRank() and index coverage counting.
     */
    public void setCompositeRanks() {
        List<Stock> stocks = stockRepository.findAll();

        // PE + ROE count = PE rank + ROE rank
        stocks.forEach(s -> s.setPeAndRoeCount(s.getPeRank() + s.getRoeRank()));

        // PE + ROE rank
        stocks.stream().sorted(Comparator.comparing(Stock::getPeAndRoeCount))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPeAndRoeRank));

        // Index count rank: more indices = better, tie-break by PE rank
        stocks.stream()
                .sorted(Comparator.comparing(Stock::getIndexCount).reversed()
                        .thenComparing(Stock::getPeRank))
                .forEach(LambadaTools.forEachWithIndex(Stock::setIndexCountRank));

        // Final composite: index rank + PE+ROE rank
        stocks.stream()
                .sorted(Comparator.comparing(s -> s.getIndexCountRank() + s.getPeAndRoeRank()))
                .forEach(LambadaTools.forEachWithIndex(Stock::setIndexPeRoeRank));

        stockRepository.saveAll(stocks);
        log.info("综合排名已更新: {} 条", stocks.size());
    }

    /**
     * Run full ranking pipeline (PE/PB/ROE ranks + composite ranks).
     */
    public void runFullRanking() {
        setPeRankAndRoeRank();
        setCompositeRanks();
    }

    public List<Stock> getTopStocks(int limit) {
        return stockRepository.findAll().stream()
                .sorted(Comparator.comparing(Stock::getIndexPeRoeRank))
                .limit(limit)
                .collect(java.util.stream.Collectors.toList());
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/service/StockRankService.java
git commit -m "feat: add StockRankService - extract ranking logic from StockService

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: Create IndexService — extract index processing logic

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/service/IndexService.java`

- [ ] **Step 1: Create IndexService**

File: `src/main/java/com/yangdingshan/stockchose/service/IndexService.java`

```java
package com.yangdingshan.stockchose.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yangdingshan.stockchose.domain.IndexRead;
import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexService {

    private final StockRepository stockRepository;

    private String indexDir = "src/main/resources/index";

    private String lastIndexRefreshTime;
    private int indexCount = 0;
    private String lastErrorMessage;

    /**
     * Download fresh index constituent files from csindex.com.cn.
     * Non-interactive version: downloads default tag hotSpot indices.
     */
    public void refreshIndexData() {
        try {
            // Clean existing index files
            File dir = new File(indexDir);
            File[] files = dir.listFiles();
            if (Objects.nonNull(files)) {
                for (File f : files) {
                    f.delete();
                }
            }
            log.info("index目录清理完成");

            // Fetch index list
            String tagList = HttpUtil.get("https://www.csindex.com.cn/csindex-home/index-list/tag-list");
            JSONObject tag = JSON.parseObject(tagList);
            JSONObject data = tag.getJSONObject("data");
            JSONArray hotSpotList = data.getJSONArray("hotSpotList");

            // Collect all tag IDs
            List<String> tagIds = new ArrayList<>();
            for (Object o : hotSpotList) {
                JSONObject hotSpot = (JSONObject) o;
                tagIds.add(hotSpot.getString("tagId"));
            }

            // Query indices by tags
            Map<String, Object> param = new HashMap<>();
            Map<String, Object> indexFilter = new HashMap<>();
            indexFilter.put("hotSpot", tagIds.toArray(new String[0]));
            param.put("indexFilter", indexFilter);

            Map<String, Object> sorter = new HashMap<>();
            sorter.put("sortField", "null");
            sorter.put("sortOrder", null);

            Map<String, Object> pager = new HashMap<>();
            pager.put("pageNum", 1);
            pager.put("pageSize", 500);
            param.put("sorter", sorter);
            param.put("pager", pager);

            String indexItem = HttpUtil.post(
                    "https://www.csindex.com.cn/csindex-home/index-list/query-index-item",
                    JSONObject.toJSONString(param));
            JSONObject jsonObject = JSON.parseObject(indexItem);
            JSONArray indexData = jsonObject.getJSONArray("data");

            int downloaded = 0;
            for (Object o : indexData) {
                try {
                    JSONObject index = (JSONObject) o;
                    String indexCode = index.getString("indexCode");
                    String indexName = index.getString("indexName");
                    log.info("下载指数: {}/{}", indexCode, indexName);

                    String urlString = String.format(
                            "https://oss-ch.csindex.com.cn/static/html/csindex/public/uploads/file/autofile/cons/%scons.xls",
                            indexCode);
                    String destinationPath = String.format("%s/%s.xls", indexDir, indexCode);
                    downloadFile(urlString, destinationPath);
                    downloaded++;
                    Thread.sleep(3000);
                } catch (Exception e) {
                    log.error("下载指数失败", e);
                }
            }

            indexCount = downloaded;
            lastIndexRefreshTime = new Date().toString();
            lastErrorMessage = null;
            log.info("指数数据刷新完成: {} 个指数", downloaded);

        } catch (Exception e) {
            lastErrorMessage = "指数数据刷新失败: " + e.getMessage();
            log.error("刷新指数数据失败", e);
        }
    }

    /**
     * Count how many indices each stock belongs to.
     */
    public void countIndexCoverage() {
        Map<String, Stock> resultMap = new HashMap<>();
        List<Stock> allStocks = stockRepository.findAll();
        allStocks.forEach(s -> resultMap.put(s.getCode(), s));

        File dir = new File(indexDir);
        File[] files = dir.listFiles();
        if (Objects.isNull(files)) {
            log.warn("index目录为空");
            return;
        }

        for (File file : files) {
            EasyExcel.read(file.getPath(), IndexRead.class, new ReadListener<IndexRead>() {
                private List<IndexRead> cachedDataList = ListUtils.newArrayListWithExpectedSize(1000);

                @Override
                public void invoke(IndexRead data, AnalysisContext context) {
                    cachedDataList.add(data);
                    if (cachedDataList.size() >= 1000) {
                        processBatch();
                        cachedDataList = ListUtils.newArrayListWithExpectedSize(1000);
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    processBatch();
                }

                private void processBatch() {
                    cachedDataList.forEach(indexRead -> {
                        if (resultMap.containsKey(indexRead.getConstituentCode())) {
                            Stock stock = resultMap.get(indexRead.getConstituentCode());
                            stock.setIndexCount(stock.getIndexCount() + 1);
                        }
                    });
                }
            }).sheet().doRead();
        }

        stockRepository.saveAll(new ArrayList<>(resultMap.values()));
        log.info("指数覆盖统计完成: {} 条股票", resultMap.size());
    }

    /**
     * Full index pipeline: download + count coverage.
     */
    public void runFullIndexPipeline() {
        refreshIndexData();
        countIndexCoverage();
    }

    public String getLastIndexRefreshTime() {
        return lastIndexRefreshTime;
    }

    public int getIndexCount() {
        if (indexCount == 0) {
            File dir = new File(indexDir);
            File[] files = dir.listFiles();
            if (Objects.nonNull(files)) {
                indexCount = files.length;
            }
        }
        return indexCount;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private void downloadFile(String urlString, String destinationPath) {
        try (BufferedInputStream in = new BufferedInputStream(new URL(urlString).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(destinationPath)) {
            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }
        } catch (IOException e) {
            log.error("文件下载失败: {} -> {}", urlString, destinationPath, e);
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/service/IndexService.java
git commit -m "feat: add IndexService - extract index processing from StockService

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: Refactor StockService — delegate to new services, keep backward compat

**Files:**
- Modify: `src/main/java/com/yangdingshan/stockchose/service/StockService.java`

- [ ] **Step 1: Refactor StockService to delegate to new services**

Replace the entire content of `StockService.java`:

```java
package com.yangdingshan.stockchose.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.domain.StockRead;
import com.yangdingshan.stockchose.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Facade service that delegates to specialized services.
 * Kept for backward compatibility with existing callers.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final StockRepository stockRepository;
    private final DataDownloadService dataDownloadService;
    private final StockRankService stockRankService;
    private final IndexService indexService;

    /** Download stock data with multi-source fallback. */
    public void downloadStockData() {
        String source = dataDownloadService.downloadStockData();
        if (source == null) {
            throw new RuntimeException("股票数据下载失败: " + dataDownloadService.getLastErrorMessage());
        }
        log.info("数据下载完成，数据源: {}", source);
    }

    /** Legacy: read from local Excel file. Used as fallback. */
    public void simpleRead() {
        stockRepository.deleteAll();
        String fileName = this.getClass().getClassLoader().getResource("stock/Table.xls").getPath();
        EasyExcel.read(fileName, StockRead.class, new ReadListener<StockRead>() {
            public static final int BATCH_COUNT = 1000;
            private List<StockRead> cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

            @Override
            public void invoke(StockRead data, AnalysisContext context) {
                if (StrUtil.isBlank(data.getName())) return;
                if (data.getName().startsWith("*ST") || data.getName().startsWith("ST")
                        || data.getName().startsWith("PT") || data.getName().startsWith("S")) return;
                if (StrUtil.isBlank(data.getPrice()) || "—".equals(data.getPrice())) return;
                if (StrUtil.isBlank(data.getPe()) || "—".equals(data.getPe())) return;
                if (StrUtil.isBlank(data.getRoe()) || "—".equals(data.getRoe())) return;
                if (StrUtil.isBlank(data.getPb()) || "—".equals(data.getPb())) return;
                if (new BigDecimal(data.getPrice()).compareTo(BigDecimal.ZERO) < 0) return;
                cachedDataList.add(data);
                if (cachedDataList.size() >= BATCH_COUNT) {
                    saveData();
                    cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                saveData();
            }

            private void saveData() {
                List<Stock> list = Lists.newArrayList();
                cachedDataList.forEach(sr -> {
                    Stock s = new Stock();
                    s.setCode(sr.getCode());
                    s.setName(sr.getName());
                    s.setPrice(new BigDecimal(sr.getPrice()));
                    s.setPe(Float.parseFloat(sr.getPe()));
                    s.setPb(Float.parseFloat(sr.getPb()));
                    s.setRoe(Float.parseFloat(sr.getRoe()));
                    s.setIndexCount(0);
                    s.setIndexCountRank(0);
                    s.setStockMarket(getStockMarket(s.getCode()));
                    s.setBuyTime(Instant.now());
                    list.add(s);
                });
                stockRepository.saveAll(list);
            }
        }).sheet().doRead();
    }

    /** Set PE/PB/ROE ranks. */
    public void setPeRankAndRoeRank() {
        stockRankService.setPeRankAndRoeRank();
    }

    /** Compute index coverage from downloaded index files. */
    public void simpleReadIndex() {
        indexService.countIndexCoverage();
        stockRankService.setCompositeRanks();
    }

    /** Refresh index constituent data from csindex.com.cn (non-interactive). */
    public void flushIndex() {
        indexService.runFullIndexPipeline();
    }

    /** Run full pipeline: download -> rank -> index -> composite. */
    public void runFullPipeline() {
        downloadStockData();
        stockRankService.runFullRanking();
        indexService.runFullIndexPipeline();
        stockRankService.setCompositeRanks();
    }

    private String getStockMarket(String code) {
        if (code.startsWith("300") || code.startsWith("301")) return "创业板";
        else if (code.startsWith("60")) return "沪市A股";
        else if (code.startsWith("900")) return "沪市B股";
        else if (code.startsWith("000")) return "深市A股";
        else if (code.startsWith("002")) return "中小板";
        else if (code.startsWith("200")) return "深圳B股";
        else if (code.startsWith("688")) return "科创板";
        else if (code.startsWith("836")) return "新三板";
        else return "其他";
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/service/StockService.java
git commit -m "refactor: StockService delegates to DataDownloadService, StockRankService, IndexService

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: Add Thymeleaf + server config to application.yaml

**Files:**
- Modify: `src/main/resources/application.yaml`

- [ ] **Step 1: Update application.yaml**

Replace content with:

```yaml
spring:
  datasource:
    username: root
    password: root
    url: jdbc:mysql://localhost:3306/stock?useUnicode=true&characterEncoding=UTF-8&useJDBCCompliantTimezoneShift=true&useLegacyDatetimeCode=false&serverTimezone=GMT%2b8&useSSL=false
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    show-sql: false
    hibernate:
      ddl-auto: update
  thymeleaf:
    cache: false
    prefix: classpath:/templates/
    suffix: .html
    encoding: UTF-8
    mode: HTML

server:
  port: 8080
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/application.yaml
git commit -m "config: add thymeleaf and server config

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Create Thymeleaf layout template

**Files:**
- Create: `src/main/resources/templates/layout.html`
- Create: `src/main/resources/static/css/style.css`

- [ ] **Step 1: Create custom CSS**

File: `src/main/resources/static/css/style.css`

```css
:root {
  --bg: #f8f9fa;
  --surface: #ffffff;
  --border: #e9ecef;
  --text: #1a1a2e;
  --text-secondary: #6c757d;
  --text-tertiary: #adb5bd;
  --accent: #0d6efd;
  --accent-soft: #e7f1ff;
  --green: #20c997;
  --green-soft: #e6fcf5;
  --amber: #f59f00;
  --amber-soft: #fff9db;
  --slate: #495057;
}

body {
  font-family: 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, sans-serif;
  background: var(--bg);
  color: var(--text);
  -webkit-font-smoothing: antialiased;
}

/* Nav */
.navbar-custom {
  background: var(--surface);
  border-bottom: 1px solid var(--border);
  padding: 12px 40px;
}
.navbar-custom .navbar-brand {
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.5px;
  color: var(--text);
}
.navbar-custom .nav-link {
  padding: 8px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  transition: all 0.15s;
  margin: 0 2px;
}
.navbar-custom .nav-link:hover {
  background: var(--bg);
  color: var(--text);
}
.navbar-custom .nav-link.active {
  background: var(--accent-soft);
  color: var(--accent);
}

/* Page */
.page-content {
  max-width: 1280px;
  margin: 0 auto;
  padding: 32px 40px;
}

/* Stats */
.stats-row {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr 1fr;
  gap: 16px;
  margin-bottom: 32px;
}
.stat-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 20px 24px;
}
.stat-card.accent { border-left: 3px solid var(--accent); }
.stat-card.green  { border-left: 3px solid var(--green); }
.stat-card.amber  { border-left: 3px solid var(--amber); }
.stat-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.8px;
  color: var(--text-tertiary);
  margin-bottom: 6px;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -1px;
  color: var(--text);
}
.stat-sub {
  font-size: 12px;
  color: var(--text-secondary);
  margin-top: 4px;
}

/* Section */
.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.section-title {
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.3px;
}

/* Table */
.table-wrap {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
}
.table-custom {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  margin: 0;
}
.table-custom thead th {
  text-align: left;
  padding: 12px 16px;
  font-size: 11px;
  font-weight: 600;
  color: var(--text-tertiary);
  background: #fafbfc;
  border-bottom: 1px solid var(--border);
  white-space: nowrap;
}
.table-custom thead th.right { text-align: right; }
.table-custom tbody td {
  padding: 10px 16px;
  border-bottom: 1px solid #f1f3f5;
  color: var(--text);
  vertical-align: middle;
}
.table-custom tbody td.right {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.table-custom tbody tr:hover { background: #fafbfc; }

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 24px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  padding: 0 8px;
}
.rank-1 { background: var(--amber-soft); color: var(--amber); }
.rank-2 { background: #f1f3f5; color: var(--slate); }
.rank-3 { background: #fff0e6; color: #e8590c; }

.score-highlight {
  font-weight: 700;
  color: var(--accent);
}

/* Download panels */
.download-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  margin-bottom: 24px;
}
.dl-panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 24px;
}
.dl-panel-title {
  font-size: 15px;
  font-weight: 600;
  letter-spacing: -0.3px;
  margin-bottom: 4px;
}
.dl-panel-desc {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 16px;
}
.dl-meta {
  display: flex;
  gap: 24px;
  margin-bottom: 16px;
}
.dl-meta-label {
  font-size: 10px;
  font-weight: 600;
  color: var(--text-tertiary);
}
.dl-meta-val {
  font-size: 13px;
  color: var(--text);
  margin-top: 2px;
}
.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-dot.ok { background: var(--green); }
.status-dot.err { background: #e64980; }

/* Log */
.log-box {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  padding: 16px 20px;
  font-family: 'JetBrains Mono', 'Cascadia Code', monospace;
  font-size: 11px;
  line-height: 1.8;
  color: var(--text-secondary);
  max-height: 200px;
  overflow-y: auto;
}

/* Index page */
.index-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.index-panel {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: 12px;
  overflow: hidden;
}
.index-panel-header {
  padding: 16px 20px;
  font-size: 14px;
  font-weight: 600;
  letter-spacing: -0.3px;
  border-bottom: 1px solid var(--border);
}
.index-panel-body { padding: 8px 0; }

/* Filter bar */
.filter-bar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.filter-input {
  padding: 8px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  width: 200px;
  outline: none;
  background: var(--surface);
}
.filter-input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-soft);
}
.filter-select {
  padding: 8px 14px;
  border: 1px solid var(--border);
  border-radius: 8px;
  font-size: 13px;
  background: var(--surface);
  outline: none;
  cursor: pointer;
  color: var(--text);
}
.filter-count {
  margin-left: auto;
  font-size: 12px;
  color: var(--text-tertiary);
}

/* Pagination */
.pagination-wrap {
  display: flex;
  justify-content: center;
  gap: 4px;
  margin-top: 16px;
}
.page-num {
  min-width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  border: 1px solid var(--border);
  background: var(--surface);
  color: var(--text);
  text-decoration: none;
  transition: all 0.15s;
  padding: 0 8px;
}
.page-num.active {
  background: var(--accent);
  color: #fff;
  border-color: var(--accent);
}
.page-num:hover:not(.active) { background: var(--bg); }

/* Buttons */
.btn-dl {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 10px 20px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  background: var(--accent);
  color: #fff;
  border: none;
  cursor: pointer;
  transition: all 0.15s;
}
.btn-dl:hover { filter: brightness(1.1); color: #fff; }
.btn-dl:active { transform: translateY(1px); }

.alert-custom {
  border-radius: 10px;
  font-size: 13px;
}

/* Spacing helpers */
.mt-section { margin-top: 48px; }
```

- [ ] **Step 2: Create Thymeleaf layout template**

File: `src/main/resources/templates/layout.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title th:text="${title} + ' - StockChose'">StockChose</title>
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.1.3/dist/css/bootstrap.min.css" rel="stylesheet">
    <link rel="stylesheet" th:href="@{/css/style.css}">
</head>
<body>
<nav class="navbar-custom sticky-top">
    <div class="d-flex align-items-center justify-content-between w-100">
        <a href="/" class="navbar-brand text-decoration-none">StockChose</a>
        <div class="d-flex">
            <a href="/" class="nav-link" th:classappend="${currentPage == 'dashboard'} ? 'active'">仪表盘</a>
            <a href="/ranking" class="nav-link" th:classappend="${currentPage == 'ranking'} ? 'active'">全部排名</a>
            <a href="/download" class="nav-link" th:classappend="${currentPage == 'download'} ? 'active'">下载管理</a>
            <a href="/index" class="nav-link" th:classappend="${currentPage == 'index'} ? 'active'">指数详情</a>
        </div>
    </div>
</nav>
<div class="page-content">
    <div th:replace="${content}"></div>
</div>
</body>
</html>
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/css/style.css src/main/resources/templates/layout.html
git commit -m "feat: add Thymeleaf layout template and custom CSS

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: Create StockController

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/controller/StockController.java`

- [ ] **Step 1: Create StockController**

File: `src/main/java/com/yangdingshan/stockchose/controller/StockController.java`

```java
package com.yangdingshan.stockchose.controller;

import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.service.DataDownloadService;
import com.yangdingshan.stockchose.service.IndexService;
import com.yangdingshan.stockchose.service.StockRankService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class StockController {

    private final StockRepository stockRepository;
    private final StockRankService stockRankService;
    private final DataDownloadService dataDownloadService;
    private final IndexService indexService;

    // ==================== Dashboard ====================

    @GetMapping("/")
    public String dashboard(Model model) {
        long totalStocks = stockRepository.count();
        long qualifiedStocks = totalStocks; // Already filtered during download

        List<Stock> top20 = stockRepository.findAll().stream()
                .filter(s -> s.getIndexPeRoeRank() != null)
                .sorted(Comparator.comparing(Stock::getIndexPeRoeRank))
                .limit(20)
                .collect(Collectors.toList());

        model.addAttribute("title", "仪表盘");
        model.addAttribute("currentPage", "dashboard");
        model.addAttribute("content", "pages/dashboard");
        model.addAttribute("totalStocks", totalStocks);
        model.addAttribute("qualifiedStocks", qualifiedStocks);
        model.addAttribute("indexCount", indexService.getIndexCount());
        model.addAttribute("lastDownloadTime", dataDownloadService.getLastDownloadTime());
        model.addAttribute("lastDownloadSource", dataDownloadService.getLastDownloadSource());
        model.addAttribute("topStocks", top20);
        return "layout";
    }

    // ==================== Full Ranking ====================

    @GetMapping("/ranking")
    public String ranking(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String market,
            @RequestParam(defaultValue = "composite") String sort,
            @RequestParam(defaultValue = "0") int page,
            Model model) {

        int pageSize = 50;
        List<Stock> allStocks = stockRepository.findAll();

        // Filter by search
        if (!search.isEmpty()) {
            allStocks = allStocks.stream()
                    .filter(s -> s.getCode().contains(search) || s.getName().contains(search))
                    .collect(Collectors.toList());
        }

        // Filter by market
        if (!market.isEmpty()) {
            allStocks = allStocks.stream()
                    .filter(s -> s.getStockMarket().equals(market))
                    .collect(Collectors.toList());
        }

        // Sort
        switch (sort) {
            case "pe":
                allStocks.sort(Comparator.comparing(Stock::getPeRank));
                break;
            case "roe":
                allStocks.sort(Comparator.comparing(Stock::getRoeRank));
                break;
            case "coverage":
                allStocks.sort(Comparator.comparing(Stock::getIndexCount).reversed());
                break;
            default: // composite
                allStocks.sort(Comparator.comparing(s -> s.getIndexPeRoeRank() != null ? s.getIndexPeRoeRank() : Integer.MAX_VALUE));
                break;
        }

        int totalPages = (int) Math.ceil((double) allStocks.size() / pageSize);
        int fromIndex = page * pageSize;
        int toIndex = Math.min(fromIndex + pageSize, allStocks.size());
        List<Stock> pageStocks = allStocks.subList(Math.min(fromIndex, allStocks.size()), toIndex);

        model.addAttribute("title", "全部排名");
        model.addAttribute("currentPage", "ranking");
        model.addAttribute("content", "pages/ranking");
        model.addAttribute("stocks", pageStocks);
        model.addAttribute("totalCount", allStocks.size());
        model.addAttribute("currentPageNum", page);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("search", search);
        model.addAttribute("market", market);
        model.addAttribute("sort", sort);
        return "layout";
    }

    // ==================== Download Management ====================

    @GetMapping("/download")
    public String downloadPage(Model model) {
        model.addAttribute("title", "下载管理");
        model.addAttribute("currentPage", "download");
        model.addAttribute("content", "pages/download");
        model.addAttribute("eastmoneyAvailable", dataDownloadService.isEastmoneyAvailable());
        model.addAttribute("cacheAvailable", dataDownloadService.isCacheAvailable());
        model.addAttribute("lastDownloadSource", dataDownloadService.getLastDownloadSource());
        model.addAttribute("lastDownloadTime", dataDownloadService.getLastDownloadTime());
        model.addAttribute("stockCount", dataDownloadService.getStockCount());
        model.addAttribute("indexCount", indexService.getIndexCount());
        model.addAttribute("lastIndexRefreshTime", indexService.getLastIndexRefreshTime());
        return "layout";
    }

    @PostMapping("/download/stocks")
    @ResponseBody
    public Map<String, Object> triggerStockDownload() {
        Map<String, Object> result = new HashMap<>();
        try {
            String source = dataDownloadService.downloadStockData();
            if (source != null) {
                stockRankService.runFullRanking();
            }
            result.put("success", source != null);
            result.put("source", source);
            result.put("count", dataDownloadService.getStockCount());
            result.put("error", dataDownloadService.getLastErrorMessage());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @PostMapping("/download/indices")
    @ResponseBody
    public Map<String, Object> triggerIndexRefresh() {
        Map<String, Object> result = new HashMap<>();
        try {
            indexService.runFullIndexPipeline();
            stockRankService.setCompositeRanks();
            result.put("success", true);
            result.put("indexCount", indexService.getIndexCount());
            result.put("time", indexService.getLastIndexRefreshTime());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    // ==================== Index Coverage ====================

    @GetMapping("/index")
    public String indexPage(Model model) {
        // Top stocks by index coverage
        List<Stock> topByCoverage = stockRepository.findAll().stream()
                .filter(s -> s.getIndexCount() != null && s.getIndexCount() > 0)
                .sorted(Comparator.comparing(Stock::getIndexCount).reversed())
                .limit(20)
                .collect(Collectors.toList());

        model.addAttribute("title", "指数覆盖详情");
        model.addAttribute("currentPage", "index");
        model.addAttribute("content", "pages/index-detail");
        model.addAttribute("topByCoverage", topByCoverage);
        model.addAttribute("indexCount", indexService.getIndexCount());
        return "layout";
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./mvnw clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/controller/StockController.java
git commit -m "feat: add StockController with 4 page routes and download API

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: Create Dashboard page template

**Files:**
- Create: `src/main/resources/templates/pages/dashboard.html`

- [ ] **Step 1: Create dashboard.html**

File: `src/main/resources/templates/pages/dashboard.html`

```html
<div th:fragment="content">
    <div class="section-header">
        <span class="section-title">仪表盘</span>
        <span style="font-size:11px;color:var(--text-tertiary)" th:if="${lastDownloadTime}">
            数据更新于 <span th:text="${lastDownloadTime}"></span>
            <span th:if="${lastDownloadSource}"> · 来源: <span th:text="${lastDownloadSource}"></span></span>
        </span>
        <span style="font-size:11px;color:var(--text-tertiary)" th:unless="${lastDownloadTime}">
            暂无数据，请前往下载管理获取数据
        </span>
    </div>

    <div class="stats-row">
        <div class="stat-card accent">
            <div class="stat-label">A股总数</div>
            <div class="stat-value" th:text="${totalStocks}">0</div>
            <div class="stat-sub">数据库中的股票数量</div>
        </div>
        <div class="stat-card green">
            <div class="stat-label">有效标的</div>
            <div class="stat-value" th:text="${qualifiedStocks}">0</div>
            <div class="stat-sub">已剔除ST及负值标的</div>
        </div>
        <div class="stat-card amber">
            <div class="stat-label">收录指数</div>
            <div class="stat-value" th:text="${indexCount}">0</div>
            <div class="stat-sub">巨潮指数成分股</div>
        </div>
        <div class="stat-card">
            <div class="stat-label">评分模型</div>
            <div class="stat-value" style="font-size:18px">PE + ROE + 指数</div>
            <div class="stat-sub">三因子综合排名</div>
        </div>
    </div>

    <div th:if="${topStocks != null && !topStocks.isEmpty()}">
        <div class="section-header">
            <span class="section-title">综合排名 Top 20</span>
            <a href="/ranking" style="font-size:12px;font-weight:500;color:var(--accent);text-decoration:none">查看全部排名</a>
        </div>
        <div class="table-wrap">
            <table class="table-custom">
                <thead>
                    <tr>
                        <th style="width:48px">排名</th>
                        <th>代码</th>
                        <th>名称</th>
                        <th class="right">股价</th>
                        <th class="right">市盈率</th>
                        <th class="right">市净率</th>
                        <th class="right">ROE</th>
                        <th class="right">PE排名</th>
                        <th class="right">ROE排名</th>
                        <th class="right">指数覆盖</th>
                        <th class="right">综合得分</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="stock,iterStat : ${topStocks}">
                        <td>
                            <span class="rank-badge" th:classappend="${iterStat.index == 0} ? 'rank-1' : (${iterStat.index == 1} ? 'rank-2' : (${iterStat.index == 2} ? 'rank-3' : ''))"
                                  th:text="${iterStat.index + 1}">1</span>
                        </td>
                        <td style="font-weight:600" th:text="${stock.code}">600519</td>
                        <td th:text="${stock.name}">贵州茅台</td>
                        <td class="right" th:text="${stock.price}">0.00</td>
                        <td class="right" th:text="${stock.pe}">0.0</td>
                        <td class="right" th:text="${stock.pb}">0.0</td>
                        <td class="right" th:text="${stock.roe} + '%'">0.0%</td>
                        <td class="right" th:text="${stock.peRank}">0</td>
                        <td class="right" th:text="${stock.roeRank}">0</td>
                        <td class="right" th:text="${stock.indexCount}">0</td>
                        <td class="right score-highlight" th:text="${stock.indexPeRoeRank}">0</td>
                    </tr>
                </tbody>
            </table>
        </div>
    </div>

    <div th:unless="${topStocks != null && !topStocks.isEmpty()}" class="mt-section">
        <div style="background:var(--surface);border:1px solid var(--border);border-radius:12px;padding:60px;text-align:center">
            <div style="font-size:40px;margin-bottom:16px;opacity:0.3">&#8943;</div>
            <div style="font-size:15px;font-weight:600;color:var(--text);margin-bottom:8px">暂无股票数据</div>
            <div style="font-size:13px;color:var(--text-secondary);margin-bottom:20px">请前往下载管理页面获取最新的A股数据</div>
            <a href="/download" style="display:inline-flex;align-items:center;gap:6px;padding:10px 20px;border-radius:8px;font-size:13px;font-weight:600;background:var(--accent);color:#fff;text-decoration:none">前往下载</a>
        </div>
    </div>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/pages/dashboard.html
git commit -m "feat: add dashboard page template with stats and Top 20 table

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: Create Full Ranking page template

**Files:**
- Create: `src/main/resources/templates/pages/ranking.html`

- [ ] **Step 1: Create ranking.html**

File: `src/main/resources/templates/pages/ranking.html`

```html
<div th:fragment="content">
    <div class="section-header">
        <span class="section-title">全部排名</span>
    </div>

    <form class="filter-bar" method="get" action="/ranking">
        <input class="filter-input" type="text" name="search" placeholder="搜索代码或名称..." th:value="${search}">
        <select class="filter-select" name="market">
            <option value="">全部市场</option>
            <option value="沪市A股" th:selected="${market == '沪市A股'}">沪市A股</option>
            <option value="深市A股" th:selected="${market == '深市A股'}">深市A股</option>
            <option value="创业板" th:selected="${market == '创业板'}">创业板</option>
            <option value="科创板" th:selected="${market == '科创板'}">科创板</option>
            <option value="中小板" th:selected="${market == '中小板'}">中小板</option>
        </select>
        <select class="filter-select" name="sort">
            <option value="composite" th:selected="${sort == 'composite'}">排序：综合得分</option>
            <option value="pe" th:selected="${sort == 'pe'}">排序：PE排名</option>
            <option value="roe" th:selected="${sort == 'roe'}">排序：ROE排名</option>
            <option value="coverage" th:selected="${sort == 'coverage'}">排序：指数覆盖</option>
        </select>
        <button type="submit" class="btn-dl" style="padding:8px 16px;font-size:12px">筛选</button>
        <span class="filter-count" th:text="'共 ' + ${totalCount} + ' 条'">共 0 条</span>
    </form>

    <div class="table-wrap">
        <table class="table-custom">
            <thead>
                <tr>
                    <th style="width:48px">排名</th>
                    <th>代码</th>
                    <th>名称</th>
                    <th>市场</th>
                    <th class="right">市盈率</th>
                    <th class="right">PE排名</th>
                    <th class="right">市净率</th>
                    <th class="right">ROE</th>
                    <th class="right">ROE排名</th>
                    <th class="right">指数覆盖</th>
                    <th class="right">综合得分</th>
                </tr>
            </thead>
            <tbody>
                <tr th:each="stock,iterStat : ${stocks}">
                    <td><span class="rank-badge" th:text="${currentPageNum * 50 + iterStat.index + 1}">1</span></td>
                    <td style="font-weight:600" th:text="${stock.code}"></td>
                    <td th:text="${stock.name}"></td>
                    <td style="color:var(--text-secondary);font-size:12px" th:text="${stock.stockMarket}"></td>
                    <td class="right" th:text="${stock.pe}"></td>
                    <td class="right" th:text="${stock.peRank}"></td>
                    <td class="right" th:text="${stock.pb}"></td>
                    <td class="right" th:text="${stock.roe} + '%'"></td>
                    <td class="right" th:text="${stock.roeRank}"></td>
                    <td class="right" th:text="${stock.indexCount}"></td>
                    <td class="right score-highlight" th:text="${stock.indexPeRoeRank}"></td>
                </tr>
                <tr th:if="${stocks == null || stocks.isEmpty()}">
                    <td colspan="11" style="text-align:center;padding:40px;color:var(--text-tertiary)">暂无数据</td>
                </tr>
            </tbody>
        </table>
    </div>

    <div class="pagination-wrap" th:if="${totalPages > 1}">
        <a th:each="i : ${#numbers.sequence(1, totalPages)}"
           class="page-num"
           th:classappend="${i - 1 == currentPageNum} ? 'active'"
           th:href="@{/ranking(search=${search}, market=${market}, sort=${sort}, page=${i - 1})}"
           th:text="${i}">1</a>
    </div>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/pages/ranking.html
git commit -m "feat: add full ranking page with search, filter, sort, pagination

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: Create Download Management page template

**Files:**
- Create: `src/main/resources/templates/pages/download.html`

- [ ] **Step 1: Create download.html**

File: `src/main/resources/templates/pages/download.html`

```html
<div th:fragment="content">
    <div class="section-header">
        <span class="section-title">下载管理</span>
    </div>

    <div class="download-grid">
        <div class="dl-panel">
            <div class="dl-panel-title">股票数据</div>
            <div class="dl-panel-desc">A股基本面数据，多数据源自动容错</div>
            <div class="dl-meta">
                <div>
                    <div class="dl-meta-label">主数据源</div>
                    <div class="dl-meta-val">
                        <span class="status-dot" th:classappend="${eastmoneyAvailable} ? 'ok' : 'err'"></span>
                        东方财富
                    </div>
                </div>
                <div>
                    <div class="dl-meta-label">本地缓存</div>
                    <div class="dl-meta-val">
                        <span class="status-dot" th:classappend="${cacheAvailable} ? 'ok' : 'err'"></span>
                        <span th:text="${cacheAvailable} ? '可用' : '不可用'">可用</span>
                    </div>
                </div>
                <div>
                    <div class="dl-meta-label">上次下载</div>
                    <div class="dl-meta-val" th:text="${lastDownloadTime} ?: '从未下载'">-</div>
                </div>
            </div>
            <div th:if="${lastDownloadSource}" style="margin-bottom:16px;font-size:12px;color:var(--text-secondary)">
                数据来源: <span th:text="${lastDownloadSource}"></span> · 库存: <span th:text="${stockCount}"></span> 条
            </div>
            <button class="btn-dl" onclick="downloadStocks()">立即下载</button>
            <span id="stock-dl-status" style="margin-left:12px;font-size:12px;color:var(--text-secondary)"></span>
        </div>

        <div class="dl-panel">
            <div class="dl-panel-title">指数成分股</div>
            <div class="dl-panel-desc">巨潮指数成分股列表，自动下载并解析</div>
            <div class="dl-meta">
                <div>
                    <div class="dl-meta-label">已下载指数</div>
                    <div class="dl-meta-val" th:text="${indexCount} + ' 个'">0 个</div>
                </div>
                <div>
                    <div class="dl-meta-label">上次刷新</div>
                    <div class="dl-meta-val" th:text="${lastIndexRefreshTime} ?: '从未刷新'">-</div>
                </div>
            </div>
            <button class="btn-dl" onclick="refreshIndices()">刷新指数</button>
            <span id="index-dl-status" style="margin-left:12px;font-size:12px;color:var(--text-secondary)"></span>
        </div>
    </div>

    <div class="section-header" style="margin-top:8px">
        <span style="font-size:14px;font-weight:600">下载日志</span>
    </div>
    <div class="log-box" id="download-log">
        <span style="color:var(--text-tertiary)">系统就绪，点击按钮开始下载...</span>
    </div>
</div>

<script>
function log(msg) {
    var logBox = document.getElementById('download-log');
    var now = new Date().toLocaleString('zh-CN');
    logBox.innerHTML = '<span style="color:var(--text-tertiary)">[' + now + ']</span> ' + msg + '<br>' + logBox.innerHTML;
}

function downloadStocks() {
    var btn = event.target;
    var status = document.getElementById('stock-dl-status');
    btn.disabled = true;
    btn.style.opacity = '0.6';
    status.textContent = '下载中...';
    log('开始下载股票数据...');

    fetch('/download/stocks', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.style.opacity = '1';
            if (data.success) {
                status.textContent = '完成，来源: ' + data.source + '，共 ' + data.count + ' 条';
                log('股票数据下载完成 | 数据源: ' + data.source + ' | 共 ' + data.count + ' 条');
                setTimeout(function() { location.reload(); }, 1500);
            } else {
                status.textContent = '失败: ' + (data.error || '未知错误');
                status.style.color = '#e64980';
                log('下载失败: ' + (data.error || '未知错误'));
            }
        })
        .catch(function(e) {
            btn.disabled = false;
            btn.style.opacity = '1';
            status.textContent = '请求失败';
            log('请求失败: ' + e.message);
        });
}

function refreshIndices() {
    var btn = event.target;
    var status = document.getElementById('index-dl-status');
    btn.disabled = true;
    btn.style.opacity = '0.6';
    status.textContent = '刷新中...';
    log('开始下载指数数据...');

    fetch('/download/indices', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function(data) {
            btn.disabled = false;
            btn.style.opacity = '1';
            if (data.success) {
                status.textContent = '完成，共 ' + data.indexCount + ' 个指数';
                log('指数数据刷新完成 | ' + data.indexCount + ' 个指数下载成功');
                setTimeout(function() { location.reload(); }, 1500);
            } else {
                status.textContent = '失败: ' + (data.error || '未知错误');
                status.style.color = '#e64980';
                log('指数刷新失败: ' + (data.error || '未知错误'));
            }
        })
        .catch(function(e) {
            btn.disabled = false;
            btn.style.opacity = '1';
            status.textContent = '请求失败';
            log('请求失败: ' + e.message);
        });
}
</script>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/pages/download.html
git commit -m "feat: add download management page with stock/index download buttons

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 13: Create Index Coverage page template

**Files:**
- Create: `src/main/resources/templates/pages/index-detail.html`

- [ ] **Step 1: Create index-detail.html**

File: `src/main/resources/templates/pages/index-detail.html`

```html
<div th:fragment="content">
    <div class="section-header">
        <span class="section-title">指数覆盖详情</span>
        <span style="font-size:11px;color:var(--text-tertiary)">已收录 <span th:text="${indexCount}">0</span> 个指数</span>
    </div>

    <div class="index-grid">
        <div class="index-panel">
            <div class="index-panel-header">个股指数覆盖排行</div>
            <div class="index-panel-body">
                <table class="table-custom">
                    <thead>
                        <tr>
                            <th>代码</th>
                            <th>名称</th>
                            <th class="right">收录指数数</th>
                            <th class="right">排名</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr th:each="stock,iterStat : ${topByCoverage}">
                            <td style="font-weight:600" th:text="${stock.code}"></td>
                            <td th:text="${stock.name}"></td>
                            <td class="right score-highlight" th:text="${stock.indexCount}"></td>
                            <td class="right" th:text="${iterStat.index + 1}"></td>
                        </tr>
                        <tr th:if="${topByCoverage == null || topByCoverage.isEmpty()}">
                            <td colspan="4" style="text-align:center;padding:40px;color:var(--text-tertiary)">
                                暂无指数数据，请先在下载管理页面刷新指数
                            </td>
                        </tr>
                    </tbody>
                </table>
            </div>
        </div>

        <div class="index-panel">
            <div class="index-panel-header">典型指数成分股数量</div>
            <div class="index-panel-body">
                <table class="table-custom">
                    <thead>
                        <tr>
                            <th>指数代码</th>
                            <th>指数名称</th>
                            <th class="right">成分股数量</th>
                        </tr>
                    </thead>
                    <tbody>
                        <tr><td style="font-weight:600">000300</td><td>沪深300</td><td class="right">300</td></tr>
                        <tr><td style="font-weight:600">000905</td><td>中证500</td><td class="right">500</td></tr>
                        <tr><td style="font-weight:600">000852</td><td>中证1000</td><td class="right">1,000</td></tr>
                        <tr><td style="font-weight:600">000688</td><td>科创50</td><td class="right">50</td></tr>
                        <tr><td style="font-weight:600">000016</td><td>上证50</td><td class="right">50</td></tr>
                    </tbody>
                </table>
            </div>
        </div>
    </div>
</div>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/pages/index-detail.html
git commit -m "feat: add index coverage detail page

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 14: Build and run the application

- [ ] **Step 1: Full build**

```bash
./mvnw clean package -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Run the application and verify pages load**

```bash
./mvnw spring-boot:run
```

Then open `http://localhost:8080` in browser. Verify:
- Dashboard loads (may show empty state)
- `/ranking` loads with filter bar
- `/download` loads with download buttons
- `/index` loads with index panels

- [ ] **Step 3: Commit any remaining files**

```bash
git status
git add -A
git commit -m "chore: finalize web frontend build

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Summary of Created Files

| File | Purpose |
|------|---------|
| `service/datasource/DataSourceAdapter.java` | Interface for stock data sources |
| `service/datasource/EastmoneyAdapter.java` | Eastmoney API implementation |
| `service/datasource/CacheAdapter.java` | Local JSON cache fallback |
| `service/DataDownloadService.java` | Multi-source download orchestration |
| `service/StockRankService.java` | PE/PB/ROE ranking logic |
| `service/IndexService.java` | CSI index download & coverage counting |
| `controller/StockController.java` | MVC controller, 4 pages + 2 POST APIs |
| `templates/layout.html` | Thymeleaf layout with nav |
| `templates/pages/dashboard.html` | Dashboard page |
| `templates/pages/ranking.html` | Full ranking page |
| `templates/pages/download.html` | Download management page |
| `templates/pages/index-detail.html` | Index coverage page |
| `static/css/style.css` | Custom styles |

## Summary of Modified Files

| File | Change |
|------|--------|
| `pom.xml` | Added `spring-boot-starter-web` and `spring-boot-starter-thymeleaf` |
| `application.yaml` | Added Thymeleaf and server config, disabled SQL logging |
| `service/StockService.java` | Refactored to facade delegating to new services |
