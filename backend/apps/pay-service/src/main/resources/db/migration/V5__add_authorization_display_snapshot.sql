-- Preserve the exact product summary shown to the user during approval.
-- This is a display projection, not the Merchant-signed Checkout artifact.

ALTER TABLE authorization_sessions
    ADD COLUMN display_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE authorization_sessions
    ALTER COLUMN display_snapshot DROP DEFAULT;

ALTER TABLE authorization_sessions
    ADD CONSTRAINT chk_authorization_sessions_display_snapshot
        CHECK (jsonb_typeof(display_snapshot) = 'object');
