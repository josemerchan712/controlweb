-- Index for the read API (GET /api/incidents).
--
-- Already covered by V1, so nothing to add for them:
--   * checks of one site by time, newest first  -> idx_checks_site_checked_at (site_id, checked_at DESC)
--     (history, statistics and "latest check per site" all use it)
--   * incidents of one site, newest first       -> idx_incidents_site_opened_at (site_id, opened_at DESC)
--   * OPEN incidents (status=open, and the open incidents of the status board)
--       -> uq_incidents_one_open_per_site_type, the partial unique index (site_id, type) WHERE resolved_at IS NULL.
--     Its predicate matches "resolved_at IS NULL", so PostgreSQL reads just the few open rows from it and sorts
--     them in memory. EXPLAIN over 40,000 incidents showed a dedicated partial index on opened_at would only
--     lower an already small cost, so it is deliberately NOT created (see ReadIndexesIntegrationTest).
--
-- Missing until now: the listing sorts by opened_at DESC across ALL sites, which without this index means
-- reading the whole table and sorting it (Seq Scan + Sort) for every page.

-- Whole list, newest first, any status. Includes id because the API orders by (opened_at DESC, id DESC):
-- with both columns in the index the database reads the first page straight from the index, with no sort.
-- It also serves status=resolved (the few open rows are skipped).
CREATE INDEX idx_incidents_opened_at ON incidents (opened_at DESC, id DESC);
