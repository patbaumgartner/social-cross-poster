package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;

/**
 * Read-only workspace publication history (UC-06 first slice, ADR-004, ADR-013, ADR-015).
 * The list shows each publication with content summary, timestamps, aggregate state and
 * per-destination states, filterable by account, platform, aggregate state, text and date
 * range, with pagination. The detail shows the approved revision and the actual
 * per-destination payloads, provider identity, external references and redacted attempts.
 * Every lookup is workspace-scoped and fails closed for foreign ids (ADR-013) — a crafted
 * cross-workspace detail URL never returns content. Browsing is read-only;
 * retry/edit/delete are separate explicit actions (UC-07/UC-08). UNKNOWN deliveries are
 * shown with their attempts; reconciliation options land with UC-07.
 */
@Service
public class HistoryService {

	public static final int DEFAULT_PAGE_SIZE = 20;

	public static final int MAX_PAGE_SIZE = 100;

	private static final int SUMMARY_LENGTH = 80;

	private final PublicationRepository publications;

	private final DeliveryRepository deliveries;

	private final DeliveryAttemptRepository attempts;

	private final PostRepository posts;

	private final PostRevisionRepository revisions;

	public HistoryService(PublicationRepository publications, DeliveryRepository deliveries,
			DeliveryAttemptRepository attempts, PostRepository posts, PostRevisionRepository revisions) {
		this.publications = publications;
		this.deliveries = deliveries;
		this.attempts = attempts;
		this.posts = posts;
		this.revisions = revisions;
	}

	/**
	 * Lists this workspace's publications newest-first, filtered and paginated (UC-06
	 * steps 1-2). {@code page} is 0-based; {@code size} is clamped to 1..100.
	 */
	public HistoryPage list(Long workspaceId, HistoryFilter filter, int page, int size) {
		HistoryFilter f = filter == null ? HistoryFilter.empty() : filter;
		int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
		int pageIndex = Math.max(page, 0);
		List<PublicationSummary> all = this.publications.findAllByWorkspaceIdOrderByIdDesc(workspaceId)
			.stream()
			.map((p) -> summarize(workspaceId, p))
			.filter((s) -> matches(f, s))
			.toList();
		int totalItems = all.size();
		int totalPages = Math.max(1, (totalItems + pageSize - 1) / pageSize);
		int from = Math.min(pageIndex * pageSize, totalItems);
		int to = Math.min(from + pageSize, totalItems);
		return new HistoryPage(all.subList(from, to), pageIndex, pageSize, totalItems, totalPages);
	}

	/** Distinct delivery platforms present in this workspace's history, sorted. */
	public List<String> platformsOf(Long workspaceId) {
		return this.deliveries.findAllByWorkspaceId(workspaceId)
			.stream()
			.map(Delivery::platform)
			.distinct()
			.sorted()
			.toList();
	}

	/**
	 * Full detail of one publication, workspace-scoped (UC-06 step 3). Unknown or foreign
	 * publication ids fail closed (ADR-013).
	 */
	public PublicationDetail detail(Long workspaceId, Long publicationId) {
		Publication publication = this.publications.findByIdAndWorkspaceId(publicationId, workspaceId)
			.orElseThrow(() -> new PublicationException("Unknown publication for this workspace."));
		Post post = this.posts.findByIdAndWorkspaceId(publication.postId(), workspaceId).orElse(null);
		PostRevision revision = this.revisions.findByIdAndWorkspaceId(publication.revisionId(), workspaceId)
			.orElse(null);
		List<DeliveryDetail> ds = this.deliveries
			.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId, publicationId)
			.stream()
			.map((d) -> new DeliveryDetail(d, this.attempts.findAllByDeliveryIdOrderByIdAsc(d.id())))
			.toList();
		return new PublicationDetail(publication, post, revision, ds);
	}

	private PublicationSummary summarize(Long workspaceId, Publication publication) {
		List<Delivery> ds = this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(workspaceId,
				publication.id());
		Post post = this.posts.findByIdAndWorkspaceId(publication.postId(), workspaceId).orElse(null);
		String postTitle = post != null && StringUtils.hasText(post.title()) ? post.title()
				: "Post #" + publication.postId();
		PostRevision revision = this.revisions.findByIdAndWorkspaceId(publication.revisionId(), workspaceId)
			.orElse(null);
		int revisionNo = revision != null ? revision.revisionNo() : 0;
		String base = ds.stream()
			.map(Delivery::payloadText)
			.filter((t) -> t != null && !t.isBlank())
			.findFirst()
			.orElse(revision != null && revision.defaultText() != null ? revision.defaultText() : "");
		String contentSummary = base.length() > SUMMARY_LENGTH ? base.substring(0, SUMMARY_LENGTH) + "…" : base;
		return new PublicationSummary(publication, postTitle, revisionNo, contentSummary, ds);
	}

	private boolean matches(HistoryFilter f, PublicationSummary s) {
		if (f.accountId() != null && s.deliveries().stream().noneMatch((d) -> f.accountId().equals(d.accountId()))) {
			return false;
		}
		if (StringUtils.hasText(f.platform())
				&& s.deliveries().stream().noneMatch((d) -> f.platform().equalsIgnoreCase(d.platform()))) {
			return false;
		}
		if (StringUtils.hasText(f.state()) && !f.state().equalsIgnoreCase(s.publication().state())) {
			return false;
		}
		if (StringUtils.hasText(f.text())) {
			String needle = f.text().toLowerCase(Locale.ROOT);
			boolean inTitle = s.postTitle() != null && s.postTitle().toLowerCase(Locale.ROOT).contains(needle);
			boolean inPayload = s.deliveries()
				.stream()
				.anyMatch((d) -> d.payloadText() != null && d.payloadText().toLowerCase(Locale.ROOT).contains(needle));
			if (!inTitle && !inPayload) {
				return false;
			}
		}
		OffsetDateTime created = s.publication().createdAt();
		if (f.from() != null && created != null && created.isBefore(f.from())) {
			return false;
		}
		if (f.to() != null && created != null && created.isAfter(f.to())) {
			return false;
		}
		return true;
	}

}
