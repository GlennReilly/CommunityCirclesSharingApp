-- Steward governance settings per circle (ADR 0027): quorum, and the two inactivity-fallback times.
ALTER TABLE circles ADD COLUMN steward_quorum INTEGER NOT NULL DEFAULT 2;
ALTER TABLE circles ADD COLUMN steward_inactive_days INTEGER NOT NULL DEFAULT 60;
ALTER TABLE circles ADD COLUMN steward_fallback_delay_days INTEGER NOT NULL DEFAULT 14;
