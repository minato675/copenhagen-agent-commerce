#!/usr/bin/env python3
"""Local product-image scenarios for the isolated preview APK; no merchant assets."""
import argparse
import io
from http.server import ThreadingHTTPServer
from PIL import Image, ImageDraw
import fixture_server as fixture

IMAGE_URL = "http://127.0.0.1:8787/fixtures/product.png"
RELATED_RESULTS = 0


def product_png():
    # A deliberately portrait-shaped synthetic tote checks fit without cropping.
    image = Image.new("RGB", (240, 360), "#E7EBE8")
    draw = ImageDraw.Draw(image)
    draw.ellipse((34, 322, 211, 344), fill="#D0D7D2")
    draw.rounded_rectangle((78, 33, 166, 159), radius=39, outline="#283D52", width=13)
    draw.polygon([(45, 111), (194, 111), (210, 321), (29, 321)], fill="#304C66")
    draw.line([(49, 116), (34, 317), (204, 317), (190, 116)], fill="#597086", width=3)
    draw.rounded_rectangle((90, 201, 151, 232), radius=3, fill="#F4EFDF")
    draw.line((103, 210, 138, 210), fill="#304C66", width=2)
    draw.line((112, 219, 129, 219), fill="#304C66", width=2)
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


PNG = product_png()


class Handler(fixture.Handler):
    def handle_request(self):
        if self.path == "/fixtures/product.png":
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(PNG)))
            self.end_headers()
            self.wfile.write(PNG)
            return
        return super().handle_request()

    def route(self, method, path, query, body):
        result, status = super().route(method, path, query, body)
        if status != 200:
            return result, status
        if path == "/api/core/v1/cart":
            result = dict(result, items=[dict(item, imageUrl=IMAGE_URL) for item in result["items"]])
        elif path == "/api/core/v1/products/search":
            data = result["data"]
            product = data["products"][0] if data["products"] else None
            products = []
            if product:
                products = [dict(product, imageUrl=IMAGE_URL),
                    dict(product, productId="portrait-two", name="롱 스트랩 코튼 토트백", imageUrl=IMAGE_URL),
                    dict(product, productId="missing-image", name="이미지 없는 상품", imageUrl=None),
                    dict(product, productId="failed-image", name="이미지 연결 실패 상품", imageUrl="http://127.0.0.1:8787/fixtures/missing.png")]
                search = body.get("query", "")
                if search:
                    products = [p for p in products if search in p["name"]]
                merchant = body.get("merchantId", "")
                if merchant:
                    products = [p for p in products if any(o["merchantId"] == merchant for o in p["offers"])]
                if search and products and RELATED_RESULTS:
                    exact = products[0]
                    products += [dict(exact, productId=f"related-{index}", name=f"{exact['name']} · 연관 상품 {index}")
                        for index in range(1, RELATED_RESULTS - len(products) + 1)]
            size, page = body.get("pageSize", 10), body.get("page", 1)
            visible = products[(page - 1) * size:page * size]
            result = dict(data=dict(data, status="OK" if visible else "EMPTY", products=visible,
                total=len(products), hasNext=page * size < len(products)))
        elif path.startswith("/api/core/v1/products/"):
            result = dict(data=dict(result["data"], product=dict(result["data"]["product"], imageUrl=IMAGE_URL)))
        # Keep Pay summary image-free to exercise the real catalog enrichment path.
        return result, status

    do_GET = do_POST = do_PUT = do_PATCH = do_DELETE = handle_request


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--related-results", type=int, choices=(0, 100), default=0,
        help="Return paginated related products for exact-title searches, as the merchant catalog does.")
    RELATED_RESULTS = parser.parse_args().related_results
    server = ThreadingHTTPServer(("127.0.0.1", 8787), Handler)
    print("Product image fixtures: http://127.0.0.1:8787 (preview APK only)", flush=True)
    server.serve_forever()
