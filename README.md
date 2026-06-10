# StockChose

A股量化选股系统。从网上下载全市场股票数据，按"估值便宜 + 盈利能力强 + 指数收录多"三个维度自动打分排名。

## 环境要求

- JDK 1.8+
- MySQL 5.7+
- Maven 3.6+（项目自带 Maven Wrapper）
- Python 3.8+（用于 AKShare 数据下载）
- AKShare 库：`pip install akshare requests`

## 快速启动

### 1. 创建数据库

```sql
CREATE DATABASE IF NOT EXISTS stock DEFAULT CHARACTER SET utf8mb4;
```

### 2. 修改数据库连接

编辑 `src/main/resources/application.yaml`，修改数据库用户名和密码：

```yaml
spring:
  datasource:
    username: root      # 改为你的用户名
    password: root      # 改为你的密码
```

### 3. 启动应用

```bash
./mvnw spring-boot:run
```

启动后访问 **http://localhost:8080**

## 页面说明

| 页面 | 路径 | 功能 |
|---|---|---|
| 仪表盘 | `/` | 数据概览 + 综合排名 Top 20 |
| 全部排名 | `/ranking` | 可搜索、筛选、排序的全量排名表 |
| 下载管理 | `/download` | 触发数据下载、查看下载日志 |
| 指数详情 | `/index` | 个股指数覆盖排行 + 典型指数成分数量 |

## 使用流程

1. 打开 **下载管理** 页面，点击「立即下载」获取最新 A 股数据
2. 点击「刷新指数」下载指数成分股数据
3. 回到 **仪表盘** 查看综合排名 Top 20
4. 在 **全部排名** 页面按不同维度筛选排序
5. 在 **指数详情** 页面查看成分股覆盖情况

## 数据下载策略

系统采用多数据源自动容错：

1. **AKShare**（主数据源）—— 调用 Python AKShare 库，从新浪财经获取股价 + 东方财富获取财务数据（EPS、每股净资产、ROE），计算 PE/PB
2. **东方财富 API**（备用）—— 直接调用东方财富 push2 接口，3 次重试 + 指数退避
3. **本地缓存**（兜底）—— 上次成功下载的数据自动备份到 `~/.stock-chose/stock-cache.json`，在线数据源不可用时自动加载

> 前置条件：需要安装 Python 3 和 AKShare 库（`pip install akshare requests`）

每次下载成功后自动更新本地缓存。页面会显示当前数据来源和更新时间。

## 评分模型

| 因子 | 说明 | 排序方向 |
|---|---|---|
| PE（市盈率） | 估值指标，越低越便宜 | 升序 |
| PB（市净率） | 资产估值指标 | 升序 |
| ROE（净资产收益率） | 盈利能力指标 | 降序 |
| 指数覆盖 | 被多少个指数收录 | 降序 |

综合得分 = 指数覆盖排名 + (PE排名 + ROE排名)

## 常用命令

```bash
# 编译
./mvnw clean compile

# 打包（跳过测试）
./mvnw clean package -DskipTests

# 运行测试
./mvnw test

# 运行单个测试
./mvnw test -Dtest=StockChoseApplicationTests

# 启动应用（开发模式）
./mvnw spring-boot:run
```
