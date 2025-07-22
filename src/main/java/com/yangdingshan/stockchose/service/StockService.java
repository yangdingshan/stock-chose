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
import com.yangdingshan.stockchose.domain.StockRead;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.util.LambadaTools;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.utils.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * @Author: yangdingshan
 * @Date: 2022/2/15 15:29
 * @Description:
 */
@Slf4j
@Service
public class StockService {

    @Autowired
    private StockRepository stockRepository;

    /**
     * 加载股市所有股票
     */
    public void simpleRead() {
        stockRepository.deleteAll();
        String fileName = this.getClass().getClassLoader().getResource("stock/Table.xls").getPath();
        EasyExcel.read(fileName, StockRead.class, new ReadListener<StockRead>() {
            /**
             * 单次缓存的数据量
             */
            public static final int BATCH_COUNT = 1000;
            /**
             *临时存储
             */
            private List<StockRead> cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

            @Override
            public void invoke(StockRead data, AnalysisContext context) {
                if (StrUtil.isBlank(data.getName())) {
                    return;
                }

                // 剔除名称以*ST、ST、PT、S开头的股票
                if (data.getName().startsWith("*ST")
                        || data.getName().startsWith("ST")
                        || data.getName().startsWith("PT")
                        || data.getName().startsWith("S")) {
                    return;
                }
                // 剔除掉股价为横杠的股票
                if (StrUtil.isBlank(data.getPrice()) || "—".equals(data.getPrice())) {
                    return;
                }
                if (StrUtil.isBlank(data.getPe()) || "—".equals(data.getPe())) {
                    return;
                }
                if (StrUtil.isBlank(data.getRoe()) || "—".equals(data.getRoe())) {
                    return;
                }
                if (StrUtil.isBlank(data.getPb()) || "—".equals(data.getPb())) {
                    return;
                }
                if (new BigDecimal(data.getPrice()).compareTo(BigDecimal.ZERO) < 0) {
                    return;
                }

                // 剔除掉ROE和PE为负数的股票
                if (Float.parseFloat(data.getPe()) <= 0
                        || Float.parseFloat(data.getRoe()) <= 0
                        || Float.parseFloat(data.getPb()) <=0) {
                    return;
                }
                cachedDataList.add(data);
                if (cachedDataList.size() >= BATCH_COUNT) {
                    saveData();
                    // 存储完成清理 list
                    cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                saveData();
            }

            /**
             * 加上存储数据库
             */
            private void saveData() {
                log.info("{}条数据，开始存储数据库！", cachedDataList.size());
                List<Stock> list = Lists.newArrayList();
                cachedDataList.forEach(stockRead -> {
                    Stock s = new Stock();
                    s.setCode(stockRead.getCode());
                    s.setName(stockRead.getName());
                    s.setPrice(new BigDecimal(stockRead.getPrice()));
                    s.setPe(Float.parseFloat(stockRead.getPe()));
                    s.setPb(Float.parseFloat(stockRead.getPb()));
                    s.setRoe(Float.parseFloat(stockRead.getRoe()));
                    s.setIndexCount(0);
                    s.setIndexCountRank(0);
                    s.setStockMarket(getStockMarket(s.getCode()));
                    s.setBuyTime(Instant.now());
                    list.add(s);
                });

                stockRepository.saveAll(list);
                log.info("存储数据库成功！");
            }
        }).sheet().doRead();
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


    public void simpleReadIndex() {
        List<Stock> result = stockRepository.findAll();
        Map<String, Stock> resultMap = result.stream().collect(Collectors.toMap(Stock::getCode, t -> t));
        setIndexCount(resultMap);
        List<Stock> stocks = new ArrayList<>(resultMap.values());
        setIndexCountRank(stocks);
        setFinalRank(stocks);
        stockRepository.saveAll(stocks);
    }

    /**
     * 设置最终排名
     *
     * @param stocks
     */
    private void setFinalRank(List<Stock> stocks) {
        stocks.forEach(stock -> stock.setPeAndRoeCount(stock.getPeRank() + stock.getRoeRank()));
        stocks.stream().sorted(Comparator.comparing(Stock::getPeAndRoeCount))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPeAndRoeRank));
        stocks.stream().sorted(Comparator.comparing(s -> s.getIndexCountRank() + s.getPeAndRoeRank()))
                .forEach(LambadaTools.forEachWithIndex(Stock::setIndexPeRoeRank));
    }

    /**
     * 设置指数次数排名
     *
     * @param stocks
     */
    private void setIndexCountRank(List<Stock> stocks) {
        stocks.stream()
                .sorted(Comparator.comparing(Stock::getIndexCount).reversed().thenComparing(Stock::getPeRank))
                .forEach(LambadaTools.forEachWithIndex(Stock::setIndexCountRank));
    }

