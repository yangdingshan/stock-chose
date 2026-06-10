# 行业报告管理功能 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 StockChose 新增行业研究报告 Web 管理页面，支持列表浏览、标题搜索、Markdown 详情查看、删除操作。

**Architecture:** ReportController → ReportService → 文件系统。纯文件系统方案，不建数据库表。ReportService 扫描 `industry-analyst/` 目录、解析 Markdown 元数据、使用 flexmark 渲染为 HTML。Thymeleaf 单模板双状态（列表/查看）。

**Tech Stack:** Spring Boot 2.6.3, Java 8, Thymeleaf, Bootstrap 5, flexmark 0.64.8

---

### Task 1: 添加 flexmark 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 在 pom.xml 的 dependencies 区域添加 flexmark 依赖**

在 `</dependencies>` 之前插入：

```xml
        <dependency>
            <groupId>com.vladsch.flexmark</groupId>
            <artifactId>flexmark-all</artifactId>
            <version>0.64.8</version>
        </dependency>
```

- [ ] **Step 2: 验证依赖下载**

```bash
./mvnw dependency:resolve -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "build: add flexmark dependency for markdown rendering

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: 创建 Report 领域对象

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/domain/Report.java`

- [ ] **Step 1: 创建 Report.java**

```java
package com.yangdingshan.stockchose.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Report {
    private String filename;
    private String title;
    private LocalDate date;
    private String dateFormatted;
    private List<String> tags;
    private String summary;
    private long size;
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/domain/Report.java
git commit -m "feat: add Report domain object for industry report metadata

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: 创建 ReportService

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/service/ReportService.java`

- [ ] **Step 1: 在 application.yaml 添加配置项**

在文件末尾追加：

```yaml

report:
  dir: ./industry-analyst
```

- [ ] **Step 2: 创建 ReportService.java**

```java
package com.yangdingshan.stockchose.service;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.yangdingshan.stockchose.domain.Report;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/service/ReportService.java src/main/resources/application.yaml
git commit -m "feat: add ReportService for report scanning, parsing, and markdown rendering

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: 创建 ReportController

**Files:**
- Create: `src/main/java/com/yangdingshan/stockchose/controller/ReportController.java`

- [ ] **Step 1: 创建 ReportController.java**

```java
package com.yangdingshan.stockchose.controller;

