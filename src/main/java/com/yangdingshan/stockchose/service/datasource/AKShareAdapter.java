package com.yangdingshan.stockchose.service.datasource;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yangdingshan.stockchose.domain.StockRead;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class AKShareAdapter implements DataSourceAdapter {

    @Override
    public String getName() {
        return "AKShare";
    }

    @Override
    public List<StockRead> downloadStockData() {
        List<StockRead> stockList = new ArrayList<>();

        try {
            String scriptPath = findScriptPath();
            if (scriptPath == null) {
                log.error("找不到 stock_download.py 脚本");
                return stockList;
            }

            ProcessBuilder pb = new ProcessBuilder(
                    "python", scriptPath);
            pb.redirectErrorStream(true);

            Process process = pb.start();
            StringBuilder output = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("{")) {
                        output.append(line);
                    }
                }
            }

            boolean finished = process.waitFor(300, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.error("AKShare脚本执行超时");
                return stockList;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.error("AKShare脚本退出码: {}", exitCode);
                return stockList;
            }

            String jsonStr = output.toString();
            if (jsonStr.isEmpty()) {
                log.error("AKShare脚本无JSON输出");
                return stockList;
            }

            JSONObject result = JSON.parseObject(jsonStr);
            if (result == null || result.containsKey("error")) {
                log.error("AKShare脚本错误: {}", result != null ? result.getString("error") : "null result");
                return stockList;
            }

            JSONArray stocks = result.getJSONArray("stocks");
            if (stocks == null || stocks.isEmpty()) {
                log.warn("AKShare返回空股票列表");
                return stockList;
            }

            for (int i = 0; i < stocks.size(); i++) {
                JSONObject s = stocks.getJSONObject(i);
                StockRead sr = new StockRead();
                sr.setCode(s.getString("code"));
                sr.setName(s.getString("name"));
                sr.setPrice(s.getString("price"));
                sr.setPe(s.getString("pe"));
                sr.setPb(s.getString("pb"));
                sr.setRoe(s.getString("roe"));
                sr.setIndustry(s.getString("industry"));
                stockList.add(sr);
            }

            log.info("AKShare下载完成，共获取有效股票数据: {} 条", stockList.size());
        } catch (Exception e) {
            log.error("AKShare下载异常: {}", e.getMessage(), e);
        }

        return stockList;
    }

    @Override
    public boolean isAvailable() {
        String scriptPath = findScriptPath();
        if (scriptPath == null) {
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python", "-c",
                    "import akshare; print('ok')");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                String output = line != null ? line.trim() : "";
                boolean finished = process.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return false;
                }
                return process.exitValue() == 0 && "ok".equals(output);
            }
        } catch (Exception e) {
            return false;
        }
    }

    private String findScriptPath() {
        // Try relative path from project root
        String[] candidates = {
                "scripts/stock_download.py",
                "../scripts/stock_download.py",
                System.getProperty("user.dir") + "/scripts/stock_download.py",
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                return new File(path).getAbsolutePath();
            }
        }
        return null;
    }
}
