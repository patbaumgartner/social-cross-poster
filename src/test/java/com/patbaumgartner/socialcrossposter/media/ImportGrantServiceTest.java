package com.patbaumgartner.socialcrossposter.media;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link ImportGrantService} (UC-13 fourth slice, ADR-008 steps 3/5,
 * ADR-007, ADR-013): a minted grant is a signed bearer URL bound to key id, workspace,
 * delivery, sha256, purpose and expiry — only the signature's SHA-256 hash is persisted,
 * never the URL itself; resolution is fail-closed (unknown or wrongly signed → UNKNOWN,
 * expired → EXPIRED, revoked → REVOKED, over the per-grant fetch rate limit →
 * RATE_LIMITED, asset gone → UNKNOWN); a re-mint revokes the delivery's still-active
 * grants first; revoke is workspace-scoped and idempotent. Mocked repositories, no Spring
 * context.
 */
class ImportGrantServiceTest {

	private static final Long WS = 1L;

	private static final Long DELIVERY = 500L;

	private static final String SHA = "a".repeat(64);

	/**
	 * Test-only AES-256 key (32 bytes, base64) — same value as the test application.yml.
	 */
	private static final String KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	private static final OffsetDateTime NOW = OffsetDateTime.now();

	private MediaImportGrantRepository grants;

	private MediaService media;

	private ImportGrantService service;

	private ImportGrantService.MintedGrant minted;

	private MediaImportGrant saved;

	@BeforeEach
	void setUp() {
		this.grants = mock(MediaImportGrantRepository.class);
		this.media = mock(MediaService.class);
		// small fetch cap so the rate-limit path is testable cheaply
		ImportGrantProperties props = new ImportGrantProperties("http://localhost:8080", KEY, "test-v1", 30, 2, 600);
		this.service = new ImportGrantService(this.grants, this.media, props);
		given(this.media.findBySha(eq(WS), anyString()))
			.willReturn(Optional.of(new MediaAsset(60L, WS, "chart.png", "image/png", 3L, SHA, NOW)));
		given(this.grants.save(any(MediaImportGrant.class))).willAnswer((inv) -> {
			MediaImportGrant g = inv.getArgument(0);
			return new MediaImportGrant(g.id() == null ? 77L : g.id(), g.workspaceId(), g.deliveryId(), g.sha256(),
					g.purpose(), g.keyId(), g.tokenHash(), g.expiresAt(), g.revokedAt(), g.createdAt());
		});
	}

	@Test
	void mintReturnsSignedBearerUrlAndStoresOnlyTheSignatureHash() throws Exception {
		ImportGrantService.MintedGrant grant = this.service.mint(WS, DELIVERY, SHA,
				ImportGrantService.PURPOSE_PLATFORM_IMPORT, NOW);

		assertThat(grant.grantId()).isEqualTo(77L);
		assertThat(grant.expiresAt()).isEqualTo(NOW.plusMinutes(30));
		assertThat(grant.url()).matches("http://localhost:8080/media/grants/77\\?exp=\\d+&sig=[0-9a-f]{64}");
		String sig = sigOf(grant.url());
		ArgumentCaptor<MediaImportGrant> captor = ArgumentCaptor.forClass(MediaImportGrant.class);
		verify(this.grants).save(captor.capture());
		MediaImportGrant row = captor.getValue();
		assertThat(row.workspaceId()).isEqualTo(WS);
		assertThat(row.deliveryId()).isEqualTo(DELIVERY);
		assertThat(row.sha256()).isEqualTo(SHA);
		assertThat(row.purpose()).isEqualTo(ImportGrantService.PURPOSE_PLATFORM_IMPORT);
		assertThat(row.keyId()).isEqualTo("test-v1");
		assertThat(row.revokedAt()).isNull();
		assertThat(row.tokenHash()).isEqualTo(sha256Hex(sig));
		assertThat(row.tokenHash()).isNotEqualTo(sig); // never the bearer token itself
	}

