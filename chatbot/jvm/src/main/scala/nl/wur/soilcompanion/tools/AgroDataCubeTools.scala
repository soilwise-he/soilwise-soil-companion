package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool}
import nl.wur.soilcompanion.Config

/**
 * LLM tools for interacting with WUR AgroDataCube v2 REST API.
 *
 * Notes:
 * - Authentication: header 'Token' with the provided access token (no Bearer prefix).
 * - Most endpoints return GeoJSON, even when 'result=nogeom' is set.
 * - This initial implementation focuses on looking up a single field by location
 *   using a WKT POINT geometry in EPSG:4326 and the previous year by default.
 */
class AgroDataCubeTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // --- HTTP helpers ---

  private def tokenHeader(): Map[String, String] = {
    val token = Option(Config.agroDataCubeConfig.accessToken).map(_.trim).getOrElse("")
    if (token.nonEmpty && !token.equalsIgnoreCase("REPLACE_ME")) Map("Token" -> token) else Map.empty
  }

  private def baseHeaders: Map[String, String] =
    Map(
      "accept" -> "application/json",
      "User-Agent" -> Config.agroDataCubeConfig.userAgent
    ) ++ tokenHeader()

  private def httpGet(url: String, query: Map[String, String] = Map.empty): requests.Response = {
    val qs = if (query.isEmpty) "" else query.map { case (k, v) => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("?", "&", "")
    val full = s"$url$qs"
    logger.debug(s"AgroDataCube GET $full")
    requests.get(
      url = full,
      headers = baseHeaders,
      readTimeout = Config.agroDataCubeConfig.timeoutMs,
      connectTimeout = Config.agroDataCubeConfig.timeoutMs
    )
  }

  private def httpPost(url: String, jsonBody: ujson.Value, query: Map[String, String] = Map.empty): requests.Response = {
    val qs = if (query.isEmpty) "" else query.map { case (k, v) => s"${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }.mkString("?", "&", "")
    val full = s"$url$qs"
    logger.debug(s"AgroDataCube POST $full body=${jsonBody.render()}")
    requests.post(
      url = full,
      headers = baseHeaders ++ Map("Content-Type" -> "application/json"),
      data = jsonBody.render(),
      readTimeout = Config.agroDataCubeConfig.timeoutMs,
      connectTimeout = Config.agroDataCubeConfig.timeoutMs
    )
  }

  private def preview(body: String, max: Int = 2000): String =
    if (body.length > max) body.take(max) + s"... [truncated ${body.length - max} chars]" else body

  private def baseUrl: String = Config.agroDataCubeConfig.baseUrl.stripSuffix("/")

  // --- GeoJSON parsing helpers & in-session memory ---

  private case class FieldSummary(
    id: String,
    title: String,
    year: Option[String],
    cropCode: Option[String],
    cropName: Option[String]
  )

  private case class FieldContext(
    fieldId: String,
    year: String,
    cropCode: Option[String],
    cropName: Option[String]
  )

  // Per-assistant/session memory. Assistant instances are scoped per chat session in SoilCompanionServer,
  // and tools are instantiated per Assistant in AssistantLive.apply(), so this var is session-scoped.
  @volatile private var lastFieldContext: Option[FieldContext] = None

  /**
   * Extract first field identifier from an AgroDataCube GeoJSON response.
   * The API typically returns a FeatureCollection with features[].properties containing an id/field id.
   */
  private def parseFirstFieldIdFromGeoJson(jsonStr: String): Option[FieldSummary] = {
    def asString(v: ujson.Value): Option[String] = v match
      case ujson.Str(s) => Some(s)
      case ujson.Num(n) =>
        // If the double is an integer, render without decimal part
        val asLong = n.toLong
        if (asLong.toDouble == n) Some(asLong.toString) else Some(n.toString)
      case ujson.Bool(b) => Some(b.toString)
      case _ => None

    val js = try ujson.read(jsonStr) catch
      case e: Throwable =>
        logger.warn(s"Failed to parse AgroDataCube GeoJSON: ${e.getMessage}")
        return None

    def extractIdFromProps(props: ujson.Value): Option[String] = {
      val m = props.obj
      m.get("id").flatMap(asString)
        .orElse(m.get("fieldId").flatMap(asString))
        .orElse(m.get("fieldid").flatMap(asString))
        .orElse(m.get("fid").flatMap(asString))
        .orElse(m.get("FIELDID").flatMap(asString))
        .orElse(m.get("code").flatMap(asString))
    }

    def extractTitleFromProps(props: ujson.Value): String = {
      val m = props.obj
      m.get("title").map(_.str)
        .orElse(m.get("naam").map(_.str))
        .orElse(m.get("name").map(_.str))
        .getOrElse("")
    }

    def extractYearFromProps(props: ujson.Value): Option[String] = {
      val m = props.obj
      m.get("year").flatMap(asString)
    }

    def extractCropCodeFromProps(props: ujson.Value): Option[String] = {
      val m = props.obj
      m.get("crop_code").flatMap(asString)
    }

    def extractCropNameFromProps(props: ujson.Value): Option[String] = {
      val m = props.obj
      m.get("crop_name").flatMap {
        case ujson.Str(s) => Some(s)
        case other => asString(other)
      }
    }

    def fromFeature(feature: ujson.Value): Option[FieldSummary] = {
      val propsOpt = scala.util.Try(feature("properties")).toOption
      propsOpt.flatMap { p =>
        extractIdFromProps(p).map { id =>
          FieldSummary(
            id = id,
            title = extractTitleFromProps(p),
            year = extractYearFromProps(p),
            cropCode = extractCropCodeFromProps(p),
            cropName = extractCropNameFromProps(p)
          )
        }
      }
    }

    val objMap = js.obj
    val tpe = objMap.get("type").map(_.str).getOrElse("")
    if (tpe.equalsIgnoreCase("FeatureCollection")) {
      val firstFeatureOpt = objMap.get("features")
        .flatMap(v => scala.util.Try(v.arr).toOption)
        .flatMap(_.headOption)
      firstFeatureOpt.flatMap(fromFeature)
    } else if (tpe.equalsIgnoreCase("Feature")) {
      fromFeature(js)
    } else {
      // Sometimes the API might give a plain object with identifiers
      val idOpt = objMap.get("id").flatMap(asString)
        .orElse(objMap.get("fieldId").flatMap(asString))
        .orElse(objMap.get("fieldid").flatMap(asString))
      idOpt.map { id =>
        FieldSummary(
          id = id,
          title = objMap.get("title").flatMap(asString).getOrElse(""),
          year = objMap.get("year").flatMap(asString),
          cropCode = objMap.get("crop_code").flatMap(asString),
          cropName = objMap.get("crop_name").flatMap(asString)
        )
      }
    }
  }

  /**
   * Look up a single field for a given location using WKT POINT geometry in EPSG:4326.
   * Uses previous calendar year when 'year' is empty.
   */
  @Tool(Array(
    "Preferred tool for crop parcel (field) information in The Netherlands (NL) only.",
    "Looks up a single AgroDataCube field (parcel) for a WGS84 location using a WKT POINT geometry and the previous year if not provided.",
    "Use when the selected location is inside The Netherlands and you need a field/parcel ID or the crop metadata (year, crop_code, crop_name) for that field.",
    "Do not use this tool for locations outside NL; choose other tools/services for other countries."
  ))
  def getAgroDataCubeFieldByLocation(
    @P("Latitude in decimal degrees (WGS84). Example: 52.75699. Only use if the location is in NL.")
    lat: Double,
    @P("Longitude in decimal degrees (WGS84). Example: 5.61071. Only use if the location is in NL.")
    lon: Double,
    @P("Optional year (e.g., '2019'); defaults to previous calendar year when empty. If unknown, leave empty.")
    year: String
  ): String = {
    val resolvedYear = Option(year).map(_.trim).filter(_.nonEmpty).getOrElse {
      val y = java.time.LocalDate.now().getYear - 1
      logger.debug(s"AgroDataCube: no year provided, defaulting to previous year: $y")
      y.toString
    }

    val wktPoint = s"POINT($lon $lat)" // WKT must be lon lat for EPSG:4326
    val url = s"${baseUrl}/fields"
    val query = Map(
      "year" -> resolvedYear,
      "geometry" -> wktPoint,
      "epsg" -> "4326",
      "output_epsg" -> "4326",
      "result" -> "nogeom"
    )

    val respOpt = scala.util.Try(httpGet(url, query)).toOption
    respOpt match
      case None =>
        s"AgroDataCube request failed for lat=$lat lon=$lon year=$resolvedYear. The service may be unavailable or requires authentication."
      case Some(r) if r.statusCode != 200 =>
        val bodyPrev = scala.util.Try(r.text()).toOption.map(preview(_)).getOrElse("")
        logger.info(s"AgroDataCube non-200 status=${r.statusCode}, body preview=$bodyPrev")
        r.statusCode match
          case 401 | 403 =>
            val hint = if (tokenHeader().isEmpty) " Missing token; set environment variable AGRODATACUBE_ACCESS_TOKEN." else " Token may be invalid or lacks permission."
            s"AgroDataCube authentication error (status ${r.statusCode}).$hint"
          case _ => s"AgroDataCube returned status ${r.statusCode} for field lookup. Please try again later."
      case Some(r) =>
        val body = r.text()
        logger.debug(s"AgroDataCube fields response (200): ${preview(body)}")
        val firstOpt = parseFirstFieldIdFromGeoJson(body)
        firstOpt match
          case Some(fs) =>
            // Build and store session-scoped field context for reuse
            val ctx = FieldContext(
              fieldId = fs.id,
              year = fs.year.getOrElse(resolvedYear),
              cropCode = fs.cropCode,
              cropName = fs.cropName
            )
            lastFieldContext = Some(ctx)

            val titlePart = if (fs.title.nonEmpty) s" (${fs.title})" else ""
            val cropPart = (ctx.cropCode, ctx.cropName) match
              case (Some(code), Some(name)) if name.nonEmpty => s" crop_code=$code, crop_name=\"$name\""
              case (Some(code), None) => s" crop_code=$code"
              case (None, Some(name)) if name.nonEmpty => s" crop_name=\"$name\""
              case _ => ""
            s"Found field (year=${ctx.year}): ID ${ctx.fieldId}$titlePart.$cropPart Saved to session memory for reuse. Service: AgroDataCube (${baseUrl}). Docs: ${Config.agroDataCubeConfig.docsUrl}"
          case None =>
            "No field found for the specified location. If the point falls near parcel edges, try slightly different coordinates."
  }

  @Tool(Array(
    "Preferred tool to resolve crop parcel (field) info in The Netherlands (NL) from a UI 'location context' JSON.",
    "Use only when the user's selected location is within NL; it will return the field/parcel ID and save crop metadata (year, crop_code, crop_name) for reuse in this session.",
    "Do not call for locations outside NL."
  ))
  def getAgroDataCubeFieldFromLocationContext(
    @P("Location context JSON with at least 'lat' and 'lon'. Use only for NL locations.")
    locationContextJson: String,
    @P("Optional year string (e.g., '2019'). Defaults to previous year when empty.")
    year: String
  ): String = {
    val (lat, lon) = try
      val js = ujson.read(locationContextJson)
      (js("lat").num, js("lon").num)
    catch
      case e: Throwable =>
        logger.warn(s"Failed to parse location context JSON: ${e.getMessage}")
        return "Could not parse the location context JSON. Please provide 'lat' and 'lon'."

    getAgroDataCubeFieldByLocation(lat, lon, year)
  }

  @Tool(Array(
    "Returns the last AgroDataCube (NL only) field context saved in this chat session, if any.",
    "The context includes: fieldId, year, crop_code, crop_name. Use to reuse previously found NL field metadata in follow-up calls.",
    "Note: AgroDataCube covers The Netherlands; do not use this for other countries."
  ))
  def getSavedAgroDataCubeFieldContext(): String = {
    lastFieldContext match
      case Some(ctx) =>
        val cropCodeStr = ctx.cropCode.getOrElse("")
        val cropNameStr = ctx.cropName.getOrElse("")
        val json = ujson.Obj(
          "fieldId" -> ctx.fieldId,
          "year" -> ctx.year,
          "crop_code" -> (if cropCodeStr.nonEmpty then cropCodeStr else ujson.Null),
          "crop_name" -> (if cropNameStr.nonEmpty then cropNameStr else ujson.Null)
        )
        s"AgroDataCube saved field context: ${json.render()}"
      case None =>
        "No AgroDataCube field context is saved yet in this session. First call a field lookup tool."
  }

  // ---- Additional NL-only tools using a known AgroDataCube field ID ----

  private def resolveFieldIdOrSaved(provided: String): Either[String, String] = {
    val id = Option(provided).map(_.trim).getOrElse("")
    if (id.nonEmpty) Right(id)
    else lastFieldContext.map(_.fieldId).toRight("No fieldId provided and none saved in session. First call getAgroDataCubeFieldByLocation to resolve a field in NL.")
  }

  private def summarizeNon200(service: String, r: requests.Response): String = {
    val bodyPrev = scala.util.Try(r.text()).toOption.map(preview(_)).getOrElse("")
    logger.info(s"$service non-200 status=${r.statusCode}, body preview=$bodyPrev")
    r.statusCode match
      case 401 | 403 =>
        val hint = if (tokenHeader().isEmpty) " Missing token; set environment variable AGRODATACUBE_ACCESS_TOKEN." else " Token may be invalid or lacks permission."
        s"$service authentication error (status ${r.statusCode}).$hint"
      case _ => s"$service returned status ${r.statusCode}. Please try again later."
  }

  private def safeJsonRead(body: String): Option[ujson.Value] = scala.util.Try(ujson.read(body)).toOption

  @Tool(Array(
    "NL-only. Retrieves crop history for a given AgroDataCube field (parcel) ID.",
    "If fieldId is empty, uses the last saved NL field from this session."
  ))
  def getAgroDataCubeCropHistory(
    @P("AgroDataCube fieldId; leave empty to use the last saved field from this session (NL only).")
    fieldId: String,
    @P("Optional: limit to the most recent N records (default 5).")
    limitYears: Int
  ): String = {
    val fidEither = resolveFieldIdOrSaved(fieldId)
    fidEither match
      case Left(msg) => msg
      case Right(fid) =>
        val url = s"${baseUrl}/fields/${fid}/crophistory"
        val rOpt = scala.util.Try(httpGet(url)).toOption
        rOpt match
          case None => s"AgroDataCube request failed for crop history of field $fid."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube crop history", r)
          case Some(r) =>
            val body = r.text()
            logger.debug(s"AgroDataCube crop history (200) for field $fid: ${preview(body)}")
            val limited =
              if (limitYears > 0) then
                safeJsonRead(body).flatMap(js => scala.util.Try(js.arr.toList).toOption).map(_.take(limitYears)).map(ujson.Arr(_*)).map(_.render()).getOrElse(preview(body))
              else preview(body)
            s"Crop history for field $fid (most recent ${if limitYears>0 then limitYears else "all"}):\n$limited"
  }

  @Tool(Array(
    "NL-only. Retrieves the crop rotation index for a given AgroDataCube field (parcel) ID.",
    "The crop rotation index is a number between 0.0 and 1.0 that indicates how intensive the crop rotation is for the field and its soil. Lower values are worse.",
    "If fieldId is empty, uses the last saved NL field from this session."
  ))
  def getAgroDataCubeCropRotationIndex(
    @P("AgroDataCube fieldId; leave empty to use the last saved field from this session (NL only).")
    fieldId: String
  ): String = {
    val fidEither = resolveFieldIdOrSaved(fieldId)
    fidEither match
      case Left(msg) => msg
      case Right(fid) =>
        val url = s"${baseUrl}/fields/${fid}/croprotationindex"
        val rOpt = scala.util.Try(httpGet(url)).toOption
        rOpt match
          case None => s"AgroDataCube request failed for crop rotation index of field $fid."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube crop rotation index", r)
          case Some(r) =>
            val body = r.text()
            logger.debug(s"AgroDataCube crop rotation index (200) for field $fid: ${preview(body)}")
            s"Crop rotation index for field $fid:\n${preview(body)}"
  }

  // TODO
  //  @Tool(Array(
  //    "NL-only. Retrieves field markers for a given AgroDataCube field (parcel) ID.",
  //    "For arable land the markers are 'voorjaar_tst' (the average yield of the previous year) and 'voorjaar_tst_max' (the maximum yield of the previous year).",
  //    "For grasslands the markers are 'grasland_tst' (the average yield of the previous year) and 'grasland_tst_max' (the maximum yield of the previous year).",
  //    "Use landUse='bouwland' (arable land) or 'grasland' (grasslands). If fieldId is empty, uses the last saved NL field from this session."
  //  ))
  //  def getAgroDataCubeFieldMarkers(
  //    @P("AgroDataCube fieldId; leave empty to use the last saved field from this session (NL only).")
  //    fieldId: String,
  //    @P("Land use type: 'bouwland' or 'grasland'.")
  //    landUse: String
  //  ): String = {
  //    val lu = Option(landUse).map(_.trim.toLowerCase).getOrElse("")
  //    val path = lu match
  //      case "bouwland" => "bouwland_markers"
  //      case "grasland" => "grasland_markers"
  //      case other => return s"Unsupported landUse '$other'. Use 'bouwland' or 'grasland'."
  //    val fidEither = resolveFieldIdOrSaved(fieldId)
  //    fidEither match
  //      case Left(msg) => msg
  //      case Right(fid) =>
  //        val url = s"${baseUrl}/fields/${fid}/${path}"
  //        val rOpt = scala.util.Try(httpGet(url)).toOption
  //        rOpt match
  //          case None => s"AgroDataCube request failed for field markers ($lu) of field $fid."
  //          case Some(r) if r.statusCode != 200 => summarizeNon200(s"AgroDataCube $lu markers", r)
  //          case Some(r) =>
  //            val body = r.text()
  //            logger.debug(s"AgroDataCube field markers ($lu) (200) for field $fid: ${preview(body)}")
  //            // Try to surface 'voorjaar_tst' if present
  //            val hint = safeJsonRead(body).flatMap { js =>
  //              scala.util.Try {
  //                val m = js.obj
  //                m.get("voorjaar_tst").flatMap { v =>
  //                  scala.util.Try(v.arr.toList.map{
  //                    case ujson.Str(s) => s
  //                    case other => other.toString
  //                  }).toOption.map(list => s" voorjaar_tst=${list.mkString(", ")}")
  //                }
  //              }.toOption.flatten
  //            }.getOrElse("")
  //            s"Field markers ($lu) for field $fid:$hint\n${preview(body)}"
  //  }

  @Tool(Array(
    "NL-only. Looks up crop code metadata in AgroDataCube (e.g., name, description)."
  ))
  def getAgroDataCubeCropCodeInfo(
    @P("Crop code as used by RVO/AgroDataCube, e.g., '233' or '331'.")
    cropCode: String
  ): String = {
    val code = Option(cropCode).map(_.trim).filter(_.nonEmpty).getOrElse(return "Please provide a cropCode (e.g., '233').")
    val url = s"${baseUrl}/codes/cropcodes/${java.net.URLEncoder.encode(code, "UTF-8") }"
    val rOpt = scala.util.Try(httpGet(url)).toOption
    rOpt match
      case None => s"AgroDataCube request failed for crop code $code."
      case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube crop code", r)
      case Some(r) =>
        val body = r.text()
        logger.debug(s"AgroDataCube crop code (200) for code $code: ${preview(body)}")
        s"Crop code $code info:\n${preview(body)}"
  }

  @Tool(Array(
    "NL-only. Looks up soil code metadata in AgroDataCube (e.g., name, description)."
  ))
  def getAgroDataCubeSoilCodeInfo(
    @P("Soil code, e.g., 'pZn21'.")
    soilCode: String
  ): String = {
    val code = Option(soilCode).map(_.trim).filter(_.nonEmpty).getOrElse(return "Please provide a soilCode (e.g., 'pZn21').")
    val url = s"${baseUrl}/codes/soilcodes/${java.net.URLEncoder.encode(code, "UTF-8") }"
    val rOpt = scala.util.Try(httpGet(url)).toOption
    rOpt match
      case None => s"AgroDataCube request failed for soil code $code."
      case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube soil code", r)
      case Some(r) =>
        val body = r.text()
        logger.debug(s"AgroDataCube soil code (200) for code $code: ${preview(body)}")
        s"Soil code $code info:\n${preview(body)}"
  }

  private def resolveFieldIdsCsvOrSaved(fieldIdsCsv: String): Either[String, String] = {
    val csv = Option(fieldIdsCsv).map(_.trim).getOrElse("")
    if (csv.nonEmpty) Right(csv)
    else lastFieldContext.map(_.fieldId).toRight("No fieldids provided and no saved field in session; provide 'fieldids' or first run a field lookup.").map(id => id)
  }

  @Tool(Array(
    "NL-only. Requests the Soil Physical KPI datapackage for one or more AgroDataCube fields.",
    "If fieldIdsCsv is empty, uses the last saved field from this session.") )
  def getAgroDataCubeKpiSoilPhysical(
    @P("Comma-separated field IDs (e.g., '23045969' or '3154523,3154523'). Leave empty to use saved field.")
    fieldIdsCsv: String
  ): String = {
    resolveFieldIdsCsvOrSaved(fieldIdsCsv) match
      case Left(msg) => msg
      case Right(ids) =>
        val url = s"${baseUrl}/datapackage/kpi/bodemfysisch"
        val body = ujson.Obj("fieldids" -> ids)
        val rOpt = scala.util.Try(httpPost(url, body, Map("nogeom" -> ""))).toOption
        rOpt match
          case None => s"AgroDataCube request failed for KPI bodemfysisch (ids=$ids)."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube KPI bodemfysisch", r)
          case Some(r) =>
            val resp = r.text()
            logger.debug(s"AgroDataCube KPI bodemfysisch (200) for ids=$ids: ${preview(resp)}")
            s"KPI bodemfysisch for fieldids=[$ids]:\n${preview(resp)}"
  }

  @Tool(Array(
    "NL-only. Requests the Greenness KPI datapackage for one or more AgroDataCube fields.",
    "The greenness data contains a time series of Normalized Difference Vegetation Index (NDVI) values (between 0.0 and 1.0) for each field.",
    "If fieldIdsCsv is empty, uses the last saved field from this session.") )
  def getAgroDataCubeKpiGreenness(
    @P("Comma-separated field IDs. Leave empty to use saved field.")
    fieldIdsCsv: String
  ): String = {
    resolveFieldIdsCsvOrSaved(fieldIdsCsv) match
      case Left(msg) => msg
      case Right(ids) =>
        val url = s"${baseUrl}/datapackage/kpi/greenness"
        val body = ujson.Obj("fieldids" -> ids)
        val rOpt = scala.util.Try(httpPost(url, body)).toOption
        rOpt match
          case None => s"AgroDataCube request failed for KPI greenness (ids=$ids)."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube KPI greenness", r)
          case Some(r) =>
            val resp = r.text()
            logger.debug(s"AgroDataCube KPI greenness (200) for ids=$ids: ${preview(resp)}")
            s"KPI greenness for fieldids=[$ids]:\n${preview(resp)}"
  }

  @Tool(Array(
    "NL-only. Requests the soil map benchmarking datapackage for one or more fields (grondsoortenkaart).",
    "If fieldIdsCsv is empty, uses the last saved field from this session.") )
  def getAgroDataCubeKpiSoilMapBenchmark(
    @P("Comma-separated field IDs. Leave empty to use saved field.")
    fieldIdsCsv: String
  ): String = {
    resolveFieldIdsCsvOrSaved(fieldIdsCsv) match
      case Left(msg) => msg
      case Right(ids) =>
        val url = s"${baseUrl}/datapackage/kpi/grondsoortenkaart"
        val body = ujson.Obj("fieldids" -> ids)
        val rOpt = scala.util.Try(httpPost(url, body)).toOption
        rOpt match
          case None => s"AgroDataCube request failed for KPI grondsoortenkaart (ids=$ids)."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube KPI grondsoortenkaart", r)
          case Some(r) =>
            val resp = r.text()
            logger.debug(s"AgroDataCube KPI grondsoortenkaart (200) for ids=$ids: ${preview(resp)}")
            s"KPI grondsoortenkaart for fieldids=[$ids]:\n${preview(resp)}"
  }

  @Tool(Array(
    "NL-only. Requests reference values datapackage for one or more fields (referentiewaarde).",
    "If fieldIdsCsv is empty, uses the last saved field from this session.") )
  def getAgroDataCubeKpiReferenceValues(
    @P("Comma-separated field IDs. Leave empty to use saved field.")
    fieldIdsCsv: String
  ): String = {
    resolveFieldIdsCsvOrSaved(fieldIdsCsv) match
      case Left(msg) => msg
      case Right(ids) =>
        val url = s"${baseUrl}/datapackage/kpi/referentiewaarde"
        val body = ujson.Obj("fieldids" -> ids)
        val rOpt = scala.util.Try(httpPost(url, body, Map("nogeom" -> ""))).toOption
        rOpt match
          case None => s"AgroDataCube request failed for KPI referentiewaarde (ids=$ids)."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube KPI referentiewaarde", r)
          case Some(r) =>
            val resp = r.text()
            logger.debug(s"AgroDataCube KPI referentiewaarde (200) for ids=$ids: ${preview(resp)}")
            s"KPI referentiewaarde for fieldids=[$ids]:\n${preview(resp)}"
  }

  @Tool(Array(
    "NL-only. Retrieves crop rotation KPI package for field(s). Uses GET with fieldids query.",
    "The crop rotation data contains information about crops grown at the field's location in previous years.",
    "If fieldIdsCsv is empty, uses the last saved field from this session.") )
  def getAgroDataCubeKpiCropRotation(
    @P("Comma-separated field IDs. Leave empty to use saved field.")
    fieldIdsCsv: String
  ): String = {
    resolveFieldIdsCsvOrSaved(fieldIdsCsv) match
      case Left(msg) => msg
      case Right(ids) =>
        val url = s"${baseUrl}/datapackage/kpi/croprotation"
        val rOpt = scala.util.Try(httpGet(url, Map("fieldids" -> ids))).toOption
        rOpt match
          case None => s"AgroDataCube request failed for KPI croprotation (ids=$ids)."
          case Some(r) if r.statusCode != 200 => summarizeNon200("AgroDataCube KPI croprotation", r)
          case Some(r) =>
            val resp = r.text()
            logger.debug(s"AgroDataCube KPI croprotation (200) for ids=$ids: ${preview(resp)}")
            s"KPI croprotation for fieldids=[$ids]:\n${preview(resp)}"
  }
}
