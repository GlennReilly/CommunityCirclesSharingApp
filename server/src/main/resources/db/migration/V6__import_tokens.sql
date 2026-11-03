-- Operator-issued, single-use tokens that let a steward import a circle this host does not have yet (ADR 0034).
CREATE TABLE import_tokens (
  token_hash BLOB PRIMARY KEY,
  circle_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
