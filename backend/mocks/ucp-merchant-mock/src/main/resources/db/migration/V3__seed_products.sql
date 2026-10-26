-- 시연·테스트용 상품 시드
-- 근거: DESIGN.md 5.4절
--
-- 통화는 KRW 고정, 금액은 minor unit 정수다. 89000 은 89,000원이다.
-- MVP 는 재고를 차감하지 않는다. 품절 시연은 시나리오 API 로 stock 을 0 으로 주입한다.
-- 마지막 상품 하나는 DISCONTINUED, 옵션 하나는 stock 0 으로 두어
-- OUT_OF_STOCK 과 상품 없음 경로를 시드만으로 확인할 수 있게 했다.

INSERT INTO products (id, external_id, title, image_url, base_price, currency, status, variants_json) VALUES
('11111111-1111-4111-8111-111111111111', 'product-101', '러닝화 A',
 'https://cdn.example.test/product-101.jpg', 89000, 'KRW', 'ACTIVE',
 '[
    {"sku": "product-101-260", "optionName": "size", "optionValue": "260", "priceDelta": 0, "stock": 12, "status": "ACTIVE"},
    {"sku": "product-101-270", "optionName": "size", "optionValue": "270", "priceDelta": 0, "stock": 7,  "status": "ACTIVE"},
    {"sku": "product-101-280", "optionName": "size", "optionValue": "280", "priceDelta": 0, "stock": 0,  "status": "ACTIVE"}
  ]'::jsonb),

('22222222-2222-4222-8222-222222222222', 'product-102', '경량 바람막이',
 'https://cdn.example.test/product-102.jpg', 129000, 'KRW', 'ACTIVE',
 '[
    {"sku": "product-102-m-black",  "optionName": "size", "optionValue": "M", "priceDelta": 0,    "stock": 20, "status": "ACTIVE"},
    {"sku": "product-102-l-black",  "optionName": "size", "optionValue": "L", "priceDelta": 0,    "stock": 15, "status": "ACTIVE"},
    {"sku": "product-102-xl-black", "optionName": "size", "optionValue": "XL","priceDelta": 5000, "stock": 4,  "status": "ACTIVE"}
  ]'::jsonb),

('33333333-3333-4333-8333-333333333333', 'product-103', '무선 이어폰',
 'https://cdn.example.test/product-103.jpg', 219000, 'KRW', 'ACTIVE',
 '[
    {"sku": "product-103-white", "optionName": "color", "optionValue": "화이트", "priceDelta": 0, "stock": 30, "status": "ACTIVE"},
    {"sku": "product-103-black", "optionName": "color", "optionValue": "블랙",   "priceDelta": 0, "stock": 25, "status": "ACTIVE"}
  ]'::jsonb),

('44444444-4444-4444-8444-444444444444', 'product-104', '보온 텀블러 500ml',
 'https://cdn.example.test/product-104.jpg', 27000, 'KRW', 'ACTIVE',
 '[
    {"sku": "product-104-single", "optionName": "type", "optionValue": "기본", "priceDelta": 0, "stock": 100, "status": "ACTIVE"}
  ]'::jsonb),

('55555555-5555-4555-8555-555555555555', 'product-105', '단종된 백팩',
 'https://cdn.example.test/product-105.jpg', 74000, 'KRW', 'DISCONTINUED',
 '[
    {"sku": "product-105-navy", "optionName": "color", "optionValue": "네이비", "priceDelta": 0, "stock": 0, "status": "DISCONTINUED"}
  ]'::jsonb);
