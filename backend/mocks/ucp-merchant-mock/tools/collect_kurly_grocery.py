"""시연용 식재료를 컬리 공개 검색으로 모은다(471, DESIGN.md 5.7절).

왜 따로 모으는가. 원본 풀 1,408건에 기본 식재료가 없다. 채소가 4건이고 마늘·두부·대파·파마산이
0건이라 "토마토 파스타 재료 찾아줘" 시나리오가 성립하지 않는다. 원본을 다시 거르는 길도 봤지만
전처리 전 89,632건짜리 원본 파일이 우리에게 없다.

무엇을 하는가. 키워드마다 검색해 상위 몇 건을 받아 시드 생성기가 먹는 모양으로 JSON 하나에 적는다.
카테고리는 응답에 없으므로 키워드에 붙여 둔 값을 쓴다 — 우리가 "마늘" 로 찾았으니 그것은 마늘이다.
원본 데이터처럼 운동화가 유아/완구로 분류되는 일이 없다.

한 번만 돌리고 결과 파일을 커밋한다. 시드를 다시 뽑을 때마다 컬리를 두드리지 않는다.

    python tools/collect_kurly_grocery.py --out tools/kurly-grocery.json

`tools/data/` 가 아니라 `tools/` 밑에 두는 이유는 루트 .gitignore 의 `data/` 패턴이 이름이 data 인
디렉터리를 전부 무시해서다. 결과 파일은 커밋해야 하므로 그 패턴을 피한다.

기존 시드에 이미 있는 상품은 빼므로 `--seed` 로 비교할 시드를 준다(기본값은 merchant-a).
"""

import argparse
import json
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

SEARCH_URL = "https://api.kurly.com/search/v4/sites/market/normal-search"
SOURCE = "kurly"

# mcp-server CatalogUiConfiguration 이 위젯 CSP 에 허용한 도메인. 이 밖의 주소는 카드에서 그림이
# 안 뜨고 남의 모듈을 고쳐야 한다. 벗어나는 상품은 버린다.
ALLOWED_IMAGE_HOSTS = ("product-image.kurly.com", "3p-image.kurly.com", "img-cf.kurly.com")

# 키워드와 그 결과에 붙일 카테고리. 카테고리 표기는 원본 sqlite 의 category_path 모양을 따른다.
# 검색이 카테고리를 매칭에 쓰므로(470) 여기 적는 낱말이 곧 검색 경로다.
KEYWORDS = [
    # 토마토 파스타 시나리오에 필요한 것들. 이 아홉이 빠지면 시연이 성립하지 않는다.
    ("스파게티면", "면/즉석식품/통조림 > 파스타 > 파스타면"),
    ("토마토소스", "면/즉석식품/통조림 > 파스타 > 파스타소스"),
    ("토마토홀", "면/즉석식품/통조림 > 통조림 > 토마토"),
    ("방울토마토", "채소 > 토마토"),
    ("양파", "채소 > 양파"),
    ("마늘", "채소 > 마늘"),
    ("올리브유", "양념/오일 > 식용유/오일 > 올리브오일"),
    ("파마산치즈", "유제품 > 치즈 > 파마산"),
    ("베이컨", "육류 > 가공육 > 베이컨"),
    # 채소·과일
    ("대파", "채소 > 대파"),
    ("감자", "채소 > 감자"),
    ("당근", "채소 > 당근"),
    ("애호박", "채소 > 애호박"),
    ("버섯", "채소 > 버섯"),
    ("오이", "채소 > 오이"),
    ("시금치", "채소 > 시금치"),
    ("상추", "채소 > 쌈채소"),
    ("바질", "채소 > 허브"),
    ("사과", "과일/견과 > 사과"),
    ("바나나", "과일/견과 > 바나나"),
    ("딸기", "과일/견과 > 딸기"),
    # 육류·수산·달걀
    ("삼겹살", "돼지고기 > 삼겹살"),
    ("닭가슴살", "닭/오리고기 > 닭가슴살"),
    ("등심", "한우/육우 > 등심"),
    ("새우", "수산 > 새우"),
    ("연어", "수산 > 연어"),
    ("계란", "달걀 > 계란"),
    # 유제품·두부
    ("우유", "유제품 > 우유 > 흰우유"),
    ("버터", "유제품 > 버터"),
    ("치즈", "유제품 > 치즈"),
    ("요거트", "유제품 > 요거트"),
    ("두부", "반찬 > 두부"),
    # 양념
    ("소금", "양념/오일 > 소금"),
    ("후추", "양념/오일 > 후추"),
    ("설탕", "양념/오일 > 설탕"),
    ("간장", "양념/오일 > 간장"),
    ("고추장", "양념/오일 > 고추장"),
    ("된장", "양념/오일 > 된장"),
    ("참기름", "양념/오일 > 식용유/오일 > 참기름"),
    ("식용유", "양념/오일 > 식용유/오일 > 식용유"),
    ("밀가루", "양념/오일 > 가루 > 밀가루"),
    ("케첩", "양념/오일 > 소스 > 케첩"),
    ("마요네즈", "양념/오일 > 소스 > 마요네즈"),
    # 주식
    ("쌀", "쌀/잡곡 > 쌀"),
    ("라면", "면/즉석식품/통조림 > 라면"),
    ("김치", "반찬 > 김치"),
]


