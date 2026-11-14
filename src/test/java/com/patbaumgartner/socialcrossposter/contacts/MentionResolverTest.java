package com.patbaumgartner.socialcrossposter.contacts;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.platform.MentionRegistry;
import com.patbaumgartner.socialcrossposter.platform.MentionStatus;
import com.patbaumgartner.socialcrossposter.platform.TelegramMentionContract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link MentionResolver} (ADR-003 points 5-7): token parsing, native vs.
 * fallback resolution, ambiguity, archived contacts and unknown tokens — with mocked
 * repositories, no Spring context.
 */
class MentionResolverTest {

	private static final Long WORKSPACE = 1L;

	private static final String TOKEN = MentionToken.format(UUID.randomUUID());

	private ContactRepository contacts;

	private ContactHandleRepository handles;

	private MentionResolver resolver;

	@BeforeEach
	void setUp() {
		this.contacts = mock(ContactRepository.class);
		this.handles = mock(ContactHandleRepository.class);
		this.resolver = new MentionResolver(this.contacts, this.handles,
				new MentionRegistry(List.of(new TelegramMentionContract())));
	}

	@Test
	void findsAllTokensInText() {
		String other = MentionToken.format(UUID.randomUUID());
		List<MentionToken.TokenOccurrence> found = MentionToken.find("hi " + TOKEN + " and " + other + "!");
		assertThat(found).extracting(MentionToken.TokenOccurrence::raw).containsExactly(TOKEN, other);
	}

	@Test
	void telegramUsernameHandleResolvesNatively() {
		givenContact(false);
		givenHandle("telegram", "@alice_example", "", "handle", "@alice_example", false);

		var matches = this.resolver.resolve(WORKSPACE, "thanks " + TOKEN, "telegram");

		assertThat(matches).hasSize(1);
		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.NATIVE);
		assertThat(matches.get(0).renderedText()).isEqualTo("@alice_example");
		assertThat(matches.get(0).displayName()).isEqualTo("Alice Example");
	}

	@Test
	void nonUsernameIdentityOnTelegramFallsBack() {
		givenContact(false);
		givenHandle("telegram", "alice@example.org", "", "url", "https://example.org/alice", false);

		var matches = this.resolver.resolve(WORKSPACE, TOKEN, "telegram");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.FALLBACK);
		assertThat(matches.get(0).renderedText()).isEqualTo("Alice Example");
	}

	@Test
	void platformWithoutVerifiedContractFallsBackWithoutInventingIdentity() {
		givenContact(false);
		givenHandle("bluesky", "alice.bsky.social", "", "did", "did:plc:abc123", false);

		var matches = this.resolver.resolve(WORKSPACE, TOKEN, "bluesky");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.FALLBACK);
		assertThat(matches.get(0).renderedText()).isEqualTo("Alice Example");
		assertThat(matches.get(0).note()).contains("No verified mention contract");
	}

	@Test
	void missingHandleForPlatformFallsBack() {
		givenContact(false);
		given(this.handles.findAllByContactId(10L)).willReturn(List.of());

		var matches = this.resolver.resolve(WORKSPACE, TOKEN, "telegram");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.FALLBACK);
		assertThat(matches.get(0).note()).contains("No handle for platform 'telegram'");
	}

	@Test
	void multipleHandlesForSamePlatformAreAmbiguous() {
		givenContact(false);
		givenHandle("telegram", "@alice_one", "", "handle", "@alice_one", false);
		givenHandle("telegram", "@alice_two", "", "handle", "@alice_two", false);

		var matches = this.resolver.resolve(WORKSPACE, TOKEN, "telegram");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.AMBIGUOUS);
		assertThat(matches.get(0).renderedText()).isEqualTo("Alice Example");
	}

	@Test
	void unknownTokenIsFlaggedAndKeptVisible() {
		String unknown = MentionToken.format(UUID.randomUUID());
		given(this.contacts.findByTokenAndWorkspaceId(unknown, WORKSPACE)).willReturn(Optional.empty());

		var matches = this.resolver.resolve(WORKSPACE, "hi " + unknown, "telegram");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.UNKNOWN_TOKEN);
		assertThat(matches.get(0).renderedText()).isEqualTo(unknown);
		assertThat(matches.get(0).contactId()).isNull();
	}

	@Test
	void archivedContactStillResolvesWithArchivedMarker() {
		givenContact(true);
		givenHandle("telegram", "@alice_example", "", "handle", "@alice_example", false);

		var matches = this.resolver.resolve(WORKSPACE, TOKEN, "telegram");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.NATIVE);
		assertThat(matches.get(0).archived()).isTrue();
		assertThat(matches.get(0).note()).contains("archived");
	}

	@Test
	void tokenFromAnotherWorkspaceIsUnknownHere() {
		given(this.contacts.findByTokenAndWorkspaceId(TOKEN, 99L)).willReturn(Optional.empty());

		var matches = this.resolver.resolve(99L, TOKEN, "telegram");

		assertThat(matches.get(0).status()).isEqualTo(MentionStatus.UNKNOWN_TOKEN);
	}

	private void givenContact(boolean archived) {
		Contact contact = new Contact(10L, WORKSPACE, TOKEN, "Alice Example", null, "secret notes", archived,
				OffsetDateTime.now(), OffsetDateTime.now());
		given(this.contacts.findByTokenAndWorkspaceId(TOKEN, WORKSPACE)).willReturn(Optional.of(contact));
	}

	private void givenHandle(String platform, String handle, String host, String identityKind, String identityValue,
			boolean verified) {
		ContactHandle h = new ContactHandle(20L, WORKSPACE, 10L, platform, handle, host, identityKind, identityValue,
				verified, OffsetDateTime.now(), OffsetDateTime.now());
		List<ContactHandle> current = this.handles.findAllByContactId(10L) == null ? List.of()
				: this.handles.findAllByContactId(10L);
		given(this.handles.findAllByContactId(10L)).willReturn(new java.util.ArrayList<>(List.of(h)));
		// append semantics for the ambiguity test
		if (!current.isEmpty()) {
			var combined = new java.util.ArrayList<>(current);
			combined.add(h);
			given(this.handles.findAllByContactId(10L)).willReturn(combined);
		}
	}

}
