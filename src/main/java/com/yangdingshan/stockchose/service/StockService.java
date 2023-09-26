package com.yangdingshan.stockchose.service;

import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.yangdingshan.stockchose.domain.IndexRead;
import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.domain.StockRead;
import com.yangdingshan.stockchose.repository.StockRepository;
import com.yangdingshan.stockchose.util.LambadaTools;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.apache.commons.compress.utils.Lists;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    @Autowired
    private MapperFacade mapperFacade;

    /**
     * 加载股市所有股票
     *
     */
    public void simpleRead() {
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
        stocks.forEach(stock -> stock.setFinalRankCount(stock.getPeRank() + stock.getRoeRank()));
        stocks.stream().sorted(Comparator.comparing(Stock::getFinalRankCount))
                .forEach(LambadaTools.forEachWithIndex(Stock::setFinalRank));
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
