/**
 * Secret-free workspace content export (UC-15 step 5, ADR-007, ADR-013): an in-memory ZIP
 * with one JSON file per whitelisted workspace-scoped table (secret-looking columns
 * redacted), the private media files and a manifest.json with per-entry SHA-256
 * checksums, an aggregate checksum and the Flyway schema version. The export excludes
 * credentials by construction and is not a recovery backup (docs/operations/README.md).
 */
package com.patbaumgartner.socialcrossposter.export;
