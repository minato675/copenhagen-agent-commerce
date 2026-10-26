-- 이슈 411: 주문 이력은 결제수단이 이후 변경·삭제되어도 완료 시점의 마스킹 표기를 유지한다.
ALTER TABLE payment_transactions
    ADD COLUMN payment_label VARCHAR(64);

-- created_at 동률에서도 seek pagination이 누락·중복 없이 진행되도록 내부 PK를 tie-breaker로 둔다.
CREATE INDEX idx_orders_user_id_created_at_id
    ON orders (user_id, created_at DESC, id DESC);