	@Test
	void mintRejectsUnknownAssetAndMalformedHash() {
		given(this.media.findBySha(eq(WS), eq("f".repeat(64)))).willReturn(Optional.empty());

		assertThatThrownBy(
				() -> this.service.mint(WS, DELIVERY, "f".repeat(64), ImportGrantService.PURPOSE_PLATFORM_IMPORT, NOW))
			.isInstanceOf(MediaException.class);
		assertThatThrownBy(
				() -> this.service.mint(WS, DELIVERY, "not-a-hash", ImportGrantService.PURPOSE_PLATFORM_IMPORT, NOW))
			.isInstanceOf(MediaException.class);
		verify(this.grants, never()).save(any());
	}

	@Test
	void mintRevokesStillActiveGrantsOfTheSameDeliveryFirst() {
		MediaImportGrant old = new MediaImportGrant(70L, WS, DELIVERY, SHA, ImportGrantService.PURPOSE_PLATFORM_IMPORT,
				"test-v1", "h".repeat(64), NOW.plusMinutes(30), null, NOW);
		given(this.grants.findAllByDeliveryIdOrderByIdAsc(DELIVERY)).willReturn(List.of(old));

		this.service.mint(WS, DELIVERY, SHA, ImportGrantService.PURPOSE_PLATFORM_IMPORT, NOW);

		ArgumentCaptor<MediaImportGrant> captor = ArgumentCaptor.forClass(MediaImportGrant.class);
		verify(this.grants, times(2)).save(captor.capture());
		assertThat(captor.getAllValues().get(0).id()).isEqualTo(70L);
		assertThat(captor.getAllValues().get(0).revokedAt()).isNotNull();
		assertThat(captor.getAllValues().get(1).id()).isNull(); // identity assigned by
																// the DB on insert
		assertThat(captor.getAllValues().get(1).revokedAt()).isNull();
	}

	@Test
	void resolveAcceptsAValidGrant() {
		mintOnce();

		ImportGrantService.GrantAccess access = this.service.resolve(77L, this.saved.expiresAt().toEpochSecond(),
				sigOf(this.minted.url()), NOW);

		assertThat(access.access()).isEqualTo(ImportGrantService.Access.VALID);
		assertThat(access.grant().id()).isEqualTo(77L);
	}

	@Test
	void resolveRejectsTamperedOrForeignSignatureAsUnknown() {
		mintOnce();
		long exp = this.saved.expiresAt().toEpochSecond();
		String sig = sigOf(this.minted.url());
		// deterministic flip of the last hex char (never assume a random char differs)
		char flipped = sig.charAt(sig.length() - 1) == 'a' ? 'b' : 'a';
		String tampered = sig.substring(0, sig.length() - 1) + flipped;
		assertThat(this.service.resolve(77L, exp, tampered, NOW).access()).isEqualTo(ImportGrantService.Access.UNKNOWN);
		// a signature minted for another delivery does not verify either (bound fields)
		String foreign = sign("test-v1", WS, 999L, SHA, ImportGrantService.PURPOSE_PLATFORM_IMPORT, exp);
		assertThat(this.service.resolve(77L, exp, foreign, NOW).access()).isEqualTo(ImportGrantService.Access.UNKNOWN);
		// the expiry is part of the signed payload — a different exp does not verify
		assertThat(this.service.resolve(77L, exp + 60, sig, NOW).access()).isEqualTo(ImportGrantService.Access.UNKNOWN);
	}

	@Test
	void resolveUnknownGrantIdIsUnknown() {
		assertThat(this.service.resolve(12345L, 1L, "ab".repeat(32), NOW).access())
			.isEqualTo(ImportGrantService.Access.UNKNOWN);
	}

	@Test
	void resolveReportsExpiredAndRevoked() {
		mintOnce();
		long exp = this.saved.expiresAt().toEpochSecond();
		String sig = sigOf(this.minted.url());
		// same valid signature, but now past the expiry
		assertThat(this.service.resolve(77L, exp, sig, NOW.plusMinutes(31)).access())
			.isEqualTo(ImportGrantService.Access.EXPIRED);
		// revoked before expiry
		given(this.grants.findById(77L)).willReturn(Optional.of(revoked(this.saved, NOW)));
		assertThat(this.service.resolve(77L, exp, sig, NOW).access()).isEqualTo(ImportGrantService.Access.REVOKED);
	}

