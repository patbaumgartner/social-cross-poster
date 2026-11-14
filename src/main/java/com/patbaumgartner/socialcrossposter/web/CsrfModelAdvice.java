package com.patbaumgartner.socialcrossposter.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes the CSRF token to Thymeleaf so the layout meta tags can feed HTMX requests
 * (htmx does not send a CSRF header by itself).
 */
@ControllerAdvice
public class CsrfModelAdvice {

	@ModelAttribute("_csrf")
	CsrfToken csrfToken(HttpServletRequest request) {
		return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
	}

}
