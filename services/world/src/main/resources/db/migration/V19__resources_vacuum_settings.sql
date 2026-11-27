-- Areas, games and entities are rewritten every turn. Room on each page lets
-- the new row version stay there (a HOT update, no new index entries), and a
-- vacuum after 2% dead rows, not the default 20%, keeps the visibility map
-- current, so index-only scans stay index-only. fillfactor applies to pages
-- written from now on.
ALTER TABLE resources SET (
    fillfactor = 85,
    autovacuum_vacuum_scale_factor = 0.02,
    autovacuum_analyze_scale_factor = 0.02
);
