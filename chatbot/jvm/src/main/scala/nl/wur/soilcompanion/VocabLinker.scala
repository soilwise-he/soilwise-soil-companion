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

package nl.wur.soilcompanion

import scala.util.matching.Regex
import scala.io.Source

/**
 * Utility for automatically linking vocabulary terms to their definitions.
 *
 * This class processes text responses and identifies terms from the SoilWise vocabulary,
 * then adds hyperlinks to their definitions in the vocabulary browser.
 */
object VocabLinker {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // Case class to represent a vocabulary term
  private case class VocabTerm(
    id: String,
    prefLabel: String,
    altLabels: List[String]
  )

  // Lazy-loaded vocabulary terms
  private lazy val vocabTerms: List[VocabTerm] = loadVocabulary()

  /**
   * Load vocabulary from CSV file.
   *
   * @return List of vocabulary terms
   */
  private def loadVocabulary(): List[VocabTerm] = {
    try {
      val vocabFile = Config.vocabConfig.vocabFilePath
      logger.debug(s"Loading vocabulary from: $vocabFile")

      val source = Source.fromFile(vocabFile)
      val lines = source.getLines().toList
      source.close()

      if (lines.isEmpty) {
        logger.warn(s"Vocabulary file is empty: $vocabFile")
        return List.empty
      }

      // Skip header line
      val dataLines = lines.drop(1)

      val terms = dataLines.flatMap { line =>
        parseCsvLine(line) match {
          case Some((id, prefLabel, altLabel)) =>
            // Parse altLabel as comma-separated list
            val altLabels = if (altLabel.trim.nonEmpty) {
              altLabel.split(";").map(_.trim).filter(_.nonEmpty).toList
            } else {
              List.empty
            }

            Some(VocabTerm(id, prefLabel, altLabels))
          case None =>
            logger.warn(s"Failed to parse vocabulary line: $line")
            None
        }
      }

      logger.debug(s"Loaded ${terms.size} vocabulary terms")
      terms
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to load vocabulary file", e)
        List.empty
    }
  }

  /**
   * Parse a CSV line, handling quoted fields.
   * Returns (ID, prefLabel, altLabel).
   */
  private def parseCsvLine(line: String): Option[(String, String, String)] = {
    try {
      val parts = parseCsvFields(line)
      if (parts.length >= 3) {
        val id = parts(0).trim
        val prefLabel = parts(1).trim
        val altLabel = parts(2).trim

        if (id.nonEmpty && prefLabel.nonEmpty) {
          Some((id, prefLabel, altLabel))
        } else {
          None
        }
      } else {
        None
      }
    } catch {
      case e: Throwable =>
        logger.warn(s"Error parsing CSV line: ${e.getMessage}")
        None
    }
  }

  /**
   * Parse CSV fields, handling quoted fields with commas.
   */
  private def parseCsvFields(line: String): Array[String] = {
    val fields = scala.collection.mutable.ArrayBuffer[String]()
    var currentField = new StringBuilder
    var inQuotes = false
    var i = 0

    while (i < line.length) {
      val char = line.charAt(i)

      if (char == '"') {
        if (inQuotes && i + 1 < line.length && line.charAt(i + 1) == '"') {
          // Escaped quote
          currentField.append('"')
          i += 1
        } else {
          // Toggle quote state
          inQuotes = !inQuotes
        }
      } else if (char == ',' && !inQuotes) {
        // Field separator
        fields += currentField.toString()
        currentField = new StringBuilder
      } else {
        currentField.append(char)
      }

      i += 1
    }

    // Add last field
    fields += currentField.toString()
    fields.toArray
  }

  /**
   * Build vocabulary URL for a term ID.
   * If termId is already a full URL (starts with http), return it as-is.
   * Otherwise, build the URL using the base URL and /id/ path.
   */
  private def buildVocabUrl(termId: String): String = {
    if (termId.startsWith("http://") || termId.startsWith("https://")) {
      // termId is already a full URL, return as-is
      termId
    } else {
      // termId is a simple identifier, build the full URL
      val encoded = java.net.URLEncoder.encode(termId, "UTF-8")
      s"${Config.vocabConfig.baseUrl}/id/$encoded"
    }
  }

  /**
   * Check if a position in the text is inside a markdown link.
   * A markdown link has the format: [text](url)
   */
  private def isInsideMarkdownLink(text: String, position: Int): Boolean = {
    // Find the nearest [ before the position
    val openBracket = text.lastIndexOf('[', position - 1)
    if (openBracket == -1) return false

    // Find the nearest ] after the open bracket
    val closeBracket = text.indexOf(']', openBracket)
    if (closeBracket == -1 || closeBracket < position) return false

    // Check if there's a ( immediately after the ]
    if (closeBracket + 1 < text.length && text.charAt(closeBracket + 1) == '(') {
      // Find the closing )
      val closeParen = text.indexOf(')', closeBracket + 2)
      if (closeParen != -1) {
        // Position is inside a markdown link if it's between [ and ]
        return position > openBracket && position < closeBracket
      }
    }

    false
  }

