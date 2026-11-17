-- V16: workspace profile columns (UC-15 third slice, main flow step 2, ADR-013).
-- `languages` is a csv of language tags (e.g. 'de,en,fr-CH') used as the workspace
-- default for AI assistance (UC-16 later slices); `brand_voice` is a free-text brand
-- voice description for the same purpose. Both are nullable; NULL/empty means unset.
-- No secrets here: AI credentials/budget stay out of the workspace row (ADR-007).
-- Dialect kept compatible with H2 in PostgreSQL mode (tests).
ALTER TABLE workspace ADD COLUMN languages VARCHAR(255);
ALTER TABLE workspace ADD COLUMN brand_voice VARCHAR(2000);
