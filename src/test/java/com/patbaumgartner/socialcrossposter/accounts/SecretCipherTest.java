package com.patbaumgartner.socialcrossposter.accounts;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

	// Test-only key: 32 bytes base64. Never used outside tests.
	private static final String TEST_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

	private final SecretCipher cipher = new SecretCipher(new SecretsProperties(TEST_KEY, "test-v1"));

	@Test
	void sealAndOpenRoundTrips() {
		var sealed = this.cipher.seal(SecretCipher.associatedData("private", "telegram", "@bot"), "secret-token");
		assertThat(sealed.keyId()).isEqualTo("test-v1");
		assertThat(this.cipher.open(SecretCipher.associatedData("private", "telegram", "@bot"), sealed))
			.isEqualTo("secret-token");
	}

	@Test
	void nonceIsUniquePerEncryption() {
		var a = this.cipher.seal("aad", "same-plaintext");
		var b = this.cipher.seal("aad", "same-plaintext");
		assertThat(a.nonce()).isNotEqualTo(b.nonce());
		assertThat(a.ciphertext()).isNotEqualTo(b.ciphertext());
	}

	@Test
	void decryptionWithDifferentAssociatedDataFails() {
		var sealed = this.cipher.seal(SecretCipher.associatedData("private", "telegram", "@bot"), "secret-token");
		assertThatThrownBy(
				() -> this.cipher.open(SecretCipher.associatedData("socrates-ch", "telegram", "@bot"), sealed))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void tamperedCiphertextFails() {
		var sealed = this.cipher.seal("aad", "secret-token");
		var tampered = new SecretCipher.SealedSecret("A" + sealed.ciphertext().substring(1), sealed.nonce(),
				sealed.keyId());
		assertThatThrownBy(() -> this.cipher.open("aad", tampered)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void missingKeyFailsClosed() {
		assertThatThrownBy(() -> new SecretCipher(new SecretsProperties(null, "v1")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("APP_SECRETS_KEY");
		assertThatThrownBy(() -> new SecretCipher(new SecretsProperties("not-base64!!!", "v1")))
			.isInstanceOf(IllegalStateException.class);
		// "aGVsbG8=" decodes to 5 bytes, not 32.
		assertThatThrownBy(() -> new SecretCipher(new SecretsProperties("aGVsbG8=", "v1")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

	@Test
	void defaultKeyIdIsV1() {
		assertThat(new SecretCipher(new SecretsProperties(TEST_KEY, null)).keyId()).isEqualTo("v1");
	}

}
