// CSP-safe HTMX setup (ADR-002): eval-based features stay disabled, and the
// CSRF token from the layout meta tags is sent as a header on every HTMX request.
if (window.htmx) {
  htmx.config.allowEval = false;
  htmx.config.allowScriptTags = false;
}
document.addEventListener("htmx:configRequest", (event) => {
  const token = document.querySelector('meta[name="_csrf"]');
  const header = document.querySelector('meta[name="_csrf_header"]');
  if (token && header) {
    event.detail.headers[header.content] = token.content;
  }
});
