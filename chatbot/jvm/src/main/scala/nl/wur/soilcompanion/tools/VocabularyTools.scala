package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool}
import nl.wur.soilcompanion.Config
import upickle.default.*

import java.net.URLEncoder
import scala.util.{Failure, Success, Try}

/**
 * Tools to query the SoilWise vocabulary SPARQL endpoint for concept information.
 *
 * This service retrieves broader concepts, narrower concepts, and exact matches
 * from the SKOS vocabulary to help users explore related terms.
 */
class VocabularyTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val sparqlEndpoint = "https://repository.soilwise-he.eu/sparql/"

  /**
   * Case class representing a vocabulary concept with related terms.
   */
  case class VocabConcept(
    uri: String,
    prefLabel: Option[String],
    definition: Option[String],
    broader: List[ConceptRef],
    narrower: List[ConceptRef],
    exactMatch: List[ConceptRef]
  ) derives ReadWriter

  case class ConceptRef(
    uri: String,
    prefLabel: Option[String]
  ) derives ReadWriter

  /**
   * Builds a SPARQL query to retrieve concept information.
   * Uses FILTER to ensure at least the concept exists, and tries multiple label predicates.
   */
  private def buildConceptQuery(conceptUri: String): String = {
    s"""
       |PREFIX skos: <http://www.w3.org/2004/02/skos/core#>
       |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
       |PREFIX dct: <http://purl.org/dc/terms/>
       |
       |SELECT DISTINCT ?prefLabel ?definition ?broader ?broaderLabel ?narrower ?narrowerLabel ?exactMatch ?exactMatchLabel ?related ?relatedLabel
       |WHERE {
       |  # The concept must exist as a subject
       |  <$conceptUri> ?p ?o .
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
       |  # Get broader concepts
       |  OPTIONAL {
       |    <$conceptUri> skos:broader ?broader .
       |    OPTIONAL {
       |      ?broader skos:prefLabel ?broaderLabel .
       |      FILTER(langMatches(lang(?broaderLabel), "en") || lang(?broaderLabel) = "")
       |    }
       |  }
       |
       |  # Get narrower concepts
       |  OPTIONAL {
       |    <$conceptUri> skos:narrower ?narrower .
       |    OPTIONAL {
       |      ?narrower skos:prefLabel ?narrowerLabel .
       |      FILTER(langMatches(lang(?narrowerLabel), "en") || lang(?narrowerLabel) = "")
       |    }
       |  }
       |
       |  # Get exact matches
       |  OPTIONAL {
       |    <$conceptUri> skos:exactMatch ?exactMatch .
       |    OPTIONAL {
       |      ?exactMatch skos:prefLabel ?exactMatchLabel .
       |      FILTER(langMatches(lang(?exactMatchLabel), "en") || lang(?exactMatchLabel) = "")
       |    }
       |  }
       |
       |  # Get related concepts
       |  OPTIONAL {
       |    <$conceptUri> skos:related ?related .
       |    OPTIONAL {
       |      ?related skos:prefLabel ?relatedLabel .
       |      FILTER(langMatches(lang(?relatedLabel), "en") || lang(?relatedLabel) = "")
       |    }
       |  }
       |}
       |LIMIT 100
       |""".stripMargin
  }

  /**
   * Executes a SPARQL query against the SoilWise repository.
   */
  private def executeSparqlQuery(query: String): Try[String] = {
    import requests.*
    Try {
      val response = post(
        sparqlEndpoint,
        data = Map("query" -> query),
        headers = Map(
          "Accept" -> "application/sparql-results+json",
          "Content-Type" -> "application/x-www-form-urlencoded"
        ),
        readTimeout = 10000,
        connectTimeout = 5000
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
      val broaderSet = scala.collection.mutable.Map[String, Option[String]]()
      val narrowerSet = scala.collection.mutable.Map[String, Option[String]]()
      val exactMatchSet = scala.collection.mutable.Map[String, Option[String]]()

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

        // Collect broader concepts
        b.get("broader").flatMap(_.obj.get("value")).map(_.str).foreach { broaderUri =>
          val label = b.get("broaderLabel").flatMap(_.obj.get("value")).map(_.str)
          broaderSet.put(broaderUri, label)
        }

        // Collect narrower concepts
        b.get("narrower").flatMap(_.obj.get("value")).map(_.str).foreach { narrowerUri =>
          val label = b.get("narrowerLabel").flatMap(_.obj.get("value")).map(_.str)
          narrowerSet.put(narrowerUri, label)
        }

        // Collect exact matches
        b.get("exactMatch").flatMap(_.obj.get("value")).map(_.str).foreach { matchUri =>
          val label = b.get("exactMatchLabel").flatMap(_.obj.get("value")).map(_.str)
          exactMatchSet.put(matchUri, label)
        }

        // Also collect related concepts and add them to narrower for now
        b.get("related").flatMap(_.obj.get("value")).map(_.str).foreach { relatedUri =>
          val label = b.get("relatedLabel").flatMap(_.obj.get("value")).map(_.str)
          // Add to narrower set to show as exploration options
          narrowerSet.put(relatedUri, label)
        }
      }

      Some(VocabConcept(
        uri = conceptUri,
        prefLabel = prefLabel,
        definition = definition,
        broader = broaderSet.map { case (uri, label) => ConceptRef(uri, label) }.toList,
        narrower = narrowerSet.map { case (uri, label) => ConceptRef(uri, label) }.toList,
        exactMatch = exactMatchSet.map { case (uri, label) => ConceptRef(uri, label) }.toList
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
    if (uri.contains("voc.soilwise-he") && uri.contains("/id/")) {
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
   * Retrieves vocabulary concept information from the SPARQL endpoint.
   *
   * @param conceptUri The URI of the vocabulary concept to look up (can be redirect URI)
   * @return JSON string with concept information including broader, narrower, and exact match concepts
   */
  @Tool(Array("Retrieve detailed information about a SoilWise vocabulary concept including related terms"))
  def getVocabConceptInfo(
    @P("The URI of the vocabulary concept to look up") conceptUri: String
  ): String = {
    logger.info(s"Looking up vocabulary concept: $conceptUri")

    // Extract the actual concept URI from redirect URL if needed
    val actualUri = extractActualUri(conceptUri)
    logger.info(s"Actual concept URI: $actualUri")

    val query = buildConceptQuery(actualUri)
    logger.debug(s"SPARQL Query: $query")

    executeSparqlQuery(query) match {
      case Success(jsonResponse) =>
        logger.debug(s"SPARQL Response: $jsonResponse")
        parseSparqlResults(jsonResponse, actualUri) match {
          case Some(concept) =>
            val result = upickle.default.write(concept)
            logger.info(s"Retrieved concept info: ${concept.prefLabel.getOrElse("unknown")} with ${concept.broader.length} broader, ${concept.narrower.length} narrower")
            result
          case None =>
            logger.warn(s"No concept found for URI: $actualUri")
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
    logger.info(s"Batch looking up ${uris.length} vocabulary concepts")

    val concepts = uris.flatMap { uri =>
      val actualUri = extractActualUri(uri)
      val query = buildConceptQuery(actualUri)
      executeSparqlQuery(query).toOption.flatMap { jsonResponse =>
        parseSparqlResults(jsonResponse, actualUri)
      }
    }

    upickle.default.write(concepts)
  }
}
