package com.patbaumgartner.socialcrossposter.contacts;

import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.patbaumgartner.socialcrossposter.platform.PlatformKeys;

/**
 * UC-03 contact operations (ADR-003, ADR-013). Every method takes the explicit workspace
 * id and fails closed on unknown or foreign ids. Photos are accepted as data: URIs only —
 * the app never fetches external image URLs (ADR-008). Private notes are stored for the
 * owner and are never part of search/autocomplete results (UC-03: AI and providers never
 * receive them by default). Manual handle entry is never marked verified; that flag
 * requires a provider-verified resolution.
 */
@Service
public class ContactService {

	/** data:image/(png|jpeg|webp);base64,... — the only accepted photo form. */
	private static final Pattern PHOTO_DATA_URI = Pattern.compile("data:image/(png|jpeg|webp);base64,[A-Za-z0-9+/=]+");

	/** Upper bound for the stored data URI (~256 KiB decoded). */
	private static final int PHOTO_MAX_LENGTH = 350_000;

	private static final Set<String> IDENTITY_KINDS = Set.of("none", "handle", "did", "urn", "url");

	private final ContactRepository contacts;

	private final ContactHandleRepository handles;

	public ContactService(ContactRepository contacts, ContactHandleRepository handles) {
		this.contacts = contacts;
		this.handles = handles;
	}

	public List<ContactView> listContacts(Long workspaceId) {
		Map<Long, List<ContactHandle>> handlesByContact = this.handles
			.findAllByWorkspaceIdOrderByContactIdAsc(workspaceId)
			.stream()
			.collect(Collectors.groupingBy(ContactHandle::contactId));
		return this.contacts.findAllByWorkspaceIdOrderByDisplayNameAsc(workspaceId)
			.stream()
			.map((c) -> toView(c, handlesByContact.getOrDefault(c.id(), List.of())))
			.toList();
	}

	/**
	 * Autocomplete search (UC-03 step 3): non-archived contacts of this workspace whose
	 * display name contains the query, max 8. Results carry only id, display name, token
	 * and photo — never private notes.
	 */
	public List<SearchResult> search(Long workspaceId, String query) {
		if (!StringUtils.hasText(query)) {
			return List.of();
		}
		String q = query.trim();
		return this.contacts
			.findTop8ByWorkspaceIdAndArchivedFalseAndDisplayNameContainingIgnoreCaseOrderByDisplayNameAsc(workspaceId,
					q)
			.stream()
			.map((c) -> new SearchResult(c.id(), c.displayName(), c.token(), c.photo()))
			.toList();
	}

	/**
	 * Creates a contact with a fresh neutral mention token (UC-03 step 1).
	 */
	public ContactView createContact(Long workspaceId, String displayName, String photo, String notes) {
		if (!StringUtils.hasText(displayName) || displayName.trim().length() > 120) {
			throw new ContactException("A contact needs a display name of at most 120 characters.");
		}
		String validatedPhoto = validatePhoto(photo);
		OffsetDateTime now = OffsetDateTime.now();
		String token = MentionToken.format(UUID.randomUUID());
		Contact saved = this.contacts.save(new Contact(null, workspaceId, token, displayName.trim(), validatedPhoto,
				StringUtils.hasText(notes) ? notes.trim() : null, false, now, now));
		return toView(saved, List.of());
	}

