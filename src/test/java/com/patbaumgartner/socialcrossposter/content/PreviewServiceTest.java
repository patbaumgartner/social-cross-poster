package com.patbaumgartner.socialcrossposter.content;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.patbaumgartner.socialcrossposter.accounts.AccountDestination;
import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.contacts.MentionMatch;
import com.patbaumgartner.socialcrossposter.contacts.MentionResolver;
import com.patbaumgartner.socialcrossposter.platform.MentionStatus;
import com.patbaumgartner.socialcrossposter.platform.TelegramTextRules;
import com.patbaumgartner.socialcrossposter.platform.TextRulesRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link PreviewService} (UC-04, ADR-003 points 1-3): override precedence
 * destination → platform → default, NULL = inherit / "" = explicit clear blocker, the
 * platform counter with an over-limit blocker (never truncated), the conservative default
 * limit for unwired platforms, mention resolution against the effective text and
 * fail-closed foreign destinations — mocked repositories, no Spring context.
 */
class PreviewServiceTest {

	private static final Long WS = 1L;

	private static final Long ACCOUNT = 7L;

	private AccountRepository accounts;

	private MentionResolver mentions;

	private PreviewService service;

	private final AccountDestination telegram = new AccountDestination(ACCOUNT, WS, 3L, "telegram", "channel",
			"-100123", null, "News channel", true, false, null, null);

	@BeforeEach
	void setUp() {
		this.accounts = mock(AccountRepository.class);
		this.mentions = mock(MentionResolver.class);
		this.service = new PreviewService(this.accounts, new TextRulesRegistry(List.of(new TelegramTextRules())),
				this.mentions);
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.of(this.telegram));
		given(this.mentions.resolve(eq(WS), anyString(), eq("telegram"))).willReturn(List.of());
	}

	@Test
	void destinationOverrideWinsOverPlatformAndDefault() {
		var preview = this.service.preview(WS, revision("default"), ACCOUNT,
				List.of(override("platform:telegram", "platform text"), override("destination:7", "destination text")));
		assertThat(preview.effectiveText()).isEqualTo("destination text");
	}

	@Test
	void platformOverrideWinsOverDefaultWhenNoDestinationOverride() {
		var preview = this.service.preview(WS, revision("default"), ACCOUNT,
				List.of(override("platform:telegram", "platform text")));
		assertThat(preview.effectiveText()).isEqualTo("platform text");
	}

	@Test
	void nullOverrideTextMeansInherit() {
		var preview = this.service.preview(WS, revision("default"), ACCOUNT,
				List.of(override("destination:7", null), override("platform:telegram", "platform text")));
		assertThat(preview.effectiveText()).isEqualTo("platform text");
		var plain = this.service.preview(WS, revision("default"), ACCOUNT, List.of(override("destination:7", null)));
		assertThat(plain.effectiveText()).isEqualTo("default");
	}

	@Test
	void explicitEmptyDestinationOverrideIsBlocking() {
		var preview = this.service.preview(WS, revision("default"), ACCOUNT, List.of(override("destination:7", "")));
		assertThat(preview.effectiveText()).isEmpty();
		assertThat(preview.blockers()).anyMatch((b) -> b.contains("explicitly clears"));
	}

	@Test
	void overLimitIsABlockerAndTextIsNeverTruncated() {
		String big = "a".repeat(5000);
		var preview = this.service.preview(WS, revision(big), ACCOUNT, List.of());
		assertThat(preview.overLimit()).isTrue();
		assertThat(preview.textLength()).isEqualTo(5000);
		assertThat(preview.maxTextLength()).isEqualTo(4096);
		assertThat(preview.effectiveText()).hasSize(5000);
		assertThat(preview.blockers()).anyMatch((b) -> b.contains("never truncates"));
	}

	@Test
	void unwiredPlatformUsesConservativeDefaultLimit() {
		AccountDestination bluesky = new AccountDestination(ACCOUNT, WS, 3L, "bluesky", "profile", "alice.bsky.social",
				null, "Alice", true, false, null, null);
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.of(bluesky));
		given(this.mentions.resolve(eq(WS), anyString(), eq("bluesky"))).willReturn(List.of());
		var preview = this.service.preview(WS, revision("hello"), ACCOUNT, List.of());
		assertThat(preview.maxTextLength()).isEqualTo(TextRulesRegistry.DEFAULT_MAX_TEXT_LENGTH);
		assertThat(preview.textLength()).isEqualTo(5);
		assertThat(preview.overLimit()).isFalse();
	}

	@Test
	void mentionsAreResolvedAgainstTheEffectiveText() {
		MentionMatch match = new MentionMatch("{{contact:x}}", 9L, "Alice", false, MentionStatus.NATIVE, "@alice",
				"note");
		given(this.mentions.resolve(WS, "dest text", "telegram")).willReturn(List.of(match));
		var preview = this.service.preview(WS, revision("default"), ACCOUNT,
				List.of(override("destination:7", "dest text")));
		assertThat(preview.mentions()).containsExactly(match);
	}

	@Test
	void unknownDestinationFailsClosed() {
		given(this.accounts.findByIdAndWorkspaceId(ACCOUNT, WS)).willReturn(Optional.empty());
		assertThatThrownBy(() -> this.service.preview(WS, revision("x"), ACCOUNT, List.of()))
			.isInstanceOf(PostException.class)
			.hasMessageContaining("Unknown destination");
	}

	private PostRevision revision(String text) {
		return new PostRevision(1L, WS, 2L, 1, text, null);
	}

	private RevisionOverride override(String target, String text) {
		return new RevisionOverride(null, WS, 1L, target, text, null);
	}

}
