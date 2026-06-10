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
                    s.setIndustry(sr.getIndustry() != null ? sr.getIndustry() : "");
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
