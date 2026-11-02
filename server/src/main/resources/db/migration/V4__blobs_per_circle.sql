-- Blobs belong to one circle (ADR 0026). The table was unused, so it is simply recreated with a per-circle key.
DROP TABLE blobs;
CREATE TABLE blobs (
  circle_id TEXT NOT NULL,
  hash TEXT NOT NULL,
  size INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (circle_id, hash)
);
