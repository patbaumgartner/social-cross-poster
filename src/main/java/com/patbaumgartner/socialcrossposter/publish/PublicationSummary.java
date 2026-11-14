package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;

/**
 * One row of the workspace history list (UC-06 step 1): the publication with its
 * aggregate state, a content summary (post title + payload snippet), the approved
 * revision number and the individual destination states.
 */
public record PublicationSummary(Publication publication, String postTitle, int revisionNo, String contentSummary,
		List<Delivery> deliveries) {

}
