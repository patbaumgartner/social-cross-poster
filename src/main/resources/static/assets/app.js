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

// Mention autocomplete (UC-03, ADR-003): clicking a result inserts the neutral
// local contact token into the composer field at the cursor position. External
// file only, so this stays CSP-safe under script-src 'self'.
document.addEventListener("click", (event) => {
  const button = event.target.closest("[data-mention-token]");
  if (!button) return;
  const composer = document.querySelector("[data-mention-composer]");
  if (!composer) return;
  const token = button.getAttribute("data-mention-token");
  const start = composer.selectionStart ?? composer.value.length;
  const end = composer.selectionEnd ?? start;
  composer.value = composer.value.slice(0, start) + token + " " + composer.value.slice(end);
  composer.dispatchEvent(new Event("input", { bubbles: true }));
  composer.focus();
});
