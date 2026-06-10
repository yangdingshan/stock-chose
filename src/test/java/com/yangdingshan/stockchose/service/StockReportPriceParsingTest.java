package com.yangdingshan.stockchose.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 验证带千位分隔符的价格能被正确解析。
 * 茅台等高价股报告里写法形如 "¥1,272.86" / "¥1,100 以下"，
 * 早期正则只接受 [\d.]+，会被截断为 1.0 从而触发错误的"减仓区"高亮。
 */
class StockReportPriceParsingTest {

    private static Double invokeParseZoneMin(String zone) throws Exception {
        StockReportService svc = new StockReportService();
        Method m = StockReportService.class.getDeclaredMethod("parseZoneMin", String.class);
        m.setAccessible(true);
        return (Double) m.invoke(svc, zone);
    }

    private static double[] invokeParseZoneRange(String zone) throws Exception {
        StockReportService svc = new StockReportService();
        Method m = StockReportService.class.getDeclaredMethod("parseZoneRange", String.class);
        m.setAccessible(true);
        return (double[]) m.invoke(svc, zone);
    }

    private static Pattern getStaticPattern(String fieldName) throws Exception {
        Field f = StockReportService.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        return (Pattern) f.get(null);
    }

    @Test
    void parseZoneMin_parsesThousandsSeparator() throws Exception {
        Double v = invokeParseZoneMin("¥1,100 以下");
        assertNotNull(v, "应解析为数字而非 null");
        assertEquals(1100.0, v, 0.0001);
    }

    @Test
    void parseZoneMin_parsesStopLossWithComma() throws Exception {
        Double v = invokeParseZoneMin("¥1,000");
        assertNotNull(v);
        assertEquals(1000.0, v, 0.0001);
    }

    @Test
    void parseZoneRange_parsesTwoCommaPrices() throws Exception {
        double[] range = invokeParseZoneRange("¥1,100 - ¥1,250");
        assertNotNull(range);
        assertArrayEquals(new double[]{1100.0, 1250.0}, range, 0.0001);
    }

    @Test
    void parseZoneMin_stillWorksWithoutComma() throws Exception {
        // 回归保护：原先的低价股写法不能被破坏
        Double v = invokeParseZoneMin("¥22 以下");
        assertNotNull(v);
        assertEquals(22.0, v, 0.0001);
    }

    @Test
    void parseZoneRange_stillWorksWithoutComma() throws Exception {
        double[] range = invokeParseZoneRange("¥22 - ¥27");
        assertNotNull(range);
        assertArrayEquals(new double[]{22.0, 27.0}, range, 0.0001);
    }

    @Test
    void pricePattern_capturesValueWithComma() throws Exception {
        Pattern p = getStaticPattern("PRICE_PATTERN");
        Matcher m = p.matcher("**当前股价:** ¥1,272.86 (2026-06-05收盘)");
        assertEquals(true, m.find(), "当前股价行应能匹配");
        // 捕获组去除逗号后必须能 parse 为 1272.86
        double parsed = Double.parseDouble(m.group(1).replace(",", ""));
        assertEquals(1272.86, parsed, 0.0001);
    }

    @Test
    void fairValuePattern_capturesValueWithComma() throws Exception {
        Pattern p = getStaticPattern("FAIR_VALUE_PATTERN");
        Matcher m = p.matcher("**加权合理估值:** ¥1,380 (对应市值 ¥1.73万亿)");
        assertEquals(true, m.find(), "加权合理估值行应能匹配");
        double parsed = Double.parseDouble(m.group(1).replace(",", ""));
        assertEquals(1380.0, parsed, 0.0001);
    }
}
