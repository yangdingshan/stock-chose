# 个股详情页：报告区间展示 + 实时股价 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `/stock-reports/{code}` 个股详情页的标题行展示实时股价，并在每份报告行末尾展示该报告的买入/加仓/减仓/止损 4 个价格区间。

**Architecture:** 后端复用 `StockReportService.fetchRealTimePrices` 批量行情接口，新增 `getRealTimePrice(code)` 单股包装方法；Controller 在 `reportList` 处调用并注入 model。模板在现有 `viewMode='reportList'` 块中追加两类元素：标题行实时价徽章、报告行 4 个区间标签。样式沿用现有语义化色系（绿/蓝/红/灰）。

**Tech Stack:** Spring Boot 2.6.3, Java 8, Thymeleaf, Bootstrap 5, flexmark, Hutool HTTP, FastJSON

---

## File Structure

修改 4 个文件，不新建文件：

```
src/main/java/com/yangdingshan/stockchose/service/StockReportService.java
  + getRealTimePrice(String code): BigDecimal
  + 单测: StockReportServiceRealTimePriceTest.java (新增测试文件)

src/main/java/com/yangdingshan/stockchose/controller/StockReportController.java
  ~ reportList(code, model) 方法体: 注入 realTimePrice

src/main/resources/templates/pages/stock-reports.html
  ~ viewMode='reportList' 块: 标题行加实时价, 行内加 4 个 zone-tag

src/main/resources/static/css/style.css
  + .real-time-price / .real-time-price--fallback
  + .zone-tag 系列样式
  + @media (max-width: 768px) 报告行 flex-wrap
```

每个文件单一职责保持不变。

---

### Task 1: 为 `getRealTimePrice` 写单测（TDD 先行）

**Files:**
- Create: `src/test/java/com/yangdingshan/stockchose/service/StockReportServiceRealTimePriceTest.java`

- [ ] **Step 1: 创建测试文件**

```java
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
```

- [ ] **Step 2: 运行测试，必须失败（方法未实现）**

```bash
cd D:/workspace/project/stock-chose && ./mvnw test -Dtest=StockReportServiceRealTimePriceTest -q 2>&1 | tail -20
```

Expected: 测试失败，错误信息包含 `NoSuchMethodException` 或 `getRealTimePrice`

- [ ] **Step 3: 提交失败测试**

```bash
cd D:/workspace/project/stock-chose && git add src/test/java/com/yangdingshan/stockchose/service/StockReportServiceRealTimePriceTest.java && git commit -m "test: add failing tests for StockReportService.getRealTimePrice

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: 实现 `getRealTimePrice` 方法

**Files:**
- Modify: `src/main/java/com/yangdingshan/stockchose/service/StockReportService.java:469`（在 `determineMarket` 之前）

- [ ] **Step 1: 添加新方法**

在 `fetchRealTimePrices` 方法结束大括号之后（约第 469 行，私有常量 `ZONE_PRICE_PATTERN` 之前），插入：

```java
    public BigDecimal getRealTimePrice(String code) {
        if (code == null || code.isEmpty()) return null;
        Map<String, BigDecimal> prices = fetchRealTimePrices(java.util.Collections.singletonList(code));
        return prices.get(code);
    }
```

- [ ] **Step 2: 验证 import 已存在**

`BigDecimal` 已在第 27 行 import；`Map` 已在第 39 行 import。无需新增 import。

- [ ] **Step 3: 重新运行测试，必须通过**

```bash
cd D:/workspace/project/stock-chose && ./mvnw test -Dtest=StockReportServiceRealTimePriceTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 3 tests passed

- [ ] **Step 4: 运行价格解析回归测试，确保不破坏现有逻辑**

```bash
cd D:/workspace/project/stock-chose && ./mvnw test -Dtest=StockReportPriceParsingTest -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS, 7 tests passed

- [ ] **Step 5: Commit**

```bash
cd D:/workspace/project/stock-chose && git add src/main/java/com/yangdingshan/stockchose/service/StockReportService.java && git commit -m "feat(service): add getRealTimePrice wrapper for single stock code

Reuses existing fetchRealTimePrices batch interface to avoid
duplicate HTTP/parsing logic. Returns null for null/empty code
or when the upstream API fails (existing internal try-catch).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Controller 注入实时价到 model

**Files:**
- Modify: `src/main/java/com/yangdingshan/stockchose/controller/StockReportController.java:44-59`