	@Test
	void resolveFailsClosedWhenTheAssetDisappeared() {
		mintOnce();
		given(this.media.findBySha(eq(WS), anyString())).willReturn(Optional.empty());

		assertThat(this.service.resolve(77L, this.saved.expiresAt().toEpochSecond(), sigOf(this.minted.url()), NOW)
			.access()).isEqualTo(ImportGrantService.Access.UNKNOWN);
	}

	@Test
	void resolveRateLimitsFetchesPerGrant() {
		mintOnce();
		long exp = this.saved.expiresAt().toEpochSecond();
		String sig = sigOf(this.minted.url());

		assertThat(this.service.resolve(77L, exp, sig, NOW).access()).isEqualTo(ImportGrantService.Access.VALID);
		assertThat(this.service.resolve(77L, exp, sig, NOW).access()).isEqualTo(ImportGrantService.Access.VALID);
		assertThat(this.service.resolve(77L, exp, sig, NOW).access()).isEqualTo(ImportGrantService.Access.RATE_LIMITED); // test
																															// cap:
																															// 2
																															// fetches
																															// per
																															// window
	}

	@Test
	void revokeIsWorkspaceScopedAndIdempotent() {
		MediaImportGrant grant = new MediaImportGrant(77L, WS, DELIVERY, SHA,
				ImportGrantService.PURPOSE_PLATFORM_IMPORT, "test-v1", "h".repeat(64), NOW.plusMinutes(30), null, NOW);
		// foreign workspace id: nothing found, nothing saved (ADR-013)
		given(this.grants.findByIdAndWorkspaceId(77L, 999L)).willReturn(Optional.empty());
		this.service.revoke(999L, 77L, NOW);
		verify(this.grants, never()).save(any());
		// own workspace: revoked once, a second call is a no-op
		given(this.grants.findByIdAndWorkspaceId(77L, WS)).willReturn(Optional.of(grant));
		this.service.revoke(WS, 77L, NOW);
		ArgumentCaptor<MediaImportGrant> captor = ArgumentCaptor.forClass(MediaImportGrant.class);
		verify(this.grants).save(captor.capture());
		assertThat(captor.getValue().revokedAt()).isEqualTo(NOW);
		given(this.grants.findByIdAndWorkspaceId(77L, WS)).willReturn(Optional.of(captor.getValue()));
		this.service.revoke(WS, 77L, NOW.plusSeconds(1));
		verify(this.grants, times(1)).save(any());
	}

	private void mintOnce() {
		this.minted = this.service.mint(WS, DELIVERY, SHA, ImportGrantService.PURPOSE_PLATFORM_IMPORT, NOW);
		ArgumentCaptor<MediaImportGrant> captor = ArgumentCaptor.forClass(MediaImportGrant.class);
		verify(this.grants).save(captor.capture());
		MediaImportGrant row = captor.getValue();
		// the save argument carries no id yet — the database assigns identity on insert
		this.saved = new MediaImportGrant(77L, row.workspaceId(), row.deliveryId(), row.sha256(), row.purpose(),
				row.keyId(), row.tokenHash(), row.expiresAt(), row.revokedAt(), row.createdAt());
		given(this.grants.findById(77L)).willReturn(Optional.of(this.saved));
	}

	private static String sigOf(String url) {
		return url.substring(url.indexOf("sig=") + 4);
	}

	private static MediaImportGrant revoked(MediaImportGrant g, OffsetDateTime at) {
		return new MediaImportGrant(g.id(), g.workspaceId(), g.deliveryId(), g.sha256(), g.purpose(), g.keyId(),
				g.tokenHash(), g.expiresAt(), at, g.createdAt());
	}

	private static String sha256Hex(String text) throws GeneralSecurityException {
		return HexFormat.of()
			.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
	}

	/** Same HMAC-SHA256 construction as the service, to forge bound-field variants. */
	private static String sign(String keyId, Long ws, Long delivery, String sha, String purpose, long exp) {
		String payload = keyId + "|" + ws + "|" + delivery + "|" + sha + "|" + purpose + "|" + exp;
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(Base64.getDecoder().decode(KEY), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
