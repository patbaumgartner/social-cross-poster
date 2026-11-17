package com.patbaumgartner.socialcrossposter.web;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.platform.TelegramBotProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
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
 * UC-14 fifth slice end-to-end with the opt-in flag ON and a feasibility record for
 * {@code signal} but no wired bridge adapter: the protected setup page at
 * {@code /w/{slug}/experiments} requires the owner login, lists every experimental target
 * with the permanent label and its precise unavailability reason, renders no pairing
 * form, and the pair action fails closed without persisting anything (UC-14 alternative
 * flow 1 — no pairing endpoint or wizard is fabricated).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "app.experimental.enabled=true",
		"app.experimental.feasibility.signal=2026-11-17 test probe record" })
class ExperimentFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ConnectionRepository connections;

	@MockitoBean
	TelegramBotProbe telegramProbe;

	@BeforeEach
	void resetState() {
		this.connections.deleteAll();
	}

	@BeforeEach
	void stubProbe() {
		given(this.telegramProbe.platform()).willReturn("telegram");
		given(this.telegramProbe.supportedModes()).willReturn(Set.of("pasted"));
	}

	@Test
	void setupPageRequiresLogin() throws Exception {
		this.mockMvc.perform(get("/w/private/experiments"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void setupPageListsTargetsWithLabelAndReasonsButNoPairingForm() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/private/experiments").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Protected setup path")))
			.andExpect(content().string(containsString("experimental / unofficial")))
			.andExpect(content().string(containsString("whatsapp-status")))
			.andExpect(content().string(containsString("signal")))
			.andExpect(content().string(containsString("2026-11-17 test probe record")))
			// whatsapp-status has no record, signal has a record but no wired adapter
			.andExpect(content().string(containsString("feasibility")))
			.andExpect(content().string(containsString("no bridge adapter")))
			// alternative flow 1: no pairing form is rendered while nothing is available
			.andExpect(content().string(not(containsString("Start protected pairing"))))
			.andExpect(content().string(not(containsString("whatsapp-business"))));
	}

	@Test
	void pairSignalWithoutWiredAdapterFailsClosedAndPersistsNothing() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/private/experiments/signal/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/w/private/experiments"))
			.andExpect(flash().attribute("error", containsString("no bridge adapter")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void pairWhatsappStatusWithoutFeasibilityRecordFailsClosed() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/private/experiments/whatsapp-status/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("feasibility")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void pairExcludedWhatsappBusinessFailsClosed() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/private/experiments/whatsapp-business/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("not supported")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void pairOfficialPlatformHasNoPairingPath() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/private/experiments/telegram/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("Only experimental targets")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void unknownWorkspaceFailsClosed() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/nope/experiments").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
		this.mockMvc.perform(post("/w/nope/experiments/signal/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
