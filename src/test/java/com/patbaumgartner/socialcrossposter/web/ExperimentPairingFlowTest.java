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
import com.patbaumgartner.socialcrossposter.platform.PairingStart;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.platform.TelegramBotProbe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-14 fifth slice with the opt-in flag ON, a feasibility record for {@code signal} and
 * a wired bridge adapter (mocked probe reporting platform {@code signal}): the setup page
 * offers the protected pairing form only for that available target, and the pair action
 * either fails closed (adapter without pairing support) or returns sanitized instructions
 * — persisting nothing yet, because session/credential storage lands with a real bridge.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = { "app.experimental.enabled=true",
		"app.experimental.feasibility.signal=2026-11-17 test probe record" })
class ExperimentPairingFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ConnectionRepository connections;

	@MockitoBean
	TelegramBotProbe signalProbe;

	@BeforeEach
	void resetState() {
		this.connections.deleteAll();
		given(this.signalProbe.platform()).willReturn("signal");
		given(this.signalProbe.supportedModes()).willReturn(Set.of("bridge"));
	}

	@Test
	void setupPageOffersPairingFormOnlyForTheAvailableTarget() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/private/experiments").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("experimental / unofficial")))
			.andExpect(content().string(containsString("available — opt in")))
			// whatsapp-status still has no record -> no pairing form for it
			.andExpect(content().string(containsString("feasibility")))
			.andExpect((MvcResult r) -> assertThat(occurrences(r, "Start protected pairing")).isEqualTo(1));
	}

	@Test
	void pairWithAdapterWithoutPairingSupportStillFailsClosed() throws Exception {
		given(this.signalProbe.startPairing())
			.willThrow(new ProbeException("This platform adapter has no locally protected pairing path (ADR-010)."));
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/private/experiments/signal/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("no locally protected pairing path")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void pairReturnsSanitizedInstructionsAndPersistsNothingYet() throws Exception {
		given(this.signalProbe.startPairing())
			.willReturn(new PairingStart("signal", "Scan the link code shown by the bridge."));
		MockHttpSession session = login();
		this.mockMvc.perform(post("/w/private/experiments/signal/pair").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("link code")));
		// UC-14 step 2: the path is protected and fail-closed; session/credential
		// persistence lands with a real bridge adapter, so nothing is saved here
		assertThat(this.connections.findAll()).isEmpty();
	}

	private static int occurrences(MvcResult result, String needle) throws Exception {
		String body = result.getResponse().getContentAsString();
		int count = 0;
		int idx = body.indexOf(needle);
		while (idx >= 0) {
			count++;
			idx = body.indexOf(needle, idx + needle.length());
		}
		return count;
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
