package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool, ToolSpecification, ToolSpecifications}
import nl.wur.soilcompanion.Config.SolrSearchConfig
import nl.wur.soilcompanion.*
import upickle.default.*

import java.net.URLEncoder
import java.util
import java.util.Base64

// --- classes for content payload ---

/* Example direct Solr access:
    curl -X 'POST' \
     'https://solr.soilwise-he.containers.wur.nl/solr/records/select' \
     -H 'accept: application/json' \
     -H 'Content-Type: application/json' -u "user:pwd" \
     -d '{ params: {
       "q": "(abstract:(soil AND health) OR pdf_content:(soil AND health)) AND type:(journalpaper)",
       "fl": ["identifier", "score", "type", "title", "abstract", "pdf_content", "pdf_link", "date_publication"],
       "sort": "score desc",
       "rows": "3"
        } }'

    Use the case class to format the parameters and convert to JSON payload:
      write(SolrParams(query="carbon AND farming", params=contentSearchConfig), indent = 2))
    Then use the other case classes to decode the Solr JSON response body
 */

case class SolrParams(
  query: String,
  params: SolrSearchConfig
) derives ReadWriter

case class ResponseHeader(
  status: Int,
  QTime: Int,
  params: Map[String, String]
) derives ReadWriter

case class Doc(
  identifier: String,
  docType: String,
  docTitle: String,
  keywords: Option[List[String]],
  docAbstract: Option[String],
  docLink: Option[String],
  docContent: Option[List[String]],
  score: Double,
  publicationDate: Option[String]
) derives ReadWriter

case class Response(
  numFound: Int,
  start: Int,
  maxScore: Double,
  numFoundExact: Boolean,
  docs: List[Doc]
) derives ReadWriter

case class SolrResponse(
  responseHeader: ResponseHeader,
  response: Response
) derives ReadWriter

// ---

/**
 * Parses the given JSON string response from Solr into a `SolrResponse` object.
 *
 * Could be derived, but property names need to match then.
 *
 * @param response the JSON string response returned by Solr
 * @return a `SolrResponse` object containing the parsed response data
 */
private def parseSolrResponse(response: String): SolrResponse = {
  val json = ujson.read(response)
  val solrResponse = SolrResponse(
    responseHeader = ResponseHeader(
      status = json("responseHeader")("status").num.toInt,
      QTime = json("responseHeader")("QTime").num.toInt,
      params = json("responseHeader")("params").obj.map {
        case (k, v) => k -> v.str
      }.toMap
    ),
    response = Response(
      numFound = json("response")("numFound").num.toInt,
      start = json("response")("start").num.toInt,
      maxScore = json("response")("maxScore").num,
      numFoundExact = json("response")("numFoundExact").bool,
      docs = json("response")("docs").arr.map { doc =>
        Doc(
          identifier = doc("identifier").str,
          docType = doc("type").str,
          docTitle = doc("title").str,
          keywords = doc.obj.get("keywords").map(_.arr.map(_.str).toList),
          docAbstract = doc.obj.get("abstract").map(_.str),
          docLink = doc.obj.get("pdf_link").map(_.str),
          docContent = doc.obj.get("pdf_content").map(_.arr.map(_.str).toList),
          score = doc("score").num,
          publicationDate = doc.obj.get("date_publication").map(_.str)
        )
      }.toList
    )
  )
  solrResponse
}

/**
 * Provides tools for interacting with the SoilWise Solr API to fetch metadata and content records
 * for datasets and documents based on specific search terms.
 *
 * This class is designed to query Solr for datasets and journal papers
 * and return metadata or content based on search criteria or unique identifiers.
 */
class CatalogTools {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val limit = Config.appConfig.catalogRetrieverMaxResults

  // --- HTTP helpers (headers + timeouts) ---
  private def solrHeaders: Map[String, String] = Map(
    "accept" -> "application/json",
    "Content-Type" -> "application/json",
    "Authorization" -> s"Basic $base64SolrCredentials",
    "User-Agent" -> Config.catalogConfig.userAgent
  )

