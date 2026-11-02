-- Steward changes need a quorum of distinct stewards (ADR 0023).
-- The day a steward became one, for the cooling-off rule. Founders get a day far in the past.
ALTER TABLE stewards ADD COLUMN added_day INTEGER NOT NULL DEFAULT -1000000;

-- Pending steward.add / steward.remove events, one row per event, until the change takes effect.
CREATE TABLE steward_votes (
  circle_id TEXT NOT NULL,
  server_seq INTEGER NOT NULL,
  kind TEXT NOT NULL,
  target TEXT NOT NULL,
  voter TEXT NOT NULL,
  day INTEGER NOT NULL,
  PRIMARY KEY (circle_id, server_seq)
);
CREATE INDEX steward_votes_by_target ON steward_votes (circle_id, kind, target);
