package com.yangdingshan.stockchose;

import com.yangdingshan.stockchose.service.StockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;

@SpringBootTest
class StockChoseApplicationTests {

    @Autowired
    private StockService stockService;

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
     * 指数基金统计
     *
     */
    @Test
    void simpleReadIndex() {
        System.out.println("ceshi");
        stockService.simpleReadIndex();
    }

}