  /**
   * Constructs a query string based on the provided search terms, document type, and fields to be queried.
   * The method combines search terms with specified fields using logical operators to produce
   * a formatted query string suitable for metadata searches.
   *
   * @param searchTerms An array of search terms. Each term is split into words, which are combined
   *                    using the AND operator. Terms are then joined together using the AND operator.
   * @param docTypes    An array of doc types to search in. By default, this is ["dataset", "journalpaper"],
   *                    but concrete tools will use configurable defaults from `catalog-config`.
   * @param fields      An array of fields to search in. By default, these are ["abstract", "pdf_content"].
   *                    Each field is combined with the search terms using the OR operator.
   * @return A constructed query string where terms, fields, and document type
   *         are logically combined to represent the full query.
   */
  private def buildQuery(
                          searchTerms: Array[String],
                          docTypes: Array[String] = Array("dataset", "journalpaper"),
                          fields: Array[String] = Array("abstract", "pdf_content")): String = {
    searchTerms.map { term =>
        val words = term.split("\\s+").mkString(" AND ")
        fields.map(field => s"$field:($words)").mkString(" OR ")
      }
      .map(tq => s"($tq)")
      .mkString("(", " AND ", ")") + s" AND type:(${docTypes.mkString(" OR ")})"
  }

  /**
   * Encodes the Solr server credentials (username and password) from the configuration into a Base64-encoded string.
   * The credentials are formatted as "username:password" before encoding.
   *
   * @return A Base64-encoded string representation of the Solr server credentials.
   */
  private def base64SolrCredentials: String = {
    val creds = s"${Config.searchServerConfig.username}:${Config.searchServerConfig.password}"
    Base64.getEncoder.encodeToString(creds.getBytes("UTF-8"))
  }

  /**
   * Constructs a catalog URL for a given identifier by encoding it and appending it to a base URL.
   *
   * @param identifier The unique identifier of the catalog item to be appended to the base URL.
   * @return A string representing the complete catalog URL.
   */
  private def createCatalogURL(identifier: String): String = {
    val id = URLEncoder.encode(identifier, "UTF-8")
    s"${Config.catalogConfig.itemLinkBaseUrl}$id"
  }

  /**
   * Formats the abstract and related metadata of a given document (Doc) into a structured string.
   * The resulting string includes the document's abstract, identifier, and various reference URLs.
   *
   * @param doc The document from which the abstract and metadata are to be extracted and formatted.
   * @return A string representing the formatted abstract and metadata of the document.
   */
  private def formatAbstract(doc: Doc): String = {
    s"""### SoilWise Catalog Item
       |Title:
       |${doc.docTitle}
       |\n
       |Abstract:
       |${doc.docAbstract.getOrElse("No abstract available.")}
       |\n
       |Reference:
       |- SoilWise identifier: ${doc.identifier}
       |- SoilWise catalog URL: ${createCatalogURL(doc.identifier)}
       |- Direct URL: ${doc.docLink.getOrElse("Not available.")}
       |""".stripMargin
  }

  /**
   * Formats the provided document metadata and content into a structured string representation.
   * The formatted string includes the document's title, abstract, content, and reference information.
   *
   * @param doc The document containing metadata and content to be formatted.
   *            Includes fields like the document title, abstract, content, and identifier.
   * @return A string representing the formatted document metadata and content, including references.
   */
  private def formatContent(doc: Doc): String = {
    s"""### Publication
       |Title:
       |${doc.docTitle}
       |\n
       |Abstract:
       |${doc.docAbstract.getOrElse("No abstract available.")}
       |\n
       |Content:
       |${doc.docContent.getOrElse("No content available.")}
       |\n
       |Reference:
       |- SoilWise identifier: ${doc.identifier}
       |- SoilWise catalog URL: ${createCatalogURL(doc.identifier)}
       |- Direct URL: ${doc.docLink.getOrElse("Not available.")}
       |""".stripMargin
  }

