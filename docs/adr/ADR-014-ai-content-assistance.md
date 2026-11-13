# ADR-014 — Optional AI assistance before human publication approval

- **Status:** Accepted as integration design; no model or account access certified
- **Date:** 2026-11-13

## Context

The owner wants ChatGPT for writing, reviewing, and correcting content before publishing. The app needs an API integration with clear editing, workspace, privacy, and cost boundaries.

## Decision

Use a provider abstraction with an initial OpenAI Responses API implementation. “ChatGPT-style assistant” describes the experience; this design uses a configured API credential and does not assume a ChatGPT login/subscription grants API access or budget. Select an available text model during implementation and store its identifier per workspace rather than hard-coding a “latest” model.

Supported actions: draft from a brief, spelling/grammar correction, editorial review, tone/clarity improvement, shortening for selected targets, translation, and variants. Reviews return findings and proposed text; they do not certify factual correctness.

### Editing contract

1. The owner chooses an action, content scope (default/platform/destination), target language/tone, and relevant workspace facts. Show what will leave the installation.
2. Submit only selected content, minimal local context, and workspace instructions; never secrets, connection metadata, unrelated drafts, contact notes, or another workspace's data. Text-only v1 sends no images; alt-text assistance may use an owner's description but cannot claim to inspect unseen images.
3. Run asynchronously against a base revision/version. Render output as untrusted text, with a comparison and warnings for changed dates, names, URLs, mention tokens, or factual anchors.
4. Accept selected changes explicitly, creating a new revision; reject leaves the draft intact. Reject stale application after another tab edited the draft, or ask for a fresh comparison.
5. Re-run deterministic destination validation and require publish/schedule approval. AI cannot approve, publish, schedule, invoke tools, fetch arbitrary links, or modify accounts.
6. Queued and individually scheduled publications never invoke AI. The approved revision is the content source.

### Privacy, usage, and failure behavior

- AI is disabled until its processing region and spending cap are explicitly agreed and configured, as well as workspace opt-in, key and model. No region or budget is inferred from the owner's location or subscription ([ADR-016](ADR-016-operational-and-editorial-policies.md)). Keep API keys server-side in the encrypted secret store; no implicit global fallback.
- Use foreground stateless Responses requests with `store: false`, supplying only selected context. This is not a promise of zero retention: provider abuse-monitoring and model/feature policies remain applicable.
- Store local request metadata, model, prompt-template version, base revision, acceptance decision, and usage. Proposed default: expire unaccepted suggestions after seven days; accepted text follows revision retention. Routine logs omit prompt/response bodies.
- Bound input/output size, duration, concurrency, and calls. Reserve estimated maximum cost before dispatch and reconcile reported token usage afterward; keep a conservative reservation when outcome/usage is unknown. Display price assumptions/date and distinguish estimates from actual provider billing. Provider-side controls supplement local budgets.
- Use bounded retries only for clearly rejected/transient requests; explain potentially billable uncertain outcomes. Canceling the UI does not guarantee provider cancellation or refund.
- On timeout, unavailable model, quota, missing key, or budget exhaustion, preserve the original draft and keep manual editing/publishing available.

## Sources and limits

OpenAI recommends Responses for new [text-generation applications](https://developers.openai.com/api/docs/guides/text). The [Responses reference](https://developers.openai.com/api/reference/resources/responses/methods/create) defines storage and usage fields. [Data controls](https://developers.openai.com/api/docs/guides/your-data) explain that disabling response storage does not remove every retention mechanism. These sources inform the API choice; editorial gates and local retention/budget rules are application decisions. Verify provider settings and model-specific exceptions before deployment.

## Alternatives

Browser automation of ChatGPT, autonomous publishing agents, automatic “corrections” on dispatch, and a mandatory AI dependency are rejected. Additional providers can be added through the same editorial interface later.
