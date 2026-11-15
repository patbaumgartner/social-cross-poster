package com.patbaumgartner.socialcrossposter.publish;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.content.Post;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevision;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link HistoryService} (UC-06 first slice, ADR-004, ADR-013, ADR-015):
 * the list returns summaries newest-first with content summary, revision number,
 * aggregate state and per-destination states; filters by account, platform, state, text
 * (title or payload, case-insensitive) and inclusive date range work; pagination clamps
 * page/size; the detail returns the full graph with redacted attempts; unknown or foreign
 * publication ids fail closed. Mocked repositories, no Spring context.
 */
class HistoryServiceTest {

	private static final Long WS = 1L;

	private static final Long POST_ID = 20L;

	private static final Long REV_ID = 30L;

	private static final Long PUB_1 = 1L;

	private static final Long PUB_2 = 2L;

	private static final Long ACC = 7L;

	private static final Long DEL = 50L;

	private static final OffsetDateTime T1 = OffsetDateTime.of(2026, 11, 14, 10, 0, 0, 0, ZoneOffset.UTC);

	private static final OffsetDateTime T2 = OffsetDateTime.of(2026, 11, 15, 10, 0, 0, 0, ZoneOffset.UTC);

	private PublicationRepository publications;

	private DeliveryRepository deliveries;

	private DeliveryAttemptRepository attempts;

	private PostRepository posts;

	private PostRevisionRepository revisions;

	private HistoryService service;

	private Publication pub1;

	private Publication pub2;

	private Delivery delivery;

	@BeforeEach
	void setUp() {
		this.publications = mock(PublicationRepository.class);
		this.deliveries = mock(DeliveryRepository.class);
		this.attempts = mock(DeliveryAttemptRepository.class);
		this.posts = mock(PostRepository.class);
		this.revisions = mock(PostRevisionRepository.class);
		this.service = new HistoryService(this.publications, this.deliveries, this.attempts, this.posts,
				this.revisions);

		this.pub1 = new Publication(PUB_1, WS, POST_ID, REV_ID, "key-1", PublicationService.P_PUBLISHED, "h1", T1, T1);
		this.pub2 = new Publication(PUB_2, WS, POST_ID, REV_ID, "key-2", PublicationService.P_FAILED, "h2", T2, T2);
		this.delivery = new Delivery(DEL, WS, PUB_2, ACC, "telegram", "News channel", null, "-1001234567890",
				"hello world", "ph", PublicationService.D_SUCCESS, "telegram:message:99", T2, T2);
		given(this.publications.findAllByWorkspaceIdOrderByIdDesc(WS)).willReturn(List.of(this.pub2, this.pub1));
		given(this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(WS, PUB_2))
			.willReturn(List.of(this.delivery));
		given(this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(WS, PUB_1)).willReturn(List.of());
		given(this.posts.findByIdAndWorkspaceId(POST_ID, WS))
			.willReturn(Optional.of(new Post(POST_ID, WS, "Launch post", "DRAFT", REV_ID, 1L, T1, T2)));
		given(this.revisions.findByIdAndWorkspaceId(REV_ID, WS))
			.willReturn(Optional.of(new PostRevision(REV_ID, WS, POST_ID, 2, "hello world", T1)));
	}

	@Test
	void listReturnsSummariesNewestFirstWithContentAndStates() {
		HistoryPage page = this.service.list(WS, HistoryFilter.empty(), 0, 20);

		assertThat(page.totalItems()).isEqualTo(2);
		assertThat(page.totalPages()).isEqualTo(1);
		assertThat(page.items()).hasSize(2);
		PublicationSummary first = page.items().get(0);
		assertThat(first.publication().id()).isEqualTo(PUB_2);
		assertThat(first.postTitle()).isEqualTo("Launch post");
		assertThat(first.revisionNo()).isEqualTo(2);
		assertThat(first.contentSummary()).isEqualTo("hello world");
		assertThat(first.deliveries()).containsExactly(this.delivery);
		// pub1 has no deliveries: summary falls back to the revision default text.
		assertThat(page.items().get(1).contentSummary()).isEqualTo("hello world");
		assertThat(page.items().get(1).deliveries()).isEmpty();
	}

	@Test
	void contentSummaryTruncatesLongPayloads() {
		Delivery longDelivery = new Delivery(DEL, WS, PUB_2, ACC, "telegram", "News channel", null, "-1",
				"x".repeat(100), "ph", "SUCCESS", null, T2, T2);
		given(this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(WS, PUB_2))
			.willReturn(List.of(longDelivery));

		HistoryPage page = this.service.list(WS, HistoryFilter.empty(), 0, 20);

		String summary = page.items().get(0).contentSummary();
		assertThat(summary).hasSize(81);
		assertThat(summary).endsWith("…");
	}

