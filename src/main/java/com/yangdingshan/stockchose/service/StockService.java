package com.yangdingshan.stockchose.service;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import com.alibaba.excel.util.ListUtils;
import com.yangdingshan.stockchose.domain.IndexRead;
import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.domain.StockRead;
import com.yangdingshan.stockchose.repository.StockRepository;
import lombok.extern.slf4j.Slf4j;
import ma.glasnost.orika.MapperFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
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

    public void simpleRead() {
        String fileName = this.getClass().getClassLoader().getResource("stock/stock.xlsx").getPath();
        EasyExcel.read(fileName, StockRead.class, new ReadListener<StockRead>() {
            /**
             * 单次缓存的数据量
             */
            public static final int BATCH_COUNT = 100;
            /**
             *临时存储
             */
            private List<StockRead> cachedDataList = ListUtils.newArrayListWithExpectedSize(BATCH_COUNT);

            @Override
            public void invoke(StockRead data, AnalysisContext context) {
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
                List<Stock> list = mapperFacade.mapAsList(cachedDataList, Stock.class);
                list.forEach(s -> {
                    s.setIndexCount(0);
                    s.setIndexCountRank(0);
                    s.setStockMarket(getStockMarket(s.getCode()));
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


    public void simpleReadIndex() throws IOException {
        List<Stock> result = stockRepository.findAll();
        Map<String, Stock> resultMap = result.stream().collect(Collectors.toMap(Stock::getCode, t -> t));
        setIndexCount(resultMap);
        setIndexCountRank(resultMap);
        setFinalRank(resultMap);
        stockRepository.saveAll(resultMap.values());
    }

    private void setFinalRank(Map<String, Stock> resultMap) {
        resultMap.values()
                .forEach(stock -> stock.setFinalRank(stock.getPeRank() + stock.getRoeRank() + stock.getIndexCountRank()));
    }

    private void setIndexCountRank(Map<String, Stock> resultMap) {
        final int[] indexRank = {0};
        resultMap.values()
                .stream()
                .sorted(Comparator.comparing(Stock::getIndexCount).reversed()
                        .thenComparing(Stock::getPeRank))
                .forEach(stock -> {
                    stock.setIndexCountRank(indexRank[0] + 1);
                    indexRank[0] = indexRank[0] + 1;
                });

    }

    private void setIndexCount(Map<String, Stock> resultMap) {
        String index = this.getClass().getClassLoader().getResource("index").getFile();
        File file = new File(index);
        File[] files = file.listFiles();
        for (File file1 : files) {
            String fileName = file1.getPath();
            EasyExcel.read(fileName, IndexRead.class, new ReadListener<IndexRead>() {
                /**
                 * 单次缓存的数据量
                 */
                public static final int BATCH_COUNT = 100;
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
}