  /**
   * Sends a request to the Solr server with the specified parameters and processes the response.
   * If the response is successful, the documents are formatted using the provided formatter function.
   * In case of an error, an appropriate message is returned.
   *
   * @param params The parameters to include in the Solr request, including the search query and configuration.
   * @param format A function that formats a document (Doc) into a string representation.
   * @return A string containing the formatted results if the response is successful, a message if no matches are found,
   *         or an error message if the Solr server is unavailable.
   */
  private def solrRequest(params: SolrParams, format: Doc => String): String = {
    logger.debug(s"Solr query: ${params.query}")

    val response = requests
      .post(
        url = Config.searchServerConfig.baseUrl,
        headers = solrHeaders,
        data = write(params),
        readTimeout = Config.catalogConfig.timeoutMs,
        connectTimeout = Config.catalogConfig.timeoutMs
      )

    logger.debug(s"Solr response: ${response.text()}")

    if (response.statusCode != 200) {
      "The SoilWise catalog is currently unavailable. Please try again later."
    } else {
      val solrResponse = parseSolrResponse(response.text())
      if (solrResponse.response.docs.isEmpty) {
        s"No matching items found in the SoilWise catalog for this search."
      } else {
        solrResponse.response.docs.map(format).mkString("\n")
      }
    }
  }

  /**
   * Retrieves SoilWise metadata records for datasets that match the given search terms.
   *
   * @param searchTerms An array of search terms. Each term's words are combined using the AND operator,
   *                    and terms are further combined using the AND operator. For example, the term
   *                    'soil health' returns records containing both 'soil' and 'health'.
   * @return A JSON string representing the metadata records for datasets that match the specified search terms.
   */
  @Tool(Array(
    "Search SoilWise metadata for DATASETS (not publications). Use this when the user asks about datasets, data collections, services, or APIs. If the user mentions papers, journal papers, publications, documents, or reports this implies a KNOWLEDGE search instead (use the knowledge tools). Do NOT include publication types as search terms — filtering by type is configured server‑side."
  ))
  def getAllDatasetRecords(
    @P(
      "Topical keywords/phrases in English. Words per term are ANDed; terms are ANDed. Example: 'soil health' returns records containing both 'soil' and 'health'. Do NOT include publication types or formats (e.g., 'journal paper', 'report', 'document'); those are not search terms. Prefer meaningful subject terms and, when helpful, include synonyms or hypernyms to broaden recall (e.g., 'soil organic matter' ~ 'humus', 'carbon'). For spatial locations, use only the broadest applicable place (e.g., a country instead of a city/region). If the query concerns multiple non‑overlapping locations (e.g., two different countries), provide them as separate terms."
    )
    // note: Need to use Array instead of List here for Java/Jackson compatibility
    searchTerms: Array[String]
  ): String = {
    val query = buildQuery(searchTerms, Config.catalogConfig.datasetDocTypes.toArray)
    solrRequest(SolrParams(query = query, params = Config.basicSearchConfig), formatAbstract)
  }

  /**
   * Retrieves SoilWise metadata records for documents that match the given search terms.
   *
   * @param searchTerms An array of search terms. Words within each term are combined using the AND operator,
   *                    and terms are further combined using the AND operator. For example, the term
   *                    "soil health" will return records that contain both "soil" and "health".
   * @return A JSON string representing the metadata records for documents that match the specified search terms.
   */
  @Tool(Array(
    "Search SoilWise KNOWLEDGE records (publications/documents) such as journal papers, reports, and other documents. Use this whenever the user mentions papers, publications, documents, or reports. Do NOT add the publication type itself as a search term — the correct type filtering is configured server‑side."
  ))
  def getAllKnowledgeRecords(
    @P(
      "Topical keywords/phrases in English. Words per term are ANDed; terms are ANDed. Example: 'soil health' returns records containing both 'soil' and 'health'. Do NOT include publication types or formats (e.g., 'journal paper', 'report', 'thesis'); those are not search terms. Prefer subject terms and, when helpful, include synonyms/hypernyms to broaden recall (e.g., 'erosion' ~ 'soil loss'; 'crop' as a hypernym for 'wheat', 'maize'). For spatial locations, include only the broadest applicable place (e.g., country instead of city/region). For distinct, non‑overlapping locations (e.g., Netherlands and Germany), list them as separate terms."
    )
    searchTerms: Array[String]
  ): String = {
    val query = buildQuery(searchTerms, Config.catalogConfig.knowledgeDocTypes.toArray)
    solrRequest(SolrParams(query = query, params = Config.basicSearchConfig), formatAbstract)
  }

