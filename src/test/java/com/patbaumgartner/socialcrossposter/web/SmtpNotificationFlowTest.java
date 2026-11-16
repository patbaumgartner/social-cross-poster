package com.patbaumgartner.socialcrossposter.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

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
 * UC-12 third slice end-to-end (ADR-004, ADR-007, ADR-013, ADR-015; SSRF rules per arc42
 * section 8 apply to SMTP hosts via {@code OutboundSmtpGuard}): SMTP channel routes
 * require login; an SSRF-rejected host or a wrong port/TLS mapping produces a sanitized
 * flash error and saves nothing; a valid SMTP channel is saved with the password stored
 * only sealed (ADR-007, AAD {@code "<slug>:notify-smtp:<label>"}) and shows on the
 * dashboard with a type badge and target; a recorded event enqueues one deduplicated
 * outbox row, and a manual {@code runDue} delivers a redacted mail to a fake local SMTP
 * relay (127.0.0.1 is allow-listed in tests); the test-notification endpoint sends a mail
 * without persisting an event; foreign workspace ids fail closed. The notify worker is
 * off in tests. The H2 database is shared across tests in this context, so cleanup runs
 * in FK-safe order — notification_delivery references
 * notification_event/notification_channel.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SmtpNotificationFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	WorkspaceService workspaces;

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

	private FakeSmtpServer smtp;

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
		if (this.smtp != null) {
			this.smtp.close();
			this.smtp = null;
		}
	}

	@BeforeEach
	void startSmtpRelay() throws IOException {
		this.smtp = new FakeSmtpServer();
		this.smtp.start();
	}

	private String relayHost() {
		return "127.0.0.1";
	}

	private String relayPort() {
		return String.valueOf(this.smtp.port());
	}

	@Test
	void smtpChannelRoutesRequireLogin() throws Exception {
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.param("label", "mail")
				.param("host", relayHost())
				.param("port", relayPort())
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		this.mockMvc.perform(post("/w/private/notifications/channels/1/test").with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
		assertThat(this.notificationChannels.findAll()).isEmpty();
	}

	@Test
	void ssrfRejectedHostAndWrongPortTlsMappingSaveNothing() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "internal")
				.param("host", "10.0.0.5")
				.param("port", "587")
				.param("tlsMode", "STARTTLS")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/dashboard"))
			.andExpect(flash().attribute("error", containsString("denied")));
		// port 587 requires STARTTLS (canonical mapping)
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "wrong-mapping")
				.param("host", relayHost())
				.param("port", "587")
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com"))
			.andExpect(flash().attribute("error", containsString("587")));
		// unencrypted SMTP off the allow-list is rejected even for a public IP
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "plain-public")
				.param("host", "1.1.1.1")
				.param("port", "25")
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com"))
			.andExpect(flash().attribute("error", containsString("allow-list")));
		// a username requires a password
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "no-pw")
				.param("host", relayHost())
				.param("port", relayPort())
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com")
				.param("username", "relay-user"))
			.andExpect(flash().attribute("error", containsString("password is required")));
		assertThat(this.notificationChannels.findAll()).isEmpty();
		assertThat(this.smtp.received).isEmpty(); // no SMTP connection was attempted
	}

	@Test
	void validSmtpChannelIsSavedWithSealedPasswordAndShownOnDashboard() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "ops-mail")
				.param("host", relayHost())
				.param("port", relayPort())
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com, oncall@example.com")
				.param("username", "relay-user")
				.param("password", "s3cret!")
				.param("categories", "delivery_failed"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/dashboard"))
			.andExpect(flash().attributeExists("notice"));

		List<NotificationChannel> channels = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId);
		assertThat(channels).hasSize(1);
		NotificationChannel channel = channels.get(0);
		assertThat(channel.channelType()).isEqualTo(NotificationChannel.TYPE_SMTP);
		assertThat(channel.isSmtp()).isTrue();
		assertThat(channel.url()).isNull();
		assertThat(channel.smtpHost()).isEqualTo("127.0.0.1");
		assertThat(channel.smtpPort()).isEqualTo(this.smtp.port());
		assertThat(channel.smtpTlsMode()).isEqualTo("NONE");
		assertThat(channel.smtpFrom()).isEqualTo("alerts@example.com");
		assertThat(channel.recipients()).containsExactly("ops@example.com", "oncall@example.com");
		assertThat(channel.smtpUsername()).isEqualTo("relay-user");
		assertThat(channel.categories()).isEqualTo("DELIVERY_FAILED");
		assertThat(channel.enabled()).isTrue();
		// the password is stored only sealed (ADR-007) — never plaintext
		assertThat(channel.secretCipher()).isNotBlank().doesNotContain("s3cret!");
		assertThat(this.channelService.openSecret("private", channel)).isEqualTo("s3cret!");
		// opening under the wrong slug fails (AAD binding)
		assertThatThrownBy(() -> this.channelService.openSecret("socrates-ch", channel))
			.isInstanceOf(IllegalStateException.class);

		this.mockMvc.perform(get("/w/private/dashboard").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("ops-mail")))
			.andExpect(content().string(containsString("SMTP")))
			.andExpect(content().string(containsString("127.0.0.1:" + this.smtp.port() + " (NONE)")));
	}

	@Test
	void recordedEventEnqueuesOutboxRowAndManualRunDueSendsRedactedMail() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "ops-mail")
				.param("host", relayHost())
				.param("port", relayPort())
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com, oncall@example.com")
				.param("username", "relay-user")
				.param("password", "s3cret!"))
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

		// the fake relay saw a full SMTP transaction for both recipients
		List<String> lines = this.smtp.received;
		assertThat(lines).contains("EHLO social-cross-poster", "MAIL FROM:<alerts@example.com>",
				"RCPT TO:<ops@example.com>", "RCPT TO:<oncall@example.com>", "DATA");
		// AUTH LOGIN used the sealed password, base64-encoded on the wire
		assertThat(lines)
			.contains(java.util.Base64.getEncoder().encodeToString("s3cret!".getBytes(StandardCharsets.UTF_8)));
		// the mail body is the redacted payload (UC-12 step 3) — never the password
		String mail = this.smtp.dataBody;
		assertThat(mail).contains("X-SCP-Event-Id: " + event.id())
			.contains("\"eventKey\":\"delivery:5:failed\"")
			.contains("\"category\":\"DELIVERY_FAILED\"")
			.contains("\"summary\":\"Delivery #5 failed; retry scheduled.\"")
			.contains("From: alerts@example.com")
			.doesNotContain("s3cret!");

		NotificationDelivery settled = this.notificationDeliveries.findAllByEventIdOrderByIdAsc(event.id()).get(0);
		assertThat(settled.state()).isEqualTo(NotificationDeliveryService.S_SENT);
		assertThat(settled.attempts()).isEqualTo(1);
		assertThat(settled.nextAttemptAt()).isNull();
	}

	@Test
	void testEndpointSendsMailWithoutPersistingAnEvent() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "ops-mail")
				.param("host", relayHost())
				.param("port", relayPort())
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com"))
			.andExpect(status().is3xxRedirection());
		NotificationChannel channel = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId).get(0);
		long eventsBefore = this.notificationEvents.count();

		this.mockMvc
			.perform(post("/w/private/notifications/channels/" + channel.id() + "/test").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("delivered")))
			.andExpect(flash().attribute("notice", containsString("Private")))
			.andExpect(flash().attribute("notice", containsString(relayHost() + ":" + relayPort())))
			.andExpect(flash().attribute("notice", containsString("ops@example.com")));

		assertThat(this.smtp.received).contains("DATA");
		// the test mail names the workspace and the exact recipient (postconditions)
		assertThat(this.smtp.dataBody).contains("\"eventKey\":\"test\"")
			.contains("\"category\":\"TEST\"")
			.contains("\"workspaceLabel\":\"Private\"")
			.contains(relayHost() + ":" + relayPort())
			.contains("ops@example.com");
		assertThat(this.notificationEvents.count()).isEqualTo(eventsBefore); // test sends
																				// persist
																				// nothing
	}

	@Test
	void foreignWorkspaceIdsFailClosed() throws Exception {
		MockHttpSession session = login();
		Long workspaceId = workspaceId("private");
		Long otherWorkspaceId = workspaceId("socrates-ch");
		this.mockMvc
			.perform(post("/w/private/notifications/smtp-channels").with(csrf())
				.session(session)
				.param("label", "ops-mail")
				.param("host", relayHost())
				.param("port", relayPort())
				.param("tlsMode", "NONE")
				.param("from", "alerts@example.com")
				.param("to", "ops@example.com"))
			.andExpect(status().is3xxRedirection());
		NotificationChannel channel = this.notificationChannels.findAllByWorkspaceIdOrderByIdAsc(workspaceId).get(0);

		// foreign workspace id fails closed (ADR-013) — no cross-workspace access
		assertThatThrownBy(() -> this.channelService.requireById(otherWorkspaceId, channel.id()))
			.isInstanceOf(NotificationException.class)
			.hasMessageContaining("Unknown notification channel");
		this.mockMvc
			.perform(post("/w/socrates-ch/notifications/channels/" + channel.id() + "/toggle").with(csrf())
				.session(session))
			.andExpect(flash().attribute("error", containsString("Unknown notification channel")));
		this.mockMvc
			.perform(post("/w/socrates-ch/notifications/channels/" + channel.id() + "/test").with(csrf())
				.session(session))
			.andExpect(flash().attribute("error", containsString("Unknown notification channel")));
		// the channel is untouched and no mail was sent
		assertThat(this.notificationChannels.findById(channel.id()).orElseThrow().enabled()).isTrue();
		assertThat(this.smtp.received).isEmpty();
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

	/**
	 * Minimal fake SMTP relay on 127.0.0.1: accepts AUTH LOGIN, captures the DATA body.
	 */
	private static final class FakeSmtpServer implements AutoCloseable {

		private final ServerSocket serverSocket;

		private Thread thread;

		final List<String> received = new CopyOnWriteArrayList<>();

		volatile String dataBody = "";

		FakeSmtpServer() throws IOException {
			this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
		}

		int port() {
			return this.serverSocket.getLocalPort();
		}

		void start() {
			this.thread = new Thread(this::serve);
			this.thread.setDaemon(true);
			this.thread.start();
		}

		private void serve() {
			try (Socket socket = this.serverSocket.accept()) {
				InputStream in = socket.getInputStream();
				OutputStream out = socket.getOutputStream();
				write(out, "220 fake-relay ESMTP ready\r\n");
				boolean inData = false;
				int authStage = 0; // 0 = none, 1 = expect base64 username, 2 = expect
									// base64 password
				StringBuilder body = new StringBuilder();
				String line;
				while ((line = readLine(in)) != null) {
					if (inData) {
						if (".".equals(line)) {
							this.dataBody = body.toString();
							inData = false;
							write(out, "250 2.0.0 Ok: queued\r\n");
						}
						else {
							body.append(line).append('\n');
						}
						continue;
					}
					this.received.add(line);
					if (authStage == 1) {
						authStage = 2;
						write(out, "334 UGFzc3dvcmQ6\r\n"); // "Password:" challenge
					}
					else if (authStage == 2) {
						authStage = 0;
						write(out, "235 2.7.0 Authentication successful\r\n");
					}
					else if (line.startsWith("EHLO")) {
						write(out, "250-fake-relay\r\n250-8BITMIME\r\n250 AUTH LOGIN\r\n");
					}
					else if (line.startsWith("AUTH LOGIN")) {
						authStage = 1;
						write(out, "334 VXNlcm5hbWU6\r\n"); // "Username:" challenge
					}
					else if (line.startsWith("DATA")) {
						write(out, "354 End data with <CR><LF>.<CR><LF>\r\n");
						inData = true;
					}
					else if (line.startsWith("QUIT")) {
						write(out, "221 Bye\r\n");
						break;
					}
					else {
						write(out, "250 OK\r\n"); // MAIL FROM / RCPT TO
					}
				}
			}
			catch (IOException ignored) {
				// client went away — assertions run on what was received
			}
		}

		private static void write(OutputStream out, String s) throws IOException {
			out.write(s.getBytes(StandardCharsets.ISO_8859_1));
			out.flush();
		}

		private static String readLine(InputStream in) throws IOException {
			StringBuilder sb = new StringBuilder(80);
			int c;
			while ((c = in.read()) != -1) {
				if (c == '\n') {
					break;
				}
				sb.append((char) c);
			}
			if (c == -1 && sb.length() == 0) {
				return null;
			}
			int len = sb.length();
			if (len > 0 && sb.charAt(len - 1) == '\r') {
				sb.setLength(len - 1);
			}
			return sb.toString();
		}

		@Override
		public void close() throws IOException {
			this.serverSocket.close();
		}

	}

}