	/**
	 * Attaches a platform handle to a contact (UC-03 step 2). An exact duplicate
	 * (platform + handle + host) updates the identity fields of the existing row instead
	 * of creating a second one. Mastodon handles require an instance host.
	 */
	public HandleView addHandle(Long workspaceId, Long contactId, String platform, String handle, String host,
			String identityKind, String identityValue) {
		Contact contact = findRequired(workspaceId, contactId);
		if (contact.archived()) {
			throw new ContactException("This contact is archived; restore it before adding handles.");
		}
		String normalizedPlatform = platform == null ? "" : platform.trim().toLowerCase();
		if (!PlatformKeys.ALL.contains(normalizedPlatform)) {
			throw new ContactException("Unknown platform: " + (StringUtils.hasText(platform) ? platform : "(empty)")
					+ ". Use a platform key from the integration catalog.");
		}
		if (!StringUtils.hasText(handle) || handle.trim().length() > 255) {
			throw new ContactException("A handle is required (at most 255 characters).");
		}
		String trimmedHandle = handle.trim();
		String normalizedHost = host == null ? "" : host.trim().toLowerCase();
		if ("mastodon".equals(normalizedPlatform) && !StringUtils.hasText(normalizedHost)) {
			throw new ContactException("Mastodon handles need an instance host (e.g. mastodon.social).");
		}
		String kind = StringUtils.hasText(identityKind) ? identityKind.trim().toLowerCase() : "none";
		if (!IDENTITY_KINDS.contains(kind)) {
			throw new ContactException("Identity kind must be one of: " + String.join(", ", IDENTITY_KINDS));
		}
		String identity = StringUtils.hasText(identityValue) ? identityValue.trim() : null;
		if (!"none".equals(kind) && !StringUtils.hasText(identity)) {
			throw new ContactException("Identity kind '" + kind + "' requires an identity value.");
		}
		if (identity != null && identity.length() > 512) {
			throw new ContactException("Identity value is too long (at most 512 characters).");
		}
		OffsetDateTime now = OffsetDateTime.now();
		Optional<ContactHandle> existing = this.handles.findByContactIdAndPlatformAndHandleAndHost(contactId,
				normalizedPlatform, trimmedHandle, normalizedHost);
		if (existing.isPresent()) {
			ContactHandle h = existing.get();
			ContactHandle updated = new ContactHandle(h.id(), workspaceId, contactId, normalizedPlatform, trimmedHandle,
					normalizedHost, kind, identity, false, h.createdAt(), now);
			return toHandleView(this.handles.save(updated));
		}
		ContactHandle saved = this.handles.save(new ContactHandle(null, workspaceId, contactId, normalizedPlatform,
				trimmedHandle, normalizedHost, kind, identity, false, now, now));
		return toHandleView(saved);
	}

	public void deleteHandle(Long workspaceId, Long contactId, Long handleId) {
		ContactHandle handle = this.handles.findByIdAndWorkspaceIdAndContactId(handleId, workspaceId, contactId)
			.orElseThrow(() -> new ContactException("Unknown handle for this workspace."));
		this.handles.delete(handle);
	}

	/**
	 * Archives/restores a contact (soft delete, UC-03 alternative flow). Archived
	 * contacts leave the list and autocomplete but their tokens keep resolving so
	 * existing drafts/snapshots stay stable.
	 */
	public void setArchived(Long workspaceId, Long contactId, boolean archived) {
		Contact contact = findRequired(workspaceId, contactId);
		this.contacts.save(new Contact(contact.id(), workspaceId, contact.token(), contact.displayName(),
				contact.photo(), contact.notes(), archived, contact.createdAt(), OffsetDateTime.now()));
	}

	public Optional<Contact> findContact(Long workspaceId, Long contactId) {
		return this.contacts.findByIdAndWorkspaceId(contactId, workspaceId);
	}

	private Contact findRequired(Long workspaceId, Long contactId) {
		return findContact(workspaceId, contactId)
			.orElseThrow(() -> new ContactException("Unknown contact for this workspace."));
	}

	private String validatePhoto(String photo) {
		if (!StringUtils.hasText(photo)) {
			return null;
		}
		String trimmed = photo.trim();
		if (trimmed.length() > PHOTO_MAX_LENGTH || !PHOTO_DATA_URI.matcher(trimmed).matches()) {
			throw new ContactException(
					"Photos must be a data:image/png|jpeg|webp;base64,... URI of at most ~256 KiB; external URLs are not fetched (ADR-008).");
		}
		try {
			Base64.getDecoder().decode(trimmed.substring(trimmed.indexOf(',') + 1));
		}
		catch (IllegalArgumentException ex) {
			throw new ContactException("The photo data URI is not valid base64.");
		}
		return trimmed;
	}

	private ContactView toView(Contact c, List<ContactHandle> contactHandles) {
		List<HandleView> handleViews = contactHandles.stream()
			.sorted(Comparator.comparing(ContactHandle::platform).thenComparing(ContactHandle::handle))
			.map(ContactService::toHandleView)
			.toList();
		return new ContactView(c.id(), c.token(), c.displayName(), c.photo(), c.notes(), c.archived(), handleViews);
	}

	private static HandleView toHandleView(ContactHandle h) {
		return new HandleView(h.id(), h.platform(), h.handle(), h.host(), h.identityKind(), h.identityValue(),
				h.verified());
	}

	/**
	 * Full contact data for the owner page; includes private notes (never leaves via
	 * search/AI).
	 */
	public record ContactView(Long id, String token, String displayName, String photo, String notes, boolean archived,
			List<HandleView> handles) {

	}

	/** One platform identity of a contact. */
	public record HandleView(Long id, String platform, String handle, String host, String identityKind,
			String identityValue, boolean verified) {

	}

	/** Autocomplete result — deliberately excludes private notes (UC-03). */
	public record SearchResult(Long id, String displayName, String token, String photo) {

	}

}