def search(keyword, timeout):
    query = urllib.parse.urlencode({"keyword": keyword, "page": 1})
    request = urllib.request.Request(
        f"{SEARCH_URL}?{query}",
        headers={"Accept": "application/json", "User-Agent": "copenhagen-merchant-mock/1.0"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        payload = json.load(response)
    for section in payload.get("data", {}).get("listSections", []):
        if section.get("view", {}).get("sectionCode") == "PRODUCT_LIST":
            return section.get("data", {}).get("items", [])
    return []


def usable(item):
    """살 수 있고, 값이 있고, 그림이 허용 도메인에 있는 것만 쓴다."""
    if item.get("isSoldOut"):
        return False
    price = item.get("discountedPrice") or item.get("salesPrice")
    if not price or price <= 0:
        return False
    image = item.get("listImageUrl") or ""
    return any(f"//{host}/" in image for host in ALLOWED_IMAGE_HOSTS)


def to_row(item, category):
    price = item.get("discountedPrice") or item.get("salesPrice")
    return {
        "product_id": f"{SOURCE}:{item['no']}",
        "source": SOURCE,
        "name": item["name"],
        "image": item["listImageUrl"],
        "price": int(price),
        "category": category,
        # 컬리 검색 응답에 브랜드 필드가 없다. 제목 앞 대괄호를 브랜드로 치지 않는다 — 원본 전처리도
        # "상품명의 브랜드 라벨을 공식 브랜드로 간주하지 않았다" 는 규칙을 썼다.
        "brand": "",
        "colors": [],
        "raw_variants": [],
    }


def seeded_ids(seed_path):
    """이미 풀에 있는 상품. 풀은 gzip 일 수 있다(2026-09-21 부터 pool.json.gz)."""
    if not seed_path or not Path(seed_path).exists():
        return set()
    raw = Path(seed_path).read_bytes()
    if raw[:2] == b"\x1f\x8b":
        import gzip

        raw = gzip.decompress(raw)
    document = json.loads(raw.decode("utf-8"))
    return {product["externalId"] for product in document.get("products", [])}


def collect(per_keyword, delay, timeout, seed_path):
    known = seeded_ids(seed_path)
    rows = {}
    skipped_duplicate = 0
    for index, (keyword, category) in enumerate(KEYWORDS):
        if index:
            time.sleep(delay)
        try:
            items = search(keyword, timeout)
        except OSError as error:
            print(f"  {keyword}: 실패 {error}", file=sys.stderr)
            continue
        taken = 0
        for item in items:
            if taken >= per_keyword:
                break
            if not usable(item):
                continue
            row = to_row(item, category)
            if row["product_id"] in known:
                skipped_duplicate += 1
                continue
            if row["product_id"] in rows:
                continue
            rows[row["product_id"]] = row
            taken += 1
        print(f"  {keyword:<8} {taken}건")
    return rows, skipped_duplicate


def main():
    parser = argparse.ArgumentParser(description="시연용 식재료 수집(컬리 공개 검색)")
    parser.add_argument("--out", required=True, help="출력 JSON 경로")
    parser.add_argument("--seed", default="src/main/resources/catalog/pool.json.gz",
                        help="중복 제거에 쓸 기존 시드")
    parser.add_argument("--per-keyword", type=int, default=4, help="키워드당 상품 수 (기본 4)")
    parser.add_argument("--delay", type=float, default=1.0, help="요청 간 대기 초 (기본 1.0)")
    parser.add_argument("--timeout", type=float, default=20.0, help="요청 제한 시간 초")
    args = parser.parse_args()

    rows, duplicates = collect(args.per_keyword, args.delay, args.timeout, args.seed)
    ordered = [rows[key] for key in sorted(rows)]
    document = {
        "source": SOURCE,
        "keywords": len(KEYWORDS),
        "count": len(ordered),
        "products": ordered,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n",
                   encoding="utf-8", newline="")
    print(f"{out}  상품 {len(ordered)}건, 기존 시드와 겹쳐 뺀 것 {duplicates}건")


if __name__ == "__main__":
    main()