- [ ] **Step 1: 修改 `reportList` 方法**

将整个 `reportList` 方法替换为以下版本：

```java
    @GetMapping("/stock-reports/{code}")
    public String reportList(@PathVariable String code, Model model) {
        List<StockReport> reports = stockReportService.listReportsByStock(code);
        StockReport first = stockReportService.getFirstByCode(code);
        String stockName = first != null ? first.getStockName() : code;

        BigDecimal realTimePrice = stockReportService.getRealTimePrice(code);

        model.addAttribute("title", stockName + "(" + code + ") - 个股报告");
        model.addAttribute("currentPage", "stock-reports");
        model.addAttribute("content", "pages/stock-reports");
        model.addAttribute("stockCode", code);
        model.addAttribute("stockName", stockName);
        model.addAttribute("reports", reports);
        model.addAttribute("realTimePrice", realTimePrice);
        model.addAttribute("search", "");
        model.addAttribute("viewMode", "reportList");
        return "layout";
    }
```

- [ ] **Step 2: 添加 `BigDecimal` import**

在文件第 14 行附近（import 区），添加：

```java
import java.math.BigDecimal;
```

- [ ] **Step 3: 编译验证**

```bash
cd D:/workspace/project/stock-chose && ./mvnw clean compile -q 2>&1 | tail -20
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
cd D:/workspace/project/stock-chose && git add src/main/java/com/yangdingshan/stockchose/controller/StockReportController.java && git commit -m "feat(controller): inject realTimePrice into stock detail model

Used by the report list template to render a live price badge
in the title row. Falsy value degrades the template to a
fallback '实时价-' label without breaking the page.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: 模板改造 — 标题行加实时价 + 报告行加 4 个区间

**Files:**
- Modify: `src/main/resources/templates/pages/stock-reports.html:104-128`

- [ ] **Step 1: 替换标题行 `<h2>`**

将原：

```html
        <div class="page-header">
            <h2 th:text="${stockName} + ' (' + ${stockCode} + ')'">股票</h2>
            <p class="text-muted">共 <span th:text="${reports.size()}">0</span> 份报告</p>
        </div>
```

替换为：

```html
        <div class="page-header">
            <h2>
                <span th:text="${stockName} + ' (' + ${stockCode} + ')'">股票</span>
                <span class="real-time-price" th:if="${realTimePrice != null}"
                      th:text="'¥' + ${realTimePrice}">¥0.00</span>
                <span class="real-time-price real-time-price--fallback"
                      th:unless="${realTimePrice != null}">实时价-</span>
            </h2>
            <p class="text-muted">共 <span th:text="${reports.size()}">0</span> 份报告</p>
        </div>
```

- [ ] **Step 2: 替换报告行 — 在 `report-list-type` 之后追加 4 个 zone-tag**

将原：

```html
            <div class="report-list-row" th:each="r : ${reports}">
                <div class="report-list-info">
                    <span class="report-list-date" th:text="${r.dateFormatted}">日期</span>
                    <span class="stock-period" th:text="${r.period}">周期</span>
                    <span class="score-badge" th:classappend="${r.score >= 70} ? 'score-high' : (${r.score >= 50} ? 'score-mid' : 'score-low')"
                          th:text="${r.score} + '分'" th:if="${r.score != null}">0分</span>
                    <span class="report-list-type" th:text="${r.reportType}">类型</span>
                </div>
                <div class="report-list-actions">
                    <a th:href="@{/stock-reports/view(file=${r.filename}, code=${stockCode})}" class="btn btn-sm btn-outline-primary">查看</a>
                    <button class="btn btn-sm btn-outline-danger" onclick="confirmDeleteReport(this)"
                            th:attr="data-file=${r.filename}">删除</button>
                </div>
            </div>
