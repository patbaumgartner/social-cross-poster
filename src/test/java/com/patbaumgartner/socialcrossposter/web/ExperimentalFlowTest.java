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

import com.patbaumgartner.socialcrossposter.accounts.AccountRepository;
import com.patbaumgartner.socialcrossposter.accounts.ConnectionRepository;
import com.patbaumgartner.socialcrossposter.platform.ProbeException;
import com.patbaumgartner.socialcrossposter.platform.ProbeResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-14 end-to-end with the experimental opt-in flag ON
 * ({@code app.experimental.enabled=true}): the accounts page lists the experimental
 * candidates with their permanent "experimental / unofficial" label and an unavailable
 * status (no bridge has passed the ADR-010 feasibility probe), WhatsApp Business is never
 * offered or connectable, an experimental connect attempt fails closed without persisting
 * anything, and official platforms keep working (ADR-010).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.experimental.enabled=true")
class ExperimentalFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ConnectionRepository connections;

	@Autowired
	AccountRepository accounts;

	@MockitoBean
	TelegramBotProbe telegramProbe;

	@BeforeEach
	void resetState() {
		this.accounts.deleteAll();
		this.connections.deleteAll();
	}

	@BeforeEach
	void stubProbe() {
		given(this.telegramProbe.platform()).willReturn("telegram");
		given(this.telegramProbe.supportedModes()).willReturn(Set.of("pasted"));
		given(this.telegramProbe.probe("pasted", "good-token"))
			.willReturn(new ProbeResult("@testbot", "@testbot (id 42)"));
	}

	@Test
	void accountsPageListsExperimentalTargetsWithLabelAndUnavailableStatus() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/private/accounts").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Experimental targets")))
			.andExpect(content().string(containsString("experimental / unofficial")))
			.andExpect(content().string(containsString("whatsapp-status")))
			.andExpect(content().string(containsString("signal")))
			.andExpect(content().string(containsString("unavailable")))
			.andExpect(content().string(containsString("feasibility")))
			.andExpect(content().string(containsString("Candidate kinds")))
			.andExpect(content().string(containsString("story target is blocked")))
			.andExpect(content().string(not(containsString("whatsapp-business"))));
	}

	@Test
	void connectExperimentalTargetWithoutFeasibilityRecordFailsClosed() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "signal")
				.param("mode", "pasted")
				.param("credential", "x"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("feasibility")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void whatsappBusinessStaysRejectedEvenWhenExperimentsEnabled() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "whatsapp-business")
				.param("mode", "pasted")
				.param("credential", "x"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("error", containsString("not supported")));
		assertThat(this.connections.findAll()).isEmpty();
	}

	@Test
	void officialPlatformStillConnectsWhenExperimentsEnabled() throws Exception {
		MockHttpSession session = login();
		this.mockMvc
			.perform(post("/w/private/accounts/connections").with(csrf())
				.session(session)
				.param("platform", "telegram")
				.param("mode", "pasted")
				.param("credential", "good-token"))
			.andExpect(status().is3xxRedirection())
			.andExpect(flash().attribute("notice", containsString("Connected")));
		assertThat(this.connections.findAll()).hasSize(1);
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
