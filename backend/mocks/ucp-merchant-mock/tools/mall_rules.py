"""몰별 편차 규칙의 파이썬 원본(DESIGN.md 5.7절 2026-09-21 개정, ADR 0011).

같은 규칙이 Java `catalog/MallVariation.java` 에도 있다. 로더가 기동 때 풀 하나로 몰 셋을 만들기 때문이다.
두 구현이 같은 답을 내는지는 `mall_variation_golden.py` 가 뽑은 황금값을 Java 테스트가 비교해 지킨다.
규칙을 바꾸려면 두 곳을 같이 바꾸고 황금값을 다시 뽑는다.

규칙은 ADR 0007·0010 그대로다. 난수를 쓰지 않는다 — 같은 입력이면 어디서 돌려도 같은 값이다.
"""

import hashlib
import uuid

PRICE_MIN_FACTOR = 0.90     # 가격 배율 범위. -10% ~ +10%
PRICE_SPAN = 0.20
SOLD_OUT_RATIO = 0.05       # 이 값보다 u 가 작으면 그 몰에서 그 상품은 품절
STOCK_OUT = 0


def u(*parts):
    """0 ≤ u < 1 인 결정적 실수. sha256 의 앞 8바이트를 부호 없는 정수로 읽어 2^64 로 나눈다."""
    digest = hashlib.sha256("|".join(parts).encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") / 2 ** 64


def deterministic_id(mall, external_id):
    """몰과 external_id 로 UUID 를 고정한다. 393 부터 id 는 몰마다 다르다(DESIGN.md 5.4절)."""
    return str(uuid.uuid5(uuid.NAMESPACE_URL, f"copenhagen:merchant-mock:product:{mall}:{external_id}"))


def rounding_unit(price):
    """1만원 미만은 10원, 이상은 100원 단위. 원본 기준가로 정한다."""
    return 10 if price < 10000 else 100


def round_to(amount, unit):
    return int(amount / unit + 0.5) * unit


def price_factor(external_id, mall):
    return PRICE_MIN_FACTOR + PRICE_SPAN * u(external_id, mall, "price")


def sold_out(external_id, mall):
    return u(external_id, mall, "stock") < SOLD_OUT_RATIO


def derive(mall, product):
    """풀의 상품 하나를 몰 하나의 행으로 바꾼다. Java `MallVariation.derive` 와 같은 답을 내야 한다.

    priceDelta 는 차액이다. 기본가와 옵션가를 각각 배율로 곱해 반올림한 뒤 차액을 구해야 base + delta 가
    반올림 단위에 떨어진다. 합이 0 이하로 떨어지면 최소 단위가 되게 차액을 올린다(옛 build_variants 규칙).
    """
    external_id = product["externalId"]
    base = product["basePrice"]
    factor = price_factor(external_id, mall)
    unit = rounding_unit(base)
    scaled_base = max(round_to(base * factor, unit), unit)
    out = sold_out(external_id, mall)
    variants = []
    for variant in product["variants"]:
        scaled_price = round_to((base + variant["priceDelta"]) * factor, unit)
        delta = scaled_price - scaled_base
        if scaled_base + delta <= 0:
            delta = unit - scaled_base
        variants.append({
            "sku": variant["sku"],
            "optionName": variant["optionName"],
            "optionValue": variant["optionValue"],
            "priceDelta": delta,
            "stock": STOCK_OUT if out else variant["stock"],
            "status": variant.get("status", "ACTIVE"),
        })
    return {
        "id": deterministic_id(mall, external_id),
        "basePrice": scaled_base,
        "variants": variants,
    }