import com.yangdingshan.stockchose.domain.Report;
import com.yangdingshan.stockchose.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping("/reports")
    public String list(@RequestParam(defaultValue = "") String search, Model model) {
        List<Report> reports = reportService.listReports(search.isEmpty() ? null : search);
        model.addAttribute("title", "行业报告");
        model.addAttribute("currentPage", "reports");
        model.addAttribute("content", "pages/reports");
        model.addAttribute("reports", reports);
        model.addAttribute("search", search);
        model.addAttribute("viewMode", false);
        return "layout";
    }

    @GetMapping("/reports/view")
    public String view(@RequestParam String file, Model model) {
        String filename = validateFilename(file);

        Report report = reportService.getReport(filename);
        if (report == null) {
            return "redirect:/reports";
        }

        String htmlContent = reportService.renderMarkdown(filename);

        model.addAttribute("title", report.getTitle() + " - 行业报告");
        model.addAttribute("currentPage", "reports");
        model.addAttribute("content", "pages/reports");
        model.addAttribute("reports", reportService.listReports(null));
        model.addAttribute("search", "");
        model.addAttribute("viewMode", true);
        model.addAttribute("viewTitle", report.getTitle());
        model.addAttribute("viewFilename", filename);
        model.addAttribute("viewDateFormatted", report.getDateFormatted());
        model.addAttribute("viewHtml", htmlContent);
        return "layout";
    }

    @PostMapping("/reports/delete")
    @ResponseBody
    public Map<String, Object> delete(@RequestParam String file) {
        Map<String, Object> result = new HashMap<>();
        String filename = validateFilename(file);
        boolean deleted = reportService.deleteReport(filename);
        result.put("success", deleted);
        if (!deleted) {
            result.put("error", "文件不存在或删除失败");
        }
        return result;
    }

    private String validateFilename(String file) {
        try {
            String decoded = URLDecoder.decode(file, StandardCharsets.UTF_8.name());
            if (decoded.contains("..") || decoded.contains("/") || decoded.contains("\\")) {
                throw new IllegalArgumentException("非法文件路径");
            }
            return decoded;
        } catch (Exception e) {
            throw new IllegalArgumentException("非法文件路径", e);
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yangdingshan/stockchose/controller/ReportController.java
git commit -m "feat: add ReportController with list, view, and delete routes

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: 创建报告管理模板

**Files:**
- Create: `src/main/resources/templates/pages/reports.html`

- [ ] **Step 1: 创建 reports.html**

```html
<div xmlns:th="http://www.thymeleaf.org">

    <!-- ==================== 列表模式 ==================== -->
    <th:block th:if="${!viewMode}">
        <div class="page-header">
            <h2>行业研究报告</h2>
            <p class="text-muted">共 <span th:text="${reports.size()}">0</span> 份报告</p>
        </div>

        <form class="search-bar mb-4" method="get" th:action="@{/reports}">
            <div class="input-group" style="max-width: 420px;">
                <input type="text" name="search" class="form-control" placeholder="搜索报告标题..."
                       th:value="${search}">
                <button class="btn btn-primary" type="submit">搜索</button>
                <a href="/reports" class="btn btn-outline-secondary" th:if="${!search.isEmpty()}">清除</a>
            </div>
        </form>

        <div th:if="${reports.isEmpty()}" class="empty-state">
            <div class="icon">📄</div>
            <p th:text="${search.isEmpty()} ? '暂无报告，将 .md 文件放入 industry-analyst 目录即可自动识别' : '未找到匹配的报告'"></p>
        </div>

        <div class="report-grid">
            <div class="report-card" th:each="r : ${reports}">
                <div class="report-card-body">
                    <h5 class="report-title" th:text="${r.title}">标题</h5>
                    <div class="report-meta">
                        <span class="report-date" th:if="${r.dateFormatted != null}" th:text="${r.dateFormatted}">日期</span>
                        <span class="report-size" th:text="${r.size} + ' KB'">大小</span>
                    </div>
                    <div class="report-tags" th:if="${!r.tags.isEmpty()}">
                        <span class="tag" th:each="tag : ${r.tags}" th:text="${tag}">标签</span>
                    </div>
                    <p class="report-summary" th:text="${r.summary}">摘要</p>
                </div>
                <div class="report-card-actions">
                    <a th:href="@{/reports/view(file=${r.filename})}" class="btn btn-sm btn-outline-primary">查看</a>
                    <button class="btn btn-sm btn-outline-danger" onclick="confirmDelete(this)" 
                            th:attr="data-file=${r.filename},data-title=${r.title}">删除</button>
                </div>
            </div>
        </div>
    </th:block>

    <!-- ==================== 查看模式 ==================== -->
    <th:block th:if="${viewMode}">
        <nav class="breadcrumb-nav mb-3">
            <a href="/reports">报告列表</a>
            <span class="separator">›</span>
            <span class="current" th:text="${viewTitle}">标题</span>
        </nav>

        <article class="report-detail">
            <div class="report-detail-header">
                <h1 class="report-detail-title" th:text="${viewTitle}">标题</h1>
                <div class="report-detail-meta" th:if="${viewDate != null}">
                    分析日期：<span th:text="${viewDateFormatted}">日期</span>
                </div>
            </div>
            <div class="markdown-body" th:utext="${viewHtml}"></div>
        </article>

        <div class="report-detail-actions mt-4">
            <a href="/reports" class="btn btn-outline-secondary">← 返回列表</a>
            <button class="btn btn-outline-danger ms-2" onclick="confirmDelete(this)" 
                    th:attr="data-file=${viewFilename},data-title=${viewTitle}">删除</button>
        </div>
    </th:block>

    <!-- ==================== 删除确认弹窗 ==================== -->
    <div class="modal fade" id="deleteModal" tabindex="-1">
        <div class="modal-dialog">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">确认删除</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal"></button>
                </div>
                <div class="modal-body">
                    <p>确定要删除报告 <strong id="deleteReportName"></strong> 吗？此操作不可恢复。</p>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-secondary" data-bs-dismiss="modal">取消</button>
                    <button type="button" class="btn btn-danger" id="confirmDeleteBtn">确认删除</button>
                </div>
            </div>
        </div>
    </div>

    <script th:inline="javascript">
        var deleteFile = '';
        var deleteBtn = null;

        function confirmDelete(btn) {
            deleteBtn = btn;
            deleteFile = btn.getAttribute('data-file');
            document.getElementById('deleteReportName').textContent = btn.getAttribute('data-title');
            var modal = new bootstrap.Modal(document.getElementById('deleteModal'));
            modal.show();
        }

        document.getElementById('confirmDeleteBtn').addEventListener('click', function() {
            var formData = new URLSearchParams();
            formData.append('file', deleteFile);
            fetch('/reports/delete', {
                method: 'POST',
                headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                body: formData.toString()
            })
            .then(function(r) { return r.json(); })
            .then(function(data) {
                if (data.success) {
                    var modal = bootstrap.Modal.getInstance(document.getElementById('deleteModal'));
                    modal.hide();
                    if (deleteBtn && deleteBtn.closest('.report-card')) {
                        deleteBtn.closest('.report-card').remove();
                    } else {
                        window.location.href = '/reports';
                    }
                } else {
                    alert('删除失败: ' + (data.error || '未知错误'));
                }
            })
            .catch(function(err) {
                alert('删除请求失败: ' + err.message);
            });
        });
    </script>

</div>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/pages/reports.html
git commit -m "feat: add reports template with list, view, and delete modal

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: 导航栏添加入口 + 样式补充

**Files:**
- Modify: `src/main/resources/templates/layout.html`
- Modify: `src/main/resources/static/css/style.css`

- [ ] **Step 1: 在 layout.html 导航栏中添加"行业报告"链接**

在 `<a href="/index" ...>指数详情</a>` 后面插入一行：

```html
            <a href="/reports" class="nav-link" th:classappend="${currentPage == 'reports'} ? 'active'">行业报告</a>
```

即修改后 nav 区域为：

```html
        <div class="d-flex">
            <a href="/" class="nav-link" th:classappend="${currentPage == 'dashboard'} ? 'active'">仪表盘</a>
            <a href="/ranking" class="nav-link" th:classappend="${currentPage == 'ranking'} ? 'active'">全部排名</a>
            <a href="/download" class="nav-link" th:classappend="${currentPage == 'download'} ? 'active'">下载管理</a>
            <a href="/index" class="nav-link" th:classappend="${currentPage == 'index'} ? 'active'">指数详情</a>
            <a href="/reports" class="nav-link" th:classappend="${currentPage == 'reports'} ? 'active'">行业报告</a>
        </div>
```

- [ ] **Step 2: 在 style.css 末尾追加报告相关样式**

```css

/* ========== 报告管理 ========== */
.report-grid {
    display: flex;
    flex-direction: column;
    gap: 16px;
}

.report-card {
    display: flex;
    justify-content: space-between;
    align-items: flex-start;
    background: var(--card-bg, #fff);
    border: 1px solid var(--border-color, #e0e0e0);
    border-radius: 8px;
    padding: 20px;
}

.report-card-body {
    flex: 1;
    min-width: 0;
}

.report-title {
    font-size: 17px;
    font-weight: 600;
    margin-bottom: 6px;
    color: var(--text-primary, #1a1a1a);
}

.report-meta {
    font-size: 13px;
    color: var(--text-muted, #888);
    margin-bottom: 8px;
    display: flex;
    gap: 16px;
}

.report-tags {
    margin-bottom: 8px;
}

.report-tags .tag {
    display: inline-block;
    background: #e8f0fe;
    color: #1967d2;
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 4px;
    margin-right: 6px;
    margin-bottom: 4px;
}

.report-summary {
    font-size: 14px;
    color: var(--text-secondary, #555);
    margin-bottom: 0;
    line-height: 1.6;
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
}

.report-card-actions {
    display: flex;
    gap: 8px;
    flex-shrink: 0;
    margin-left: 20px;
    white-space: nowrap;
}

.empty-state {
    text-align: center;
    padding: 60px 20px;
    color: var(--text-muted, #999);
}

.empty-state .icon {
    font-size: 48px;
    margin-bottom: 16px;
}

.breadcrumb-nav {
    font-size: 14px;
    color: var(--text-muted, #888);
}

.breadcrumb-nav a {
    color: var(--link-color, #1967d2);
    text-decoration: none;
}

.breadcrumb-nav a:hover {
    text-decoration: underline;
}

.breadcrumb-nav .separator {
    margin: 0 8px;
}

.breadcrumb-nav .current {
    color: var(--text-primary, #1a1a1a);
    font-weight: 500;
}

.report-detail {
    background: var(--card-bg, #fff);
    border: 1px solid var(--border-color, #e0e0e0);
    border-radius: 8px;
    padding: 32px 40px;
}

.report-detail-header {
    border-bottom: 1px solid var(--border-color, #e0e0e0);
    padding-bottom: 16px;
    margin-bottom: 24px;
}

.report-detail-title {
    font-size: 24px;
    font-weight: 700;
    margin-bottom: 8px;
}

.report-detail-meta {
    font-size: 14px;
    color: var(--text-muted, #888);
}

.markdown-body h2 {
    font-size: 20px;
    font-weight: 600;
    margin-top: 32px;
    margin-bottom: 12px;
    padding-bottom: 8px;
    border-bottom: 1px solid #eee;
}

.markdown-body h3 {
    font-size: 17px;
    font-weight: 600;
    margin-top: 24px;
    margin-bottom: 10px;
}

.markdown-body h4 {
    font-size: 15px;
    font-weight: 600;
    margin-top: 20px;
    margin-bottom: 8px;
}

.markdown-body table {
    width: 100%;
    margin-bottom: 16px;
}

.markdown-body th, .markdown-body td {
    padding: 8px 12px;
    border: 1px solid #dee2e6;
    font-size: 14px;
}

.markdown-body th {
    background: #f8f9fa;
    font-weight: 600;
}

.markdown-body pre {
    background: #f5f5f5;
    padding: 16px;
    border-radius: 6px;
    overflow-x: auto;
    font-size: 13px;
    line-height: 1.5;
}

.markdown-body code {
    background: #f0f0f0;
    padding: 2px 6px;
    border-radius: 3px;
    font-size: 13px;
}

.markdown-body pre code {
    background: none;
    padding: 0;
}

.markdown-body blockquote {
    border-left: 4px solid #1967d2;
    padding: 8px 16px;
    margin: 16px 0;
    background: #f8f9fa;
    color: #555;
}

.markdown-body ul, .markdown-body ol {
    padding-left: 24px;
    margin-bottom: 12px;
}

.markdown-body li {
    margin-bottom: 4px;
    line-height: 1.7;
}

.markdown-body p {
    margin-bottom: 12px;
    line-height: 1.8;
}

.markdown-body hr {
    border: none;
    border-top: 1px solid #e0e0e0;
    margin: 24px 0;
}

.report-detail-actions {
    display: flex;
    align-items: center;
}

@media (max-width: 640px) {
    .report-card {
        flex-direction: column;
    }
    .report-card-actions {
        margin-left: 0;
        margin-top: 12px;
    }
    .report-detail {
        padding: 20px 16px;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/layout.html src/main/resources/static/css/style.css
git commit -m "feat: add reports nav link and report management styles

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: 构建验证 + 功能测试

- [ ] **Step 1: 重新构建项目**

```bash
./mvnw clean package -DskipTests -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 启动应用**

```bash
java -jar target/stock-chose-0.0.1-SNAPSHOT.jar
```

Expected: 应用在 localhost:8080 启动成功

- [ ] **Step 3: 验证列表页**

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/reports
```

Expected: 200

- [ ] **Step 4: 验证查看页（URL 编码文件名）**

```bash
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/reports/view?file=%E5%85%89%E4%BC%8F%E8%A1%8C%E4%B8%9A%E6%B7%B1%E5%BA%A6%E5%88%86%E6%9E%90%E6%8A%A5%E5%91%8A_2026%E5%B9%B45%E6%9C%88.md"
```

Expected: 200

- [ ] **Step 5: 验证搜索 API**

```bash
curl -s "http://localhost:8080/reports?search=光伏" | grep -c "report-card"
```

Expected: 至少 1

- [ ] **Step 6: 验证路径穿越防护**

```bash
curl -s -o /dev/null -w "%{http_code}" "http://localhost:8080/reports/view?file=../etc/passwd"
```

Expected: 500 (IllegalArgumentException)

- [ ] **Step 7: Commit（如有微调）**

```bash
git add -A
git diff --cached --stat
git commit -m "chore: build verification after report management feature

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: 浏览器手动验证

- [ ] **Step 1: 访问 http://localhost:8080/reports**，确认：
  - 导航栏"行业报告"高亮
  - 显示 3 份报告卡片
  - 每个卡片有标题、日期、标签、摘要、查看/删除按钮

- [ ] **Step 2: 搜索测试**
  - 搜索"光伏" → 只显示匹配报告
  - 点击"清除" → 恢复全部列表
  - 搜索不存在的词 → 显示空状态提示

- [ ] **Step 3: 查看详情**
  - 点击"查看" → 进入详情页
  - 面包屑导航显示正确
  - Markdown 渲染正常（标题层级、表格、加粗、列表）
  - 点击"返回列表" → 回到列表页

- [ ] **Step 4: 删除功能**
  - 点击"删除" → 弹出确认弹窗
  - 点击"取消" → 弹窗关闭，报告还在
  - 点击"确认删除" → 卡片消失（或从详情页跳回列表）

- [ ] **Step 5: 新增报告实时识别**
  - 复制一份 .md 到 `industry-analyst/` 目录
  - 刷新页面 → 新报告自动出现在列表中
