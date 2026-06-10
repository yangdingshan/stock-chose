package com.yangdingshan.stockchose.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.options.MutableDataSet;
import com.yangdingshan.stockchose.domain.Stock;
import com.yangdingshan.stockchose.domain.StockReport;
import com.yangdingshan.stockchose.repository.StockRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockReportService {

    @Value("${stock-report.dir:./stock}")
    private String reportDir;

    @Autowired
    private StockRepository stockRepository;

    private Parser markdownParser;
    private HtmlRenderer htmlRenderer;

    private static final Pattern FILENAME_PATTERN =
            Pattern.compile("^(.+?)_(\\d{5,6})_(\\d+年)_(.+?)_(?:(\\d+)分_)?(\\d{8})\\.md$");
    private static final Pattern TITLE_PATTERN = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern INDUSTRY_PATTERN =
            Pattern.compile("\\|\\s*所属行业\\s*\\|\\s*([^|]+)\\s*\\|");
    private static final Pattern PRICE_PATTERN =
            Pattern.compile("\\*\\*当前股价:\\*\\*\\s*(?:¥|HK\\$)?([\\d,.]+)");
    private static final Pattern FAIR_VALUE_PATTERN =
            Pattern.compile("\\*\\*加权合理估值:\\*\\*\\s*(?:¥|HK\\$)?([\\d,.]+)");
    private static final Pattern SAFETY_MARGIN_PATTERN =
            Pattern.compile("\\*\\*安全边际:\\*\\*\\s*([+-]?[\\d.]+)%");
    private static final Pattern BUY_ZONE_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?(?:买入区间|(?<!分批)建仓区间).*?[：:]\\s*\\*{0,2}\\s*(.+)");
    private static final Pattern ADD_ZONE_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?(?:加仓区间|增持区间|分批建仓区间).*?[：:]\\s*\\*{0,2}\\s*(.+)");
    private static final Pattern REDUCE_ZONE_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?(?:减仓区间).*?[：:]\\s*\\*{0,2}\\s*(.+)");
    private static final Pattern STOP_LOSS_PATTERN =
            Pattern.compile("-\\s*\\*{0,2}.*?止损价格.*?[：:]\\s*\\*{0,2}\\s*(.+)");

    @PostConstruct
    public void init() {
        MutableDataSet options = new MutableDataSet();
        options.set(Parser.EXTENSIONS, Arrays.asList(TablesExtension.create()));
        markdownParser = Parser.builder(options).build();
        htmlRenderer = HtmlRenderer.builder(options).build();

        try {
            Files.createDirectories(Paths.get(reportDir));
        } catch (IOException e) {
            log.warn("无法创建报告目录: {}", reportDir);
        }
    }

    public List<Map<String, Object>> listStocks(String keyword, String sort, String industry) {
        Map<String, List<StockReport>> grouped = scanAndGroup();
        List<Map<String, Object>> result = new ArrayList<>();

        // Batch fetch real-time prices from Eastmoney
        List<String> allCodes = new ArrayList<>(grouped.keySet());
        Map<String, BigDecimal> priceMap = fetchRealTimePrices(allCodes);

        for (Map.Entry<String, List<StockReport>> entry : grouped.entrySet()) {
            String code = entry.getKey();
            List<StockReport> reports = entry.getValue();
            StockReport latest = reports.get(0);
            String ind = latest.getIndustry() != null ? latest.getIndustry() : "";
            if (ind.isEmpty()) {
                ind = getIndustryFromDb(code);
            }

            if (keyword != null && !keyword.isEmpty()) {
                String kw = keyword.toLowerCase();
                if (!code.contains(kw)
                        && (latest.getStockName() == null || !latest.getStockName().toLowerCase().contains(kw))
                        && !ind.toLowerCase().contains(kw)) {
                    continue;
                }
            }

            if (industry != null && !industry.isEmpty()) {
                if (!ind.contains(industry)) {
                    continue;
                }
            }

            // Real-time price, fallback to report price
            BigDecimal rtPrice = priceMap.get(code);
            String priceStr = rtPrice != null ? "¥" + rtPrice : (latest.getPrice() != null ? "¥" + latest.getPrice() : "-");

            // Check if current price is at or below buy zone minimum
            boolean inBuyZone = false;
            if (rtPrice != null && latest.getBuyZone() != null) {
                Double buyMin = parseZoneMin(latest.getBuyZone());
                if (buyMin != null && rtPrice.doubleValue() <= buyMin) {
                    inBuyZone = true;
                }
            }

            // Check if current price is within add zone range
            boolean inAddZone = false;
            if (rtPrice != null && latest.getAddZone() != null) {
                double[] addRange = parseZoneRange(latest.getAddZone());
                if (addRange != null) {
                    double p = rtPrice.doubleValue();
                    if (p >= addRange[0] && p <= addRange[1]) {
                        inAddZone = true;
                    }
                }
            }

            // Check if current price is at or below stop loss price
            boolean inStopLoss = false;
            if (rtPrice != null && latest.getStopLoss() != null) {
                Double sl = parseZoneMin(latest.getStopLoss());
                if (sl != null && rtPrice.doubleValue() <= sl) {
                    inStopLoss = true;
                }
            }

            // Check if current price is at or above reduce zone minimum
            boolean inReduceZone = false;
            if (rtPrice != null && latest.getReduceZone() != null) {
                Double rz = parseZoneMin(latest.getReduceZone());
                if (rz != null && rtPrice.doubleValue() >= rz) {
                    inReduceZone = true;
                }
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stockCode", code);
            item.put("stockName", latest.getStockName());
            item.put("industry", ind);
            item.put("latestDate", latest.getDateFormatted());
            item.put("reportCount", reports.size());
            item.put("latestTitle", latest.getTitle());
            item.put("score", latest.getScore());
            item.put("period", latest.getPeriod());
            item.put("price", priceStr);
            item.put("fairValue", latest.getFairValue());
            item.put("safetyMargin", latest.getSafetyMargin());
            item.put("buyZone", latest.getBuyZone());
            item.put("addZone", latest.getAddZone());
            item.put("reduceZone", latest.getReduceZone());
            item.put("stopLoss", latest.getStopLoss());
            item.put("inBuyZone", inBuyZone);
            item.put("inAddZone", inAddZone);
            item.put("inStopLoss", inStopLoss);
            item.put("inReduceZone", inReduceZone);
            result.add(item);
        }

        // Sort
        switch (sort != null ? sort : "score_desc") {
            case "score_asc":
                result.sort(Comparator.comparing(m -> (Integer) m.getOrDefault("score", 0)));
                break;
            case "margin_desc":
                result.sort((a, b) -> {
                    Double ma = (Double) a.get("safetyMargin");
                    Double mb = (Double) b.get("safetyMargin");
                    if (ma == null && mb == null) return 0;
                    if (ma == null) return 1;
                    if (mb == null) return -1;
                    return mb.compareTo(ma);
                });
                break;
            case "margin_asc":
                result.sort((a, b) -> {
                    Double ma = (Double) a.get("safetyMargin");
                    Double mb = (Double) b.get("safetyMargin");
                    if (ma == null && mb == null) return 0;
                    if (ma == null) return 1;
                    if (mb == null) return -1;
                    return ma.compareTo(mb);
                });
                break;
            default: // score_desc
                result.sort((a, b) -> {
                    Integer sa = (Integer) a.getOrDefault("score", 0);
                    Integer sb = (Integer) b.getOrDefault("score", 0);
                    return sb.compareTo(sa);
                });
                break;
        }

        return result;
    }

    public List<String> getIndustries() {
        return scanAndGroup().values().stream()
                .flatMap(List::stream)
                .map(StockReport::getIndustry)
                .filter(s -> s != null && !s.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    public List<StockReport> listReportsByStock(String code) {
        Map<String, List<StockReport>> grouped = scanAndGroup();
        return grouped.getOrDefault(code, new ArrayList<>());
    }

    public StockReport getReport(String filename) {
        File file = new File(reportDir, filename);
        if (!file.exists()) return null;
        return parseReport(file);
    }

    public String renderMarkdown(String filename) {
        File file = new File(reportDir, filename);
        if (!file.exists()) {
            throw new IllegalArgumentException("报告文件不存在: " + filename);
        }
        try {
            String markdown = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Node document = markdownParser.parse(markdown);
            return htmlRenderer.render(document);
        } catch (IOException e) {
            throw new RuntimeException("读取报告失败: " + filename, e);
        }
    }

    public boolean deleteReport(String filename) {
        File file = new File(reportDir, filename);
        if (!file.exists()) return false;
        return file.delete();
    }

    public StockReport getFirstByCode(String code) {
        List<StockReport> reports = listReportsByStock(code);
        return reports.isEmpty() ? null : reports.get(0);
    }

    private Map<String, List<StockReport>> scanAndGroup() {
        Map<String, List<StockReport>> grouped = new LinkedHashMap<>();
        File dir = new File(reportDir);
        if (!dir.exists() || !dir.isDirectory()) return grouped;

        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) return grouped;

        for (File file : files) {
            StockReport report = parseReport(file);
            if (report != null && report.getStockCode() != null) {
                grouped.computeIfAbsent(report.getStockCode(), k -> new ArrayList<>()).add(report);
            }
        }

        // Sort each stock's reports by date descending, then by file creation time descending
        // so that same-day reports are ordered newest-first rather than in directory traversal order.
        for (List<StockReport> list : grouped.values()) {
            list.sort(Comparator.comparing(StockReport::getReportDate,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(StockReport::getCreatedTime,
                            Comparator.nullsLast(Comparator.reverseOrder())));
        }

        // Sort groups by the latest report date descending
        return grouped.entrySet().stream()
                .sorted((a, b) -> {
                    LocalDate da = a.getValue().get(0).getReportDate();
                    LocalDate db = b.getValue().get(0).getReportDate();
                    if (da == null && db == null) return 0;
                    if (da == null) return 1;
                    if (db == null) return -1;
                    return db.compareTo(da);
                })
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (e1, e2) -> e1, LinkedHashMap::new));
    }

    private StockReport parseReport(File file) {
        String fileName = file.getName();
        Matcher m = FILENAME_PATTERN.matcher(fileName);
        if (!m.find()) {
            log.debug("文件名格式不匹配: {}", fileName);
            return null;
        }

        String stockName = m.group(1);
        String stockCode = m.group(2);
        String period = m.group(3);
        String reportType = m.group(4).replace("_", " ");
        Integer score = m.group(5) != null ? Integer.parseInt(m.group(5)) : null;
        String dateStr = m.group(6);

        LocalDate reportDate = parseDate(dateStr);
        String dateFormatted = reportDate != null
                ? reportDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : null;

        String title = null;
        String industry = null;
        Double price = null;
        Double fairValue = null;
        Double safetyMargin = null;
        String buyZone = null;
        String addZone = null;
        String reduceZone = null;
        String stopLoss = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (title == null) {
                    Matcher titleMatcher = TITLE_PATTERN.matcher(trimmed);
                    if (titleMatcher.find()) {
                        title = titleMatcher.group(1);
                    }
                }
                if (industry == null) {
                    Matcher indMatcher = INDUSTRY_PATTERN.matcher(trimmed);
                    if (indMatcher.find()) {
                        industry = indMatcher.group(1).trim();
                    }
                }
                if (price == null) {
                    Matcher pMatcher = PRICE_PATTERN.matcher(trimmed);
                    if (pMatcher.find()) {
                        price = parsePrice(pMatcher.group(1));
                    }
                }
                if (fairValue == null) {
                    Matcher fvMatcher = FAIR_VALUE_PATTERN.matcher(trimmed);
                    if (fvMatcher.find()) {
                        fairValue = parsePrice(fvMatcher.group(1));
                    }
                }
                if (safetyMargin == null) {
                    Matcher smMatcher = SAFETY_MARGIN_PATTERN.matcher(trimmed);
                    if (smMatcher.find()) {
                        safetyMargin = Double.parseDouble(smMatcher.group(1));
                    }
                }
                if (buyZone == null) {
                    Matcher bzMatcher = BUY_ZONE_PATTERN.matcher(trimmed);
                    if (bzMatcher.find()) {
                        buyZone = cleanZoneValue(bzMatcher.group(1));
                    }
                }
                if (addZone == null) {
                    Matcher azMatcher = ADD_ZONE_PATTERN.matcher(trimmed);
                    if (azMatcher.find()) {
                        addZone = cleanZoneValue(azMatcher.group(1));
                    }
                }
                if (reduceZone == null) {
                    Matcher rzMatcher = REDUCE_ZONE_PATTERN.matcher(trimmed);
                    if (rzMatcher.find()) {
                        reduceZone = cleanZoneValue(rzMatcher.group(1));
                    }
                }
                if (stopLoss == null) {
                    Matcher slMatcher = STOP_LOSS_PATTERN.matcher(trimmed);
                    if (slMatcher.find()) {
                        stopLoss = cleanZoneValue(slMatcher.group(1));
                    }
                }
                if (title != null && industry != null && price != null
                        && fairValue != null && safetyMargin != null
                        && buyZone != null && addZone != null && reduceZone != null
                        && stopLoss != null) break;
            }
        } catch (IOException e) {
            log.warn("读取报告失败: {}", fileName, e);
        }

        return StockReport.builder()
                .filename(fileName)
                .stockName(stockName)
                .stockCode(stockCode)
                .industry(industry)
                .period(period)
                .reportType(reportType)
                .score(score)
                .reportDate(reportDate)
                .dateFormatted(dateFormatted)
                .createdTime(readCreatedTime(file))
                .title(title)
                .size(file.length() / 1024)
                .price(price)
                .fairValue(fairValue)
                .safetyMargin(safetyMargin)
                .buyZone(buyZone)
                .addZone(addZone)
                .reduceZone(reduceZone)
                .stopLoss(stopLoss)
                .build();
    }

    private LocalDateTime readCreatedTime(File file) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            Instant instant = attrs.creationTime().toInstant();
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (IOException e) {
            log.debug("读取文件创建时间失败: {}", file.getName());
            return null;
        }
    }

    private Map<String, BigDecimal> fetchRealTimePrices(List<String> codes) {
        Map<String, BigDecimal> priceMap = new java.util.HashMap<>();
        if (codes.isEmpty()) return priceMap;

        try {
            StringBuilder qs = new StringBuilder();
            for (String code : codes) {
                if (qs.length() > 0) qs.append(",");
                qs.append(marketPrefix(code)).append(code);
            }

            String response = HttpRequest.get("http://qt.gtimg.cn/q=" + qs.toString())
                    .timeout(15_000)
                    .header("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .execute()
                    .body();

            if (StrUtil.isNotBlank(response)) {
                String[] lines = response.split("\n");
                for (String line : lines) {
                    if (line.trim().isEmpty()) continue;
                    // Format: v_{prefix}{code}="1~name~code~price~..."
                    int start = line.indexOf('"');
                    int end = line.lastIndexOf('"');
                    if (start < 0 || end <= start) continue;

                    String[] fields = line.substring(start + 1, end).split("~");
                    if (fields.length > 3) {
                        String code = fields[2].trim();
                        String priceStr = fields[3].trim();
                        if (!code.isEmpty() && !priceStr.isEmpty() && !priceStr.equals("0.00")) {
                            try {
                                priceMap.put(code, new BigDecimal(priceStr));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取实时股价失败: {}", e.getMessage());
        }
        return priceMap;
    }

    public BigDecimal getRealTimePrice(String code) {
        if (code == null || code.isEmpty()) return null;
        Map<String, BigDecimal> prices = fetchRealTimePrices(java.util.Collections.singletonList(code));
        return prices.get(code);
    }

    private String marketPrefix(String code) {
        if (code == null || code.isEmpty()) return "sz";
        if (code.length() == 5) return "hk";
        char first = code.charAt(0);
        if (first == '6') return "sh";
        if (first == '8' || first == '4') return "bj";
        return "sz";
    }

    private static final Pattern ZONE_PRICE_PATTERN = Pattern.compile("(?:¥|HK\\$)?([\\d,.]+)");

    private static double parsePrice(String raw) {
        return Double.parseDouble(raw.replace(",", ""));
    }

    private String cleanZoneValue(String raw) {
        return raw.trim()
                .replaceAll("[（(].*$", "")
                .replaceAll("^\\*+\\s*", "")
                .replaceAll("\\s*\\*+$", "")
                .trim();
    }

    private Double parseZoneMin(String zone) {
        Matcher m = ZONE_PRICE_PATTERN.matcher(zone);
        if (m.find()) {
            try {
                return parsePrice(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private double[] parseZoneRange(String zone) {
        Matcher m = ZONE_PRICE_PATTERN.matcher(zone);
        if (!m.find()) return null;
        try {
            double first = parsePrice(m.group(1));
            double second = first;
            if (m.find()) {
                second = parsePrice(m.group(1));
            }
            if (first > second) {
                double tmp = first;
                first = second;
                second = tmp;
            }
            return new double[]{first, second};
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    private String getIndustryFromDb(String code) {
        try {
            Stock stock = stockRepository.findByCode(code);
            if (stock != null && stock.getIndustry() != null && !stock.getIndustry().isEmpty()) {
                return stock.getIndustry();
            }
        } catch (Exception e) {
            log.debug("从数据库查询行业失败: {}", code);
        }
        return "";
    }

    private LocalDate parseDate(String dateStr) {
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyyMMdd"));
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
