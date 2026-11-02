-- The circle key epoch each circle has reached (ADR 0021). 0 means no key has been wrapped yet.
ALTER TABLE circles ADD COLUMN current_epoch INTEGER NOT NULL DEFAULT 0;
