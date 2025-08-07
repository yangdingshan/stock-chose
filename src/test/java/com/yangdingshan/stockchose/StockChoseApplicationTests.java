package com.yangdingshan.stockchose;

import com.yangdingshan.stockchose.service.StockService;
import com.yangdingshan.stockchose.service.WxOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

@SpringBootTest
class StockChoseApplicationTests {

    @Autowired
    private StockService stockService;

    @Autowired
    private WxOrderService wxOrderService;

    @Test
    void contextLoads() {
    }


    /**
     * 测试下载股票数据
     */
    @Test
    void testDownloadStockData() {
        stockService.downloadStockData();
    }

    /**
     * 重新下载指数数据
     */
    @Test
    void flushIndex() {
        stockService.flushIndex();
    }

    /**
     * 指数基金统计
     *
     */
    @Test
    void simpleReadIndex() {
        stockService.simpleReadIndex();
    }

    @Test
    void allInOne() {
        // 1.下载最新股票数据
        stockService.downloadStockData();
        // 2.设置PE、ROE排名
        stockService.setPeRankAndRoeRank();
        // 3.重新下载指数数据
        stockService.flushIndex();
        // 4.指数基金统计
        stockService.simpleReadIndex();
    }


    @Test
    void wxOrderTest() {
        wxOrderService.getPhone();
    }

}
