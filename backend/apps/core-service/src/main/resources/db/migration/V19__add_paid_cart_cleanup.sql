-- Preserve the source line identity even after a cart line is deleted/re-added.
ALTER TABLE checkout_snapshot_items ADD COLUMN cart_item_public_id UUID;
ALTER TABLE purchase_requests ADD COLUMN cart_cleaned_at TIMESTAMPTZ;
CREATE INDEX idx_purchase_cart_cleanup ON purchase_requests (id) WHERE cart_cleaned_at IS NULL;
