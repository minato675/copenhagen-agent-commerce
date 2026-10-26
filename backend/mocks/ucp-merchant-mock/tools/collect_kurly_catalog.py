#!/usr/bin/env python3
"""컬리 카테고리 API 로 상품 목록을 전량 받는다(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).

왜 카테고리로 받는가. 검색 API 응답에는 카테고리가 없어 471 은 "우리가 어느 검색어로 찾았나" 를
카테고리로 썼다. 검색어는 넓게 잡힌다 — "우유" 에 우유베개·우유거품기가 걸린다. 카테고리 API 는
"어느 카테고리 코드로 받았나" 가 곧 카테고리라 정확하다. 응답에 카테고리 필드가 없는 것은 같지만
질문이 다르다.

무엇을 하는가. 카테고리 트리(최상위 32 → 하위 259)를 받아 제외 최상위를 뺀 하위 코드를 코드순으로 돌며
페이지마다 96건씩 받는다. 한 상품이 여러 하위 카테고리에 나오면 먼저 만난 것을 쓴다. 결과는 시드 생성기가
먹는 모양(`collect_kurly_grocery.py` 와 같은 행 구조)으로 JSON 하나에 적는다.

    python tools/collect_kurly_catalog.py --out ~/Downloads/kurly-catalog-2026-09-21.json

**결과 파일은 리포에 넣지 않는다.** 9만 건 원본은 20MB 가 넘고 리포에 들어갈 것은 생성기가 만든 풀이다.
한 번 돌리고 Downloads 에 보관한다(DESIGN.md 24절). 재수집하면 컬리 진열이 바뀌어 결과가 다르다 —
결정성은 풀 파일부터다.

요청 사이 1초, 실패한 페이지는 한 번 다시 시도하고 그래도 안 되면 건너뛰며 stderr 에 적는다. 전체 약 1,100
요청, 20분 안팎.
"""

import argparse
import datetime as dt
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

BASE = "https://api.kurly.com/collection/v2/home/sites/market"
TREE_URL = f"{BASE}/category-groups"
LIST_URL = f"{BASE}/product-categories/{{code}}/products"
SOURCE = "kurly"
PER_PAGE = 96
SORT_TYPE = 4
USER_AGENT = "copenhagen-merchant-mock/1.0 (+catalog seed; 1 req/s)"

# mcp-server CatalogUiConfiguration 이 위젯 CSP 에 허용한 도메인. 벗어나는 상품은 카드에 그림이 안 뜬다.
ALLOWED_IMAGE_HOSTS = ("product-image.kurly.com", "3p-image.kurly.com", "img-cf.kurly.com")

# 시연 카탈로그에 넣지 않는 최상위 카테고리(DESIGN.md 5.7절).
#   251 전통주, 722 와인·위스키·데낄라 — 주류. 290 컬리상품권. 269 2026년 추석 — 기획전(다른 카테고리와 겹친다).
#   904 더퍼플셀렉션 — 멤버십 전용.
EXCLUDED_ROOTS = {"251", "722", "290", "269", "904"}

# 스키마 제약(V2). 넘는 상품은 버린다 — 잘라 넣으면 원문과 달라진다.
MAX_TITLE = 255
MAX_URL = 500

# 하위 카테고리 이름 앞의 이모지·장식 기호를 뗀다. "💝추천선물" 같은 것. 한글·영숫자·공백과 · / & , ( ) - + 만 남긴다.
NAME_CLEAN = re.compile(r"[^0-9A-Za-z가-힣\s·/&,()\-+.]")
BRAND_LABEL = re.compile(r"^\s*\[([^\]]{1,40})\]")


