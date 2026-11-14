package com.patbaumgartner.socialcrossposter.accounts;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Authenticated encryption for provider secrets (ADR-007): AES-256-GCM with a unique
 * random 12-byte nonce per encryption and the versioned key ID stored alongside the
 * ciphertext. Workspace slug, platform and purpose are bound as associated data (AAD), so
 * a ciphertext cannot be replayed against another workspace or secret type. Keys come
 * from the environment, never the DB.
 */
@Component
public class SecretCipher {

	private static final String ALGORITHM = "AES/GCM/NoPadding";

	private static final int GCM_TAG_BITS = 128;

	private static final int GCM_NONCE_BYTES = 12;

	private final SecretKey key;

	private final String keyId;

	private final SecureRandom random = new SecureRandom();

	public SecretCipher(SecretsProperties properties) {
		String encodedKey = properties.key();
		this.keyId = properties.keyId() != null ? properties.keyId() : "v1";
		if (encodedKey == null || encodedKey.isBlank()) {
			throw new IllegalStateException("Secret encryption key is not configured: set APP_SECRETS_KEY "
					+ "(base64, 32 bytes) and optionally APP_SECRETS_KEY_ID. Startup fails closed without it "
					+ "(ADR-007); keys are supplied through the protected environment, never the database.");
		}
		byte[] raw;
		try {
			raw = Base64.getDecoder().decode(encodedKey);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("APP_SECRETS_KEY is not valid base64", ex);
		}
		if (raw.length != 32) {
			throw new IllegalStateException("APP_SECRETS_KEY must decode to 32 bytes (AES-256), got " + raw.length);
		}
		this.key = new SecretKeySpec(raw, "AES");
	}

	public String keyId() {
		return this.keyId;
	}

	/**
	 * Encrypts {@code plaintext} bound to the given associated data. Returns the base64
	 * ciphertext (tag included) and the base64 nonce as a {@link SealedSecret}.
	 */
	public SealedSecret seal(String associatedData, String plaintext) {
		byte[] nonce = new byte[GCM_NONCE_BYTES];
		this.random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.ENCRYPT_MODE, this.key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
			cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return new SealedSecret(Base64.getEncoder().encodeToString(ciphertext),
					Base64.getEncoder().encodeToString(nonce), this.keyId);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Secret encryption failed", ex);
		}
	}

	/**
	 * Decrypts a {@link SealedSecret}, verifying the tag and the associated data. Any
	 * mismatch (wrong workspace/purpose, tampered ciphertext) throws.
	 */
	public String open(String associatedData, SealedSecret sealed) {
		try {
			Cipher cipher = Cipher.getInstance(ALGORITHM);
			cipher.init(Cipher.DECRYPT_MODE, this.key,
					new GCMParameterSpec(GCM_TAG_BITS, Base64.getDecoder().decode(sealed.nonce())));
			cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
			return new String(cipher.doFinal(Base64.getDecoder().decode(sealed.ciphertext())), StandardCharsets.UTF_8);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Secret decryption failed (wrong key, AAD or tampered ciphertext)", ex);
		}
	}

	/** Ciphertext + nonce + versioned key ID as stored in the database (ADR-007). */
	public record SealedSecret(String ciphertext, String nonce, String keyId) {
	}

	/** Builds the AAD binding a secret to its workspace, platform and purpose. */
	public static String associatedData(String workspaceSlug, String platform, String purpose) {
		return workspaceSlug + ":" + platform + ":" + purpose;
	}

}
