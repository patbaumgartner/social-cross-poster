package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;

/**
 * One destination delivery with its append-only attempts for the history detail view
 * (UC-06 step 3). The delivery row snapshots the destination identity and the actual
 * approved payload; attempt details stay redacted (ADR-007, ADR-015).
 */
public record DeliveryDetail(Delivery delivery, List<DeliveryAttempt> attempts) {

}
