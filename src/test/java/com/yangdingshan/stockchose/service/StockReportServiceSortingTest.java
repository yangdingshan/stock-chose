package com.yangdingshan.stockchose.service;

import com.yangdingshan.stockchose.domain.StockReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockReportServiceSortingTest {

    private Path tempDir;
    private StockReportService service;

    @BeforeEach
    void setUp() throws Exception {
        tempDir = Files.createTempDirectory("stock-report-sort-test-");
        service = new StockReportService();
        setField(service, "reportDir", tempDir.toString());
        service.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    void sameDateSortedByCreationTimeDescending() throws Exception {
        Path earlier = createReport("阳光电源_300274_1年_价值投资深度分析报告_62分_20260608.md", 62);
        setCreationTime(earlier, Instant.parse("2026-06-08T10:00:00Z"));

        Path later = createReport("阳光电源_300274_1年_价值投资深度分析报告_77分_20260608.md", 77);
        setCreationTime(later, Instant.parse("2026-06-08T12:00:00Z"));

        assertTrue(Files.getAttribute(later, "basic:creationTime", java.nio.file.LinkOption.NOFOLLOW_LINKS) != null);

        List<StockReport> reports = service.listReportsByStock("300274");

        assertEquals(2, reports.size());
        assertNotNull(reports.get(0).getCreatedTime());
        assertNotNull(reports.get(1).getCreatedTime());
        assertEquals(77, reports.get(0).getScore());
        assertEquals(62, reports.get(1).getScore());
    }

    @Test
    void differentDatesStillSortedByDateDescending() throws Exception {
        Path older = createReport("阳光电源_300274_1年_价值投资深度分析报告_78分_20260602.md", 78);
        setCreationTime(older, Instant.parse("2026-06-02T08:00:00Z"));

        Path newer = createReport("阳光电源_300274_1年_价值投资深度分析报告_77分_20260608.md", 77);
        setCreationTime(newer, Instant.parse("2026-06-08T12:00:00Z"));

        List<StockReport> reports = service.listReportsByStock("300274");

        assertEquals(2, reports.size());
        assertEquals(77, reports.get(0).getScore());
        assertEquals(78, reports.get(1).getScore());
    }

    private Path createReport(String name, int score) throws IOException {
        Path file = tempDir.resolve(name);
        String content = "# 阳光电源(300274)价值投资深度分析报告\n"
                + "## 报告日期\n"
                + "| 所属行业 | 电力设备 |\n"
                + "| **当前股价:** | ¥50.0 |\n"
                + "| **加权合理估值:** | ¥60.0 |\n"
                + "| **安全边际:** | 20% |\n"
                + "- **买入区间**: ¥45-50\n"
                + "- **加仓区间**: ¥40-45\n"
                + "- **减仓区间**: ¥65-70\n"
                + "- **止损价格**: ¥35\n";
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private void setCreationTime(Path file, Instant instant) throws IOException {
        FileTime ft = FileTime.from(instant);
        BasicFileAttributeView view = Files.getFileAttributeView(file, BasicFileAttributeView.class);
        view.setTimes(ft, ft, ft);
    }

    private static void setField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(obj, value);
    }
}
