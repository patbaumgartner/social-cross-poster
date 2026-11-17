-- V17: protected event facts/links (UC-15 fourth slice, main flow step 2, ADR-013/ADR-004).
-- `event_facts` is free text with the workspace's factual event anchors (name, dates,
-- venue); `event_links` is a csv of http(s) URLs (event page, tickets, ...). Both are
-- nullable; NULL/empty means unset. They are PROTECTED anchors: only an explicit owner
-- edit on /w/{slug}/settings changes them — AI assistance (UC-16) must anchor on them
-- and never silently rewrite them. No secrets here (ADR-007).
-- Dialect kept compatible with H2 in PostgreSQL mode (tests).
ALTER TABLE workspace ADD COLUMN event_facts VARCHAR(2000);
ALTER TABLE workspace ADD COLUMN event_links VARCHAR(2000);
