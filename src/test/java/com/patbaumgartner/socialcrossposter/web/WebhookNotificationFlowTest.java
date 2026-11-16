package com.patbaumgartner.socialcrossposter.web;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.accounts.SecretCipher;
import com.patbaumgartner.socialcrossposter.contacts.ContactHandleRepository;
import com.patbaumgartner.socialcrossposter.contacts.ContactRepository;
import com.patbaumgartner.socialcrossposter.content.PostRepository;
import com.patbaumgartner.socialcrossposter.content.PostRevisionRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionDestinationRepository;
import com.patbaumgartner.socialcrossposter.content.RevisionOverrideRepository;
import com.patbaumgartner.socialcrossposter.media.MediaAssetRepository;
import com.patbaumgartner.socialcrossposter.media.RevisionMediaRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannel;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationChannelService;
import com.patbaumgartner.socialcrossposter.notify.NotificationDelivery;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationDeliveryService;
import com.patbaumgartner.socialcrossposter.notify.NotificationEvent;
import com.patbaumgartner.socialcrossposter.notify.NotificationEventRepository;
import com.patbaumgartner.socialcrossposter.notify.NotificationException;
import com.patbaumgartner.socialcrossposter.notify.NotificationService;
import com.patbaumgartner.socialcrossposter.platform.TelegramPublisher;
import com.patbaumgartner.socialcrossposter.publish.DeliveryAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryJobRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryMediaRepository;
import com.patbaumgartner.socialcrossposter.publish.DeliveryRepository;
import com.patbaumgartner.socialcrossposter.publish.PublicationRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionAttemptRepository;
import com.patbaumgartner.socialcrossposter.publish.RemoteActionRepository;
import com.patbaumgartner.socialcrossposter.scheduling.PublicationScheduleRepository;
import com.patbaumgartner.socialcrossposter.workspace.WorkspaceService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-12 second slice end-to-end (ADR-004, ADR-007, ADR-013, ADR-015; SSRF rules per arc42
 * section 8): channel routes require login; an SSRF-rejected URL produces a sanitized
 * flash error and saves nothing; a valid channel is saved with a sealed HMAC secret
 * (never plaintext) and shows on the dashboard; a recorded event enqueues one
 * deduplicated outbox row per enabled channel, and a manual {@code runDue} delivers a
 * signed webhook POST to a local receiver (127.0.0.1 is allow-listed in tests) whose
 * {@code X-SCP-Signature} header verifies against the sealed secret; the
 * test-notification endpoint sends a signed test payload; toggle and delete work and
 * foreign workspace ids fail closed. The notify worker is off in tests. The H2 database
 * is shared across tests in this context, so cleanup runs in FK-safe order —
 * notification_delivery references notification_event/notification_channel.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebhookNotificationFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceService workspaces;

	@Autowired
	SecretCipher secrets;

	@Autowired
	NotificationService notifications;

	@Autowired
	NotificationChannelService channelService;

	@Autowired
	NotificationDeliveryService deliveryService;

	@Autowired
	NotificationEventRepository notificationEvents;

	@Autowired
	NotificationChannelRepository notificationChannels;

	@Autowired
	NotificationDeliveryRepository notificationDeliveries;

	@Autowired
	PostRepository posts;

	@Autowired
	PostRevisionRepository revisions;

	@Autowired
	RevisionDestinationRepository destinations;

	@Autowired
	RevisionOverrideRepository overrides;

	@Autowired
	PublicationRepository publications;

	@Autowired
	DeliveryRepository deliveries;

	@Autowired
	DeliveryAttemptRepository attempts;

	@Autowired
	DeliveryJobRepository jobs;

	@Autowired
	RemoteActionRepository remoteActions;

	@Autowired
	RemoteActionAttemptRepository remoteActionAttempts;

	@Autowired
	PublicationScheduleRepository scheduleRepo;

	@Autowired
	RevisionMediaRepository revisionMedia;

	@Autowired
	MediaAssetRepository mediaAssets;

	@Autowired
	DeliveryMediaRepository deliveryMedia;

	@Autowired
	ContactRepository contacts;

	@Autowired
	ContactHandleRepository handles;

	@Autowired
	AccountRepository accounts;

	@Autowired
	ConnectionRepository connections;

	@MockitoBean
	TelegramPublisher telegramPublisher;

	private HttpServer receiver;

	private int receiverPort;

	private final List<Captured> received = new CopyOnWriteArrayList<>();

	private record Captured(String path, String eventId, String timestamp, String signature, String body) {
	}

	@BeforeEach
	@AfterEach
	void resetState() throws IOException {
		// notification_delivery references notification_event/notification_channel —
		// delete outbox rows and channels first (FK-safe order, UC-12 second slice).
		this.notificationDeliveries.deleteAll();
		this.notificationChannels.deleteAll();
		this.notificationEvents.deleteAll();
		this.revisionMedia.deleteAll();
		this.mediaAssets.deleteAll();
		this.remoteActionAttempts.deleteAll();
		this.remoteActions.deleteAll();
		this.jobs.deleteAll();
		this.attempts.deleteAll();
		this.deliveryMedia.deleteAll();
		this.deliveries.deleteAll();
		this.scheduleRepo.deleteAll();
		this.publications.deleteAll();
		this.overrides.deleteAll();
		this.destinations.deleteAll();
		this.posts.clearCurrentRevisionPointers();
		this.revisions.deleteAll();
		this.posts.deleteAll();
		this.handles.deleteAll();
		this.contacts.deleteAll();
		this.accounts.deleteAll();
		this.connections.deleteAll();
	}

	@BeforeEach
	void startReceiver() throws IOException {
		this.received.clear();
		this.receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.receiver.createContext("/", (exchange) -> {
			byte[] body = exchange.getRequestBody().readAllBytes();
			this.received.add(new Captured(exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("X-SCP-Event-Id"),
					exchange.getRequestHeaders().getFirst("X-SCP-Timestamp"),
					exchange.getRequestHeaders().getFirst("X-SCP-Signature"),
					new String(body, StandardCharsets.UTF_8)));
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(new byte[0]);
			}
			exchange.close();
		});
		this.receiver.start();
		this.receiverPort = this.receiver.getAddress().getPort();
	}

	@AfterEach
	void stopReceiver() {
		if (this.receiver != null) {
			this.receiver.stop(0);
		}
	}

	private String hookUrl() {
		return "http://127.0.0.1:" + this.receiverPort + "/hook";
	}

	@Test
	void channelRoutesRequireLogin() throws Exception {
		this.mockMvc.perform(
				post("/w/private/notifications/channels").with(csrf()).param("label", "ops").param("url", hookUrl()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc.perform(post("/w/private/notifications/channels/1/test").with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		assertThat(this.notificationChannels.findAll()).isEmpty();
	}

	@Test
	void ssrfRejectedUrlProducesFlashErrorAndSavesNothing() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/notifications/channels").with(csrf())
				.session(session)
				.param("label", "internal")
				.param("url", "https://10.0.0.5/hook"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/dashboard"))
			.andExpect(flash().attribute("error", containsString("denied")));
		this.mockMvc
			.perform(post("/w/private/notifications/channels").with(csrf())
				.session(session)
				.param("label", "plain-http")
				.param("url", "http://example.com/hook"))
			.andExpect(flash().attribute("error", containsString("https")));
		assertThat(this.notificationChannels.findAll()).isEmpty();
		assertThat(this.received).isEmpty();
	}

	@Test
	void validChannelIsSavedWithSealedSecretAndShownOnDashboard() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		this.mockMvc
			.perform(post("/w/private/notifications/channels").with(csrf())
				.session(session)
				.param("label", "ops")
				.param("url", hookUrl())
				.param("categories", "delivery_failed"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/dashboard"))
			.andExpect(flash().attributeExists("notice"));

		List<NotificationChannel> channels = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId);
		assertThat(channels).hasSize(1);
		NotificationChannel channel = channels.get(0);
		assertThat(channel.label()).isEqualTo("ops");
		assertThat(channel.url()).isEqualTo(hookUrl());
		assertThat(channel.enabled()).isTrue();
		assertThat(channel.categories()).isEqualTo("DELIVERY_FAILED");
		// the HMAC secret is stored sealed (ADR-007) — base64 ciphertext, never a 64-hex
		// plaintext; opening it under the right AAD yields the secret
		assertThat(channel.secretCipher()).isNotBlank().doesNotMatch("^[0-9a-f]{64}$");
		String secret = this.channelService.openSecret("private", channel);
		assertThat(secret).matches("^[0-9a-f]{64}$");

		this.mockMvc.perform(get("/w/private/dashboard").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("ops")))
			.andExpect(content().string(containsString(hookUrl())));
	}

	@Test
	void recordedEventEnqueuesOutboxRowAndManualRunDueDeliversSignedWebhook() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		this.mockMvc
			.perform(post("/w/private/notifications/channels").with(csrf())
				.session(session)
				.param("label", "ops")
				.param("url", hookUrl()))
			.andExpect(status().is3xxRedirection());
		NotificationChannel channel = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId).get(0);

		// recording an event enqueues one deduplicated PENDING outbox row (UC-12 step 2);
		// the optional links stay NULL here (FK targets need real rows)
		NotificationEvent event = this.notifications.record(workspaceId, "delivery:5:failed",
				NotificationService.C_DELIVERY_FAILED, null, null, null, "Delivery #5 failed; retry scheduled.");
		NotificationEvent again = this.notifications.record(workspaceId, "delivery:5:failed",
				NotificationService.C_DELIVERY_FAILED, null, null, null, "Delivery #5 failed; retry scheduled.");
		assertThat(again.id()).isEqualTo(event.id()); // deduplicated, not re-saved
		List<NotificationDelivery> outbox = this.notificationDeliveries.findAllByEventIdOrderByIdAsc(event.id());
		assertThat(outbox).hasSize(1);
		assertThat(outbox.get(0).state()).isEqualTo(NotificationDeliveryService.S_PENDING);
		assertThat(outbox.get(0).channelId()).isEqualTo(channel.id());

		// the worker is off in tests; drive the outbox manually
		this.deliveryService.runDue(workspaceId, OffsetDateTime.now());

		assertThat(this.received).hasSize(1);
		Captured request = this.received.get(0);
		assertThat(request.path()).isEqualTo("/hook");
		assertThat(request.eventId()).isEqualTo(event.id().toString());
		assertThat(request.body()).contains("\"eventKey\":\"delivery:5:failed\"")
			.contains("\"category\":\"DELIVERY_FAILED\"")
			.contains("\"summary\":\"Delivery #5 failed; retry scheduled.\"")
			.contains("\"deliveryId\":null");
		// the signature header verifies against the sealed secret (ADR-007 AAD-bound)
		String secret = this.channelService.openSecret("private", channel);
		String expected = "t=" + request.timestamp() + ",v1="
				+ hmacHex(secret, request.timestamp() + "." + request.body());
		assertThat(request.signature()).isEqualTo(expected);

		NotificationDelivery settled = this.notificationDeliveries.findAllByEventIdOrderByIdAsc(event.id()).get(0);
		assertThat(settled.state()).isEqualTo(NotificationDeliveryService.S_SENT);
		assertThat(settled.attempts()).isEqualTo(1);
		assertThat(settled.nextAttemptAt()).isNull();
	}

	@Test
	void testEndpointSendsSignedTestNotificationWithoutPersistingAnEvent() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		this.mockMvc
			.perform(post("/w/private/notifications/channels").with(csrf())
				.session(session)
				.param("label", "ops")
				.param("url", hookUrl()))
			.andExpect(status().is3xxRedirection());
		NotificationChannel channel = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId).get(0);
		long eventsBefore = this.notificationEvents.count();

		this.mockMvc
			.perform(post("/w/private/notifications/channels/" + channel.id() + "/test").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("delivered")))
			.andExpect(flash().attribute("notice", containsString("Private")))
			.andExpect(flash().attribute("notice", containsString(hookUrl())));

		assertThat(this.received).hasSize(1);
		// the test payload names the workspace and the exact recipient (postconditions)
		assertThat(this.received.get(0).body()).contains("\"eventKey\":\"test\"")
			.contains("\"category\":\"TEST\"")
			.contains("\"workspaceLabel\":\"Private\"")
			.contains(hookUrl());
		assertThat(this.notificationEvents.count()).isEqualTo(eventsBefore); // test sends
																				// persist
																				// nothing
	}

	@Test
	void toggleAndDeleteWorkAndForeignWorkspaceIdsFailClosed() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long otherWorkspaceId = workspaceId("socrates-ch");
		this.mockMvc
			.perform(post("/w/private/notifications/channels").with(csrf())
				.session(session)
				.param("label", "ops")
				.param("url", hookUrl()))
			.andExpect(status().is3xxRedirection());
		NotificationChannel channel = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId).get(0);
		this.notifications.record(workspaceId, "delivery:5:failed", NotificationService.C_DELIVERY_FAILED, null, null,
				null, "summary");
		assertThat(this.notificationDeliveries.findAll()).hasSize(1);

		// foreign workspace id fails closed (ADR-013) — no cross-workspace access
		assertThatThrownBy(() -> this.channelService.requireById(otherWorkspaceId, channel.id()))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("Unknown notification channel");
		this.mockMvc
			.perform(post("/w/socrates-ch/notifications/channels/" + channel.id() + "/toggle").with(csrf())
				.session(session))
			.andExpect(flash().attribute("error", containsString("Unknown notification channel")));

		// toggle flips enabled
		this.mockMvc
			.perform(
					post("/w/private/notifications/channels/" + channel.id() + "/toggle").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection());
		assertThat(this.notificationChannels.findById(channel.id()).orElseThrow().enabled()).isFalse();

		// delete removes the channel and its outbox rows (FK-safe order)
		this.mockMvc
			.perform(
					post("/w/private/notifications/channels/" + channel.id() + "/delete").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attributeExists("notice"));
		assertThat(this.notificationChannels.findById(channel.id())).isEmpty();
		assertThat(this.notificationDeliveries.findAll()).isEmpty();
	}

	private Long workspaceId(String slug) {
		return this.workspaces.findBySlug(slug).orElseThrow().id();
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

	private static String hmacHex(String secret, String message) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
		StringBuilder hex = new StringBuilder(digest.length * 2);
		for (byte b : digest) {
			hex.append(Character.forDigit((b >> 4) & 0xF, 16));
			hex.append(Character.forDigit(b & 0xF, 16));
		}
		return hex.toString();
	}

}
