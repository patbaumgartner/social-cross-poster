package com.patbaumgartner.socialcrossposter.platform;

import org.springframework.stereotype.Component;

/**
 * Telegram reconciler (UC-07, ADR-011, ADR-015). The Bot API {@code sendMessage} offers
 * no read-back by idempotency key, and this installation deliberately does not use
 * heuristic {@code getUpdates} text matching (it could falsely confirm a write). Every
 * reconciliation is therefore INCONCLUSIVE: the delivery becomes NEEDS_ATTENTION and the
 * owner reviews the chat manually. No request is made and no token is logged (ADR-007).
 */
@Component
public class TelegramReconciler implements PlatformReconciler {

	public static final String PLATFORM = "telegram";

	@Override
	public String platform() {
		return PLATFORM;
	}

	@Override
	public ReconcileResult reconcile(ReconcileContext context) {
		return ReconcileResult
			.inconclusive("Bot API sendMessage has no read-back by idempotency key; the write can be neither confirmed "
					+ "nor ruled out from this installation — review the chat manually.");
	}

}
