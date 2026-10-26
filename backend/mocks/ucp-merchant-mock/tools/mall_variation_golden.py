#!/usr/bin/env python3
"""몰별 편차 규칙의 황금값을 뽑는다. Java `MallVariationTest` 가 이 파일과 자기 계산을 비교한다(DESIGN.md 5.7절).

    python tools/mall_variation_golden.py --pool src/main/resources/catalog/pool.json.gz \
        --out src/test/resources/catalog-pool/golden.json

풀에서 상품 열두 건을 고른다 — 앞 넷(기존 시드), 기준가 1만원 이상 넷, 옵션 차액이 0 이 아닌 넷. 몰 셋 각각의
id·basePrice·variants 를 적는다. 해시 원시값(u, uuid5)도 하나씩 적어 규칙의 어느 층이 틀렸는지 바로 보이게 한다.
"""

import argparse
import gzip
import json
from pathlib import Path

import mall_rules

MALLS = ("merchant-a", "merchant-b", "merchant-c")


def load_pool(path):
    raw = Path(path).read_bytes()
    if path.endswith(".gz"):
        raw = gzip.decompress(raw)
    return json.loads(raw.decode("utf-8"))["products"]


def pick(products):
    chosen = []
    seen = set()

    def take(product):
        if product["externalId"] not in seen and len(chosen) < 12:
            seen.add(product["externalId"])
            chosen.append(product)

    for product in products[:4]:
        take(product)
    for product in products:
        if len([p for p in chosen if p["basePrice"] >= 10000]) >= 4 + sum(1 for p in chosen[:4] if p["basePrice"] >= 10000):
            break
        if product["basePrice"] >= 10000:
            take(product)
    for product in products:
        if any(v["priceDelta"] != 0 for v in product["variants"]):
            take(product)
        if len(chosen) >= 12:
            break
    return chosen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pool", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()
    products = load_pool(args.pool)
    cases = []
    for product in pick(products):
        cases.append({
            "externalId": product["externalId"],
            "basePrice": product["basePrice"],
            "variants": [{"sku": v["sku"], "optionName": v["optionName"], "optionValue": v["optionValue"],
                          "priceDelta": v["priceDelta"], "stock": v["stock"], "status": v.get("status", "ACTIVE")}
                         for v in product["variants"]],
            "malls": {mall: mall_rules.derive(mall, product) for mall in MALLS},
        })
    sample = "kurly:1000017436"
    document = {
        "note": "tools/mall_variation_golden.py 가 tools/mall_rules.py 로 뽑았다. 규칙을 바꾸면 다시 뽑는다",
        "primitives": {
            "externalId": sample,
            "mall": "merchant-a",
            "uPrice": mall_rules.u(sample, "merchant-a", "price"),
            "uStock": mall_rules.u(sample, "merchant-a", "stock"),
            "factor": mall_rules.price_factor(sample, "merchant-a"),
            "id": mall_rules.deterministic_id("merchant-a", sample),
        },
        "cases": cases,
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="")
    print(f"황금값 {len(cases)}건 → {args.out}")


if __name__ == "__main__":
    main()