```

替换为：

```html
            <div class="report-list-row" th:each="r : ${reports}">
                <div class="report-list-info">
                    <span class="report-list-date" th:text="${r.dateFormatted}">日期</span>
                    <span class="stock-period" th:text="${r.period}">周期</span>
                    <span class="score-badge" th:classappend="${r.score >= 70} ? 'score-high' : (${r.score >= 50} ? 'score-mid' : 'score-low')"
                          th:text="${r.score} + '分'" th:if="${r.score != null}">0分</span>
                    <span class="report-list-type" th:text="${r.reportType}">类型</span>

                    <span class="zone-tag zone-buy" th:if="${r.buyZone != null}">
                        <span class="zone-tag-label">买入</span>
                        <span class="zone-tag-value" th:text="${r.buyZone}">¥52 以下</span>
                    </span>
                    <span class="zone-tag zone-add" th:if="${r.addZone != null}">
                        <span class="zone-tag-label">加仓</span>
                        <span class="zone-tag-value" th:text="${r.addZone}">¥52 - ¥58</span>
                    </span>
                    <span class="zone-tag zone-reduce" th:if="${r.reduceZone != null}">
                        <span class="zone-tag-label">减仓</span>
                        <span class="zone-tag-value" th:text="${r.reduceZone}">¥68 以上</span>
                    </span>
                    <span class="zone-tag zone-stop" th:if="${r.stopLoss != null}">
                        <span class="zone-tag-label">止损</span>
                        <span class="zone-tag-value" th:text="${r.stopLoss}">¥48</span>
                    </span>
                </div>
                <div class="report-list-actions">
                    <a th:href="@{/stock-reports/view(file=${r.filename}, code=${stockCode})}" class="btn btn-sm btn-outline-primary">查看</a>
                    <button class="btn btn-sm btn-outline-danger" onclick="confirmDeleteReport(this)"
                            th:attr="data-file=${r.filename}">删除</button>
                </div>
            </div>
```

- [ ] **Step 3: 视觉检查 — 用浏览器打开现有页面确认无解析错误**

```bash
cd D:/workspace/project/stock-chose && ./mvnw clean package -DskipTests -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS（构建不验证 Thymeleaf 模板语法，需手动检查）

- [ ] **Step 4: Commit**

```bash
cd D:/workspace/project/stock-chose && git add src/main/resources/templates/pages/stock-reports.html && git commit -m "feat(template): show real-time price in title and 4 zone tags per report

Title row now shows live price (or '实时价-' fallback).
Each report row shows buy/add/reduce/stop zone tags pulled
from the parsed StockReport entity. Missing zones are
silently omitted via th:if.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: CSS 样式补充

**Files:**
- Modify: `src/main/resources/static/css/style.css`（追加到文件末尾）

- [ ] **Step 1: 追加样式**

在 `style.css` 文件末尾追加：

```css

/* ========== 个股详情页：实时价 + 区间标签 ========== */
.real-time-price {
    font-size: 0.55em;
    font-weight: 600;
    vertical-align: middle;
    margin-left: 16px;
    padding: 4px 12px;
    background: #e8f0fe;
    color: #1967d2;
    border-radius: 4px;
}

.real-time-price--fallback {
    background: #f1f3f4;
    color: #888;
}

.zone-tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 4px;
    border-left: 3px solid;
    white-space: nowrap;
}

.zone-tag-label {
    font-weight: 600;
    color: #555;
}

.zone-tag-value {
    color: #1a1a1a;
}

.zone-tag.zone-buy {
    background: #e6f4ea;
    border-left-color: #1e7e34;
}

.zone-tag.zone-add {
    background: #e8f0fe;
    border-left-color: #1967d2;
}

.zone-tag.zone-reduce {
    background: #fce8e6;
    border-left-color: #d93025;
}

.zone-tag.zone-stop {
    background: #f1f3f4;
    border-left-color: #5f6368;
}

@media (max-width: 768px) {
    .report-list-info {
        flex-wrap: wrap;
    }
}
```

- [ ] **Step 2: 验证 target 目录同步**

`style.css` 同时存在于 `src/main/resources/static/css/` 和 `target/classes/static/css/`，但只有源码会被 git 追踪。构建时会自动同步。无需手动操作。

- [ ] **Step 3: Commit**

```bash
cd D:/workspace/project/stock-chose && git add src/main/resources/static/css/style.css && git commit -m "feat(style): real-time price badge and zone tag styles

Reuses the green/blue/red/gray semantic palette from the
stock list page so the price-zone relationship reads the
same across views. Adds flex-wrap on small screens so
zone tags stack gracefully on mobile.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: 构建验证 + 回归测试

- [ ] **Step 1: 重新构建项目**

