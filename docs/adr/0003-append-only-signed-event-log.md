# 0003. Append-only signed event log

- Status: Accepted
- Date: 2026-11-02

## Context

Local-first operation, handover to a new host, and audit all need a data model that merges simply and can be exported without server secrets.

## Decision

Every state change is an event: an envelope signed with the author's device key, identified by the hash of its canonical bytes. Current state (items, loans, standing, tiers) is derived by folding the log. Sync is "push my new events, pull events after cursor N". The server assigns a per-circle `serverSeq` on acceptance.

## Consequences

Merging replicas is set union and duplicates are harmless. The log is the backup and the export format. Folding must be deterministic; where order matters (config, key epochs) `serverSeq` then `id` break ties. `serverSeq` is unsigned, so a hostile server could reorder such events; the spec lists this as a known risk.
