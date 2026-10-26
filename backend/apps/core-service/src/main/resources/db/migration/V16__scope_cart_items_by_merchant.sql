ALTER TABLE cart_items DROP CONSTRAINT uq_cart_items_cart_product_variant;
ALTER TABLE cart_items ADD CONSTRAINT uq_cart_items_cart_product_variant
    UNIQUE (cart_id, merchant_id, merchant_product_id, merchant_variant_id);
