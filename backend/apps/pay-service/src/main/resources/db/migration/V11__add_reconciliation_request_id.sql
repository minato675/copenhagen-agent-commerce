-- Preserve the originating Pay request ID when an asynchronous reconciliation job is created.
-- Existing jobs remain valid and use a worker-generated request ID when claimed.
ALTER TABLE payment_reconciliation_jobs
    ADD COLUMN request_id VARCHAR(100);

ALTER TABLE payment_reconciliation_jobs
    ADD CONSTRAINT chk_reconciliation_jobs_request_id
        CHECK (request_id IS NULL OR length(btrim(request_id)) BETWEEN 1 AND 100);
