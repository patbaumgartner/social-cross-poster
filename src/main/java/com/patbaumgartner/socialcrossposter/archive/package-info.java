/**
 * Workspace archive lifecycle (UC-15 seventh slice, main flow step 6, ADR-013): archiving
 * a workspace lists its pending schedules/jobs and active/UNKNOWN deliveries, cancels the
 * unclaimed work (PENDING schedules and jobs), acknowledges the unresolved delivery
 * effects ({@code delivery.acknowledged_at}), blocks new publication/scheduling/AI via
 * the {@code workspace.archived} guard, and retains all history and private media —
 * nothing is deleted. Reopening clears the flag and never auto-resumes canceled work.
 */
package com.patbaumgartner.socialcrossposter.archive;
