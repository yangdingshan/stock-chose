package com.yangdingshan.stockchose.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 .md 报告里 "买入/加仓/减仓/止损" 行的正则解析不会把 markdown 的 ** 带进结果。
 */
class StockReportZoneRegexTest {

    private static final Pattern BUY_ZONE_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?(?:买入区间|(?<!分批)建仓区间).*?[：:]\\s*\\*{0,2}\\s*(.+)");
    private static final Pattern ADD_ZONE_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?(?:加仓区间|增持区间|分批建仓区间).*?[：:]\\s*\\*{0,2}\\s*(.+)");
    private static final Pattern REDUCE_ZONE_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?(?:减仓区间).*?[：:]\\s*\\*{0,2}\\s*(.+)");
    private static final Pattern STOP_LOSS_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?止损价格.*?[：:]\\s*\\*{0,2}\\s*(.+)");

    private static String cleanZoneValue(String raw) {
        return raw.trim()
                .replaceAll("[（(].*$", "")
                .replaceAll("^\\*+\\s*", "")
                .replaceAll("\\s*\\*+$", "")
                .trim();
    }

    private static String extract(Pattern p, String line) {
        Matcher m = p.matcher(line);
        return m.find() ? cleanZoneValue(m.group(1)) : null;
    }

    @Test
    void parsesRealReportLine_buyZone() {
        String line = "- **买入区间:** ¥22 以下 (对应市值 < ¥763亿, PE 2026E < 5.3x) — 积极建仓";
        assertEquals("¥22 以下", extract(BUY_ZONE_PATTERN, line));
    }

    @Test
    void parsesRealReportLine_addZone() {
        String line = "- **加仓区间:** ¥22 - ¥27 (对应市值 ¥763亿 - ¥937亿, PE 2026E 5.3-6.5x) — 分批加仓";
        assertEquals("¥22 - ¥27", extract(ADD_ZONE_PATTERN, line));
    }

    @Test
    void parsesRealReportLine_reduceZone() {
        String line = "- **减仓区间:** ¥37 以上 (对应市值 > ¥1,283亿, PE 2026E > 9x) — 分批减仓";
        assertEquals("¥37 以上", extract(REDUCE_ZONE_PATTERN, line));
    }

    @Test
    void parsesRealReportLine_stopLoss() {
        String line = "- **止损价格:** ¥20.0 (对应市值 ¥694亿) — 跌破此价位无条件止损，基于：跌破 2024 年低点支撑";
        assertEquals("¥20.0", extract(STOP_LOSS_PATTERN, line));
    }

    @Test
    void handlesNoBoldFormat() {
        String line = "- 买入区间: ¥22 以下 (无 markdown 粗体)";
        assertEquals("¥22 以下", extract(BUY_ZONE_PATTERN, line));
    }

    @Test
    void handlesSingleAsteriskItalic() {
        String line = "- *买入区间:* ¥22 以下 (单星号斜体)";
        assertEquals("¥22 以下", extract(BUY_ZONE_PATTERN, line));
    }

    /**
     * 端到端:把样本报告写到一个临时 .md 文件,反射调用 private parseReport,验证字段里没有 ** 残留。
     */
    @Test
    void parseReportFile_endToEnd() throws Exception {
        File tmp = new File(System.getProperty("java.io.tmpdir"),
                "云铝股份_000807_1年_价值投资深度分析测试_82分_20260605.md");
        tmp.deleteOnExit();
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.println("# 云铝股份 (000807) 价值投资深度分析报告");
            w.println();
            w.println("| 指标 | 数值 |");
            w.println("|------|------|");
            w.println("| 股票代码 | 000807 |");
            w.println("| 所属行业 | 有色金属 - 铝 |");
            w.println("| 当前股价 | ¥27.64 |");
            w.println();
            w.println("**当前股价:** ¥27.64");
            w.println("**加权合理估值:** ¥39.4");
            w.println("**安全边际:** +42.5%");
            w.println();
            w.println("**操作建议:**");
            w.println("- **买入区间:** ¥22 以下 (对应市值 < ¥763亿, PE 2026E < 5.3x) — 积极建仓");
            w.println("- **加仓区间:** ¥22 - ¥27 (对应市值 ¥763亿 - ¥937亿, PE 2026E 5.3-6.5x) — 分批加仓");
            w.println("- **持有区间:** ¥27 - ¥37 — 耐心持有");
            w.println("- **减仓区间:** ¥37 以上 (对应市值 > ¥1,283亿, PE 2026E > 9x) — 分批减仓");
            w.println("- **止损价格:** ¥20.0 (对应市值 ¥694亿) — 跌破此价位无条件止损");
        }

        StockReportService svc = new StockReportService();
        java.lang.reflect.Field f = StockReportService.class.getDeclaredField("reportDir");
        f.setAccessible(true);
        f.set(svc, tmp.getParent());

        Method m = StockReportService.class.getDeclaredMethod("parseReport", File.class);
        m.setAccessible(true);
        Object report = m.invoke(svc, tmp);

        // 通过反射读取字段
        java.lang.reflect.Field buyF = report.getClass().getDeclaredField("buyZone");
        buyF.setAccessible(true);
        java.lang.reflect.Field addF = report.getClass().getDeclaredField("addZone");
        addF.setAccessible(true);
        java.lang.reflect.Field reduceF = report.getClass().getDeclaredField("reduceZone");
        reduceF.setAccessible(true);
        java.lang.reflect.Field slF = report.getClass().getDeclaredField("stopLoss");
        slF.setAccessible(true);

        String buy = (String) buyF.get(report);
        String add = (String) addF.get(report);
        String reduce = (String) reduceF.get(report);
        String sl = (String) slF.get(report);

        System.out.println("buyZone    = [" + buy + "]");
        System.out.println("addZone    = [" + add + "]");
        System.out.println("reduceZone = [" + reduce + "]");
        System.out.println("stopLoss   = [" + sl + "]");

        assertEquals("¥22 以下", buy);
        assertEquals("¥22 - ¥27", add);
        assertEquals("¥37 以上", reduce);
        assertEquals("¥20.0", sl);
    }
}
