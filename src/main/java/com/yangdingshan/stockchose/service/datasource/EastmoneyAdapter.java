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
        int pageSize = 200; // API caps at ~100 regardless, but request 200 as upper bound
        int totalCount = -1;
        int consecutiveErrors = 0;
        final int MAX_CONSECUTIVE_ERRORS = 5;

        while (true) {
            String apiUrl = "http://push2.eastmoney.com/api/qt/clist/get";
            String params = String.format(
                    "pn=%d&pz=%d&po=1&np=1&ut=bd1d9ddb04089700cf9c27f6f7426281&fltt=2&invt=2&fid=f3&fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23&fields=f12,f14,f2,f9,f23,f37,f100",
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

            // Read total on first page to know when to stop
            if (totalCount < 0) {
                totalCount = data.getIntValue("total");
                log.info("API返回总数: {} 条", totalCount);
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
                String industry = stock.getString("f100");

                // Only require price to be valid; PE/PB/ROE can be negative or missing
                if (!isNumber(priceStr)) {
                    continue;
                }
                if (StrUtil.isBlank(name) || name.startsWith("*ST") || name.startsWith("ST")
                        || name.startsWith("PT") || name.startsWith("S")) {
                    continue;
                }
                if (Double.parseDouble(priceStr) <= 0) {
                    continue;
                }

                // Default PE/PB/ROE to 0 if missing or invalid
                if (!isNumber(peStr)) peStr = "0";
                if (!isNumber(pbStr)) pbStr = "0";
                if (!isNumber(roeStr)) roeStr = "0";

                StockRead stockRead = new StockRead();
                stockRead.setCode(code);
                stockRead.setName(name);
                stockRead.setPrice(priceStr);
                stockRead.setPe(peStr);
                stockRead.setPb(pbStr);
                stockRead.setRoe(roeStr);
                stockRead.setIndustry(industry != null ? industry : "");
                stockList.add(stockRead);
            }

            if (totalCount > 0 && stockList.size() >= totalCount) {
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
