import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("catalog", Path(__file__).with_name("import-qa-catalog.py"))
catalog = importlib.util.module_from_spec(spec)
spec.loader.exec_module(catalog)


class CatalogImportTest(unittest.TestCase):
    def test_prices_are_repeatable_integer_qa_values_and_unknown_is_preserved(self):
        self.assertIsNone(catalog.price_noise(None, "p1", "qa-a"))
        self.assertEqual(0, catalog.price_noise(0, "p1", "qa-a"))
        prices = [catalog.price_noise(10000, "p1", shop) for shop in ("qa-a", "qa-b", "qa-c")]
        self.assertGreater(len(set(prices)), 1)
        self.assertTrue(all(9300 <= p <= 10700 and isinstance(p, int) for p in prices))
        self.assertTrue(all(p % 100 == 0 for p in prices))
        self.assertEqual(prices[0], catalog.price_noise(10000, "p1", "qa-a"))
        self.assertEqual("UNKNOWN", catalog.availability("unspecified"))

    def test_import_is_read_only_and_does_not_promote_comparison_options(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "catalog.sqlite"
            connection = sqlite3.connect(source)
            connection.executescript("""
                CREATE TABLE products(product_id, name, brand, category_path, primary_image_url,
                  sale_price, availability, description);
                CREATE TABLE product_variants(variant_id, product_id, name, sale_price, availability, purchase_allowed);
                CREATE TABLE product_specs(product_id, name, value);
                CREATE TABLE comparison_configurations(product_id, label, price);
                INSERT INTO products VALUES('p1','<script>external text</script>','brand','group > child',
                  'javascript:alert(1)',10000,'unknown','description');
                INSERT INTO comparison_configurations VALUES('p1','Not a verified SKU',1234);
                INSERT INTO product_specs VALUES('p1','size','10cm'),('p1','size','10cm'),('p1','size','20cm');
            """)
            connection.commit()
            connection.close()
            before = source.read_bytes()
            result = catalog.convert(source)["products"][0]
            self.assertEqual(before, source.read_bytes())
            self.assertEqual([], result["variants"])
            self.assertEqual("group", result["category"])
            self.assertEqual("", result["imageUrl"])
            self.assertEqual("<script>external text</script>", result["name"])
            self.assertEqual([{"name": "size", "value": "10cm"}, {"name": "size", "value": "20cm"}], result["specs"])

    def test_local_shoe_options_are_stable_explicit_and_can_be_disabled(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.fixture_database(temporary)
            before = source.read_bytes()
            fixtures = catalog.load_option_fixtures(catalog.DEFAULT_OPTION_FIXTURES)
            first, other = catalog.convert(source, fixtures)["products"]
            self.assertEqual("LOCAL_FIXTURE", first["optionSource"])
            self.assertEqual(18, len(first["variants"]))
            self.assertEqual(18, len({variant["optionId"] for variant in first["variants"]}))
            self.assertEqual("라이트블루 · 250mm", first["variants"][2]["label"])
            self.assertTrue(first["optionsRequired"])
            self.assertTrue(first["optionsComplete"])
            self.assertEqual(["노르드 마켓", "하운 스토어", "벨루아 셀렉트"],
                             [offer["merchantName"] for offer in first["offers"]])
            for variant in first["variants"]:
                self.assertEqual({offer["merchantId"]: offer["price"] for offer in first["offers"]},
                                 variant["prices"])
            self.assertEqual([], other["variants"])
            self.assertEqual("NONE", other["optionSource"])
            self.assertEqual(first, catalog.convert(source, fixtures)["products"][0])
            self.assertEqual([], catalog.convert(source)["products"][0]["variants"])
            self.assertEqual(before, source.read_bytes())

    def test_verified_source_variants_take_priority_over_local_options(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.fixture_database(temporary)
            connection = sqlite3.connect(source)
            connection.execute("INSERT INTO product_variants VALUES(?,?,?,?,?,?)",
                               ("source-sku", "danawa:42339224", "actual-sku", 12000, "out_of_stock", 0))
            connection.commit()
            connection.close()
            fixtures = catalog.load_option_fixtures(catalog.DEFAULT_OPTION_FIXTURES)
            result = catalog.convert(source, fixtures)["products"][0]
            self.assertEqual("SOURCE_VARIANTS", result["optionSource"])
            self.assertEqual(1, len(result["variants"]))
            self.assertEqual("actual-sku", result["variants"][0]["label"])
            self.assertFalse(result["variants"][0]["purchaseAllowed"])
            self.assertEqual("OUT_OF_STOCK", result["variants"][0]["availability"])

    def test_fixture_loader_rejects_duplicate_or_unbounded_combinations(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "options.json"
            for fixture in [{"colors": ["blue", "blue"], "sizesMm": [240]},
                            {"colors": ["blue"], "sizesMm": [240, 240]},
                            {"colors": ["blue"], "sizesMm": list(range(200, 260))}]:
                source.write_text(json.dumps({"format": 1, "mode": "QA", "products": {"p": fixture}}),
                                  encoding="utf-8")
                with self.assertRaises(ValueError):
                    catalog.load_option_fixtures(source)

    def test_unknown_inventory_is_deterministic_and_source_only_preserves_unknown(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = self.fixture_database(temporary)
            before = source.read_bytes()
            first = catalog.convert(source, mock_inventory=True)
            self.assertEqual(first, catalog.convert(source, mock_inventory=True))
            for product in first["products"]:
                self.assertEqual("qa-stock-v1", product["inventoryFixture"])
                self.assertTrue(all(offer["availability"] in ["IN_STOCK", "OUT_OF_STOCK"]
                                    for offer in product["offers"]))
            self.assertTrue(all(offer["availability"] == "UNKNOWN"
                                for product in catalog.convert(source)["products"] for offer in product["offers"]))
            self.assertEqual({"IN_STOCK", "OUT_OF_STOCK"},
                             {catalog.fixture_availability(f"product-{i}:qa-a") for i in range(100)})
            self.assertEqual(before, source.read_bytes())

    def test_inventory_fixture_preserves_source_states_and_purchase_restrictions(self):
        product = {"productId": "source-stock", "offers": [
            {"merchantId": "qa-a", "availability": "OUT_OF_STOCK"},
            {"merchantId": "qa-b", "availability": "DISCONTINUED"},
            {"merchantId": "qa-c", "availability": "IN_STOCK"}],
            "variants": [{"optionId": "source-option", "availability": "IN_STOCK", "purchaseAllowed": False}]}
        original = json.loads(json.dumps(product))
        catalog.apply_inventory_fixture(product)
        self.assertEqual(original, product)

    def test_unknown_product_stock_follows_its_options(self):
        for states, expected in [(["OUT_OF_STOCK", "IN_STOCK"], "IN_STOCK"),
                                 (["OUT_OF_STOCK", "OUT_OF_STOCK"], "OUT_OF_STOCK"),
                                 (["DISCONTINUED"], "DISCONTINUED")]:
            product = {"productId": "variant-stock", "offers": [
                {"merchantId": "qa-a", "availability": "UNKNOWN"}],
                "variants": [{"optionId": f"option-{i}", "availability": state, "purchaseAllowed": False}
                             for i, state in enumerate(states)]}
            catalog.apply_inventory_fixture(product)
            self.assertEqual(expected, product["offers"][0]["availability"])
            self.assertEqual(states, [variant["availability"] for variant in product["variants"]])
            self.assertTrue(all(variant["purchaseAllowed"] is False for variant in product["variants"]))

    def test_unknown_option_stock_is_filled_without_overriding_purchase_restriction(self):
        product = {"productId": "variant-stock", "offers": [
            {"merchantId": "qa-a", "availability": "UNKNOWN"}],
            "variants": [{"optionId": "unknown-option", "availability": "UNKNOWN", "purchaseAllowed": False}]}
        catalog.apply_inventory_fixture(product)
        self.assertEqual(catalog.fixture_availability("variant-stock:unknown-option"),
                         product["variants"][0]["availability"])
        self.assertEqual(product["variants"][0]["availability"], product["offers"][0]["availability"])
        self.assertFalse(product["variants"][0]["purchaseAllowed"])

    @staticmethod
    def fixture_database(temporary):
        source = Path(temporary) / "catalog.sqlite"
        connection = sqlite3.connect(source)
        connection.executescript("""
            CREATE TABLE products(product_id, name, brand, category_path, primary_image_url,
              sale_price, availability, description);
            CREATE TABLE product_variants(variant_id, product_id, name, sale_price, availability, purchase_allowed);
            CREATE TABLE product_specs(product_id, name, value);
            INSERT INTO products VALUES('danawa:42339224','shoes','brand','footwear','',10000,'unknown','');
            INSERT INTO products VALUES('other','other shoes','brand','footwear','',10000,'unknown','');
        """)
        connection.commit()
        connection.close()
        return source


if __name__ == "__main__":
    unittest.main()
