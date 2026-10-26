#!/usr/bin/env python3
"""카탈로그 풀 생성기. 원본 넷을 읽어 풀 파일 하나(`catalog/pool.json.gz`)를 만든다.

설계는 DESIGN.md 5.7절(2026-09-21 개정)과 ADR 0011 이 소유한다. 이 스크립트는 그 절을 구현한 것이다.
옛 `build_demo_catalog.py`(몰 셋 파일을 만들던 것)를 개조했다. 몰 전개는 이제 로더가 한다 — 풀에는
몰도 id 도 없고 상품 본문(이름·카테고리·브랜드·이미지·기준가·옵션)만 있다.

    python tools/build_catalog_pool.py \
        --clean-sqlite ~/Downloads/korean_products_clean_bundle/korean_products_clean.sqlite \
        --raw-sqlite   ~/Downloads/korean_products_bundle/korean_products.sqlite \
        --kurly-catalog ~/Downloads/kurly-catalog-2026-09-21.json \
        --grocery tools/kurly-grocery.json \
        --out src/main/resources/catalog/pool.json.gz

순서가 곧 표본이다. 앞은 기존 시드(clean 1,408 + 식재료 184, 시연 필수), 그 뒤는 sha256(externalId) 오름차순이다.
그래서 로더의 pool-limit 으로 앞 N 건만 실어도 시연 재료는 항상 있고 나머지는 치우침 없는 균등 표본이 된다.

같은 입력이면 바이트까지 같은 파일이 나온다. gzip 헤더의 시각을 0 으로 쓰고 생성 시각을 파일에 넣지 않는다.
"""

import argparse
import gzip
import hashlib
import json
import re
import sqlite3
from pathlib import Path

# 옵션을 합성할 카테고리(428, ADR 0010). category_path 에 이 낱말이 있으면 그 축을 만든다.
SHOE_CATEGORIES = ("스포츠화", "운동화", "러닝화", "신발", "슈즈", "부츠", "샌들")
APPAREL_CATEGORIES = ("의류", "스포츠웨어", "양말", "장갑", "이너웨어", "티셔츠", "언더웨어", "키즈웨어")
SHOE_SIZES = ("250", "260", "270", "280")
APPAREL_SIZES = ("S", "M", "L")
DIGITAL_CATEGORIES = ("휴대폰", "태블릿", "노트북", "웨어러블", "이어폰", "헤드셋", "헤드폰")
GOODS_CATEGORIES = ("여행가방", "가방", "의자", "파우치", "지갑")
DIGITAL_COLORS = ("블랙", "화이트", "실버")
GOODS_COLORS = ("블랙", "베이지", "네이비")

STOCK_IN = 100
STOCK_OUT = 0

# 스키마 제약(V2·V11)과 위젯 CSP 허용 도메인(mcp-server CatalogUiConfiguration).
MAX_TITLE = 255
MAX_URL = 500
MAX_BRAND = 100
MAX_CATEGORY = 255
ALLOWED_IMAGE_HOSTS = ("product-image.kurly.com", "3p-image.kurly.com", "img-cf.kurly.com",
                       "cdn.daisomall.co.kr", "img.danuri.io")

MISSING = {"", "null", "n/a", "미제공", "상세페이지 참조"}


def has(category, keywords):
    return any(keyword in category for keyword in keywords)


def color_axis(category, source_colors):
    colors = []
    seen = set()
    for color in source_colors:
        value = str(color)[:40]
        if value.casefold() in seen:
            continue
        seen.add(value.casefold())
        colors.append(value)
    if has(category, DIGITAL_CATEGORIES) or has(category, SHOE_CATEGORIES):
        palette = DIGITAL_COLORS
    elif has(category, GOODS_CATEGORIES):
        palette = GOODS_COLORS
    else:
        return colors
    for color in palette:
        if len(colors) >= len(palette):
            break
        if color not in colors:
            colors.append(color)
    return colors


def size_axis(category):
    if has(category, SHOE_CATEGORIES):
        return list(SHOE_SIZES)
    if has(category, APPAREL_CATEGORIES):
        return list(APPAREL_SIZES)
    return []


def slug(axis_initial, index, value):
    return value.lower() if value.isascii() and value.isalnum() else f"{axis_initial}{index + 1}"


