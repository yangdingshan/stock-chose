# 个股详情页：报告区间展示 + 实时股价

**日期**：2026-06-07
**版本**：v1

---

## 概述

为 `/stock-reports/{code}` 个股详情页（点击股票名进入的"该股票多份报告"列表）增加两项展示能力：

1. **每份报告的买卖区间**：在每行末尾、动作按钮之前，列出该报告的买入区间、加仓区间、减仓区间、止损价格。
2. **股票实时股价**：在该页标题（股票名 + 代码）同行显示当前实时股价，调用东方财富接口获取。

## 需求摘要

| 编号 | 需求 | 说明 |
|------|------|------|
| F1 | 每份报告展示 4 个区间 | 买入 / 加仓 / 减仓 / 止损，缺失则不渲染 |
| F2 | 标题行展示实时股价 | 股票名 + 代码同行右侧，价格缺失降级为 `实时价-` |
| F3 | 模板/样式改造 | 沿用现有 `report-list-row` 横向布局 |
| F4 | 后端提供单股实时价查询 | 复用现有 `fetchRealTimePrices` |

不在范围内：
- 不做"当前价落入哪个区间"的颜色高亮（用户明确不要）
- 不改股票列表页 (`/stock-reports`) 的现有布局
- 不改动 `StockReport` 实体字段
- 不新增数据库表

## 现状分析

后端数据已完备：
- `StockReportService.parseReport()`（src/main/java/.../service/StockReportService.java:307-423）用 4 条正则（`BUY_ZONE_PATTERN` / `ADD_ZONE_PATTERN` / `REDUCE_ZONE_PATTERN` / `STOP_LOSS_PATTERN`）从 markdown 报告提取 4 个区间，写入 `StockReport.buyZone/addZone/reduceZone/stopLoss`。
- `fetchRealTimePrices(List<String> codes)`（:425-470）已封装东方财富 `push2.eastmoney.com` 批量行情接口，15s 超时，自动容错（异常返回空 Map）。
- `StockReportPriceParsingTest`（src/test/.../StockReportPriceParsingTest.java）已覆盖千位分隔符价格解析回归。

模板层缺口：
- `stock-reports.html` 的 `viewMode='reportList'` 块（:113-128）只渲染日期/周期/分数/类型/动作，没有用 `r.buyZone` 等 4 个字段。
- 同一模板 `viewMode='stockList'` 块已在表格里展示了 4 个区间（:74-77），可作为视觉参考。
- 标题行（:104-107）只显示股票名 + 报告数。

## 架构

```
浏览器
  │
  ▼
StockReportController.reportList(code)
  │  调用 getRealTimePrice(code) 注入 model.realTimePrice
  │  调用 listReportsByStock(code) 注入 model.reports（含 4 个区间字段）
  ▼
StockReportService
  │  getRealTimePrice(code)         ── 新增 public 方法
  │  ├── 委托 fetchRealTimePrices(Collections.singletonList(code))
  │  listReportsByStock(code)       ── 已有，扫描 markdown 解析区间
  ▼
stock/*.md  +  push2.eastmoney.com  (东方财富实时行情)
```

## 数据模型

无需改动 `StockReport` 实体（已包含 buyZone/addZone/reduceZone/stopLoss 四个字段）。

## 新增/修改文件

```
修改:
  src/main/java/.../service/StockReportService.java
    + getRealTimePrice(String code): BigDecimal
  src/main/java/.../controller/StockReportController.java
    ~ reportList(code, model) 加 model.addAttribute("realTimePrice", ...)
  src/main/resources/templates/pages/stock-reports.html
    ~ viewMode='reportList' 块: 标题行加实时价, 行内加 4 个区间 span
  src/main/resources/static/css/style.css
    + .real-time-price, .zone-tag 系列样式
```

## 接口 / 路由

| 方法 | 路由 | 变化 |
|------|------|------|
| `GET` | `/stock-reports/{code}` | 模板 + model 变化 |

无新增路由，无 API 路径变化。

## 详细设计

### 1. `StockReportService.getRealTimePrice(code)`

```java
public BigDecimal getRealTimePrice(String code) {
    if (code == null || code.isEmpty()) return null;
    Map<String, BigDecimal> prices = fetchRealTimePrices(java.util.Collections.singletonList(code));
    return prices.get(code);
}
```

- 复用现有 `fetchRealTimePrices`，避免重复实现 HTTP/解析逻辑
- 单元素批量调用，东方财富接口兼容
- 接口失败 → `fetchRealTimePrices` 内部 catch 返回空 Map → 此方法返回 null → 模板降级