    public void flushIndex() {
        File file = new File("src/main/resources/index");
        File[] files = file.listFiles();
        if (Objects.nonNull(files) && files.length > 0) {
            for (File file1 : files) {
                file1.delete();
            }
        }
        System.out.println("index目录清理完成。。。。");
        String tagList = HttpUtil.get("https://www.csindex.com.cn/csindex-home/index-list/tag-list");
        JSONObject tag = JSON.parseObject(tagList);
        JSONObject data = tag.getJSONObject("data");
        JSONArray hotSpotList = data.getJSONArray("hotSpotList");
        for (Object o : hotSpotList) {
            JSONObject hotSpot = (JSONObject) o;
            System.out.println("tagId:" + hotSpot.getString("tagId") + " tagName:" + hotSpot.getString("tagName"));
        }
        Map<String, Object> param = new HashMap<>();
        System.out.println("请输入查询的tag(多个用逗号分隔):");
        Scanner scanner = new Scanner(System.in);
        String scannerTags = scanner.nextLine();
        Map<String, Object> indexFilter = new HashMap<>();
        if (StrUtil.isNotBlank(scannerTags)) {
            indexFilter.put("hotSpot", scannerTags.split(","));
        }
        param.put("indexFilter", indexFilter);

        System.out.println("通过指数代码、指数名称或关键字搜索:");
        String searchInput = scanner.nextLine();
        if (StrUtil.isNotBlank(searchInput)) {
            try {
                param.put("searchInput", URLEncoder.encode(searchInput, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                log.error("searchInput编码失败", e);
            }
        }

        Map<String, Object> sorter = new HashMap<>();
        sorter.put("sortField", "null");
        sorter.put("sortOrder", null);
        Map<String, Object> pager = new HashMap<>();
        pager.put("pageNum", 1);
        pager.put("pageSize", 500);
        param.put("sorter", sorter);
        param.put("pager", pager);
        String indexItem = HttpUtil.post("https://www.csindex.com.cn/csindex-home/index-list/query-index-item", JSONObject.toJSONString(param));
        JSONObject jsonObject = JSON.parseObject(indexItem);
        JSONArray indexData = jsonObject.getJSONArray("data");
        for (Object o : indexData) {
            try {
                JSONObject index = (JSONObject) o;
                String indexCode = index.getString("indexCode");
                String indexName = index.getString("indexName");
                System.out.println("指数代码/名称：" + indexCode + "/" + indexName);
                System.out.println("开始下载" + indexCode);
                String urlString = String.format("https://oss-ch.csindex.com.cn/static/html/csindex/public/uploads/file/autofile/cons/%scons.xls", indexCode);
                String destinationPath = String.format("src/main/resources/index/%s.xls", indexCode);
                downloadFile(urlString, destinationPath);
                System.out.println("下载完成" + indexCode);
            } catch (Exception e) {
                log.error("下载失败", e);
            }
        }
        scanner.close();
    }

    private void downloadFile(String urlString, String destinationPath) {
        try (BufferedInputStream in = new BufferedInputStream(new URL(urlString).openStream());
             FileOutputStream fileOutputStream = new FileOutputStream(destinationPath)) {

            byte[] dataBuffer = new byte[1024];
            int bytesRead;
            while ((bytesRead = in.read(dataBuffer, 0, 1024)) != -1) {
                fileOutputStream.write(dataBuffer, 0, bytesRead);
            }

            System.out.println("File has been downloaded and saved successfully.");

        } catch (MalformedURLException e) {
            System.err.println("Invalid URL: " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IO Error: " + e.getMessage());
        }
    }


    private void setIndexCount(Map<String, Stock> resultMap) {
        String index = this.getClass().getClassLoader().getResource("index").getFile();
        File file = new File(index);
        File[] files = file.listFiles();
        if (Objects.isNull(files)) {
            return;
        }
        for (File file1 : files) {
            String fileName = file1.getPath();
            EasyExcel.read(fileName, IndexRead.class, new ReadListener<IndexRead>() {
                /**
                 * 单次缓存的数据量
                 */
                public static final int BATCH_COUNT = 1000;
                /**
                 *临时存储
                 */
                private List<IndexRead> cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

                @Override
                public void invoke(IndexRead data, AnalysisContext context) {
                    cachedDataList.add(data);
                    if (cachedDataList.size() >= BATCH_COUNT) {
                        saveData();
                        // 存储完成清理 list
                        cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);
                    }
                }

                @Override
                public void doAfterAllAnalysed(AnalysisContext context) {
                    saveData();
                }

                /**
                 * 加上存储数据库
                 */
                private void saveData() {
                    cachedDataList.forEach(indexRead -> {
                        if (resultMap.containsKey(indexRead.getConstituentCode())) {
                            Stock stock = resultMap.get(indexRead.getConstituentCode());
                            stock.setIndexCount(stock.getIndexCount() + 1);
                            resultMap.put(indexRead.getConstituentCode(), stock);
                        }
                    });
                }
            }).sheet().doRead();
        }
    }

    /**
     * 设置pe和roe排名
     *
     */
    public void setPeRankAndRoeRank() {
        List<Stock> stocks = stockRepository.findAll();
        // pe排名
        stocks.stream().sorted(Comparator.comparing(Stock::getPe))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPeRank));
        // pb排名
        stocks.stream().sorted(Comparator.comparing(Stock::getPb))
                .forEach(LambadaTools.forEachWithIndex(Stock::setPbRank));
        // roe排名
        stocks.stream().sorted(Comparator.comparing(Stock::getRoe).reversed())
                .forEach(LambadaTools.forEachWithIndex(Stock::setRoeRank));
        stockRepository.saveAll(stocks);
    }
}