  /**
   * Retrieves the content of SoilWise documents for records that match the given search terms.
   *
   * @param searchTerms An array of search terms. Words within each term are combined using the AND operator,
   *                    and terms are further combined using the AND operator. For example, the term
   *                    "soil health" will return records that contain both "soil" and "health".
   * @return A JSON string representing the content of documents that match the specified search terms.
   */
  @Tool(Array(
    "Return fulltext/content snippets for SoilWise KNOWLEDGE items matching the search terms (publications/documents). Use when the user wants the content of papers, publications, documents, or reports. Do NOT include publication types as search terms — type filtering is configured."
  ))
  def getAllKnowledgeContent(
    @P(
      "Topical keywords/phrases in English. Words per term are ANDed; terms are ANDed. Example: 'soil health' returns records containing both 'soil' and 'health'. Exclude publication types or formats (e.g., 'report', 'paper'). Prefer domain terms and consider adding synonyms/hypernyms to improve recall. For spatial locations, choose the broadest applicable place only (e.g., a country rather than a city/region). If multiple distinct, non‑overlapping locations apply, provide them as separate terms."
    )
    searchTerms: Array[String]
  ): String = {
    val query = buildQuery(searchTerms, Config.catalogConfig.knowledgeContentDocTypes.toArray)
    solrRequest(SolrParams(query = query, params = Config.contentSearchConfig), formatContent)
  }

  /**
   * Retrieves the content of the SoilWise catalog entry that matches the provided identifier.
   *
   * @param identifier The unique identifier of the SoilWise catalog item.
   * @return A string containing the catalog item's content, metadata, and any available links.
   *         If no matching record is found, a message indicating this is returned.
   *         If the catalog is unavailable, an error message is returned.
   */
  @Tool(Array(
    "Return the content/metadata for a single SoilWise item by identifier."
  ))
  def getItemContent(
    @P("The SoilWise catalog item identifier (exact ID).")
    identifier: String
  ): String = {
    val query = buildQuery(Array(identifier), Config.catalogConfig.itemContentDocTypes.toArray)
    solrRequest(SolrParams(query = query, params = Config.contentSearchConfig), formatContent)
  }

  /**
   * Creates a back link URL to the SoilWise entry that matches the given identifier.
   * If the generated link is invalid, the method returns None.
   *
   * @param identifier The unique identifier of the SoilWise catalog entry for which the back link is to be created.
   * @return An Option containing the back link URL as a String if the link is valid, or None if the link is invalid.
   */
  @Tool(Array(
    "Create a verified SoilWise catalog URL (link) for a given identifier. Returns the identifier when the URL cannot be verified."
  ))
  def createVerifiedCatalogURL(
    @P("The SoilWise catalog identifier to create a URL (link) for.")
    identifier: String
  ): Option[String] = {
    val url = createCatalogURL(identifier)
    val headers = Map(
      "User-Agent" -> Config.catalogConfig.userAgent,
      "accept" -> "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )
    if (requests.get(url, headers = headers, readTimeout = Config.catalogConfig.timeoutMs, connectTimeout = Config.catalogConfig.timeoutMs).statusCode == 200) {
      Some(url)
    } else {
      Some(identifier)
    }
  }

}

/**
 * CatalogTools is an entry point object providing functionality for interacting with
 * SoilWise metadata and knowledge records. It supports querying metadata and retrieving
 * data/document content based on search terms or identifiers. The object implements
 * utilities to help in constructing queries and fetching relevant information.
 *
 * The main functionalities include:
 * - Constructing search queries combining logical operations across terms and fields.
 * - Fetching metadata records for datasets and documents based on search terms.
 * - Retrieving detailed content for individual documents or matching records.
 *
 * This object also provides a helper method for accessing tool specifications for
 * metadata processing utilities.
 */
object CatalogTools extends App {
  /**
   * Retrieves a list of tool specifications for metadata processing utilities.
   *
   * Tool specifications define the capabilities and metadata of tools available
   * within the SoilWise Catalog. These specifications include details necessary
   * for understanding and utilizing the tools effectively for metadata operations.
   *
   * @return A list of tool specifications as `ToolSpecification` objects.
   */
  def getSpecifications: util.List[ToolSpecification] =
    ToolSpecifications.toolSpecificationsFrom(classOf[CatalogTools])
}

