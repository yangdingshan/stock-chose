# 行业报告管理功能 — 设计文档

**日期**：2026-05-31  
**版本**：v1

---

## 概述

为现有 StockChose 应用新增行业研究报告的 Web 管理功能。报告以 Markdown 文件形式存放在 `industry-analyst/` 目录，通过 Web 页面实现列表浏览、标题搜索、详情查看、删除操作。

## 需求摘要

| 功能 | 说明 |
|------|------|
| 列表展示 | 卡片式展示所有报告：标题、日期、标签、摘要 |
| 标题搜索 | 按标题关键词过滤，不支持全文搜索 |
| 详情查看 | Markdown 渲染为 HTML 展示，排版清晰可读 |
| 删除 | 确认后从磁盘删除文件 |
| 实时同步 | 新报告放入目录后自动可见，无需重启 |

## 架构

```
浏览器
  │
  ▼
ReportController  ──  GET /reports, /reports/view, POST /reports/delete
  │
  ▼
ReportService  ──  扫描目录、解析 Markdown、渲染 HTML、删除文件
  │
  ▼
industry-analyst/*.md  (文件系统)
```

## 新增/修改文件

```
新增:
  src/main/java/.../controller/ReportController.java
  src/main/java/.../service/ReportService.java
  src/main/java/.../domain/Report.java
  src/main/resources/templates/pages/reports.html       — 列表+查看（单文件双状态，th:if 切换）

修改:
  src/main/resources/templates/layout.html              — 导航栏新增"行业报告"链接
  src/main/resources/application.yaml               — 新增 report.dir 配置
  pom.xml                                           — 新增 flexmark 依赖
```

## 数据模型

```java
public class Report {
    String filename;   // "光伏行业深度分析报告_2026年5月.md"
    String title;      // 从第一个 # 标题行提取
    LocalDate date;    // 从 **分析日期**： 解析
    List<String> tags; // 从文件名分词提取，过滤数字/年份噪声
    String summary;    // 跳过标题和元数据后取前 150 字
    long size;         // 文件大小 (KB)
}
```

- 元数据解析容错优先：解析不到的信息留空，不报错
- 标签从文件名 `_` 和 `-` 分词，过滤年份数字等噪声

## 路由

| 方法 | 路由 | 说明 |
|------|------|------|
| `GET` | `/reports` | 列表页，`?search=` 可选标题搜索 |
| `GET` | `/reports/view?file=xxx.md` | 查看报告，Markdown 渲染为 HTML |
| `POST` | `/reports/delete?file=xxx.md` | 删除，返回 JSON `{success, error}` |

- `file` 参数做路径穿越校验（拒绝含 `../` 的路径）
- 文件名 URL 编码传输

## 页面设计

### 列表页 (`/reports`)

- 顶部搜索框（`<input>` + 搜索按钮），实时提交 GET 请求
- 报告卡片：标题、日期、标签徽章、摘要（2 行截断）、[查看] [删除] 按钮
- 无搜索结果的空状态提示
- 复用 `layout.html`，导航栏 `currentPage = 'reports'`

### 查看页 (`/reports/view`)

- 面包屑：`报告列表 > 报告标题`
- Markdown 内容渲染：
  - 标题 → `<h2>` / `<h3>`
  - 加粗 → `<strong>`
  - 表格 → `<table class="table table-striped">`
  - 列表 → `<ul>` / `<ol>`
  - 代码块 → `<pre class="bg-light p-3">`
- 底部操作栏：[返回列表] [删除]

### 删除交互

- 点击删除 → Bootstrap Modal 确认 → 确认后 POST → JSON 响应 → 成功则从 DOM 移除卡片/跳回列表

## Markdown 渲染

- 使用 `com.vladsch.flexmark` 库
- 配置：启用表格扩展、删除线、代码高亮
- HTML 输出做 XSS 过滤（移除 `<script>` 等危险标签）

## 配置

```yaml
# application.yaml 新增
report:
  dir: ./industry-analyst
```

- 默认值 `./industry-analyst`，可通过配置文件修改
- 目录不存在时自动创建，列表返回空

## 错误处理

| 场景 | 行为 |
|------|------|
| 目录不存在 | 自动创建，列表显示空 |
| 文件已被删除 | 返回 404 JSON |
| 文件不是 .md | 跳过不展示 |
| 路径穿越攻击 | 400 拒绝 |
| Markdown 解析异常 | 降级显示原文 |

## 不做什么

- 不上传/新建报告（用户手动放文件到目录）
- 不编辑报告内容
- 不支持全文搜索
- 不建数据库表
- 不做用户认证
