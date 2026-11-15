/**
 * Stateless per-platform strategy adapters receiving explicit workspace, destination and
 * credential context (ADR-001). UC-13 second slice adds direct media upload: payloads may
 * carry approved private images ({@code MediaAttachment}) which upload-capable platforms
 * send as multipart files — never as public URLs (ADR-008).
 */
package com.patbaumgartner.socialcrossposter.platform;