def synthesize_variants(external_id, category, source_colors, stock, max_variants):
    """원본에 옵션이 없는 상품의 옵션을 만든다(428). 만들 재료가 없으면 빈 목록이다. priceDelta 는 0 이다."""
    colors = color_axis(category, source_colors)
    sizes = size_axis(category)
    if not colors and not sizes:
        return []
    if colors and sizes and len(colors) * len(sizes) > max_variants:
        colors = colors[:max(max_variants // len(sizes), 1)]
    if colors and sizes:
        option_name = "색상/사이즈"
        combos = [([color, size], [slug("c", ci, color), slug("s", si, size)])
                  for ci, color in enumerate(colors) for si, size in enumerate(sizes)]
    elif colors:
        option_name = "색상"
        combos = [([color], [slug("c", ci, color)]) for ci, color in enumerate(colors)]
    else:
        option_name = "사이즈"
        combos = [([size], [slug("s", si, size)]) for si, size in enumerate(sizes)]
    variants = []
    skus = set()
    for values, parts in combos[:max_variants]:
        sku = external_id + "-" + "-".join(parts)
        if sku in skus:
            sku = f"{sku}-{len(skus) + 1}"
        skus.add(sku)
        variants.append({"sku": sku, "optionName": option_name, "optionValue": " / ".join(values),
                         "priceDelta": 0, "stock": stock, "status": "ACTIVE"})
    return variants


def pool_variants(row, max_variants):
    """원본 variant 를 풀의 variants 로 바꾼다. 몰 편차 전이라 배율·반올림이 없다.

    priceDelta 는 원본 옵션가 − 원본 기준가(정수 차액)다. 로더가 몰마다 배율을 곱하고 반올림한다.
    stock 은 원본 재고 정보만 반영한다(재고 있음 100, 품절 0). 몰별 품절은 로더가 얹는다.
    """
    external_id = row["product_id"]
    stock = STOCK_IN if row.get("in_stock", True) else STOCK_OUT
    raw_variants = row.get("raw_variants") or []
    if not raw_variants:
        synthesized = synthesize_variants(external_id, row["category"], row.get("colors") or [], stock, max_variants)
        if synthesized:
            return synthesized
        return [{"sku": f"{external_id}-default", "optionName": "option", "optionValue": "기본",
                 "priceDelta": 0, "stock": stock, "status": "ACTIVE"}]
    variants = []
    seen = set()
    for raw in raw_variants:
        if len(variants) == max_variants:
            break
        sku = raw.get("sku")
        if not sku or sku in seen:
            continue
        seen.add(sku)
        price = raw.get("sale_price")
        if price is None:
            price = row["price"]
        out_of_stock = raw.get("availability") == "out_of_stock"
        variants.append({
            "sku": f"{external_id}-{sku}",
            "optionName": "option",
            "optionValue": (raw.get("name") or "기본")[:100],
            "priceDelta": int(price) - int(row["price"]),
            "stock": STOCK_OUT if (out_of_stock or stock == STOCK_OUT) else STOCK_IN,
            "status": "ACTIVE",
        })
    if variants:
        return variants
    row = dict(row, raw_variants=[])
    return pool_variants(row, max_variants)


def valid_text(value):
    return value is not None and str(value).strip().lower() not in MISSING


def allowed_image(url):
    return url and len(url) <= MAX_URL and any(f"//{host}/" in url for host in ALLOWED_IMAGE_HOSTS)


def usable(row):
    return (row.get("product_id") and valid_text(row.get("name")) and len(row["name"]) <= MAX_TITLE
            and row.get("price") and int(row["price"]) > 0 and allowed_image(row.get("image"))
            and len(row.get("category") or "") <= MAX_CATEGORY and len(row.get("brand") or "") <= MAX_BRAND)


def load_clean_rows(sqlite_path):
    """전처리본 1,408건. 옛 생성기의 load_rows 와 같다."""
    connection = sqlite3.connect(sqlite_path)
    query = """
        SELECT product_id, source_code, name, primary_image_url, sale_price, variants_json,
               category_path, colors_json, brand
          FROM clean_catalog
         ORDER BY product_id
    """
    rows = []
    for (product_id, source, name, image, price, variants_json,
            category_path, colors_json, brand) in connection.execute(query):
        raw = json.loads(variants_json) if variants_json and variants_json not in ("[]", "null") else []
        colors = json.loads(colors_json) if colors_json and colors_json not in ("[]", "null") else []
        rows.append({
            "source": source, "product_id": product_id, "name": name, "image": image, "price": price,
            "raw_variants": raw, "category": category_path or "", "brand": brand or "",
            "colors": [color for color in colors if str(color).strip()], "in_stock": True,
        })
    connection.close()
    return [row for row in rows if usable(row)]


def load_extra(path):
    """수집본(471 식재료, 2026-09-21 컬리 카테고리). 두 수집기가 같은 행 구조를 쓴다."""
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    rows = []
    for product in document["products"]:
        rows.append({
            "source": product.get("source", "kurly"), "product_id": product["product_id"],
            "name": product["name"], "image": product["image"], "price": product["price"],
            "raw_variants": product.get("raw_variants") or [], "category": product.get("category", ""),
            "brand": product.get("brand", ""), "colors": product.get("colors") or [], "in_stock": True,
        })
    return [row for row in rows if usable(row)]


def load_raw_rows(sqlite_path):
    """원본 sqlite 의 다이소(재고 있음)·다나와 행. 카테고리가 있는 것만. 같은 제목 재등록은 첫 것만 남긴다.

    컬리 행은 여기서 안 읽는다 — 64,027건에 카테고리가 없고 발견 키워드는 카테고리 대용이 못 된다(5.7절).
    같은 상품이 카테고리 API 수집본에서 정확한 카테고리로 들어온다.
    """
    connection = sqlite3.connect(sqlite_path)
    query = """
        SELECT product_id, source_code, name, primary_image_url, sale_price, category_path, colors_json, brand,
               availability
          FROM products
         WHERE source_code IN ('daiso', 'danawa')
           AND category_path IS NOT NULL AND trim(category_path) <> ''
           AND (source_code <> 'daiso' OR availability = 'in_stock')
         ORDER BY product_id
    """
    rows = []
    seen_titles = set()
    for (product_id, source, name, image, price, category_path, colors_json, brand,
            availability) in connection.execute(query):
        title = (name or "").strip()
        if source == "daiso":
            if title in seen_titles:
                continue
            seen_titles.add(title)
        colors = json.loads(colors_json) if colors_json and colors_json not in ("[]", "null") else []
        rows.append({
            "source": source, "product_id": product_id, "name": title, "image": image, "price": price,
            "raw_variants": [], "category": category_path or "", "brand": (brand or "").strip(),
            "colors": [color for color in colors if str(color).strip()],
            "in_stock": availability != "out_of_stock",
        })
    connection.close()
    return [row for row in rows if usable(row)]


def to_pool_product(row, max_variants):
    return {
        "externalId": row["product_id"],
        "title": row["name"].strip(),
        "category": row["category"],
        "brand": row["brand"],
        "imageUrl": row["image"],
        "basePrice": int(row["price"]),
        "currency": "KRW",
        "status": "ACTIVE",
        "variants": pool_variants(row, max_variants),
    }


def shuffle_key(product_id):
    return hashlib.sha256(product_id.encode("utf-8")).hexdigest()


def main():
    parser = argparse.ArgumentParser(description="카탈로그 풀 JSON(gz) 생성")
    parser.add_argument("--clean-sqlite", required=True, help="korean_products_clean.sqlite 경로")
    parser.add_argument("--raw-sqlite", help="korean_products.sqlite(원본 89,632건) 경로. 다이소·다나와 행을 읽는다")
    parser.add_argument("--kurly-catalog", help="collect_kurly_catalog.py 산출물")
    parser.add_argument("--grocery", help="collect_kurly_grocery.py 산출물(471)")
    parser.add_argument("--out", required=True, help="출력 경로. .json.gz 면 gzip, .json 이면 평문")
    parser.add_argument("--max-variants", type=int, default=10, help="상품당 옵션 상한 (기본 10)")
    args = parser.parse_args()

    base = load_clean_rows(args.clean_sqlite)
    known = {row["product_id"] for row in base}
    if args.grocery:
        grocery = [row for row in load_extra(args.grocery) if row["product_id"] not in known]
        base.extend(grocery)
        known.update(row["product_id"] for row in grocery)
    base.sort(key=lambda row: row["product_id"])

    rest = []
    if args.raw_sqlite:
        rest.extend(row for row in load_raw_rows(args.raw_sqlite) if row["product_id"] not in known)
        known.update(row["product_id"] for row in rest)
    if args.kurly_catalog:
        kurly = [row for row in load_extra(args.kurly_catalog) if row["product_id"] not in known]
        rest.extend(kurly)
        known.update(row["product_id"] for row in kurly)
    rest.sort(key=lambda row: shuffle_key(row["product_id"]))

    rows = base + rest
    products = [to_pool_product(row, args.max_variants) for row in rows]
    by_source = {}
    for row in rows:
        by_source[row["source"]] = by_source.get(row["source"], 0) + 1
    document = {
        "source": "korean_products_clean.sqlite(strict 1,408) + 식재료 수집본(471) + korean_products.sqlite 의 "
                  "다이소·다나와 + 컬리 카테고리 API 수집본. 앞 baseCount 건은 시연 필수, 뒤는 sha256(externalId) 순",
        "baseCount": len(base),
        "bySource": dict(sorted(by_source.items())),
        "count": len(products),
        "products": products,
    }
    payload = json.dumps(document, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    if out.suffix == ".gz":
        with out.open("wb") as handle:
            # mtime=0, 파일명 없음 — 같은 입력이면 gzip 바이트까지 같아야 sha256 으로 결정성을 확인할 수 있다.
            with gzip.GzipFile(filename="", mode="wb", fileobj=handle, mtime=0) as archive:
                archive.write(payload)
    else:
        out.write_bytes(payload)

    skus = sum(len(product["variants"]) for product in products)
    multi = sum(1 for product in products if len(product["variants"]) >= 2)
    leaves = len({product["category"] for product in products})
    print(f"풀 {len(products)}건 (기존 시드 {len(base)} + 추가 {len(rest)}), 출처 {document['bySource']}")
    print(f"SKU {skus}, 옵션 둘 이상 {multi}건({multi * 100 // max(len(products), 1)}%), 말단 카테고리 {leaves}개")
    print(f"원문 {len(payload) / 1048576:.1f}MB → {out} {out.stat().st_size / 1048576:.2f}MB  "
          f"sha256 {hashlib.sha256(out.read_bytes()).hexdigest()[:16]}…")


if __name__ == "__main__":
    main()
