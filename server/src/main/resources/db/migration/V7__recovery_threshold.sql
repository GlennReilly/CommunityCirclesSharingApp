-- Recovery shares (ADR 0048): how many stewards must come together to rebuild the circle key; 0 means no shares.
ALTER TABLE circles ADD COLUMN steward_recovery_threshold INTEGER NOT NULL DEFAULT 0;
