package com.patbaumgartner.socialcrossposter.workspace;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link WorkspaceService#create} and
 * {@link WorkspaceService#updateSettings} (UC-15 steps 1-2, ADR-013): fail-closed
 * validation of slug/label/color/timezone, duplicate-slug rejection, defaults, and the
 * persisted field mapping — update keeps the id, slug and created_at of the existing row.
 * Mocked repository, no Spring context.
 */
class WorkspaceServiceTest {

	private WorkspaceRepository repository;

	private WorkspaceService service;

	@BeforeEach
	void setUp() {
		this.repository = mock(WorkspaceRepository.class);
		this.service = new WorkspaceService(this.repository);
		given(this.repository.findBySlug(anyString())).willReturn(Optional.empty());
		given(this.repository.save(any(Workspace.class))).willAnswer((inv) -> inv.getArgument(0));
	}

	@Test
	void createPersistsTrimmedFieldsAndExplicitTimestamp() {
		Workspace created = this.service.create(" acme ", " Acme ", "#A1B2C3", " Europe/Zurich ");

		ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
		verify(this.repository).save(captor.capture());
		Workspace saved = captor.getValue();
		assertThat(saved.id()).isNull();
		assertThat(saved.slug()).isEqualTo("acme");
		assertThat(saved.label()).isEqualTo("Acme");
		assertThat(saved.color()).isEqualTo("#A1B2C3");
		assertThat(saved.timezone()).isEqualTo("Europe/Zurich");
		assertThat(saved.createdAt()).isNotNull();
		assertThat(created).isSameAs(saved);
	}

	@Test
	void createAppliesDefaultsWhenColorAndTimezoneOmitted() {
		Workspace created = this.service.create("defaults-ws", "Defaults", null, "  ");
		assertThat(created.color()).isEqualTo("#3b82f6");
		assertThat(created.timezone()).isEqualTo("Europe/Zurich");
	}

	@Test
	void duplicateSlugFailsClosedAndSavesNothing() {
		given(this.repository.findBySlug("private"))
			.willReturn(Optional.of(new Workspace(1L, "private", "Private", "#6b7280", "Europe/Zurich", null)));

		assertThatThrownBy(() -> this.service.create("private", "Dup", null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("already exists");
		verify(this.repository, never()).save(any());
	}

	@Test
	void invalidSlugsFailClosed() {
		assertThatThrownBy(() -> this.service.create("Bad Slug!", "X", null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Slug");
		assertThatThrownBy(() -> this.service.create("-dash-first", "X", null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Slug");
		assertThatThrownBy(() -> this.service.create("", "X", null, null)).isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Slug");
		assertThatThrownBy(() -> this.service.create("a".repeat(64), "X", null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Slug");
		verify(this.repository, never()).save(any());
	}

	@Test
	void invalidLabelFailsClosed() {
		assertThatThrownBy(() -> this.service.create("acme", "   ", null, null)).isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Label");
		assertThatThrownBy(() -> this.service.create("acme", "x".repeat(121), null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Label");
		verify(this.repository, never()).save(any());
	}

	@Test
	void invalidColorFailsClosed() {
		assertThatThrownBy(() -> this.service.create("acme", "Acme", "red", null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Color");
		assertThatThrownBy(() -> this.service.create("acme", "Acme", "#12345", null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Color");
		verify(this.repository, never()).save(any());
	}

	@Test
	void unknownTimezoneFailsClosed() {
		assertThatThrownBy(() -> this.service.create("acme", "Acme", null, "Mars/Olympus"))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("timezone");
		verify(this.repository, never()).save(any());
	}

	@Test
	void updateSettingsKeepsSlugIdAndCreatedAtAndTrimsFields() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-17T10:00:00Z");
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", createdAt)));

		Workspace updated = this.service.updateSettings(" acme ", " Acme AG ", "#A1B2C3", " Europe/Zurich ", null,
				null);

		ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
		verify(this.repository).save(captor.capture());
		Workspace saved = captor.getValue();
		assertThat(saved.id()).isEqualTo(7L);
		assertThat(saved.slug()).isEqualTo("acme");
		assertThat(saved.label()).isEqualTo("Acme AG");
		assertThat(saved.color()).isEqualTo("#A1B2C3");
		assertThat(saved.timezone()).isEqualTo("Europe/Zurich");
		assertThat(saved.createdAt()).isEqualTo(createdAt);
		assertThat(updated).isSameAs(saved);
	}

	@Test
	void updateSettingsAppliesDefaultsWhenColorAndTimezoneOmitted() {
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", null)));

		Workspace updated = this.service.updateSettings("acme", "Acme", null, "  ", null, null);

		assertThat(updated.color()).isEqualTo("#3b82f6");
		assertThat(updated.timezone()).isEqualTo("Europe/Zurich");
	}

	@Test
	void updateSettingsUnknownOrBlankSlugFailsClosedAndSavesNothing() {
		assertThatThrownBy(() -> this.service.updateSettings("ghost", "X", null, null, null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Unknown workspace");
		assertThatThrownBy(() -> this.service.updateSettings("   ", "X", null, null, null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Unknown workspace");
		verify(this.repository, never()).save(any());
	}

	@Test
	void updateSettingsInvalidFieldsFailClosedAndSaveNothing() {
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", null)));

		assertThatThrownBy(() -> this.service.updateSettings("acme", "   ", null, null, null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Label");
		assertThatThrownBy(() -> this.service.updateSettings("acme", "x".repeat(121), null, null, null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Label");
		assertThatThrownBy(() -> this.service.updateSettings("acme", "Acme", "red", null, null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Color");
		assertThatThrownBy(() -> this.service.updateSettings("acme", "Acme", null, "Mars/Olympus", null, null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("timezone");
		verify(this.repository, never()).save(any());
	}

	@Test
	void updateSettingsPersistsNormalizedLanguagesAndBrandVoice() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-11-17T10:00:00Z");
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", createdAt)));

		Workspace updated = this.service.updateSettings("acme", "Acme", null, null, " de , en , fr-CH ",
				"  Friendly, concise, no emoji.  ");

		ArgumentCaptor<Workspace> captor = ArgumentCaptor.forClass(Workspace.class);
		verify(this.repository).save(captor.capture());
		Workspace saved = captor.getValue();
		assertThat(saved.id()).isEqualTo(7L);
		assertThat(saved.slug()).isEqualTo("acme");
		assertThat(saved.createdAt()).isEqualTo(createdAt);
		assertThat(saved.languages()).isEqualTo("de,en,fr-CH");
		assertThat(saved.brandVoice()).isEqualTo("Friendly, concise, no emoji.");
		assertThat(updated).isSameAs(saved);
	}

	@Test
	void updateSettingsBlankProfileStoresNull() {
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", null)));

		Workspace updated = this.service.updateSettings("acme", "Acme", null, null, "   ", "  ");

		assertThat(updated.languages()).isNull();
		assertThat(updated.brandVoice()).isNull();
	}

	@Test
	void updateSettingsInvalidLanguagesFailClosedAndSaveNothing() {
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", null)));

		assertThatThrownBy(() -> this.service.updateSettings("acme", "Acme", null, null, "english!", null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("language");
		assertThatThrownBy(
				() -> this.service.updateSettings("acme", "Acme", null, null, "en,de,fr,it,es,pt,nl,sv,da,pl,cs", null))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("languages");
		verify(this.repository, never()).save(any());
	}

	@Test
	void updateSettingsInvalidBrandVoiceFailsClosedAndSavesNothing() {
		given(this.repository.findBySlug("acme"))
			.willReturn(Optional.of(new Workspace(7L, "acme", "Acme", "#3b82f6", "Europe/Zurich", null)));

		assertThatThrownBy(() -> this.service.updateSettings("acme", "Acme", null, null, null, "x".repeat(2001)))
			.isInstanceOf(WorkspaceException.class)
			.hasMessageContaining("Brand voice");
		verify(this.repository, never()).save(any());
	}

}
