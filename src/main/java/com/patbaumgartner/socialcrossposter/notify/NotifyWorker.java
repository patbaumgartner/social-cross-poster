package com.patbaumgartner.socialcrossposter.notify;

import java.time.OffsetDateTime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.patbaumgartner.socialcrossposter.workspace.Workspace;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

/**
 * Scheduled outbox worker for external notification channels (UC-12 second slice step 4,
 * ADR-015). Polls every workspace for due notification deliveries. Disabled with
 * {@code APP_NOTIFY_WORKER_ENABLED=false} — tests drive
 * {@link NotificationDeliveryService#runDue} manually instead.
 */
@Component
@ConditionalOnProperty(prefix = "app.notify", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class NotifyWorker {

	private final NotificationDeliveryService deliveryService;

	private final WorkspaceService workspaces;

	public NotifyWorker(NotificationDeliveryService deliveryService, WorkspaceService workspaces) {
		this.deliveryService = deliveryService;
		this.workspaces = workspaces;
	}

	@Scheduled(fixedDelayString = "${app.notify.poll-interval-ms:5000}",
			initialDelayString = "${app.notify.initial-delay-ms:2000}")
	public void poll() {
		OffsetDateTime now = OffsetDateTime.now();
		for (Workspace workspace : this.workspaces.listAll()) {
			this.deliveryService.runDue(workspace.id(), now);
		}
	}

}
