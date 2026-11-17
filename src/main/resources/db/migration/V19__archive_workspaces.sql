-- V19: workspace archive state (UC-15 seventh slice, main flow step 6, ADR-013).
-- `workspace.archived` blocks new publication, scheduling and AI assistance in the
-- service guards; `archived_at` records when the owner archived the workspace.
-- `delivery.acknowledged_at` marks active/UNKNOWN deliveries whose unresolved effects
-- the owner acknowledged at archive time — history and private media are retained,
-- nothing is deleted. Reopening clears the flag and never auto-resumes canceled work.
-- Dialect kept compatible with H2 in PostgreSQL mode (tests).
ALTER TABLE workspace ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE workspace ADD COLUMN archived_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE delivery ADD COLUMN acknowledged_at TIMESTAMP WITH TIME ZONE;
