package nl.wur.soilcompanion.domain

import upickle.default.*

case class QueryPartialResponse(content: String)
  derives ReadWriter

/**
 * Lightweight event message sent over WebSocket to inform the UI about the assistant phase.
 * `event` is a short code like: received, thinking, retrieving_context, generating, done, error.
 * `detail` is optional human-friendly text to show in the footer or UI.
 * `questionId` optionally carries the server-generated question ID so the UI can correlate
 * feedback and logs. It may be included on early lifecycle events (e.g., "received" or
 * "thinking").
 */
case class QueryEvent(event: String, detail: Option[String] = None, questionId: Option[String] = None)
  derives ReadWriter
