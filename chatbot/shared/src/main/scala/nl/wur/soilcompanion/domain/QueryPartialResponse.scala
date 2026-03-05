/*
 * Copyright (c) 2024-2026 Wageningen University and Research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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

/**
 * Metadata about links extracted from the AI response.
 * Sent after response completion to cleanly separate link data from display content.
 * `wikipediaLinks` contains extracted Wikipedia article URLs.
 * `vocabularyLinks` contains extracted vocabulary/concept URLs.
 * `displayText` is the clean response text without any markdown links.
 */
case class LinksMetadata(
  wikipediaLinks: List[String],
  vocabularyLinks: List[String],
  displayText: String
) derives ReadWriter
