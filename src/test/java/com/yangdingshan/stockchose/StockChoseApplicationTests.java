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
     * 添加股票数据
     */
    @Test
    void testRead() {
        stockService.simpleRead();
        stockService.setPeRankAndRoeRank();
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
        // 添加股票数据
        stockService.simpleRead();
        stockService.setPeRankAndRoeRank();
        // 重新下载指数数据
        stockService.flushIndex();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        // 指数基金统计
        stockService.simpleReadIndex();
    }


    @Test
    void wxOrderTest() {
        wxOrderService.getPhone();
    }
}