### 2. `StockReportController.reportList` 改动

```java
@GetMapping("/stock-reports/{code}")
public String reportList(@PathVariable String code, Model model) {
    List<StockReport> reports = stockReportService.listReportsByStock(code);
    StockReport first = stockReportService.getFirstByCode(code);
    String stockName = first != null ? first.getStockName() : code;

    BigDecimal realTimePrice = stockReportService.getRealTimePrice(code);  // 新增

    model.addAttribute("title", stockName + "(" + code + ") - 个股报告");
    model.addAttribute("currentPage", "stock-reports");
    model.addAttribute("content", "pages/stock-reports");
    model.addAttribute("stockCode", code);
    model.addAttribute("stockName", stockName);
    model.addAttribute("reports", reports);
    model.addAttribute("realTimePrice", realTimePrice);  // 新增
    model.addAttribute("search", "");
    model.addAttribute("viewMode", "reportList");
    return "layout";
}
```

### 3. 模板改动

**标题行加实时价**（替换原 `h2` 内容）：

```html
<div class="page-header">
    <h2>
        <span th:text="${stockName} + ' (' + ${stockCode} + ')'">股票</span>
        <span class="real-time-price" th:if="${realTimePrice != null}"
              th:text="'¥' + ${realTimePrice}">¥0.00</span>
        <span class="real-time-price real-time-price--fallback" th:unless="${realTimePrice != null}">实时价-</span>
    </h2>
    <p class="text-muted">共 <span th:text="${reports.size()}">0</span> 份报告</p>
</div>
```

**报告行加 4 个区间标签**（在 `report-list-type` 之后、关闭 `</div>` 之前）：

```html
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
```

### 4. CSS 改动

追加到 `style.css` 末尾或合适区块：

```css
/* 标题行实时价徽章 */
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

/* 报告行区间标签 */
.zone-tag {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    font-size: 12px;
    padding: 2px 8px;
    border-radius: 4px;
    border-left: 3px solid;
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

/* 报告行小屏允许换行 */
@media (max-width: 768px) {
    .report-list-info {
        flex-wrap: wrap;
    }
}
```

## 视觉示意

```
中国中免 (601888)  ¥57.20
共 3 份报告

┌──────────────────────────────────────────────────────────────────┐
│ 2026-06-07  1年  72分  价值投资深度分析  │买入│¥52 以下  │加仓│¥52 - ¥58  │减仓│¥68 以上  │止损│¥48  [查看][删除] │
│ 2026-06-02  1年  66分  价值投资深度分析  │买入│¥55 以下  │加仓│¥55 - ¥62  │减仓│¥70 以上  │止损│¥50  [查看][删除] │
└──────────────────────────────────────────────────────────────────┘
```

## 错误处理

| 场景 | 行为 |
|------|------|
| 实时价接口超时/异常 | 降级为 `实时价-`（灰色徽章），不抛错 |
| 报告无买入/加仓/减仓/止损 | `th:if` 跳过该 span，行内其他元素照常渲染 |
| 股票无任何报告 | 现有 `empty-state` 分支处理，与本次改动无关 |
| markdown 文件名格式不匹配 | `parseReport` 返回 null，报告不展示，与本次改动无关 |

## 测试

| 层级 | 内容 |
|------|------|
| 单元 | `StockReportPriceParsingTest` 回归必须通过（保护 `parseZoneMin` / `parseZoneRange` / `PRICE_PATTERN` / `FAIR_VALUE_PATTERN`） |
| 集成 | `./mvnw clean package -DskipTests` 编译通过 |
| 手工 | 启动应用，点击 `/stock-reports` 中任一股票名 → 标题行右侧出现实时价，每行末尾出现 4 个区间徽章 |
| 手工 | 选一个 4 个区间都齐备的报告（如 601888 / 000858 等），对照 markdown 报告核对 4 个值 |
| 手工 | 选一个 4 个区间部分缺失的报告，验证缺失项不渲染 |
| 手工 | 关闭网络或断网，刷新详情页 → 实时价降级为灰色 `实时价-`，其余部分正常 |

## 配置

无新增配置项。`stock-report.dir` 已在 `application.yaml` 存在，`push2.eastmoney.com` 接口路径沿用现有。

## 不做什么

- 不在详情页做"实时价 vs 区间"的高亮联动
- 不把实时价持久化到数据库
- 不在股票列表页加新列
- 不动 `StockReport` 实体
- 不新增接口 / 路由
