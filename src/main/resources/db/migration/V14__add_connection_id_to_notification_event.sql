-- V14: connection/bridge-failure notification events (UC-12 fourth slice, ADR-004,
-- ADR-013, ADR-015). Nullable FK so the dashboard can deep-link connection health
-- events to the accounts page (UC-12 step 3, authenticated detail link). The category
-- column gains CONNECTION_FAILED|CONNECTION_RECOVERED (no CHECK constraint; categories
-- stay code-enforced). Dialect kept compatible with H2 in PostgreSQL mode (tests).
ALTER TABLE notification_event ADD COLUMN connection_id BIGINT NULL;
ALTER TABLE notification_event ADD CONSTRAINT notification_event_connection_fk FOREIGN KEY (connection_id) REFERENCES connection (id);
