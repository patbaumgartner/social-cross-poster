package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;

/**
 * One page of the filtered workspace history (UC-06 step 2). Pagination is in-memory —
 * the per-owner data volume is small and the optional filters make derived queries
 * impractical (see {@link HistoryService}). {@code page} is 0-based.
 */
public record HistoryPage(List<PublicationSummary> items, int page, int size, int totalItems, int totalPages) {

}