def get_json(url, timeout, retries=1):
    request = urllib.request.Request(url, headers={"Accept": "application/json", "User-Agent": USER_AGENT})
    for attempt in range(retries + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except (OSError, ValueError) as error:
            if attempt >= retries:
                raise
            print(f"  재시도 {url}: {error}", file=sys.stderr)
            time.sleep(3)
    return None


def clean_name(name):
    cleaned = NAME_CLEAN.sub("", name or "").strip()
    return re.sub(r"\s+", " ", cleaned)


def load_tree(timeout):
    """(하위 코드, 카테고리 경로) 목록. 코드 오름차순. 제외 최상위와 product_category 가 아닌 항목은 뺀다."""
    payload = get_json(TREE_URL, timeout)
    leaves = []
    for root in payload.get("data", {}).get("main", []):
        if root.get("kind") != "product_category" or root.get("code") in EXCLUDED_ROOTS:
            continue
        root_name = clean_name(root.get("name"))
        for child in root.get("sub_category_groups", []):
            if child.get("kind") != "product_category":
                continue
            child_name = clean_name(child.get("name"))
            if not child_name:
                continue
            leaves.append((child["code"], f"{root_name} > {child_name}"))
    return sorted(leaves)


def list_page(code, page, timeout):
    query = urllib.parse.urlencode({"sort_type": SORT_TYPE, "page": page, "per_page": PER_PAGE, "filters": ""})
    payload = get_json(LIST_URL.format(code=code) + "?" + query, timeout)
    items = payload.get("data") or []
    pagination = (payload.get("meta") or {}).get("pagination") or {}
    return items, int(pagination.get("total_pages") or 1), int(pagination.get("total") or len(items))


def usable(item):
    """살 수 있고, 값이 있고, 그림이 허용 도메인에 있고, 스키마 길이 안에 드는 것만 쓴다."""
    if item.get("is_sold_out") or item.get("is_only_adult") or item.get("is_sales") is False:
        return False
    if item.get("is_purchase_status") is False:
        return False
    price = item.get("discounted_price") or item.get("sales_price")
    if not price or price <= 0:
        return False
    name = (item.get("name") or "").strip()
    image = item.get("list_image_url") or ""
    if not name or len(name) > MAX_TITLE or len(image) > MAX_URL:
        return False
    return any(f"//{host}/" in image for host in ALLOWED_IMAGE_HOSTS)


def to_row(item, code, category):
    name = item["name"].strip()
    label = BRAND_LABEL.match(name)
    return {
        "product_id": f"{SOURCE}:{item['no']}",
        "source": SOURCE,
        "name": name,
        "image": item["list_image_url"],
        "price": int(item.get("discounted_price") or item["sales_price"]),
        "category": category,
        "category_code": code,
        # 제목 앞 대괄호 라벨을 브랜드로 쓴다(DESIGN.md 5.7절). 응답에 브랜드 필드가 없다.
        "brand": label.group(1).strip() if label else "",
        "colors": [],
        "raw_variants": [],
        # 상세를 받으면 진짜 옵션이 있는 상품. 후속 작업(상세 조회)의 대상 표시다.
        "has_options": bool(item.get("is_multiple_price")),
    }


def collect(delay, timeout, limit_pages):
    leaves = load_tree(timeout)
    print(f"하위 카테고리 {len(leaves)}개", flush=True)
    rows = {}
    per_category = []
    requests_made = 1
    for index, (code, category) in enumerate(leaves):
        taken = duplicates = skipped = 0
        page = 1
        total_pages = 1
        while page <= total_pages and (limit_pages == 0 or page <= limit_pages):
            time.sleep(delay)
            requests_made += 1
            try:
                items, total_pages, _ = list_page(code, page, timeout)
            except (OSError, ValueError) as error:
                print(f"  {code} p{page} 실패, 건너뜀: {error}", file=sys.stderr, flush=True)
                page += 1
                continue
            for item in items:
                if not usable(item):
                    skipped += 1
                    continue
                key = f"{SOURCE}:{item['no']}"
                if key in rows:
                    duplicates += 1
                    continue
                rows[key] = to_row(item, code, category)
                taken += 1
            page += 1
        per_category.append({"code": code, "category": category, "pages": total_pages,
                             "taken": taken, "duplicates": duplicates, "skipped": skipped})
        print(f"[{index + 1:3d}/{len(leaves)}] {code} {category:<28} 페이지 {total_pages:2d}  "
              f"신규 {taken:4d}  중복 {duplicates:4d}  제외 {skipped:3d}  누적 {len(rows)}", flush=True)
    return rows, per_category, requests_made


def main():
    parser = argparse.ArgumentParser(description="컬리 카테고리 API 전량 수집")
    parser.add_argument("--out", required=True, help="출력 JSON 경로 (리포 밖, Downloads)")
    parser.add_argument("--delay", type=float, default=1.0, help="요청 사이 초 (기본 1.0)")
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--limit-pages", type=int, default=0, help="카테고리당 최대 페이지. 0 이면 전부. 시험용")
    args = parser.parse_args()

    started = dt.datetime.now(dt.timezone.utc)
    rows, per_category, requests_made = collect(args.delay, args.timeout, args.limit_pages)
    finished = dt.datetime.now(dt.timezone.utc)

    document = {
        "source": SOURCE,
        "collected_at": started.isoformat(timespec="seconds"),
        "finished_at": finished.isoformat(timespec="seconds"),
        "endpoints": {"tree": TREE_URL, "list": LIST_URL},
        "excluded_roots": sorted(EXCLUDED_ROOTS),
        "per_page": PER_PAGE,
        "sort_type": SORT_TYPE,
        "requests": requests_made,
        "categories": per_category,
        "count": len(rows),
        "products": [rows[key] for key in sorted(rows)],
    }
    out = Path(args.out).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="") as handle:
        json.dump(document, handle, ensure_ascii=False, indent=1)
        handle.write("\n")
    minutes = (finished - started).total_seconds() / 60
    print(f"완료. 상품 {len(rows)}건, 카테고리 {len(per_category)}개, 요청 {requests_made}회, "
          f"{minutes:.1f}분 → {out}", flush=True)


if __name__ == "__main__":
    main()
