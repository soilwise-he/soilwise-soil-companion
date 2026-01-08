package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool}
import nl.wur.soilcompanion.Config
import upickle.default.*

import java.net.URLEncoder

/**
 * LLM tools to search Wikipedia and retrieve article content.
 *
 * This tool enables the chatbot to search for terms and phrases on Wikipedia
 * and use the page content as part of the context for answering user questions.
 */
class WikipediaTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /**
   * Case class representing a Wikipedia search result.
   */
  private case class WikiSearchResult(
    title: String,
    snippet: String,
    pageId: Long
  )

  /**
   * Case class representing Wikipedia page content.
   */
  private case class WikiPageContent(
    title: String,
    extract: String,
    url: String
  )

  /**
   * Builds the Wikipedia API search URL for searching articles.
   */
  private def buildSearchUrl(searchTerm: String, limit: Int): String = {
    val base = Config.wikipediaConfig.baseUrl.stripSuffix("/")
    val encoded = URLEncoder.encode(searchTerm, "UTF-8")
    s"$base/w/api.php?action=opensearch&search=$encoded&limit=$limit&format=json"
  }

  /**
   * Builds the Wikipedia API URL for retrieving page content.
   */
  private def buildPageContentUrl(title: String): String = {
    val base = Config.wikipediaConfig.baseUrl.stripSuffix("/")
    val encoded = URLEncoder.encode(title, "UTF-8")
    s"$base/w/api.php?action=query&prop=extracts&titles=$encoded&format=json&explaintext=1&exintro=0"
  }

  /**
   * Builds a direct link to a Wikipedia article.
   */
  private def buildArticleUrl(title: String): String = {
    val base = Config.wikipediaConfig.baseUrl.stripSuffix("/")
    val encoded = URLEncoder.encode(title.replace(" ", "_"), "UTF-8")
    s"$base/wiki/$encoded"
  }

  /**
   * Parses the Wikipedia opensearch API response.
   * Returns a list of (title, snippet, url) tuples.
   */
  private def parseSearchResults(jsonStr: String): List[WikiSearchResult] = {
    try {
      val js = ujson.read(jsonStr)
      val arr = js.arr
      if (arr.length >= 4) {
        val titles = arr(1).arr.map(_.str).toList
        val snippets = arr(2).arr.map(_.str).toList
        val urls = arr(3).arr.map(_.str).toList

        titles.zip(snippets).zip(urls).zipWithIndex.map {
          case (((title, snippet), url), idx) =>
            WikiSearchResult(title, snippet, idx.toLong)
        }
      } else {
        List.empty
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to parse Wikipedia search results", e)
        List.empty
    }
  }

  /**
   * Parses the Wikipedia page content API response.
   */
  private def parsePageContent(jsonStr: String, title: String): Option[WikiPageContent] = {
    try {
      val js = ujson.read(jsonStr)
      val pages = js("query")("pages").obj

      pages.headOption.map { case (pageId, pageData) =>
        val pageTitle = pageData.obj.get("title").map(_.str).getOrElse(title)
        val extract = pageData.obj.get("extract").map(_.str).getOrElse("")
        val url = buildArticleUrl(pageTitle)
        WikiPageContent(pageTitle, extract, url)
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to parse Wikipedia page content", e)
        None
    }
  }

  /**
   * Formats search results into a readable string.
   */
  private def formatSearchResults(results: List[WikiSearchResult]): String = {
    if (results.isEmpty) {
      "No Wikipedia articles found for this search term."
    } else {
      val formatted = results.map { result =>
        s"""### ${result.title}
           |${result.snippet}
           |Link: ${buildArticleUrl(result.title)}
           |""".stripMargin
      }.mkString("\n")

      s"""Wikipedia Search Results:
         |$formatted
         |
         |Source: Wikipedia (${Config.wikipediaConfig.baseUrl})
         |License: ${Config.wikipediaConfig.licenseUrl}
         |""".stripMargin
    }
  }

  /**
   * Formats page content into a readable string with character limit.
   */
  private def formatPageContent(content: WikiPageContent): String = {
    val maxChars = Config.wikipediaConfig.maxContentChars
    val extract = if (content.extract.length > maxChars) {
      content.extract.take(maxChars) + s"\n\n[Content truncated at $maxChars characters. Visit the Wikipedia page for full content.]"
    } else {
      content.extract
    }

    s"""### Wikipedia Article: ${content.title}
       |
       |$extract
       |
       |Reference:
       |- Wikipedia URL: ${content.url}
       |- Source: Wikipedia (${Config.wikipediaConfig.baseUrl})
       |- License: ${Config.wikipediaConfig.licenseUrl}
       |""".stripMargin
  }

  /**
   * Performs a Wikipedia search.
   */
  private def doSearch(searchTerm: String, limit: Int): String = {
    try {
      val url = buildSearchUrl(searchTerm, limit)
      logger.debug(s"Wikipedia search URL: $url")

      val response = requests.get(
        url = url,
        headers = Map(
          "User-Agent" -> Config.wikipediaConfig.userAgent
        ),
        readTimeout = Config.wikipediaConfig.timeoutMs,
        connectTimeout = Config.wikipediaConfig.timeoutMs
      )

      logger.debug(s"Wikipedia search HTTP status: ${response.statusCode}")

      if (response.statusCode != 200) {
        s"Wikipedia service returned status ${response.statusCode}. Please try again later."
      } else {
        val results = parseSearchResults(response.text())
        logger.debug(s"Wikipedia search found ${results.length} results")
        formatSearchResults(results)
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Wikipedia search failed for term: $searchTerm", e)
        "The Wikipedia service is currently unavailable or the request failed. Please try again later."
    }
  }

  /**
   * Retrieves full content of a Wikipedia article.
   */
  private def getPageContent(title: String): String = {
    try {
      val url = buildPageContentUrl(title)
      logger.debug(s"Wikipedia page content URL: $url")

      val response = requests.get(
        url = url,
        headers = Map(
          "User-Agent" -> Config.wikipediaConfig.userAgent
        ),
        readTimeout = Config.wikipediaConfig.timeoutMs,
        connectTimeout = Config.wikipediaConfig.timeoutMs
      )

      logger.debug(s"Wikipedia page content HTTP status: ${response.statusCode}")

      if (response.statusCode != 200) {
        s"Wikipedia service returned status ${response.statusCode}. Please try again later."
      } else {
        parsePageContent(response.text(), title) match {
          case Some(content) =>
            logger.debug(s"Wikipedia page content retrieved: ${content.title}, ${content.extract.length} chars")
            formatPageContent(content)
          case None =>
            s"Could not retrieve content for Wikipedia article: $title"
        }
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Wikipedia page content retrieval failed for title: $title", e)
        "The Wikipedia service is currently unavailable or the request failed. Please try again later."
    }
  }

  @Tool(Array(
    "Search Wikipedia for articles matching a search term or phrase.",
    "Returns a list of matching article titles with snippets and links.",
    "Use this when the user asks for general information or concepts that might be found in Wikipedia."
  ))
  def searchWikipedia(
    @P("Search term or phrase to look up on Wikipedia. Can be a single word or multiple words.")
    searchTerm: String,
    @P("Optional maximum number of results to return (default: 3, max: 10).")
    maxResults: Int
  ): String = {
    val limit = if (maxResults > 0 && maxResults <= 10) maxResults else Config.wikipediaConfig.defaultMaxResults
    doSearch(searchTerm, limit)
  }

  @Tool(Array(
    "Retrieve the full content of a specific Wikipedia article by title.",
    "Use this when you need detailed information from a specific Wikipedia page.",
    "The title should be the exact article title as it appears on Wikipedia."
  ))
  def getWikipediaArticle(
    @P("The exact title of the Wikipedia article to retrieve (e.g., 'Soil', 'Organic farming').")
    articleTitle: String
  ): String = {
    getPageContent(articleTitle)
  }

  @Tool(Array(
    "Search Wikipedia and retrieve the full content of the most relevant article.",
    "This combines search and content retrieval in one step.",
    "Use this when you want both to find and retrieve detailed content about a topic."
  ))
  def searchAndGetWikipediaContent(
    @P("Search term or phrase to look up on Wikipedia.")
    searchTerm: String
  ): String = {
    try {
      // First, do a search to find the most relevant article
      val url = buildSearchUrl(searchTerm, 1)
      logger.debug(s"Wikipedia search URL: $url")

      val response = requests.get(
        url = url,
        headers = Map(
          "User-Agent" -> Config.wikipediaConfig.userAgent
        ),
        readTimeout = Config.wikipediaConfig.timeoutMs,
        connectTimeout = Config.wikipediaConfig.timeoutMs
      )

      if (response.statusCode != 200) {
        s"Wikipedia service returned status ${response.statusCode}. Please try again later."
      } else {
        val results = parseSearchResults(response.text())
        if (results.isEmpty) {
          s"No Wikipedia articles found for search term: $searchTerm"
        } else {
          // Get the content of the first (most relevant) result
          getPageContent(results.head.title)
        }
      }
    } catch {
      case e: Throwable =>
        logger.error(s"Wikipedia search and retrieve failed for term: $searchTerm", e)
        "The Wikipedia service is currently unavailable or the request failed. Please try again later."
    }
  }
}
