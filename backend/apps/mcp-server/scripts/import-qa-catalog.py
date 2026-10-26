"""Read the supplied SQLite bundle without changing it; write ignored local QA data.

No UCP/network access. Synthetic shop prices are deterministic and are not live offers.
"""
import argparse
from contextlib import closing
import hashlib
import json
from pathlib import Path
import sqlite3
import tempfile
import zipfile
from urllib.parse import urlsplit


MERCHANTS = (("qa-a", "노르드 마켓"), ("qa-b", "하운 스토어"), ("qa-c", "벨루아 셀렉트"))
DEFAULT_OPTION_FIXTURES = Path(__file__).with_name("qa-product-options.json")
INVENTORY_FIXTURE = "qa-stock-v1"


def price_noise(price, product_id, shop):
    if price is None:
        return None
    basis = 9300 + int.from_bytes(hashlib.sha256(f"{product_id}:{shop}".encode()).digest()[:2], "big") % 1401
    return max(0, int(price) * basis // 10000 // 100 * 100)


def text(value, limit):
    return str(value or "").strip()[:limit]


def image(value):
    value = text(value, 2048)
    parsed = urlsplit(value)
    return value if parsed.scheme == "https" and parsed.hostname and not parsed.username else ""


def availability(value):
    return {"in_stock": "IN_STOCK", "out_of_stock": "OUT_OF_STOCK",
            "discontinued": "DISCONTINUED"}.get(value, "UNKNOWN")


def load_option_fixtures(path):
    """Explicit, replaceable local selections; never infer seller SKUs from comparison links."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    if payload.get("format") != 1 or payload.get("mode") != "QA":
        raise ValueError("Expected QA option fixtures format 1")
    fixtures = payload.get("products", {})
    for fixture in fixtures.values():
        colors, sizes = fixture.get("colors", []), fixture.get("sizesMm", [])
        if not colors or not sizes or len(colors) * len(sizes) > 50:
            raise ValueError("QA option fixtures must contain 1 to 50 combinations")
        if any(not isinstance(color, str) or not color.strip() or len(color) > 80 for color in colors):
            raise ValueError("Invalid QA fixture color")
        if any(type(size) is not int or size < 100 or size > 400 for size in sizes):
            raise ValueError("Invalid QA fixture shoe size")
        if len(set(colors)) != len(colors) or len(set(sizes)) != len(sizes):
            raise ValueError("Duplicate QA option fixture")
    return fixtures


def mock_options(product_id, price, fixture):
    return [{"optionId": "qa-fixture-" + hashlib.sha256(
                f"{product_id}:{color}:{size}".encode()).hexdigest()[:16],
             "label": f"{color} · {size}mm",
             "prices": {shop: price_noise(price, product_id, shop) for shop, _ in MERCHANTS},
             "purchaseAllowed": True, "availability": "IN_STOCK"}
            for color in fixture["colors"] for size in fixture["sizesMm"]]


def fixture_availability(identifier):
    value = hashlib.sha256(f"{INVENTORY_FIXTURE}:{identifier}".encode()).digest()[0]
    return "OUT_OF_STOCK" if value % 10 == 0 else "IN_STOCK"


def apply_inventory_fixture(product):
    """Fill only unknown source states in local data, preserving known source stock and purchase restrictions."""
    changed = False
    for variant in product["variants"]:
        if variant["availability"] == "UNKNOWN":
            variant["availability"] = fixture_availability(f"{product['productId']}:{variant['optionId']}")
            changed = True
    for offer in product["offers"]:
        if offer["availability"] != "UNKNOWN":
            continue
        states = [variant["availability"] for variant in product["variants"]]
        if states:
            offer["availability"] = ("IN_STOCK" if "IN_STOCK" in states else
                                     "DISCONTINUED" if all(state == "DISCONTINUED" for state in states) else
                                     "OUT_OF_STOCK")
        else:
            offer["availability"] = fixture_availability(f"{product['productId']}:{offer['merchantId']}")
        changed = True
    if changed:
        product["inventoryFixture"] = INVENTORY_FIXTURE


def convert(database, option_fixtures=None, mock_inventory=False):
    option_fixtures = option_fixtures or {}
    with closing(sqlite3.connect(database.resolve().as_uri() + "?mode=ro", uri=True)) as connection:
        connection.row_factory = sqlite3.Row
        rows = connection.execute("SELECT * FROM products ORDER BY product_id").fetchall()
        products = []
        for row in rows:
            product_id = row["product_id"]
            variants = connection.execute(
                "SELECT * FROM product_variants WHERE product_id = ? ORDER BY variant_id LIMIT 50",
                (product_id,)).fetchall()
            specs = connection.execute(
                "SELECT name, value FROM product_specs WHERE product_id = ? LIMIT 30", (product_id,)).fetchall()
            products.append({
                "productId": product_id, "name": text(row["name"], 240),
                "brand": text(row["brand"], 120),
                "category": text((row["category_path"] or "기타").split(" > ")[0], 160),
                "imageUrl": image(row["primary_image_url"]),
                "offers": [{"merchantId": shop, "merchantName": name,
                            "price": price_noise(row["sale_price"], product_id, shop), "currency": "KRW",
                            "availability": availability(row["availability"])}
                           for shop, name in MERCHANTS],
                "description": text(row["description"], 4000),
                "specs": [{"name": name, "value": value} for name, value in dict.fromkeys(
                    (text(spec["name"], 80), text(spec["value"], 400)) for spec in specs)],
                # Only source variants; comparison_configurations are NOT verified seller options.
                "variants": [{"optionId": "qa-option-" + hashlib.sha256(str(v["variant_id"]).encode()).hexdigest()[:16],
                              "label": text(v["name"], 240),
                              "prices": {shop: price_noise(v["sale_price"], product_id, shop)
                                         for shop, _ in MERCHANTS},
                              "purchaseAllowed": None if v["purchase_allowed"] is None else bool(v["purchase_allowed"]),
                              "availability": availability(v["availability"])} for v in variants],
                # Local fixture selection rule only; the real required-option rule must come from Core.
                "optionsRequired": True if len(variants) > 1 else None,
                "optionsComplete": connection.execute(
                    "SELECT count(*) FROM product_variants WHERE product_id = ?", (product_id,)).fetchone()[0] <= 50,
                "optionSource": "SOURCE_VARIANTS" if variants else "NONE",
            })
            if not variants and product_id in option_fixtures:
                product = products[-1]
                product["variants"] = mock_options(product_id, row["sale_price"], option_fixtures[product_id])
                product["optionsRequired"] = True
                product["optionSource"] = "LOCAL_FIXTURE"
            if mock_inventory:
                apply_inventory_fixture(products[-1])
        return {"format": 1, "mode": "QA", "products": products}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Provided SQLite file or clean bundle ZIP")
    parser.add_argument("--option-fixtures", type=Path, default=DEFAULT_OPTION_FIXTURES,
                        help="Replaceable local QA options; source variants always take precedence")
    parser.add_argument("--source-options-only", action="store_true", help="Disable local option fixtures")
    parser.add_argument("--source-stock-only", action="store_true", help="Preserve unknown source inventory states")
    args = parser.parse_args()
    fixtures = {} if args.source_options_only else load_option_fixtures(args.option_fixtures)
    output = Path(__file__).resolve().parent.parent / "build" / "qa2" / "catalog.json"
    with tempfile.TemporaryDirectory(prefix="copenhagen-catalog-") as temporary:
        source = args.source
        if source.suffix.lower() == ".zip":
            with zipfile.ZipFile(source) as archive:
                matches = [item for item in archive.infolist() if item.filename.endswith("korean_products_clean.sqlite")]
                if len(matches) != 1 or matches[0].file_size > 50_000_000:
                    raise ValueError("Expected one bounded clean catalog database")
                source = Path(temporary) / "catalog.sqlite"
                source.write_bytes(archive.read(matches[0]))
        payload = convert(source, fixtures, mock_inventory=not args.source_stock_only)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    hosts = sorted({urlsplit(p["imageUrl"]).hostname for p in payload["products"] if p["imageUrl"]})
    print(json.dumps({"products": len(payload["products"]), "imageHosts": hosts, "output": str(output)}))


if __name__ == "__main__":
    main()
