-- Test-only migration: the first statement is fine, the second is not, so the whole file must roll back.
CREATE TABLE t3 (id INTEGER PRIMARY KEY);
THIS IS NOT SQL;
