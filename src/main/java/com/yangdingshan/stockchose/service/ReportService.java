package com.yangdingshan.stockchose.service;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.options.MutableDataSet;
import com.yangdingshan.stockchose.domain.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ReportService {

    @Value("${report.dir:./industry-analyst}")
    private String reportDir;

    private Parser markdownParser;
    private HtmlRenderer htmlRenderer;

    private static final Pattern TITLE_PATTERN = Pattern.compile("^#\\s+(.+)$");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\*\\*分析日期\\*\\*[：:]\\s*(.+)$");
    private static final Pattern BOLD_PATTERN = Pattern.compile("\\*\\*(.+?)\\*\\*");

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

    public List<Report> listReports(String keyword) {
        List<Report> reports = new ArrayList<>();
        File dir = new File(reportDir);
        if (!dir.exists() || !dir.isDirectory()) {
            return reports;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".md"));
        if (files == null) {
            return reports;
        }

        for (File file : files) {
            try {
                Report report = parseReport(file);
                if (report != null) {
                    if (keyword != null && !keyword.isEmpty()) {
                        if (report.getTitle() != null && report.getTitle().contains(keyword)) {
                            reports.add(report);
                        }
                    } else {
                        reports.add(report);
                    }
                }
            } catch (Exception e) {
                log.warn("解析报告失败: {}", file.getName(), e);
            }
        }

        reports.sort((a, b) -> {
            if (a.getDate() != null && b.getDate() != null) {
                return b.getDate().compareTo(a.getDate());
            }
            return b.getFilename().compareTo(a.getFilename());
        });

        return reports;
    }

    public String renderMarkdown(String filename) {
        File file = new File(reportDir, filename);
        if (!file.exists()) {
            throw new IllegalArgumentException("报告文件不存在: " + filename);
        }

        try {
            String markdown = new String(Files.readAllBytes(file.toPath()), "UTF-8");
            Node document = markdownParser.parse(markdown);
            return htmlRenderer.render(document);
        } catch (IOException e) {
            throw new RuntimeException("读取报告失败: " + filename, e);
        }
    }

    public Report getReport(String filename) {
        File file = new File(reportDir, filename);
        if (!file.exists()) {
            return null;
        }
        return parseReport(file);
    }

    public boolean deleteReport(String filename) {
        File file = new File(reportDir, filename);
        if (!file.exists()) {
            return false;
        }
        return file.delete();
    }

    private Report parseReport(File file) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String fileName = file.getName();
            String title = null;
            LocalDate date = null;
            List<String> tags = extractTagsFromFilename(fileName);
            StringBuilder bodyBuilder = new StringBuilder();
            String line;
            boolean foundTitle = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!foundTitle) {
                    Matcher titleMatcher = TITLE_PATTERN.matcher(trimmed);
                    if (titleMatcher.find()) {
                        title = cleanBold(titleMatcher.group(1));
                        foundTitle = true;
                        continue;
                    }
                }

                if (date == null) {
                    Matcher dateMatcher = DATE_PATTERN.matcher(trimmed);
                    if (dateMatcher.find()) {
                        date = parseDate(dateMatcher.group(1).trim());
                    }
                }

                bodyBuilder.append(line).append("\n");
            }

            if (title == null) {
                title = fileName.replace(".md", "");
            }

            String bodyText = bodyBuilder.toString();
            String summary = buildSummary(bodyText);

            String dateFormatted = null;
            if (date != null) {
                dateFormatted = date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            }

            return Report.builder()
                    .filename(fileName)
                    .title(title)
                    .date(date)
                    .dateFormatted(dateFormatted)
                    .tags(tags)
                    .summary(summary)
                    .size(file.length() / 1024)
                    .build();
        } catch (IOException e) {
            log.warn("读取文件失败: {}", file.getName(), e);
            return null;
        }
    }

    private List<String> extractTagsFromFilename(String filename) {
        String name = filename.replace(".md", "");
        String[] parts = name.split("[_\\-、，]");
        return Arrays.stream(parts)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .filter(s -> !s.matches("\\d{4}.*"))
                .filter(s -> !s.matches("^(年|月|日|第).*"))
                .filter(s -> !s.equals("v") && !s.equals("版"))
                .limit(5)
                .collect(Collectors.toList());
    }

    private String cleanBold(String text) {
        return BOLD_PATTERN.matcher(text).replaceAll("$1");
    }

    private LocalDate parseDate(String dateStr) {
        String[] patterns = {
                "yyyy年M月d日",
                "yyyy-M-d",
                "yyyy/M/d",
                "yyyy年M月",
                "yyyy-M",
        };
        for (String pattern : patterns) {
            try {
                DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern);
                return LocalDate.parse(dateStr, fmt);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String buildSummary(String bodyText) {
        String cleaned = bodyText
                .replaceAll("\\*\\*.+?\\*\\*[：:].*", "")
                .replaceAll("#+\\s+.*", "")
                .replaceAll("\\*\\*", "")
                .replaceAll("```[\\s\\S]*?```", "")
                .replaceAll("\\|.*\\|", "")
                .replaceAll("[-*]{3,}", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();

        if (cleaned.length() > 150) {
            cleaned = cleaned.substring(0, 150) + "...";
        }
        return cleaned;
    }
}