  /**
   * Strip all markdown links from text, keeping only the link text.
   * E.g., "[Soil health](https://example.com)" becomes "Soil health"
   *
   * @param text The text containing markdown links
   * @return The text with all markdown links removed, keeping only link text
   */
  def stripAllLinks(text: String): String = {
    val markdownLinkPattern = """\[([^\]]+)\]\([^\)]+\)""".r
    markdownLinkPattern.replaceAllIn(text, m => m.group(1)) // Keep only link text
  }

  /**
   * Extract vocabulary links from text by identifying recognized vocabulary terms.
   * Returns a list of unique vocabulary URLs without modifying the text.
   *
   * @param text The text to analyze
   * @return List of vocabulary URLs
   */
  def extractVocabLinks(text: String): List[String] = {
    if (!Config.vocabConfig.autoLinkTerms) {
      return List.empty
    }

    try {
      // First strip any existing markdown to work with plain text
      val plainText = stripAllLinks(text)

      val minLength = Config.vocabConfig.minTermLength

      // Find all vocabulary terms that appear in the text
      val candidates = findCandidateTerms(plainText, minLength)

      if (candidates.isEmpty) {
        return List.empty
      }

      logger.debug(s"Found ${candidates.size} vocabulary term candidates: ${candidates.map(_._2).mkString(", ")}")

      // Build list of unique vocabulary URLs
      val urls = candidates.map { case (term, matchedLabel) =>
        buildVocabUrl(term.id)
      }.distinct

      urls
    } catch {
      case e: Throwable =>
        logger.error("Failed to extract vocabulary links from response", e)
        List.empty
    }
  }

  /**
   * Process response text and add vocabulary links to recognized terms.
   * Only processes if auto-linking is enabled in config.
   *
   * @param text The response text to process
   * @return The text with vocabulary links added, or original text if disabled
   */
  def addVocabLinks(text: String): String = {
    if (!Config.vocabConfig.autoLinkTerms) {
      return text
    }

    try {
      val minLength = Config.vocabConfig.minTermLength

      // Find all vocabulary terms that appear in the text
      val candidates = findCandidateTerms(text, minLength)

      if (candidates.isEmpty) {
        return text
      }

      logger.debug(s"Found ${candidates.size} vocabulary term candidates: ${candidates.map(_._2).mkString(", ")}")

      // For each candidate, add a vocabulary link
      var result = text
      val linkedTerms = scala.collection.mutable.Set[String]() // Track terms we've already linked

      candidates.foreach { case (term, matchedLabel) =>
        val lowerLabel = matchedLabel.toLowerCase

        // Skip if we've already linked this term (case-insensitive)
        if (!linkedTerms.contains(lowerLabel)) {
          val vocabUrl = buildVocabUrl(term.id)

          // Only link the first occurrence of each term to avoid clutter
          // Use case-insensitive matching
          val pattern = s"(?i)\\b${Regex.quote(matchedLabel)}\\b".r

          // Find first match
          val firstValidMatch = pattern.findFirstMatchIn(result)

          firstValidMatch.foreach { m =>
            val matched = m.matched
            // Replace only the first occurrence with a markdown link, preserving original case
            val before = result.substring(0, m.start)
            val after = result.substring(m.end)
            result = before + s"[$matched]($vocabUrl)" + after
            linkedTerms.add(lowerLabel)
            logger.debug(s"Added vocabulary link for term: $matched -> $vocabUrl")
          }
        }
      }

      result
    } catch {
      case e: Throwable =>
        logger.error("Failed to add vocabulary links to response", e)
        text // Return original text on error
    }
  }

  /**
   * Find candidate vocabulary terms in the text that should be linked.
   *
   * @param text The text to analyze
   * @param minLength Minimum length for terms to consider
   * @return List of (VocabTerm, matchedLabel) tuples
   */
  private def findCandidateTerms(text: String, minLength: Int): List[(VocabTerm, String)] = {
    val lowerText = text.toLowerCase

    val matches = vocabTerms.flatMap { term =>
      // Check prefLabel
      val prefLabelMatch = if (term.prefLabel.length >= minLength && lowerText.contains(term.prefLabel.toLowerCase)) {
        Some((term, term.prefLabel))
      } else {
        None
      }

      // Check altLabels
      val altLabelMatches = term.altLabels.flatMap { altLabel =>
        if (altLabel.length >= minLength && lowerText.contains(altLabel.toLowerCase)) {
          Some((term, altLabel))
        } else {
          None
        }
      }

      prefLabelMatch.toList ++ altLabelMatches
    }

    // Sort by term length (longer terms first to avoid partial matches)
    // and limit to avoid too many links
    matches
      .sortBy { case (_, label) => -label.length }
      .take(Config.vocabConfig.maxLinksPerResponse)
  }
}
