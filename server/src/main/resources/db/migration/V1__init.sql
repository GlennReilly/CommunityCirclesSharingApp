-- Initial schema (spec 4.2, with the changes recorded in ADR 0011 and ADR 0014).
CREATE TABLE schema_version (version INTEGER NOT NULL);

CREATE TABLE circles (
  id TEXT PRIMARY KEY,
  created_day INTEGER NOT NULL
);

CREATE TABLE devices (
  device_id TEXT PRIMARY KEY,
  circle_id TEXT NOT NULL,
  member_id TEXT NOT NULL,
  sign_pub BLOB NOT NULL,
  box_pub BLOB NOT NULL,
  added_seq INTEGER NOT NULL,
  revoked_seq INTEGER
);
CREATE INDEX devices_by_member ON devices (circle_id, member_id);

CREATE TABLE events (
  server_seq INTEGER NOT NULL,
  circle_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  v INTEGER NOT NULL,
  author TEXT NOT NULL,
  device TEXT NOT NULL,
  type TEXT NOT NULL,
  day INTEGER NOT NULL,
  ctr INTEGER NOT NULL,
  epoch INTEGER NOT NULL,
  public BLOB NOT NULL,
  nonce BLOB NOT NULL,
  encrypted BLOB NOT NULL,
  sig BLOB NOT NULL,
  received_at INTEGER NOT NULL,
  PRIMARY KEY (circle_id, server_seq),
  UNIQUE (circle_id, event_id)
);
CREATE INDEX events_by_device_ctr ON events (device, ctr);

CREATE TABLE stewards (
  circle_id TEXT NOT NULL,
  member_id TEXT NOT NULL,
  added_seq INTEGER NOT NULL,
  removed_seq INTEGER,
  PRIMARY KEY (circle_id, member_id)
);

CREATE TABLE invites (
  circle_id TEXT NOT NULL,
  event_id TEXT NOT NULL,
  author TEXT NOT NULL,
  invite_pub BLOB NOT NULL,
  expires_day INTEGER NOT NULL,
  max_uses INTEGER NOT NULL,
  used INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (circle_id, event_id)
);

CREATE TABLE blobs (
  hash TEXT PRIMARY KEY,
  circle_id TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE sessions (
  token_hash BLOB PRIMARY KEY,
  device_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);

CREATE TABLE challenges (
  nonce BLOB PRIMARY KEY,
  device_id TEXT NOT NULL,
  expires_at INTEGER NOT NULL
);
