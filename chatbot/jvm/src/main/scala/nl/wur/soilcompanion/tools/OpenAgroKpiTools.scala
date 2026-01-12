package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool, ToolSpecification, ToolSpecifications}
import nl.wur.soilcompanion.Config
import upickle.default.*

/**
 * LLM tools to access country-specific agricultural field data services.
 *
 * Initial implementation targets The Netherlands via OpenAgroKPI.
 * Docs: see configuration `openagro-config.docs-url`.
 */
class OpenAgroKpiTools {

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

  private def httpPost(url: String, jsonBody: ujson.Value, query: Map[String, String] = Map.empty, acceptLanguage: String = "en"): requests.Response = {
    val qs = if (query.isEmpty) "" else query.map { case (k, v) => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("?", "&", "")
    val full = s"$url$qs"
    val headers = baseHeaders ++ Map(
      "Content-Type" -> "application/json",
      "Accept-Language" -> Option(acceptLanguage).map(_.trim).filter(_.nonEmpty).getOrElse("en")
    )
    logger.debug(s"OpenAgroKPI POST $full body=${jsonBody.render()}")
    requests.post(
      url = full,
      headers = headers,
      data = jsonBody.render(),
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
   * Resolve a requested layer name to a valid '/fields-{layer}' suffix.
   * - Performs strict, case-insensitive matching against configured knownLayers when available.
   * - Applies light normalization (spaces/underscores -> hyphens, collapse repeats, lowercase).
   * - Supports a curated alias map for common user variants.
   * - Rejects partial or fuzzy matches to avoid constructing invalid URLs.
   */
  private def resolveLayer(requested: String): Either[String, String] = {
    val inputRaw = Option(requested).getOrElse("").trim
    if (inputRaw.isEmpty) return Left("Missing 'layer'. Provide the KPI layer suffix that follows 'fields-' in the OpenAgroKPI API docs (e.g., 'annual-greenness').")

    val normalize: String => String = s =>
      s.toLowerCase
        .replaceAll("[ _]+", "-")
        .replaceAll("-+", "-")
        .stripPrefix("-").stripSuffix("-")

    val normalized = normalize(inputRaw)

    // Curated aliases for common variants
    val aliasMap: Map[String, String] = Map(
      // greenness
      "annual-greenness" -> "annual-greenness",
      "annualgreenness" -> "annual-greenness",
      "winter-greenness" -> "winter-greenness",
      "wintergreenness" -> "winter-greenness",
      // crops
      "crop-types" -> "crop-types",
      "croptypes" -> "crop-types",
      "crop-diversity" -> "crop-diversity",
      "cropdiversity" -> "crop-diversity",
      "crop-rotation" -> "crop-rotation",
      "croprotation" -> "crop-rotation",
      "crop-soils" -> "crop-soils",
      "cropsoils" -> "crop-soils",
      // others
      "carbon-flux" -> "carbon-flux",
      "carbonflux" -> "carbon-flux",
      "dominant-soil-type" -> "dominant-soil-type",
      "dominantsoiltype" -> "dominant-soil-type",
      "standard-revenue" -> "standard-revenue",
      "standardrevenue" -> "standard-revenue"
    )

    val known = Option(Config.openAgroConfig.knownLayers).getOrElse(Nil).map(normalize)

    // First check alias map
    val aliasResolved = aliasMap.get(normalized)

    // Then exact match against known layers (if configured)
    val resolved = aliasResolved
      .orElse(if (known.nonEmpty && known.contains(normalized)) Some(normalized) else None)
      .orElse(if (aliasMap.values.toSet.contains(normalized)) Some(normalized) else None)

    resolved match {
      case Some(ok) =>
        // If known layers are configured and the resolved isn't in them, warn and reject to avoid bad URLs
        if (known.nonEmpty && !known.contains(ok)) {
          val msg = s"Layer '$inputRaw' is not in the configured known layers. Allowed: ${known.mkString(", ")}."
          logger.info(s"OpenAgroKPI layer resolution rejected: $msg")
          Left(msg)
        } else {
          logger.debug(s"OpenAgroKPI layer resolved: '$inputRaw' -> '$ok'")
          Right(ok)
        }
      case None =>
        val allowed = if (known.nonEmpty) known.mkString(", ") else "(no known layers configured)"
        val msg = s"Unknown or ambiguous layer '$inputRaw'. Please use an exact layer name. Allowed: $allowed."
        logger.info(s"OpenAgroKPI layer resolution failed: $msg")
        Left(msg)
    }
  }

  // --- Tools ---

  @Tool(Array(
    "Describe how to call OpenAgroKPI '/api/v1/fields-*' endpoints without needing external docs.",
    "Explains authentication header, base URL, parameters for single vs multiple fields, and lists known layers from config if available.",
    "Use AgroDataCubeTools first to resolve NL field ids, then call the specific KPI tools here. NL only."
  ))
  def describeOpenAgroKpiFieldsApi(): String = {
    val base = buildBase()
    val known = Option(Config.openAgroConfig.knownLayers).getOrElse(Nil).filter(_.nonEmpty)
    val knownStr = if (known.nonEmpty) known.mkString(", ") else "greenness, soil, soil-physical, soilmap-benchmark, reference-values, crop-rotation (examples; check your deployment)"
    val authNote = "Header x-api-key: <YOUR_API_KEY> (no Bearer prefix)"
    val lines = List(
      s"OpenAgroKPI fields-* API overview (NL only)",
      s"Base URL: ${base}",
      s"Authentication: ${authNote}",
      s"Endpoint pattern: POST {base}/fields-{layer}",
      s"Known/Example layers: ${knownStr}",
      "Parameters:",
      "- Query: details-level=1 (default), info-level=0 (default), optional year=YYYY",
      "- Body (JSON array): [<fieldId>, ...] — even for a single field",
      "Usage flow:",
      "1) Use AgroDataCubeTools.getAgroDataCubeFieldByLocation or ...FromLocationContext to obtain NL field id(s).",
      "2) Call getOpenAgroKpiForField(fieldId, layer, year, countryCode='NL') for one field, or",
      "   getOpenAgroKpiForFields(fieldIdsCsv, layer, year, countryCode='NL') for multiple fields.",
      s"Examples:",
      s"- Single: curl -X POST '${base}/fields-greenness?details-level=1&info-level=0&year=2023' -H 'accept: application/json' -H 'x-api-key: <KEY>' -H 'Accept-Language: en' -H 'Content-Type: application/json' -d '[12345]'",
      s"- Multiple: curl -X POST '${base}/fields-greenness?details-level=1&info-level=0' -H 'accept: application/json' -H 'x-api-key: <KEY>' -H 'Accept-Language: en' -H 'Content-Type: application/json' -d '[12345,67890]'",
      s"Notes: Country currently supported: NL only. Responses may include crop category/codes; use AgroDataCubeTools.getAgroDataCubeCropCodeInfo to decode. Timeouts/user-agent configurable; see openagro-config."
    )
    lines.mkString("\n")
  }

  /**
   * Fetch KPI data from OpenAgroKPI for a single field in NL.
   *
   * Notes for the LLM:
   * - Use AgroDataCubeTools to discover an NL field/parcel id from a user-selected location.
   * - Then call this tool to fetch a KPI layer for that field via OpenAgroKPI.
   * - Only NL is supported. For other countries do not call this tool.
   */
  @Tool(Array(
    "Get OpenAgroKPI KPIs for a single NL field (parcel) id via POST.",
    "Use AgroDataCubeTools first to find the NL field id from a location, then call this tool to retrieve a KPI layer.",
    "Parameter 'layer' maps to '/api/v1/fields-{layer}'. Examples: 'greenness', 'soil-physical', 'reference-values' (see describeOpenAgroKpiFieldsApi).",
    "This tool performs a POST to '/fields-{layer}?details-level=1&info-level=0[&year=YYYY]' with JSON body '[fieldId]'.",
    "Authentication: header 'x-api-key'. NL only. Responses may include crop codes; use AgroDataCubeTools.getAgroDataCubeCropCodeInfo to decode."
  ))
  def getOpenAgroKpiForField(
    @P("Field identifier (from AgroDataCubeTools lookup in NL).")
    fieldId: String,
    @P("KPI layer name; corresponds to '/api/v1/fields-{layer}'. Example: greenness")
    layer: String,
    @P("Optional year, e.g. '2023'. If unknown, leave empty to let the API decide the default.")
    year: String,
    @P("Two-letter country code; only 'NL' is supported. If empty, defaults to NL.")
    countryCode: String
  ): String = {
    ensureCountrySupported(Option(countryCode).getOrElse("")) match {
      case Left(msg) =>
        logger.info(s"OpenAgroKPI country unsupported: $msg")
        return msg
      case Right(cc) => logger.debug(s"OpenAgroKPI country validated: $cc")
    }

    val lid = Option(fieldId).map(_.trim).filter(_.nonEmpty).getOrElse {
      return "Missing fieldId. First use AgroDataCubeTools to look up a field (parcel) in NL and pass its id here."
    }
    val lyr = resolveLayer(layer) match {
      case Left(err) => return err
      case Right(ok) => ok
    }

    if (apiKeyHeader().isEmpty && !Config.openAgroConfig.allowUnauthenticated) {
      logger.info("OpenAgroKPI call blocked: no API key configured and unauthenticated not allowed")
      return "OpenAgroKPI requires an API key (header 'x-api-key'). Configure env OPENAGRO_ACCESS_TOKEN or enable allow-unauthenticated for public endpoints."
    }

    val url = s"${buildBase()}/fields-$lyr"
    val query = Map(
      "details-level" -> "1",
      "info-level" -> "0"
    ) ++ Option(year).map(_.trim).filter(_.nonEmpty).map(y => Map("year" -> y)).getOrElse(Map.empty)

    // Body must be a JSON array of field IDs, even for a single field
    val idValue: ujson.Value =
      scala.util.Try(lid.toLong).toOption match {
        case Some(n) => ujson.Num(n.toDouble) // ujson has Num as Double; server should accept numeric
        case None => ujson.Str(lid)
      }
    val body = ujson.Arr(idValue)

    val resp = scala.util.Try(httpPost(url, body, query, acceptLanguage = "en")).toEither
    resp match
      case Left(ex) =>
        logger.info(s"OpenAgroKPI request failed for field_id=$lid layer=$lyr: ${ex.getMessage}")
        s"OpenAgroKPI request failed for field_id=$lid layer=$lyr. ${ex.getMessage}"
      case Right(r) if r.statusCode != 200 =>
        val bodyPrev = scala.util.Try(r.text()).toOption.map(preview(_)).getOrElse("")
        logger.info(s"OpenAgroKPI non-200 status=${r.statusCode} for $url, body preview=$bodyPrev")
        r.statusCode match
          case 401 | 403 =>
            val hint = if (apiKeyHeader().isEmpty) " Missing API key; set OPENAGRO_ACCESS_TOKEN." else " API key may be invalid or lacks permission."
            s"OpenAgroKPI authentication error (status ${r.statusCode}).$hint"
          case _ => s"OpenAgroKPI returned status ${r.statusCode}. Please try again later."
      case Right(r) =>
        val body = r.text()
        logger.debug(s"OpenAgroKPI fields-$lyr response (200) for field_id=$lid: ${preview(body)}")
        s"OpenAgroKPI fields-$lyr (POST) for field_id=$lid: ${preview(body, 1500)}\nService: ${buildBase()}\nDocs: ${Config.openAgroConfig.docsUrl}"
  }

  /**
   * Fetch KPI data from OpenAgroKPI for multiple fields in NL.
   *
   * Notes for the LLM:
   * - Prefer using AgroDataCubeTools to obtain one or more NL field ids first.
   * - Then call this tool to fetch a KPI layer for those fields.
   * - Only NL is supported at present.
   */
  @Tool(Array(
    "Get OpenAgroKPI KPIs for multiple NL fields by CSV of field ids via POST.",
    "Use AgroDataCubeTools first to find NL field ids, then call this tool to retrieve a KPI layer for multiple fields at once.",
    "Parameter 'layer' maps to '/api/v1/fields-{layer}'. This tool performs a POST with JSON body '[id1,id2,...]'.",
    "Authentication: header 'x-api-key'. NL only. Responses may include crop codes; use AgroDataCubeTools.getAgroDataCubeCropCodeInfo to decode."
  ))
  def getOpenAgroKpiForFields(
    @P("Comma-separated list of field ids (from AgroDataCubeTools). Example: '1234,5678,9012'")
    fieldIdsCsv: String,
    @P("KPI layer name; corresponds to '/api/v1/fields-{layer}'. Example: greenness")
    layer: String,
    @P("Optional year, e.g. '2023'. If unknown, leave empty to let the API decide the default.")
    year: String,
    @P("Two-letter country code; only 'NL' is supported. If empty, defaults to NL.")
    countryCode: String
  ): String = {
    ensureCountrySupported(Option(countryCode).getOrElse("")) match {
      case Left(msg) =>
        logger.info(s"OpenAgroKPI country unsupported: $msg")
        return msg
      case Right(cc) => logger.debug(s"OpenAgroKPI country validated: $cc")
    }

    val ids = Option(fieldIdsCsv).map(_.split(',').map(_.trim).filter(_.nonEmpty).toList).getOrElse(Nil)
    if (ids.isEmpty) return "Missing fieldIdsCsv. Provide one or more NL field ids as a comma-separated list."

    val lyr = resolveLayer(layer) match {
      case Left(err) => return err
      case Right(ok) => ok
    }

    if (apiKeyHeader().isEmpty && !Config.openAgroConfig.allowUnauthenticated) {
      logger.info("OpenAgroKPI call blocked: no API key configured and unauthenticated not allowed")
      return "OpenAgroKPI requires an API key (header 'x-api-key'). Configure env OPENAGRO_ACCESS_TOKEN or enable allow-unauthenticated for public endpoints."
    }

    val url = s"${buildBase()}/fields-$lyr"
    val query = Map(
      "details-level" -> "1",
      "info-level" -> "0"
    ) ++ Option(year).map(_.trim).filter(_.nonEmpty).map(y => Map("year" -> y)).getOrElse(Map.empty)

    // Body must be JSON array of field IDs
    val idValues: Seq[ujson.Value] = ids.map { id =>
      scala.util.Try(id.toLong).toOption match {
        case Some(n) => ujson.Num(n.toDouble)
        case None => ujson.Str(id)
      }
    }
    val body = ujson.Arr(idValues*)

    val resp = scala.util.Try(httpPost(url, body, query, acceptLanguage = "en")).toEither
    resp match
      case Left(ex) =>
        logger.info(s"OpenAgroKPI request failed for field_ids=${ids.size} layer=$lyr: ${ex.getMessage}")
        s"OpenAgroKPI request failed for ${ids.size} field ids, layer=$lyr. ${ex.getMessage}"
      case Right(r) if r.statusCode != 200 =>
        val bodyPrev = scala.util.Try(r.text()).toOption.map(preview(_)).getOrElse("")
        logger.info(s"OpenAgroKPI non-200 status=${r.statusCode} for $url, body preview=$bodyPrev")
        r.statusCode match
          case 401 | 403 =>
            val hint = if (apiKeyHeader().isEmpty) " Missing API key; set OPENAGRO_ACCESS_TOKEN." else " API key may be invalid or lacks permission."
            s"OpenAgroKPI authentication error (status ${r.statusCode}).$hint"
          case _ => s"OpenAgroKPI returned status ${r.statusCode}. Please try again later."
      case Right(r) =>
        val body = r.text()
        logger.debug(s"OpenAgroKPI fields-$lyr response (200) for ${ids.size} fields: ${preview(body)}")
        s"OpenAgroKPI fields-$lyr (POST) for ${ids.size} fields: ${preview(body, 1500)}\nService: ${buildBase()}\nDocs: ${Config.openAgroConfig.docsUrl}"
  }
}


object OpenAgroKpiTools {
  def getSpecifications: java.util.List[ToolSpecification] =
    ToolSpecifications.toolSpecificationsFrom(classOf[OpenAgroKpiTools])
}
