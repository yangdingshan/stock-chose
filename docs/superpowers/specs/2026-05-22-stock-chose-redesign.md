# StockChose Redesign Specification

**Date:** 2026-05-22
**Status:** Approved

## Overview

Transform the existing command-line stock selection tool into a web-based application with:
- Multi-source redundant data download (Eastmoney → AKShare → local cache fallback)
- Thymeleaf-based web frontend with four pages
- Retained core ranking/scoring algorithm from existing `StockService`

## Architecture

```
Frontend (Thymeleaf + Bootstrap 5)
  → Controller layer (Spring MVC @Controller)
    → Service layer
      → StockService (ranking algorithm, retained & refactored)
      → DataDownloadService (orchestrates multi-source download)
      → IndexService (index constituent processing)
      → DataSource adapter layer
        → EastmoneyAdapter
        → AKShareAdapter
        → LocalCacheAdapter (fallback)
    → Repository layer (Spring Data JPA)
      → MySQL (stock + ranking data)
      → Local JSON file cache (download snapshots)
```

## Pages

1. **Dashboard** (`/`) — Summary stat cards (total A-shares, qualified stocks, index count, model info) + composite Top 20 table
2. **Full Ranking** (`/ranking`) — Searchable, sortable, filterable full ranking table with pagination
3. **Download Management** (`/download`) — Trigger downloads, view data source status, download log
4. **Index Coverage** (`/index`) — Stock-by-index-coverage ranking + largest indices by constituent count

## Data Download Strategy

Priority order with automatic fallback:
1. **Eastmoney API** (`push2.eastmoney.com`) — 3 retries with exponential backoff + random UA
2. **AKShare** — Via HTTP proxy to Python microservice or direct API call
3. **Local cache** — JSON snapshot from last successful download, ensures system is always usable

Each successful download overwrites the local cache. Frontend displays data source and last update time.

## Visual Design

- Clean light theme, single accent color (blue `#0d6efd`)
- Off-black text (`#1a1a2e`), desaturated palette
- Cards with subtle borders + tinted shadows, left-border color accents for status
- Monospace font for log areas, tabular-nums for data columns
- `PingFang SC` / `Microsoft YaHei` font stack for Chinese

## Technical Decisions

- Keep Spring Boot 2.6.3 + Java 8 (no framework upgrade)
- Add `spring-boot-starter-thymeleaf` for server-side rendering
- Bootstrap 5 for base styles, custom CSS for taste-level polish
- Remove `Scanner`-based interactive console input from `flushIndex()`
- Remove `WxOrderService` from main flow (keep class but no UI)
- Refactor `StockService` into smaller focused classes: `StockRankService`, `DataDownloadService`, `IndexService`

## Scope Boundaries

**In scope:**
- Multi-source data download with redundancy
- Four Thymeleaf pages with clean design
- Refactor monolithic StockService into focused services
- Local JSON file cache for offline resilience

**Out of scope:**
- User authentication / login
- Real-time stock price updates
- Historical data tracking
- Export to Excel/CSV
- Docker deployment
