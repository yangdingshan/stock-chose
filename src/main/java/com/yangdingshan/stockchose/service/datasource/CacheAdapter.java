package com.yangdingshan.stockchose.service.datasource;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.yangdingshan.stockchose.domain.StockRead;
import lombok.extern.slf4j.Slf4j;
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
                stockRead.setIndustry(obj.getString("industry"));
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
            obj.put("industry", s.getIndustry() != null ? s.getIndustry() : "");
            array.add(obj);
        }
        try {
            // Ensure parent directory exists
            Path parent = cachePath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
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
