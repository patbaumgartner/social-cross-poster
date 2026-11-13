# UC-16 — Write, review, correct, and translate with AI

- **Actor:** Owner; external AI provider
- **Trigger:** Explicit assistant action in a draft or remote-edit editor
- **Preconditions:** Active workspace; AI opt-in/key/model configured; processing region and spending cap explicitly agreed and configured
- **Related decisions:** ADR-003, ADR-014

## Main flow

1. Choose “Write from brief”, “Review”, “Correct spelling/grammar”, “Improve clarity/tone”, “Shorten”, “Translate”, or “Suggest variants”.
2. Select default/platform/destination text, language, and allowed workspace brand/factual context. Show the selected content to be sent outside the NAS.
3. Reserve estimated usage budget and send a bounded server-side request associated with workspace, base revision/version, action, and prompt-template version.
4. Display suggestions/findings beside the original with a diff. Highlight altered factual anchors, dates/names/URLs, invalid mention tokens, and unverified claims.
5. Accept selected edits into a new revision, or reject. Acceptance validates tokens and checks the base version before saving.
6. Re-run destination previews. Publishing/scheduling still requires separate human confirmation (UC-05/10).

## Alternative flows

- AI disabled, budget exceeded, model unavailable, or provider timeout: keep the draft intact and permit manual writing/publishing.
- Another tab changed content: reject stale application and offer fresh comparison; never silently overwrite.
- Translation/variants can populate explicit platform/destination overrides, including two accounts of the same platform.
- Malformed or hostile model output is escaped and treated as content, never executable HTML/instructions or a tool call.
- Image interpretation is outside text-only v1. Alt-text suggestions based on an owner's description still need human review.
- Canceling a local request does not promise remote cancellation or no charge. Retain conservative usage reservations when outcome is uncertain.
- A remote edit uses the action revision rather than rewriting the original published payload.

## Postconditions and UI

AI suggestions have no publishing authority. Accepted changes retain provenance and undo through revisions; unaccepted suggestions expire according to workspace policy (default seven days). Review improves editorial quality but does not certify facts or link safety. Explain API credentials, provider retention, and usage estimates in settings.
