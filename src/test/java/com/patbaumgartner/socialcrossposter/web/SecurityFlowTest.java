package com.patbaumgartner.socialcrossposter.web;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UC-01 end-to-end through the security filter chain: login, workspace picker, workspace
 * dashboard, logout, CSRF and throttling (ADR-005, ADR-013).
 */
@SpringBootTest
@AutoConfigureMockMvc
class SecurityFlowTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	LoginAttemptService loginAttempts;

	@org.junit.jupiter.api.BeforeEach
	void resetThrottle() {
		this.loginAttempts.reset();
	}

	@Test
	void unauthenticatedRequestsRedirectToLogin() throws Exception {
		this.mockMvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
	}

	@Test
	void loginPageIsPublic() throws Exception {
		this.mockMvc.perform(get("/login"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Sign in")));
	}

	@Test
	void staticAssetsArePublic() throws Exception {
		this.mockMvc.perform(get("/assets/htmx-4.0.1.min.js")).andExpect(status().isOk());
	}

	@Test
	void wrongCredentialsShowGenericError() throws Exception {
		this.mockMvc.perform(post("/login").with(csrf()).param("username", "owner").param("password", "wrong"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?error"))
			.andExpect(unauthenticated());
	}

	@Test
	void loginPickerDashboardLogoutFlow() throws Exception {
		MvcResult login = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"))
			.andExpect(authenticated())
			.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession();

		this.mockMvc.perform(get("/").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Private")))
			.andExpect(content().string(containsString("socrates-ch")))
			.andExpect(content().string(containsString("Voxxed Days Zurich")))
			.andExpect(content().string(containsString("Voxxed Days Ticino")));

		this.mockMvc.perform(get("/w/private/dashboard").session(session))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("Private")));

		this.mockMvc.perform(post("/logout").with(csrf()).session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?logout"));

		this.mockMvc.perform(get("/").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void unknownWorkspaceSlugReturnsToPicker() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(get("/w/does-not-exist/dashboard").session(session))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/"));
	}

	@Test
	void logoutRequiresCsrfToken() throws Exception {
		MockHttpSession session = login();
		this.mockMvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
	}

	@Test
	void loginIsThrottledAfterFiveFailures() throws Exception {
		for (int i = 0; i < 5; i++) {
			this.mockMvc.perform(post("/login").with(csrf()).param("username", "owner").param("password", "wrong"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login?error"));
		}
		// Even valid credentials are rejected while the pair is throttled.
		this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?locked"))
			.andExpect(unauthenticated());
	}

	private MockHttpSession login() throws Exception {
		MvcResult result = this.mockMvc.perform(formLogin().user("owner").password("test-password"))
			.andExpect(authenticated())
			.andReturn();
		return (MockHttpSession) result.getRequest().getSession();
	}

}
