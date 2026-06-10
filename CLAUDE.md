# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test

```bash
# Build the project
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=StockChoseApplicationTests

# Run a single test method
./mvnw test -Dtest=StockChoseApplicationTests#allInOne
```

## Architecture

Spring Boot 2.6.3 + Java 8 application for stock selection and scoring. Uses JPA/Hibernate with MySQL for persistence.

### Core flow (StockService)

1. **Data ingestion** — `downloadStockData()` pulls live stock data from Eastmoney's API (`push2.eastmoney.com`), with pagination, retry, and rate-limiting. Falls back to `simpleRead()` which reads from `resources/stock/Table.xls` via EasyExcel.
2. **Ranking** — `setPeRankAndRoeRank()` ranks all stocks by PE (asc), PB (asc), and ROE (desc) independently, storing ordinal ranks.
3. **Index cross-referencing** — `setIndexCount()` reads CSI index constituent `.xls` files from `resources/index/` and counts how many major indexes each stock belongs to.
4. **Composite scoring** — `setIndexCountRank()` ranks by index count (desc, tie-broken by PE rank). `setFinalRank()` computes `peAndRoeCount` (PE rank + ROE rank), then `indexPeRoeRank` (index rank + PE+ROE rank).
5. **Index download** — `flushIndex()` is interactive (reads from stdin): queries CSI index list via `csindex.com.cn` API, downloads constituent `.xls` files to `src/main/resources/index/`.

### Key classes

| Class | Role |
|---|---|
| `Stock` | JPA entity mapped to `stock` table — code, name, price, PE/PB/ROE values and their ranks, index count, composite ranks |
| `StockRead` | EasyExcel DTO for reading stock data from spreadsheet |
| `IndexRead` | EasyExcel DTO for reading CSI index constituent files |
| `StockRepository` | Spring Data JPA repository for `Stock` |
| `LambadaTools` | Utility providing `forEachWithIndex()` — a `Consumer<T>` wrapper that tracks iteration index for use in stream ranking |
| `WxOrderService` | Fetches order data from a WeChat mini-program logistics backend (`scwmwl.com`), currently configured for 西科大 with 万达 parameters commented out |

### Database

MySQL database `stock` at `localhost:3306`. JPA configured with `ddl-auto: update` — schema is auto-managed from entity annotations. Application config is in `application.yaml` (no profiles).

### Configuration note

`application.yaml` contains hardcoded database credentials and `WxOrderService.java` line 36 contains a hardcoded session token. These are not suitable for production use.
