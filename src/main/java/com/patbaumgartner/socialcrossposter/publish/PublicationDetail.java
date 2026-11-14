package com.patbaumgartner.socialcrossposter.publish;

import java.util.List;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRevision;

/**
 * Full history detail of one publication (UC-06 step 3): the publication, its post and
 * approved revision, and every destination delivery with provider identity, actual
 * payload, external reference and redacted attempts. Browsing is read-only; retry/edit/
 * delete are separate explicit actions (UC-07/UC-08).
 */
public record PublicationDetail(Publication publication, Post post, PostRevision revision,
		List<DeliveryDetail> deliveries) {

}
