package nl.wur.soilcompanion

import dev.langchain4j.agent.tool.{P, Tool}
import upickle.default.*

/**
 * LLM tools to access country-specific agricultural field data services.
 *
 * Initial implementation targets The Netherlands via OpenAgroKPI.
 * Docs: see configuration `openagro-config.docs-url`.
 */
private class OpenAgroKpiTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // --- Helpers ---

  private def apiKeyHeader(): Map[String, String] = {
    val token = Option(Config.openAgroConfig.accessToken).map(_.trim).getOrElse("")
    // OpenAgroKPI expects the API key in header 'x-api-key' (no 'Bearer' prefix)
    if (token.nonEmpty && !token.equalsIgnoreCase("REPLACE_ME")) Map("x-api-key" -> token) else Map.empty
  }

  private def baseHeaders: Map[String, String] =
    Map(
      "accept" -> "application/json",
      "User-Agent" -> Config.openAgroConfig.userAgent
    ) ++ apiKeyHeader()

  private def httpGet(url: String, query: Map[String, String] = Map.empty): requests.Response = {
    val qs = if (query.isEmpty) "" else query.map { case (k, v) => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("?", "&", "")
    val full = s"$url$qs"
    logger.debug(s"OpenAgroKPI GET $full")
    requests.get(
      url = full,
      headers = baseHeaders,
      readTimeout = Config.openAgroConfig.timeoutMs,
      connectTimeout = Config.openAgroConfig.timeoutMs
    )
  }

  private def preview(body: String, max: Int = 2000): String =
    if (body.length > max) body.take(max) + s"... [truncated ${body.length - max} chars]" else body

  private def ensureCountrySupported(countryCode: String): Either[String, String] = {
    // For now we only have explicit support for NL via OpenAgroKPI. Others may be added later.
    val cc = Option(countryCode).map(_.trim.toUpperCase).filter(_.nonEmpty).getOrElse(Config.openAgroConfig.defaultCountry.toUpperCase)
    cc match {
      case "NL" => Right(cc)
      case other => Left(s"Country '$other' is not yet supported for field-specific services. Currently supported: NL (OpenAgroKPI).")
    }
  }

  private def buildBase(): String = Config.openAgroConfig.baseUrl.stripSuffix("/")

  /**
   * Attempt to find agricultural fields near a location and return a compact summary.
   * The implementation is conservative and returns a helpful message if the remote API changes or is unavailable.
   */
  @Tool(Array(
    "Looks up agricultural field information for a location using country-specific services (e.g., OpenAgroKPI for NL).",
    "Use this when you have latitude/longitude and want field IDs or KPIs for that location."
  ))
  def getFieldData(
    @P("Latitude in decimal degrees (WGS84). Example: 52.0929")
    lat: Double,
    @P("Longitude in decimal degrees (WGS84). Example: 5.1045")
    lon: Double,
    @P("ISO 3166-1 alpha-2 country code (e.g., 'NL'). Defaults to configured country if empty.")
    countryCode: String,
    @P("Optional KPI or layer name depending on the service; leave empty if unknown.")
    layer: String,
    @P("Optional year (e.g., '2023') if the KPI requires a year.")
    year: String,
    @P("Optional search radius in meters around the location to match nearby fields (default 50m).")
    extentMeters: Int
  ): String = {
    val ccEither = ensureCountrySupported(countryCode)
    ccEither.left.foreach(msg => logger.info(msg))
    ccEither match {
      case Left(msg) =>
        s"$msg If applicable, I can still provide general soil context or SoilGrids estimates for the location."
      case Right(cc) =>
        val base = buildBase()
        val resolvedYear = Option(year).map(_.trim).filter(_.nonEmpty).getOrElse {
          val y = java.time.LocalDate.now().getYear - 1
          logger.debug(s"OpenAgroKPI: no year provided, defaulting to previous year: $y")
          y.toString
        }

        val url = s"$base/lookup-field-by-location"
        val query = Map(
          "year" -> resolvedYear,
          "lat" -> lat.toString,
          "lon" -> lon.toString
        )

        val respTry = scala.util.Try(httpGet(url, query))
        respTry.toOption match {
          case None =>
            s"The OpenAgroKPI field lookup failed for lat=$lat lon=$lon year=$resolvedYear. The service may be unavailable or requires authentication."
          case Some(response) if response.statusCode != 200 =>
            val bodyPrev = scala.util.Try(response.text()).toOption.map(s => preview(s)).getOrElse("")
            logger.info(s"OpenAgroKPI field lookup non-200 status=${response.statusCode}, body preview=$bodyPrev")
            response.statusCode match
              case 401 | 403 =>
                val hint = if (apiKeyHeader().isEmpty) " Missing API key; set environment variable OPENAGRO_ACCESS_TOKEN." else " API key may be invalid or lacks permission."
                s"OpenAgroKPI authentication error (status ${response.statusCode}).$hint"
              case _ =>
                // Bubble up body hint if it mentions the header explicitly
                val missingHeaderHint =
                  if (bodyPrev.toLowerCase.contains("x-api-key") && bodyPrev.toLowerCase.contains("missing"))
                    " The service reports that header 'x-api-key' is missing."
                  else ""
                s"OpenAgroKPI returned status ${response.statusCode} for field lookup.$missingHeaderHint Please try again later."
          case Some(response) =>
            val body = response.text()
            logger.debug(s"OpenAgroKPI lookup-field-by-location response (200): ${preview(body)}")

            val parsed = scala.util.Try(ujson.read(body)).toOption
            // Try to extract some identifiers
            val idOpt: Option[String] = parsed.flatMap { js =>
              if (js.isInstanceOf[ujson.Obj]) then
                js.obj.get("id").map(_.str)
                  .orElse(js.obj.get("fieldId").map(_.str))
                  .orElse(js.obj.get("properties").flatMap(_.obj.get("id")).map(_.str))
              else None
            }

            val summary = idOpt match
              case Some(fid) => s"Found field for the specified location (year=$resolvedYear): ID $fid."
              case None => "Found a field for the specified location; could not parse a stable ID from the response."

            val maybeKpi: Option[String] = Option(layer).map(_.trim).filter(_.nonEmpty).flatMap { l =>
              // If we have a field id, try to fetch KPI for that field
              idOpt.flatMap { fid =>
                val kpiUrl = s"$base/nl/fields/$fid/kpis"
                val kpiQ = Map("layer" -> l, "year" -> resolvedYear)
                val rOpt = scala.util.Try(httpGet(kpiUrl, kpiQ)).toOption.filter(_.statusCode == 200)
                rOpt.map { r =>
                  val p = preview(r.text())
                  logger.debug(s"OpenAgroKPI KPI response (200) from $kpiUrl: $p")
                  s"Sample KPI for field $fid, layer '$l', year $resolvedYear:\n$p"
                }
              }
            }

            val service = s"Service: OpenAgroKPI ($base)"
            val authNote = if (apiKeyHeader().isEmpty && !Config.openAgroConfig.allowUnauthenticated) "Note: API key required (x-api-key) but not configured." else ""
            val docs = s"Docs: ${Config.openAgroConfig.docsUrl}"

            s"""$summary
               |$service
               |$docs
               |$authNote
               |""".stripMargin + maybeKpi.map(s => "\n" + s).getOrElse("")
        }
    }
  }

  @Tool(Array(
    "Parses a 'location context' JSON (from the UI) and retrieves agricultural field information via OpenAgroKPI (NL)."
  ))
  def getFieldDataFromLocationContext(
    @P("Location context JSON with at least 'lat' and 'lon', and optionally 'countryCode'.")
    locationContextJson: String,
    @P("Optional KPI/layer name; leave empty when just listing fields.")
    layer: String,
    @P("Optional year string (e.g., '2023').")
    year: String,
    @P("Optional search radius in meters (default 50m).")
    extentMeters: Int
  ): String = {
    try
      val js = ujson.read(locationContextJson)
      val lat = js("lat").num
      val lon = js("lon").num
      val cc = js.obj.get("countryCode").map(_.str).getOrElse(Config.openAgroConfig.defaultCountry)
      getFieldData(lat, lon, cc, layer, year, extentMeters)
    catch
      case e: Throwable =>
        logger.warn(s"Failed to parse location context JSON: ${e.getMessage}")
        "Could not parse the location context JSON. Please provide 'lat' and 'lon'."
  }
}