```bash
cd D:/workspace/project/stock-chose && ./mvnw clean package -DskipTests -q 2>&1 | tail -10
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 跑全部单元测试**

```bash
cd D:/workspace/project/stock-chose && ./mvnw test -q 2>&1 | tail -30
```

Expected: BUILD SUCCESS, 所有测试通过（至少 10 个：3 个新增 + 7 个 PriceParsingTest + 其他已有测试）

- [ ] **Step 3: 如有失败，定位并修复后再跑一次**

不要跳过失败的测试。

---

### Task 7: 启动应用 + 接口冒烟

- [ ] **Step 1: 启动应用（后台）**

```bash
cd D:/workspace/project/stock-chose && java -jar target/stock-chose-0.0.1-SNAPSHOT.jar > app.log 2>&1 &
```

- [ ] **Step 2: 等待应用就绪**

```bash
cd D:/workspace/project/stock-chose && for i in {1..30}; do curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/ 2>/dev/null | grep -q 200 && echo "ready" && break; sleep 1; done
```

Expected: `ready`

- [ ] **Step 3: 验证个股详情页返回 200 + 含实时价**

```bash
cd D:/workspace/project/stock-chose && curl -s "http://localhost:8080/stock-reports/601888" -o detail.html && grep -c "real-time-price" detail.html
```

Expected: ≥ 1（标题行含实时价 span）

- [ ] **Step 4: 验证 4 个 zone-tag 渲染**

```bash
cd D:/workspace/project/stock-chose && grep -c "zone-tag" detail.html
```

Expected: ≥ 5（1 个 .real-time-price 不算；4 个 zone-tag × 至少 1 个样本 + container 标签）

如果报告数 ≥ 1，期望 `≥ 8`（4 zone × 2-3 份报告），实际最少 4。

- [ ] **Step 5: 验证模板无 Thymeleaf 错误**

```bash
cd D:/workspace/project/stock-chose && grep -i "error\|exception" app.log | head -5
```

Expected: 无新错误（构建期已编译过 Thymeleaf 模板，运行时不会报解析错误，但应检查 controller 日志）

- [ ] **Step 6: 关闭应用**

```bash
cd D:/workspace/project/stock-chose && pkill -f stock-chose-0.0.1-SNAPSHOT.jar
```

---

### Task 8: 浏览器手动验证清单

启动应用后逐项核对：

- [ ] **Step 1: 访问 http://localhost:8080/stock-reports**
  - 现有列表功能未损坏
  - 列表页本身不显示实时价（不在范围内）

- [ ] **Step 2: 点击任一股票名进入 `/stock-reports/{code}`**
  - 标题行右侧出现蓝色徽章显示实时股价（如 `¥57.20`）
  - 数字格式为 `¥xx.xx`

- [ ] **Step 3: 选一份 4 个区间齐备的报告（如 601888 / 000858 / 600188）**
  - 对照 markdown 报告，确认 4 个 zone-tag 内容正确
  - 4 个颜色分别为 绿/蓝/红/灰
  - 标签 + 值都可见

- [ ] **Step 4: 选一份 4 个区间部分缺失的报告（如某些小盘股报告）**
  - 缺失的 zone-tag 不渲染
  - 存在的 zone-tag 正常显示

- [ ] **Step 5: 模拟实时价不可用**
  - 临时把 `StockReportService.fetchRealTimePrices` 的 URL 改错（仅本地测试，不提交）
  - 刷新页面 → 实时价徽章降级为灰色 `实时价-`
  - 其余内容（报告列表、4 个区间）正常显示
  - 恢复 URL

- [ ] **Step 6: 移动端宽度验证**
  - 浏览器 dev tools 切到 375px 宽
  - zone-tag 自动换行不撑破布局
  - 实时价徽章仍在标题行

---

## Self-Review

- [x] **Spec coverage**:
  - F1 (4 区间) → Task 4 + Task 5
  - F2 (实时价) → Task 1-3 + Task 4
  - F3 (模板/样式) → Task 4 + Task 5
  - F4 (后端方法) → Task 1 + Task 2

- [x] **Placeholder scan**: 无 TBD/TODO/"待实现"

- [x] **Type consistency**:
  - `getRealTimePrice(String): BigDecimal` 在 Task 1 测试中、Task 2 实现中、Task 3 调用中、Task 4 模板 `${realTimePrice != null}` 中口径一致
  - `realTimePrice` model attribute 名 Task 3 定义、Task 4 模板引用一致
  - CSS class 名 `.real-time-price` / `.real-time-price--fallback` / `.zone-tag` / `.zone-buy` / `.zone-add` / `.zone-reduce` / `.zone-stop` 全部对齐

- [x] **Frequent commits**: 8 个任务 = 5 个代码 commit + 1 个测试 commit + 构建/启动/验证无 commit
