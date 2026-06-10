#!/usr/bin/env python3
"""
Download index constituent data via AKShare and count per-stock index coverage.
Outputs JSON: {"stocks": {"000001": 5, ...}, "index_count": 80, "last_update": "..."}
"""

import sys
import json
import time
import os
import re

if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8')

os.environ['no_proxy'] = 'eastmoney.com,sina.com.cn,sina.cn,gtimg.cn,qq.com,csindex.com.cn'

import akshare as ak


# Major A-share equity indexes curated for scoring model (55 indexes)
MAJOR_INDEXES = [
    # === 规模指数 (Size) ===
    ("000001", "上证指数"),
    ("000300", "沪深300"),
    ("000905", "中证500"),
    ("000852", "中证1000"),
    ("932000", "中证2000"),
    ("000510", "中证A500"),
    ("000688", "科创50"),
    ("000698", "科创100"),
    ("000016", "上证50"),
    ("000010", "上证180"),
    ("000009", "上证380"),
    ("000903", "中证100"),
    ("000904", "中证200"),
    ("000906", "中证800"),
    ("399006", "创业板指"),
    ("399330", "深证100"),
    ("399673", "创业板50"),
    ("399001", "深证成指"),
    ("399106", "深证综指"),
    ("399102", "创业板综"),
    # === 行业指数 (Sector) ===
    ("000932", "中证消费"),
    ("000933", "中证医药"),
    ("000934", "中证金融"),
    ("000935", "中证信息"),
    ("000936", "中证电信"),
    ("000937", "中证能源"),
    ("000941", "中证新能源"),
    ("000807", "中证食品饮料"),
    ("000808", "中证医药100"),
    ("000827", "中证环保"),
    ("000821", "中证军工"),
    ("399975", "中证证券"),
    ("399986", "中证银行"),
    ("399989", "中证医疗"),
    ("399987", "中证酒"),
    ("399997", "中证白酒"),
    ("399971", "中证传媒"),
    ("399993", "中证生物医药"),
    ("399998", "中证煤炭"),
    ("000823", "中证有色"),
    # === 主题/策略指数 (Thematic) ===
    ("000922", "中证红利"),
    ("000015", "上证红利"),
    ("000925", "中证国企"),
    ("930721", "CS电子"),
    ("930746", "CS计算机"),
    ("930791", "CS医药"),
    ("930903", "中证云计算"),
    ("930904", "中证半导体"),
    ("930905", "中证5G通信"),
    ("930906", "中证新能源汽车"),
    ("930907", "中证光伏产业"),
    ("930901", "中证人工智能"),
    ("930902", "中证大数据"),
    ("930910", "中证新材料"),
    ("930914", "中证碳中和"),
]


def print_progress(current, total, code, name, stocks_count, status):
    """Output progress as JSON line to stdout so Java can parse it."""
    print(json.dumps({
        "progress": {
            "current": current,
            "total": total,
            "code": code,
            "name": name,
            "stocks": stocks_count,
            "status": status,
        }
    }, ensure_ascii=False))
    sys.stdout.flush()