	@Test
	void filterByAccountPlatformAndState() {
		assertThat(this.service.list(WS, new HistoryFilter(ACC, null, null, null, null, null), 0, 20).items())
			.hasSize(1);
		assertThat(this.service.list(WS, new HistoryFilter(999L, null, null, null, null, null), 0, 20).items())
			.isEmpty();
		assertThat(this.service.list(WS, new HistoryFilter(null, "TELEGRAM", null, null, null, null), 0, 20).items())
			.hasSize(1);
		assertThat(this.service.list(WS, new HistoryFilter(null, "mastodon", null, null, null, null), 0, 20).items())
			.isEmpty();
		assertThat(this.service.list(WS, new HistoryFilter(null, null, "PUBLISHED", null, null, null), 0, 20).items())
			.hasSize(1);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, "FAILED", null, null, null), 0, 20).items())
			.hasSize(1);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, "SKIPPED", null, null, null), 0, 20).items())
			.isEmpty();
	}

	@Test
	void filterByTextMatchesTitleOrPayloadCaseInsensitive() {
		assertThat(this.service.list(WS, new HistoryFilter(null, null, null, "HELLO", null, null), 0, 20).items())
			.hasSize(1);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, null, "launch", null, null), 0, 20).items())
			.hasSize(2);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, null, "zzz", null, null), 0, 20).items())
			.isEmpty();
	}

	@Test
	void filterByDateRangeIsInclusive() {
		OffsetDateTime from = OffsetDateTime.of(2026, 11, 15, 0, 0, 0, 0, ZoneOffset.UTC);
		OffsetDateTime to = OffsetDateTime.of(2026, 11, 15, 23, 59, 59, 0, ZoneOffset.UTC);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, null, null, from, to), 0, 20).items())
			.hasSize(1);
		OffsetDateTime future = OffsetDateTime.of(2026, 11, 16, 0, 0, 0, 0, ZoneOffset.UTC);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, null, null, future, null), 0, 20).items())
			.isEmpty();
		OffsetDateTime past = OffsetDateTime.of(2026, 11, 14, 9, 59, 59, 0, ZoneOffset.UTC);
		assertThat(this.service.list(WS, new HistoryFilter(null, null, null, null, null, past), 0, 20).items())
			.isEmpty();
	}

	@Test
	void paginationClampsSizeAndPageAndSplitsItems() {
		Publication pub3 = new Publication(3L, WS, POST_ID, REV_ID, "key-3", PublicationService.P_SKIPPED, "h3", T2,
				T2);
		given(this.publications.findAllByWorkspaceIdOrderByIdDesc(WS)).willReturn(List.of(pub3, this.pub2, this.pub1));
		given(this.deliveries.findAllByWorkspaceIdAndPublicationIdOrderByIdAsc(WS, 3L)).willReturn(List.of());

		HistoryPage page0 = this.service.list(WS, HistoryFilter.empty(), 0, 2);
		assertThat(page0.items()).hasSize(2);
		assertThat(page0.totalItems()).isEqualTo(3);
		assertThat(page0.totalPages()).isEqualTo(2);
		HistoryPage page1 = this.service.list(WS, HistoryFilter.empty(), 1, 2);
		assertThat(page1.items()).hasSize(1);
		assertThat(page1.items().get(0).publication().id()).isEqualTo(PUB_1);

		assertThat(this.service.list(WS, HistoryFilter.empty(), 0, 500).size()).isEqualTo(HistoryService.MAX_PAGE_SIZE);
		assertThat(this.service.list(WS, HistoryFilter.empty(), -5, 2).page()).isZero();
	}

	@Test
	void detailReturnsFullGraphWithAttempts() {
		given(this.publications.findByIdAndWorkspaceId(PUB_2, WS)).willReturn(Optional.of(this.pub2));
		DeliveryAttempt a1 = new DeliveryAttempt(60L, WS, DEL, 1, "send", "SUCCESS", "ok", "telegram:message:99", T2);
		DeliveryAttempt a2 = new DeliveryAttempt(61L, WS, DEL, 2, "reconcile", "UNKNOWN", "timeout", null, T2);
		given(this.attempts.findAllByDeliveryIdOrderByIdAsc(DEL)).willReturn(List.of(a1, a2));

		PublicationDetail detail = this.service.detail(WS, PUB_2);

		assertThat(detail.publication()).isEqualTo(this.pub2);
		assertThat(detail.post().title()).isEqualTo("Launch post");
		assertThat(detail.revision().revisionNo()).isEqualTo(2);
		assertThat(detail.deliveries()).hasSize(1);
		DeliveryDetail dd = detail.deliveries().get(0);
		assertThat(dd.delivery().payloadText()).isEqualTo("hello world");
		assertThat(dd.delivery().externalRef()).isEqualTo("telegram:message:99");
		assertThat(dd.attempts()).containsExactly(a1, a2);
	}

	@Test
	void detailWithUnknownOrForeignIdFailsClosed() {
		given(this.publications.findByIdAndWorkspaceId(999L, WS)).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.detail(WS, 999L)).isInstanceOf(PublicationException.class)
			.hasMessageContaining("Unknown publication");
	}

	@Test
	void platformsOfReturnsDistinctSorted() {
		given(this.deliveries.findAllByWorkspaceId(WS)).willReturn(List.of(this.delivery, new Delivery(51L, WS, PUB_1,
				ACC, "mastodon", "toot", null, "@a", "toot", "ph", "SUCCESS", null, T1, T1), this.delivery));

		assertThat(this.service.platformsOf(WS)).containsExactly("mastodon", "telegram");
	}

}
