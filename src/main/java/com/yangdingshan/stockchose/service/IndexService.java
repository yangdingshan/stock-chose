package com.yangdingshan.stockchose.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.yangdingshan.stockchose.domain.IndexRead;
import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexService {

    private final StockRepository stockRepository;

    private String indexDir = "src/main/resources/index";

    private String lastIndexRefreshTime;
    private int indexCount = 0;
    private String lastErrorMessage;
    private Map<String, Map<String, Object>> indexStocksCache = new HashMap<>();

    // Progress tracking for real-time frontend feedback
    private volatile int refreshProgressCurrent;
    private volatile int refreshProgressTotal;
    private volatile String refreshProgressName = "";
    private volatile int refreshProgressStocks;
    private volatile String refreshProgressStatus = "";

    /**
     * Refresh index data by calling AKShare Python script.
     * Downloads constituent data for 80+ major A-share indexes.
     */
    public void refreshIndexData() {
        try {
            String scriptPath = findScriptPath();
            if (scriptPath == null) {
                lastErrorMessage = "找不到 index_download.py 脚本";
                log.error(lastErrorMessage);
                return;
            }

            ProcessBuilder pb = new ProcessBuilder("python", scriptPath);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            // Reset progress
            refreshProgressCurrent = 0;
            refreshProgressTotal = 0;
            refreshProgressName = "";

            // Read stdout in a separate thread so the main thread can enforce timeout
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("{")) {
                            // Parse progress updates
                            JSONObject obj = JSON.parseObject(line);
                            if (obj.containsKey("progress")) {
                                JSONObject p = obj.getJSONObject("progress");
                                refreshProgressCurrent = p.getIntValue("current");
                                refreshProgressTotal = p.getIntValue("total");
                                refreshProgressName = p.getString("name");
                                refreshProgressStocks = p.getIntValue("stocks");
                                refreshProgressStatus = p.getString("status");
                            } else {
                                // Final result
                                output.append(line);
                            }
                        }
                    }
                } catch (IOException e) {
                    // stream closed
                }
            }, "index-script-reader");
            readerThread.start();

            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                lastErrorMessage = "指数下载脚本执行超时";
                log.error(lastErrorMessage);
                return;
            }

            // Wait for reader thread to finish consuming remaining output
            readerThread.join(5000);

            if (process.exitValue() != 0) {
                lastErrorMessage = "指数下载脚本退出码: " + process.exitValue();
                log.error(lastErrorMessage);
                return;
            }

            String jsonStr = output.toString();
            if (jsonStr.isEmpty()) {
                lastErrorMessage = "指数下载脚本无输出";
                log.error(lastErrorMessage);
                return;
            }

            JSONObject result = JSON.parseObject(jsonStr);
            if (result == null || result.containsKey("error")) {
                lastErrorMessage = "指数下载脚本错误: " + (result != null ? result.getString("error") : "null");
                log.error(lastErrorMessage);
                return;
            }

            // Save to cache file for countIndexCoverage
            Path cachePath = getIndexCachePath();
            Path parent = cachePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(cachePath, jsonStr.getBytes(StandardCharsets.UTF_8));

            // Parse per-index constituent data for search
            JSONObject indexStocksJson = result.getJSONObject("index_stocks");
            if (indexStocksJson != null) {
                indexStocksCache.clear();
                for (String code : indexStocksJson.keySet()) {
                    JSONObject info = indexStocksJson.getJSONObject(code);
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", info.getString("name"));
                    map.put("count", info.getInteger("count"));
                    map.put("codes", info.getJSONArray("codes").toJavaList(String.class));
                    indexStocksCache.put(code, map);
                }
            }

            indexCount = result.getIntValue("index_count");
            lastIndexRefreshTime = new Date().toString();
            lastErrorMessage = null;
            log.info("指数数据刷新完成: {} 个指数", indexCount);

        } catch (Exception e) {
            lastErrorMessage = "指数数据刷新失败: " + e.getMessage();
            log.error("刷新指数数据失败", e);
        }
    }

    /**
     * Count how many indices each stock belongs to.
     * Reads from cached JSON or falls back to legacy .xls files.
     */
    public void countIndexCoverage() {
        Map<String, Stock> resultMap = new HashMap<>();
        List<Stock> allStocks = stockRepository.findAll();
        allStocks.forEach(s -> {
            s.setIndexCount(0);
            resultMap.put(s.getCode(), s);
        });

        Path cachePath = getIndexCachePath();
        if (Files.exists(cachePath)) {
            // Read from AKShare JSON cache
            try {
                String json = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
                JSONObject data = JSON.parseObject(json);
                JSONObject stocks = data.getJSONObject("stocks");
                if (stocks != null) {
                    for (String code : stocks.keySet()) {
                        Stock stock = resultMap.get(code);
                        if (stock != null) {
                            stock.setIndexCount(stocks.getIntValue(code));
                        }
                    }
                }
                stockRepository.saveAll(new ArrayList<>(resultMap.values()));
                log.info("指数覆盖统计完成(JSON): {} 条股票", resultMap.size());
                return;
            } catch (IOException e) {
                log.warn("读取指数JSON缓存失败，回退到xls文件", e);
            }
        }

        // Legacy: read from .xls files in index directory
        File dir = new File(indexDir);
        File[] files = dir.listFiles();
        if (Objects.isNull(files) || files.length == 0) {
            log.warn("index目录为空，且无JSON缓存");
            return;
        }

        for (File file : files) {
            if (!file.getName().endsWith(".xls")) continue;
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
        log.info("指数覆盖统计完成(xls): {} 条股票", resultMap.size());
    }

    private volatile boolean indexRefreshRunning = false;

    /**
     * Full index pipeline: download + count coverage.
     * Runs in a background thread to avoid blocking the HTTP request.
     */
    public void runFullIndexPipeline() {
        if (indexRefreshRunning) {
            log.warn("指数刷新正在进行中，跳过重复触发");
            return;
        }
        indexRefreshRunning = true;
        new Thread(() -> {
            try {
                refreshIndexData();
                countIndexCoverage();
            } finally {
                indexRefreshRunning = false;
            }
        }, "index-refresh-thread").start();
    }

    /**
     * Async version with callback after completion.
     */
    public void runFullIndexPipelineAsync(Runnable onComplete) {
        if (indexRefreshRunning) {
            log.warn("指数刷新正在进行中，跳过重复触发");
            return;
        }
        indexRefreshRunning = true;
        new Thread(() -> {
            try {
                refreshIndexData();
                countIndexCoverage();
                if (onComplete != null) {
                    onComplete.run();
                }
            } finally {
                indexRefreshRunning = false;
            }
        }, "index-refresh-thread").start();
    }

    public void runSelectiveIndexPipeline(List<String> codes, Runnable onComplete) {
        if (indexRefreshRunning) {
            log.warn("指数刷新正在进行中，跳过重复触发");
            return;
        }
        indexRefreshRunning = true;
        new Thread(() -> {
            try {
                downloadSelectedIndices(codes);
                countIndexCoverage();
                if (onComplete != null) {
                    onComplete.run();
                }
            } finally {
                indexRefreshRunning = false;
            }
        }, "index-refresh-thread").start();
    }

    public boolean isIndexRefreshRunning() {
        return indexRefreshRunning;
    }

    public int getRefreshProgressCurrent() {
        return refreshProgressCurrent;
    }

    public int getRefreshProgressTotal() {
        return refreshProgressTotal;
    }

    public String getRefreshProgressName() {
        return refreshProgressName;
    }

    public int getRefreshProgressStocks() {
        return refreshProgressStocks;
    }

    public String getRefreshProgressStatus() {
        return refreshProgressStatus;
    }

    public String getLastIndexRefreshTime() {
        return lastIndexRefreshTime;
    }

    public int getIndexCount() {
        if (indexCount == 0) {
            // Check AKShare cache first
            Path cachePath = getIndexCachePath();
            if (Files.exists(cachePath)) {
                try {
                    String json = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
                    JSONObject data = JSON.parseObject(json);
                    indexCount = data.getIntValue("index_count");
                } catch (IOException e) {
                    // ignore
                }
            }
            // Fall back to counting .xls files
            if (indexCount == 0) {
                File dir = new File(indexDir);
                File[] files = dir.listFiles();
                if (Objects.nonNull(files)) {
                    indexCount = files.length;
                }
            }
        }
        return indexCount;
    }

    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    private void ensureIndexStocksCacheLoaded() {
        if (!indexStocksCache.isEmpty()) {
            return;
        }
        Path cachePath = getIndexCachePath();
        if (Files.exists(cachePath)) {
            try {
                String json = new String(Files.readAllBytes(cachePath), StandardCharsets.UTF_8);
                JSONObject data = JSON.parseObject(json);
                JSONObject indexStocksJson = data.getJSONObject("index_stocks");
                if (indexStocksJson != null) {
                    for (String code : indexStocksJson.keySet()) {
                        JSONObject info = indexStocksJson.getJSONObject(code);
                        Map<String, Object> map = new HashMap<>();
                        map.put("name", info.getString("name"));
                        map.put("count", info.getInteger("count"));
                        map.put("codes", info.getJSONArray("codes").toJavaList(String.class));
                        indexStocksCache.put(code, map);
                    }
                }
            } catch (IOException e) {
                log.warn("读取index_stocks缓存失败", e);
            }
        }
    }

    public List<Map<String, Object>> getIndexList() {
        ensureIndexStocksCacheLoaded();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : indexStocksCache.entrySet()) {
            Map<String, Object> item = new HashMap<>();
            item.put("code", entry.getKey());
            item.put("name", entry.getValue().get("name"));
            item.put("count", entry.getValue().get("count"));
            list.add(item);
        }
        list.sort(Comparator.comparing(m -> (String) m.get("name")));
        return list;
    }

    public List<String> getIndexConstituents(String indexCode) {
        ensureIndexStocksCacheLoaded();
        Map<String, Object> info = indexStocksCache.get(indexCode);
        if (info == null) {
            return Collections.emptyList();
        }
        @SuppressWarnings("unchecked")
        List<String> codes = (List<String>) info.get("codes");
        return codes != null ? codes : Collections.emptyList();
    }

    private JSONObject runPythonScript(String... args) {
        try {
            String scriptPath = findScriptPath();
            if (scriptPath == null) {
                lastErrorMessage = "找不到 index_download.py 脚本";
                log.error(lastErrorMessage);
                return null;
            }

            List<String> cmd = new ArrayList<>();
            cmd.add("python");
            cmd.add(scriptPath);
            cmd.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            // Reset progress
            refreshProgressCurrent = 0;
            refreshProgressTotal = 0;
            refreshProgressName = "";

            // Read stdout in a separate thread so the main thread can enforce timeout
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("{")) {
                            JSONObject obj = JSON.parseObject(line);
                            if (obj.containsKey("progress")) {
                                JSONObject p = obj.getJSONObject("progress");
                                refreshProgressCurrent = p.getIntValue("current");
                                refreshProgressTotal = p.getIntValue("total");
                                refreshProgressName = p.getString("name");
                                refreshProgressStocks = p.getIntValue("stocks");
                                refreshProgressStatus = p.getString("status");
                            } else {
                                output.append(line);
                            }
                        }
                    }
                } catch (IOException e) {
                    // stream closed
                }
            }, "python-script-reader");
            readerThread.start();

            boolean finished = process.waitFor(600, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                readerThread.interrupt();
                lastErrorMessage = "Python脚本执行超时";
                log.error(lastErrorMessage);
                return null;
            }

            // Wait for reader thread to finish consuming remaining output
            readerThread.join(5000);

            if (process.exitValue() != 0) {
                lastErrorMessage = "Python脚本退出码: " + process.exitValue();
                log.error(lastErrorMessage);
                return null;
            }

            String jsonStr = output.toString();
            if (jsonStr.isEmpty()) {
                lastErrorMessage = "Python脚本无输出";
                log.error(lastErrorMessage);
                return null;
            }

            JSONObject result = JSON.parseObject(jsonStr);
            if (result == null || result.containsKey("error")) {
                lastErrorMessage = "脚本错误: " + (result != null ? result.getString("error") : "null");
                log.error(lastErrorMessage);
                return null;
            }

            lastErrorMessage = null;
            return result;

        } catch (Exception e) {
            lastErrorMessage = "执行Python脚本失败: " + e.getMessage();
            log.error("执行Python脚本失败", e);
            return null;
        }
    }

    public List<Map<String, Object>> searchIndices(String keyword) {
        JSONObject result = runPythonScript("--search", keyword);
        if (result == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object item : result.getJSONArray("results")) {
            JSONObject obj = (JSONObject) item;
            Map<String, Object> map = new HashMap<>();
            map.put("code", obj.getString("code"));
            map.put("name", obj.getString("name"));
            map.put("fullName", obj.getString("full_name"));
            list.add(map);
        }
        return list;
    }

    public void downloadSelectedIndices(List<String> codesWithNames) {
        try {
            String codeArg = String.join(",", codesWithNames);
            JSONObject result = runPythonScript("--codes", codeArg);
            if (result == null) {
                return;
            }

            Path cachePath = getIndexCachePath();
            Files.write(cachePath, result.toJSONString().getBytes(StandardCharsets.UTF_8));

            indexStocksCache.clear();
            JSONObject indexStocksJson = result.getJSONObject("index_stocks");
            if (indexStocksJson != null) {
                for (String code : indexStocksJson.keySet()) {
                    JSONObject info = indexStocksJson.getJSONObject(code);
                    Map<String, Object> map = new HashMap<>();
                    map.put("name", info.getString("name"));
                    map.put("count", info.getInteger("count"));
                    map.put("codes", info.getJSONArray("codes").toJavaList(String.class));
                    indexStocksCache.put(code, map);
                }
            }

            indexCount = indexStocksCache.size();
            lastIndexRefreshTime = new Date().toString();
            log.info("选择性指数下载完成: {} 个指数", indexCount);

        } catch (Exception e) {
            lastErrorMessage = "选择性下载失败: " + e.getMessage();
            log.error("选择性下载失败", e);
        }
    }

    private String findScriptPath() {
        String[] candidates = {
                "scripts/index_download.py",
                System.getProperty("user.dir") + "/scripts/index_download.py",
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                return new File(path).getAbsolutePath();
            }
        }
        return null;
    }

    private Path getIndexCachePath() {
        String userHome = System.getProperty("user.home");
        return Paths.get(userHome, ".stock-chose", "index-cache.json");
    }
}