def main():
    try:
        from collections import defaultdict

        coverage = defaultdict(int)
        index_stocks = {}  # index_code -> {"name": str, "codes": [str], "count": int}
        success_count = 0
        fail_count = 0

        print(f"Starting index download: {len(MAJOR_INDEXES)} indexes...", file=sys.stderr)

        for i, (idx_code, idx_name) in enumerate(MAJOR_INDEXES):
            success = False
            for attempt in range(3):
                try:
                    print(f"  [{i+1}/{len(MAJOR_INDEXES)}] {idx_code}...", end="", file=sys.stderr)
                    df = ak.index_stock_cons_csindex(symbol=idx_code)

                    if df is None or df.empty:
                        print(f" no data", file=sys.stderr)
                        break

                    codes = df["成分券代码"].dropna().astype(str).tolist()
                    cleaned = []
                    for code in codes:
                        code = code.strip().zfill(6)
                        coverage[code] += 1
                        cleaned.append(code)
                    index_stocks[idx_code] = {"name": idx_name, "codes": cleaned, "count": len(cleaned)}

                    success_count += 1
                    print(f" {len(codes)} stocks", file=sys.stderr)
                    success = True
                    break

                except Exception as e:
                    if attempt < 2:
                        print(f" retry...", file=sys.stderr)
                        time.sleep(2)
                    else:
                        print(f" FAIL: {e}", file=sys.stderr)

            if not success:
                # Fallback: try generic index_stock_cons for any index
                try:
                    df = ak.index_stock_cons(symbol=idx_code)
                    if df is not None and not df.empty:
                        col = "品种代码" if "品种代码" in df.columns else df.columns[0]
                        codes = df[col].dropna().astype(str).tolist()
                        cleaned = []
                        for code in codes:
                            code = code.strip().zfill(6)
                            coverage[code] += 1
                            cleaned.append(code)
                        index_stocks[idx_code] = {"name": idx_name, "codes": cleaned, "count": len(cleaned)}
                        success_count += 1
                        print(f"    -> generic fallback: {len(codes)} stocks", file=sys.stderr)
                        success = True
                except Exception:
                    pass

            if not success:
                fail_count += 1
                print_progress(i + 1, len(MAJOR_INDEXES), idx_code, idx_name, 0, "fail")
            else:
                count = index_stocks[idx_code]["count"]
                print_progress(i + 1, len(MAJOR_INDEXES), idx_code, idx_name, count, "ok")

            # Rate limiting
            if i < len(MAJOR_INDEXES) - 1:
                time.sleep(0.5)

        result = {
            "stocks": dict(coverage),
            "index_stocks": index_stocks,
            "index_count": success_count,
            "total_indexes_attempted": len(MAJOR_INDEXES),
            "failed": fail_count,
            "last_update": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

        print(f"\nDone! {success_count}/{len(MAJOR_INDEXES)} indexes downloaded.", file=sys.stderr)
        print(f"Total stocks with index coverage: {len(coverage)}", file=sys.stderr)

        print(json.dumps(result, ensure_ascii=False))

    except Exception as e:
        import traceback
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


def search_indexes(keyword):
    """Search CS indexes by keyword, output JSON list."""
    try:
        df = ak.index_csindex_all()
        cols = df.columns.tolist()
        code_col = cols[0]
        name_col = cols[1]
        full_name_col = cols[2]

        mask = df[name_col].str.contains(keyword, na=False) | df[full_name_col].str.contains(keyword, na=False)
        matched = df[mask][[code_col, name_col, full_name_col]]

        results = []
        for _, row in matched.iterrows():
            results.append({
                "code": str(row[code_col]).strip(),
                "name": str(row[name_col]).strip(),
                "full_name": str(row[full_name_col]).strip(),
            })

        print(json.dumps({"results": results, "count": len(results)}, ensure_ascii=False))
    except Exception as e:
        import traceback
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


def download_indexes(codes, merge_cache_path=None):
    """Download specific index codes, optionally merging with existing cache."""
    try:
        from collections import defaultdict

        # Load existing cache for merge
        if merge_cache_path and os.path.exists(merge_cache_path):
            with open(merge_cache_path, 'r', encoding='utf-8') as f:
                existing = json.load(f)
            coverage = defaultdict(int, existing.get("stocks", {}))
            index_stocks = existing.get("index_stocks", {})
        else:
            coverage = defaultdict(int)
            index_stocks = {}

        success_count = 0
        fail_count = 0

        print(f"Downloading {len(codes)} indexes...", file=sys.stderr)

        for i, (idx_code, idx_name) in enumerate(codes):
            success = False
            for attempt in range(3):
                try:
                    print(f"  [{i+1}/{len(codes)}] {idx_code} {idx_name}...", end="", file=sys.stderr)
                    df = ak.index_stock_cons_csindex(symbol=idx_code)

                    if df is None or df.empty:
                        print(f" no data", file=sys.stderr)
                        break

                    code_list = df["成分券代码"].dropna().astype(str).tolist()
                    cleaned = []
                    for code in code_list:
                        code = code.strip().zfill(6)
                        if merge_cache_path:
                            coverage[code] = coverage.get(code, 0) + 1
                        else:
                            coverage[code] += 1
                        cleaned.append(code)
                    index_stocks[idx_code] = {"name": idx_name, "codes": cleaned, "count": len(cleaned)}

                    success_count += 1
                    print(f" {len(cleaned)} stocks", file=sys.stderr)
                    success = True
                    break

                except Exception as e:
                    if attempt < 2:
                        print(f" retry...", file=sys.stderr)
                        time.sleep(2)
                    else:
                        print(f" FAIL: {e}", file=sys.stderr)

            if not success:
                try:
                    df = ak.index_stock_cons(symbol=idx_code)
                    if df is not None and not df.empty:
                        col = "品种代码" if "品种代码" in df.columns else df.columns[0]
                        code_list = df[col].dropna().astype(str).tolist()
                        cleaned = []
                        for code in code_list:
                            code = code.strip().zfill(6)
                            if merge_cache_path:
                                coverage[code] = coverage.get(code, 0) + 1
                            else:
                                coverage[code] += 1
                            cleaned.append(code)
                        index_stocks[idx_code] = {"name": idx_name, "codes": cleaned, "count": len(cleaned)}
                        success_count += 1
                        print(f"    -> generic fallback: {len(cleaned)} stocks", file=sys.stderr)
                        success = True
                except Exception:
                    pass

            if not success:
                fail_count += 1
                print_progress(i + 1, len(codes), idx_code, idx_name, 0, "fail")
            else:
                count = index_stocks[idx_code]["count"]
                print_progress(i + 1, len(codes), idx_code, idx_name, count, "ok")

            if i < len(codes) - 1:
                time.sleep(0.5)

        result = {
            "stocks": dict(coverage),
            "index_stocks": index_stocks,
            "index_count": len(index_stocks),
            "total_indexes_attempted": len(codes),
            "failed": fail_count,
            "last_update": time.strftime("%Y-%m-%d %H:%M:%S"),
        }

        print(f"\nDone! {success_count}/{len(codes)} indexes downloaded.", file=sys.stderr)
        print(f"Total stocks with index coverage: {len(coverage)}", file=sys.stderr)

        print(json.dumps(result, ensure_ascii=False))

    except Exception as e:
        import traceback
        print(json.dumps({"error": str(e)}, ensure_ascii=False))
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) >= 2:
        if sys.argv[1] == "--search" and len(sys.argv) >= 3:
            search_indexes(sys.argv[2])
        elif sys.argv[1] == "--codes" and len(sys.argv) >= 3:
            # Parse codes and optional names from args
            # Format: python script.py --codes 000932:中证消费,000933:中证医药 [--merge]
            merge_path = None
            code_args = sys.argv[2:]
            if "--merge" in code_args:
                merge_path = os.path.expanduser("~/.stock-chose/index-cache.json")
                code_args = [a for a in code_args if a != "--merge"]

            codes = []
            for arg in code_args[0].split(","):
                parts = arg.strip().split(":", 1)
                code = parts[0].strip()
                name = parts[1].strip() if len(parts) > 1 else code
                codes.append((code, name))
            download_indexes(codes, merge_cache_path=merge_path)
        else:
            print("Usage: python index_download.py [--search <keyword>] [--codes CODE:NAME,... [--merge]]", file=sys.stderr)
            sys.exit(1)
    else:
        main()
