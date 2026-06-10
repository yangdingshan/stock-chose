#!/usr/bin/env python3
"""
Download A-share stock data: prices from Sina + fundamentals from AKShare.
Outputs JSON to stdout with fields: code, name, price, pe, pb, roe
"""

import sys
import json
import time
import os
import re

# Ensure UTF-8 output regardless of system locale
if hasattr(sys.stdout, 'reconfigure'):
    sys.stdout.reconfigure(encoding='utf-8')
if hasattr(sys.stderr, 'reconfigure'):
    sys.stderr.reconfigure(encoding='utf-8')

# Bypass proxy for domestic Chinese financial sites
os.environ['no_proxy'] = 'eastmoney.com,sina.com.cn,sina.cn,gtimg.cn,qq.com'

import requests
import akshare as ak


def get_stock_codes():
    """Get all A-share stock codes from Sina API by iterating market prefixes."""
    codes = []
    # SH: 600-605, 688, 900
    # SZ: 000-003, 300-301, 002
    prefixes = [
        "sh60", "sh68", "sh90",
        "sz00", "sz30", "sz20",
    ]

    for prefix in prefixes:
        for sub in range(100):
            if prefix.startswith("sh"):
                if prefix == "sh60":
                    page_prefix = f"sh60{str(sub).zfill(4)[:2]}"
                elif prefix == "sh68":
                    page_prefix = f"sh68{str(sub).zfill(4)[:2]}"
                elif prefix == "sh90":
                    page_prefix = f"sh90{str(sub).zfill(4)[:2]}"
                else:
                    continue
            elif prefix.startswith("sz"):
                page_prefix = f"{prefix}{str(sub).zfill(4)[:2]}"
                # Actually this approach is too complex and fragile

    # Simpler approach: use AKShare to get all stock codes
    return None


def get_prices_batch(codes_batch):
    """Get prices for a batch of stock codes from Sina API."""
    if not codes_batch:
        return {}

    url = f"http://hq.sinajs.cn/list={','.join(codes_batch)}"
    try:
        r = requests.get(url, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer": "https://finance.sina.com.cn",
        }, timeout=30)
        r.encoding = "gbk"
    except Exception as e:
        print(f"Request error: {e}", file=sys.stderr)
        return {}

    if r.status_code != 200:
        return {}

    results = {}
    for line in r.text.strip().split("\n"):
        if not line.strip():
            continue
        # var hq_str_sh600519="name,open,close,current,...";
        m = re.match(r'var hq_str_(\w+)="(.+)"', line.strip())
        if not m:
            continue
        sid = m.group(1)  # e.g., sh600519
        fields = m.group(2).split(",")
        if len(fields) < 4:
            continue
        # Ensure the stock code is normalized (remove market prefix for code)
        code = sid[2:]  # Remove sh/sz prefix
        name = fields[0]
        price = fields[3]  # Current price is field index 3

        try:
            price_val = float(price)
        except (ValueError, TypeError):
            continue

        if price_val <= 0:
            continue

        results[code] = {
            "name": name,
            "price": str(price_val),
        }

    return results


def main():
    try:
        # Step 1: Get stock list from AKShare via industry/sector query
        # Use stock_yjbb_em which works with proxy - it has all stock codes
        print("Fetching stock fundamentals from AKShare...", file=sys.stderr)
        try:
            # Try most recent complete quarter first (2025Q4), then 2026Q1
            df_fund = ak.stock_yjbb_em(date="20251231")
        except Exception as e:
            print(f"Failed 20251231, trying 20260331: {e}", file=sys.stderr)
            df_fund = ak.stock_yjbb_em(date="20260331")

        print(f"Got {len(df_fund)} fundamental records", file=sys.stderr)

        # Build fundamental lookup: code -> {eps, bvps, roe}
        fund_data = {}
        for _, row in df_fund.iterrows():
            code = str(row.get("股票代码", ""))
            name = str(row.get("股票简称", ""))
            eps = row.get("每股收益", None)
            bvps = row.get("每股净资产", None)
            roe = row.get("净资产收益率", None)

            if not code or len(code) < 6:
                continue
            code = code.zfill(6)

            if name.startswith("*ST") or name.startswith("ST") or name.startswith("PT") or name.startswith("S"):
                continue

            try:
                eps_val = float(eps) if eps is not None and str(eps) != "nan" else None
                bvps_val = float(bvps) if bvps is not None and str(bvps) != "nan" else None
                roe_val = float(roe) if roe is not None and str(roe) != "nan" else None
            except (ValueError, TypeError):
                continue

            # Allow negative/zero values to keep full stock list;
            # use 0 as default for missing fields
            if eps_val is None:
                eps_val = 0.0
            if bvps_val is None:
                bvps_val = 0.0
            if roe_val is None:
                roe_val = 0.0

            industry = str(row.get("所处行业", "")) if row.get("所处行业") is not None else ""

            fund_data[code] = {
                "name": name,
                "eps": eps_val,
                "bvps": bvps_val,
                "roe": roe_val,
                "industry": industry,
            }

        print(f"Valid fundamental records: {len(fund_data)}", file=sys.stderr)

        # Step 2: Get prices from Sina API in batches
        print("Fetching prices from Sina...", file=sys.stderr)
        all_codes = sorted(fund_data.keys())
        batch_size = 800  # Sina supports up to ~800 stocks per request

        price_data = {}
        for i in range(0, len(all_codes), batch_size):
            batch = all_codes[i:i + batch_size]
            # Add market prefix: sh for 60xxxx/68xxxx/90xxxx, sz for 00xxxx/30xxxx/20xxxx
            sina_codes = []
            for code in batch:
                if code.startswith(("60", "68", "90")):
                    sina_codes.append(f"sh{code}")
                elif code.startswith(("00", "30", "20")):
                    sina_codes.append(f"sz{code}")
                else:
                    # Try both
                    sina_codes.append(f"sh{code}")
                    sina_codes.append(f"sz{code}")

            print(f"  Batch {i // batch_size + 1}: {len(sina_codes)} codes...", file=sys.stderr)
            batch_prices = get_prices_batch(sina_codes)
            price_data.update(batch_prices)

            if i + batch_size < len(all_codes):
                time.sleep(0.5)  # Rate limiting

        print(f"Got prices for {len(price_data)} stocks", file=sys.stderr)

        # Step 3: Combine - calculate PE = Price/EPS, PB = Price/BVPS
        stocks = []
        for code, fd in fund_data.items():
            if code not in price_data:
                continue

            pd_data = price_data[code]
            price = float(pd_data["price"])

            eps = fd["eps"]
            bvps = fd["bvps"]
            roe = fd["roe"]

            pe = price / eps if eps > 0 else 0
            pb = price / bvps if bvps > 0 else 0

            # Only filter extreme outliers
            if pe > 10000:
                pe = 0
            if pb > 1000:
                pb = 0

            stocks.append({
                "code": code,
                "name": fd["name"],
                "price": str(price),
                "pe": f"{pe:.2f}",
                "pb": f"{pb:.2f}",
                "roe": str(roe),
                "industry": fd.get("industry", ""),
            })

        print(json.dumps({"stocks": stocks, "total": len(stocks)}, ensure_ascii=False))

    except ImportError as e:
        print(json.dumps({"error": f"Missing library: {e}. Run: pip install akshare requests"}))
        sys.exit(1)
    except Exception as e:
        import traceback
        print(json.dumps({"error": str(e)}), file=sys.stderr)
        traceback.print_exc(file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
