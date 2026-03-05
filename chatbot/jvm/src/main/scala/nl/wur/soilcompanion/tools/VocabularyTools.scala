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

package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool, ToolSpecification, ToolSpecifications}
import nl.wur.soilcompanion.Config
import upickle.default.*

import scala.util.{Failure, Success, Try}

/**
 * Tools to query the SoilWise vocabulary SPARQL endpoint for concept information.
 *
 * This service retrieves broader concepts, narrower concepts, and exact matches
 * from the SKOS vocabulary to help users explore related terms.
 *
 * Configuration is loaded from application.conf under vocabulary-tools-config.
 */
class VocabularyTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val config = Config.vocabularyToolsConfig

  /**
   * Case class representing a vocabulary concept with related terms.
   */
  case class VocabConcept(
    uri: String,
    prefLabel: Option[String],
    definition: Option[String],
    broader: List[ConceptRef],
    narrower: List[ConceptRef],
    related: List[ConceptRef],
    exactMatch: List[ConceptRef]
  ) derives ReadWriter

  case class ConceptRef(
    uri: String,
    prefLabel: Option[String],
    definition: Option[String] = None
  ) derives ReadWriter

  /**
   * Builds a SPARQL query to retrieve concept information.
   * Uses FILTER to ensure at least the concept exists, and tries multiple label predicates.
   * Also attempts to retrieve definitions for broader, narrower, and related concepts.
   */
  private def buildConceptQuery(conceptUri: String): String = {
    s"""
       |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
       |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
       |PREFIX dct: <http://purl.org/dc/terms/>
       |
       |SELECT DISTINCT ?prefLabel ?definition ?broader ?broaderLabel ?broaderDef ?narrower ?narrowerLabel ?narrowerDef ?exactMatch ?exactMatchLabel ?related ?relatedLabel ?relatedDef
       |WHERE {
       |  # The concept must exist as a subject and be a SKOS concept
       |  <$conceptUri> ?p ?o .
       |  <$conceptUri> a skos:Concept .
       |
       |  # Try to get preferred label
       |  OPTIONAL {
       |    <$conceptUri> skos:prefLabel ?prefLabel .
       |    FILTER(langMatches(lang(?prefLabel), "en") || lang(?prefLabel) = "")
       |  }
       |
       |  # Try to get definition
       |  OPTIONAL {
       |    <$conceptUri> skos:definition ?definition .
       |    FILTER(langMatches(lang(?definition), "en") || lang(?definition) = "")
       |  }
       |
       |  # Get broader concepts with their definitions
       |  OPTIONAL {
       |    ?broader a skos:Concept .
       |    <$conceptUri> skos:broader ?broader .
       |    OPTIONAL {
       |      ?broader skos:prefLabel ?broaderLabel .
       |      FILTER(langMatches(lang(?broaderLabel), "en") || lang(?broaderLabel) = "")
       |    }
       |    OPTIONAL {
       |      ?broader skos:definition ?broaderDef .
       |      FILTER(langMatches(lang(?broaderDef), "en") || lang(?broaderDef) = "")
       |    }
       |  }
       |
       |  # Get narrower concepts with their definitions
       |  OPTIONAL {
       |    ?narrower a skos:Concept .
       |    <$conceptUri> skos:narrower ?narrower .
       |    OPTIONAL {
       |      ?narrower skos:prefLabel ?narrowerLabel .
       |      FILTER(langMatches(lang(?narrowerLabel), "en") || lang(?narrowerLabel) = "")
       |    }
       |    OPTIONAL {
       |      ?narrower skos:definition ?narrowerDef .
       |      FILTER(langMatches(lang(?narrowerDef), "en") || lang(?narrowerDef) = "")
       |    }
       |  }
       |
       |  # Get exact matches
       |  OPTIONAL {
       |    ?exactMatch a skos:Concept .
       |    <$conceptUri> skos:exactMatch ?exactMatch .
       |    OPTIONAL {
       |      ?exactMatch skos:prefLabel ?exactMatchLabel .
       |      FILTER(langMatches(lang(?exactMatchLabel), "en") || lang(?exactMatchLabel) = "")
       |    }
       |  }
       |
       |  # Get related concepts with their definitions
       |  OPTIONAL {
       |    ?related a skos:Concept .
       |    <$conceptUri> skos:related ?related .
       |    OPTIONAL {
       |      ?related skos:prefLabel ?relatedLabel .
       |      FILTER(langMatches(lang(?relatedLabel), "en") || lang(?relatedLabel) = "")
       |    }
       |    OPTIONAL {
       |      ?related skos:definition ?relatedDef .
       |      FILTER(langMatches(lang(?relatedDef), "en") || lang(?relatedDef) = "")
       |    }
       |  }
       |}
       |LIMIT ${config.maxResults}
       |""".stripMargin
  }

  /**
   * Executes a SPARQL query against the SoilWise repository.
   */
  private def executeSparqlQuery(query: String): Try[String] = {
    import requests.*
    Try {
      val response = post(
        config.sparqlEndpoint,
        data = Map("query" -> query),
        headers = Map(
          "Accept" -> "application/sparql-results+json",
          "Content-Type" -> "application/x-www-form-urlencoded",
          "User-Agent" -> config.userAgent
        ),
        readTimeout = config.readTimeoutMs,
        connectTimeout = config.connectTimeoutMs
      )

      if (response.statusCode == 200) {
        response.text()
      } else {
        throw new RuntimeException(s"SPARQL query failed with status ${response.statusCode}")
      }
    }
  }

  /**
   * Parses SPARQL JSON results into a VocabConcept.
   */
  private def parseSparqlResults(jsonStr: String, conceptUri: String): Option[VocabConcept] = {
    try {
      val js = ujson.read(jsonStr)
      val bindings = js("results")("bindings").arr

      if (bindings.isEmpty) {
        logger.warn(s"No bindings found in SPARQL results for $conceptUri")
        return None
      }

      var prefLabel: Option[String] = None
      var definition: Option[String] = None
      val broaderSet = scala.collection.mutable.Map[String, (Option[String], Option[String])]()
      val narrowerSet = scala.collection.mutable.Map[String, (Option[String], Option[String])]()
      val relatedSet = scala.collection.mutable.Map[String, (Option[String], Option[String])]()
      val exactMatchSet = scala.collection.mutable.Map[String, (Option[String], Option[String])]()

      bindings.foreach { binding =>
        val b = binding.obj

        // Extract prefLabel (should be same across all rows)
        if (prefLabel.isEmpty) {
          prefLabel = b.get("prefLabel").flatMap(_.obj.get("value")).map(_.str)
        }

        // Extract definition (should be same across all rows)
        if (definition.isEmpty) {
          definition = b.get("definition").flatMap(_.obj.get("value")).map(_.str)
        }

        // Collect broader concepts with definitions
        b.get("broader").flatMap(_.obj.get("value")).map(_.str).foreach { broaderUri =>
          val label = b.get("broaderLabel").flatMap(_.obj.get("value")).map(_.str)
          val defn = b.get("broaderDef").flatMap(_.obj.get("value")).map(_.str)
          // Only update if we don't have this URI or if we have a better definition now
          if (!broaderSet.contains(broaderUri) || (defn.isDefined && broaderSet(broaderUri)._2.isEmpty)) {
            broaderSet.put(broaderUri, (label, defn))
          }
        }

        // Collect narrower concepts with definitions
        b.get("narrower").flatMap(_.obj.get("value")).map(_.str).foreach { narrowerUri =>
          val label = b.get("narrowerLabel").flatMap(_.obj.get("value")).map(_.str)
          val defn = b.get("narrowerDef").flatMap(_.obj.get("value")).map(_.str)
          if (!narrowerSet.contains(narrowerUri) || (defn.isDefined && narrowerSet(narrowerUri)._2.isEmpty)) {
            narrowerSet.put(narrowerUri, (label, defn))
          }
        }

        // Collect exact matches with definitions (use exactMatch definition if available)
        b.get("exactMatch").flatMap(_.obj.get("value")).map(_.str).foreach { matchUri =>
          val label = b.get("exactMatchLabel").flatMap(_.obj.get("value")).map(_.str)
          // For exactMatch, we don't have a separate definition field in the query yet
          // but we could add it in the future
          exactMatchSet.put(matchUri, (label, None))
        }

        // Collect related concepts with definitions
        b.get("related").flatMap(_.obj.get("value")).map(_.str).foreach { relatedUri =>
          val label = b.get("relatedLabel").flatMap(_.obj.get("value")).map(_.str)
          val defn = b.get("relatedDef").flatMap(_.obj.get("value")).map(_.str)
          if (!relatedSet.contains(relatedUri) || (defn.isDefined && relatedSet(relatedUri)._2.isEmpty)) {
            relatedSet.put(relatedUri, (label, defn))
          }
        }
      }

      Some(VocabConcept(
        uri = conceptUri,
        prefLabel = prefLabel,
        definition = definition,
        broader = broaderSet.map { case (uri, (label, defn)) => ConceptRef(uri, label, defn) }.toList,
        narrower = narrowerSet.map { case (uri, (label, defn)) => ConceptRef(uri, label, defn) }.toList,
        related = relatedSet.map { case (uri, (label, defn)) => ConceptRef(uri, label, defn) }.toList,
        exactMatch = exactMatchSet.map { case (uri, (label, defn)) => ConceptRef(uri, label, defn) }.toList
      ))
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to parse SPARQL results", e)
        None
    }
  }

  /**
   * Extracts the actual concept URI from a vocabulary service redirect URI.
   * Example: https://voc.soilwise-he.containers.wur.nl/id/https%3A%2F%2F...
   * becomes: https://...
   */
  private def extractActualUri(uri: String): String = {
    // Check if this is a vocabulary service redirect URL
    if (uri.contains(config.redirectUrlPattern) && uri.contains("/id/")) {
      val parts = uri.split("/id/")
      if (parts.length > 1) {
        // Decode the actual URI
        val encoded = parts(1)
        try {
          java.net.URLDecoder.decode(encoded, "UTF-8")
        } catch {
          case _: Throwable =>
            logger.warn(s"Failed to decode URI: $encoded")
            uri
        }
      } else {
        uri
      }
    } else {
      uri
    }
  }

  /**
   * Replaces the LLM-friendly prefix with the actual vocabulary prefix.
   * More robust: extracts the last path element from any vocab-like URI and uses it.
   * Examples:
   *   http://soilwise.eu/vocab/clay -> https://soilwise-he.github.io/soil-health#Clay
   *   http://soilwise.org/vocab/clay -> https://soilwise-he.github.io/soil-health#Clay
   *   http://soilwise.eu/vocab/soil-health -> https://soilwise-he.github.io/soil-health#Soil-health
   */
  private def replacePrefixIfNeeded(uri: String): String = {
    // Check if this looks like an LLM-generated vocab URI (contains "soilwise" and "vocab")
    if (uri.contains("soilwise") && uri.contains("/vocab/")) {
      // Extract the last path element after /vocab/
      val lastSlashIndex = uri.lastIndexOf('/')
      if (lastSlashIndex > 0 && lastSlashIndex < uri.length - 1) {
        val localName = uri.substring(lastSlashIndex + 1)
        // Capitalize first letter of the local name for proper IRI format
        val capitalizedLocalName = if (localName.nonEmpty) {
          localName.head.toUpper + localName.tail
        } else {
          localName
        }
        val result = config.actualPrefix + capitalizedLocalName
        logger.trace(s"Replaced IRI prefix: $uri -> $result")
        result
      } else {
        logger.warn(s"Could not extract local name from URI: $uri")
        uri
      }
    } else {
      uri
    }
  }

  /**
   * Retrieves vocabulary concept information from the SPARQL endpoint.
   *
   * http://soilwise.eu/vocab/clay
   * https://soilwise-he.github.io/soil-health#Clay
   *
   * @param conceptUri The URI of the vocabulary concept to look up (can be redirect URI)
   * @return JSON string with concept information including broader, narrower, and exact match concepts
   */
  @Tool(Array("Retrieve detailed information about a SoilWise vocabulary concept including related terms"))
  def getVocabConceptInfo(
    @P("The URI of the vocabulary concept to look up") conceptUri: String
  ): String = {
    logger.trace(s"Looking up vocabulary concept: $conceptUri")

    // First, replace LLM prefix with actual prefix if needed
    val prefixReplacedUri = replacePrefixIfNeeded(conceptUri)

    // Then extract the actual concept URI from redirect URL if needed
    val actualUri = extractActualUri(prefixReplacedUri)
    logger.trace(s"Actual concept URI: $actualUri")

    val query = buildConceptQuery(actualUri)
    logger.trace(s"SPARQL Query: $query")

    executeSparqlQuery(query) match {
      case Success(jsonResponse) =>
        logger.trace(s"SPARQL Response: $jsonResponse")
        parseSparqlResults(jsonResponse, actualUri) match {
          case Some(concept) =>
            val result = upickle.default.write(concept)
            logger.debug(s"Found uri $actualUri with ${concept.exactMatch.length} exactMatch, ${concept.broader.length} broader, ${concept.narrower.length} narrower, ${concept.related.length} related")
            result
          case None =>
            logger.debug(s"No concept found for uri $actualUri")
            s"""{"error": "No concept found for URI: $actualUri"}"""
        }
      case Failure(e) =>
        logger.error(s"Failed to query SPARQL endpoint for $actualUri", e)
        s"""{"error": "Failed to query vocabulary: ${e.getMessage}"}"""
    }
  }

  /**
   * Batch retrieves information for multiple vocabulary concepts.
   *
   * @param conceptUris Comma-separated list of vocabulary concept URIs
   * @return JSON array with concept information for each URI
   */
  @Tool(Array("Retrieve information for multiple vocabulary concepts at once"))
  def batchGetVocabConcepts(
    @P("Comma-separated list of vocabulary concept URIs to look up") conceptUris: String
  ): String = {
    val uris = conceptUris.split(",").map(_.trim).filter(_.nonEmpty).toList
    logger.debug(s"Batch looking up ${uris.length} vocabulary concepts")

    val concepts = uris.flatMap { uri =>
      val prefixReplacedUri = replacePrefixIfNeeded(uri)
      val actualUri = extractActualUri(prefixReplacedUri)
      val query = buildConceptQuery(actualUri)
      executeSparqlQuery(query).toOption.flatMap { jsonResponse =>
        parseSparqlResults(jsonResponse, actualUri)
      }
    }

    upickle.default.write(concepts)
  }
}


object VocabularyTools {
  def getSpecifications: java.util.List[ToolSpecification] =
    ToolSpecifications.toolSpecificationsFrom(classOf[VocabularyTools])
}

