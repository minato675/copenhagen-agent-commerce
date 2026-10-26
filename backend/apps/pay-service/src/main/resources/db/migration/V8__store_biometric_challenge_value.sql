ALTER TABLE biometric_challenges
    ADD COLUMN challenge_value VARCHAR(64);

COMMENT ON COLUMN biometric_challenges.challenge_value IS
    'Server-held one-time challenge value; never expose or log outside the challenge response.';
