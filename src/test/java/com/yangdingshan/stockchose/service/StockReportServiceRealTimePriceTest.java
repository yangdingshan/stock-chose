package com.yangdingshan.stockchose.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StockReportServiceRealTimePriceTest {

    private static BigDecimal invokeGetRealTimePrice(String code) throws Exception {
        StockReportService svc = new StockReportService();
        Method m = StockReportService.class.getDeclaredMethod("getRealTimePrice", String.class);
        m.setAccessible(true);
        return (BigDecimal) m.invoke(svc, code);
    }

    @Test
    void getRealTimePrice_returnsNullForNullCode() throws Exception {
        assertNull(invokeGetRealTimePrice(null));
    }

    @Test
    void getRealTimePrice_returnsNullForEmptyCode() throws Exception {
        assertNull(invokeGetRealTimePrice(""));
    }

    @Test
    void getRealTimePrice_methodExistsWithCorrectSignature() throws Exception {
        Method m = StockReportService.class.getDeclaredMethod("getRealTimePrice", String.class);
        assertEquals(BigDecimal.class, m.getReturnType());
    }
}
