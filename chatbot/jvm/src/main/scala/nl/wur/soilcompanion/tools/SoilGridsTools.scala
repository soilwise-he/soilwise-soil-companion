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

import java.net.URLEncoder

/**
 * LLM tools to query ISRIC SoilGrids v2.0 for soil information at a location.
 *
 * Important usage warning (also repeated in outputs):
 * - SoilGrids provides modelled estimates at ~250 m grid resolution. Values are not field measurements
 *   and may deviate locally, particularly in heterogeneous terrain, urban areas, or disturbed soils.
 * - Always interpret results as indicative. For decisions with real-world impact, verify with local data
 *   and professional advice.
 */
class SoilGridsTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  private case class SGDepthValue(depth: String, unit: String, stat: String, value: Double)

  /**
   * Build the SoilGrids properties query URL.
   */
  private def buildQueryUrl(lat: Double, lon: Double, properties: Seq[String], depths: Seq[String], stat: String): String = {
    val base = Config.soilGridsConfig.baseUrl.stripSuffix("/")
    val endpoint = Config.soilGridsConfig.queryEndpoint.stripPrefix("/")
    def enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    val propParams = properties.map(p => s"&property=${enc(p)}").mkString
    val depthParams = depths.map(d => s"&depth=${enc(d)}").mkString
    s"$base/$endpoint?lon=$lon&lat=$lat$propParams$depthParams&value=${enc(stat)}"
  }

  /**
   * Parse a minimal SoilGrids response for the selected properties/depths.
   * The JSON shape (as of v2.0 properties/query) resembles:
   * {
   *   "properties": {
   *     "bdod": { "unit": "kg/m3", "values": { "0-5cm": {"mean": 123.4}, ... } },
   *     "soc":  { "unit": "g/kg",  "values": { "0-5cm": {"mean": 12.3}, ... } }
   *   },
   *   "links": [...]
   * }
   */
  private def parseValues(jsonStr: String, properties: Seq[String], depths: Seq[String], stat: String): Map[String, List[SGDepthValue]] = {
    val js = ujson.read(jsonStr)

    // SoilGrids properties/query currently responds with a GeoJSON Feature-like object
    // with shape: { type: "Feature", geometry: {...}, properties: { layers: [ { name, unit_measure{...}, depths: [ {label, values{stat}} ] }, ... ] }, query_time_s: ... }
    // However, be defensive and support legacy or alternative shapes if they appear.

    def parseFromLayers(): Map[String, List[SGDepthValue]] = {
      val propsOpt = js.obj.get("properties").map(_.obj)
      val layers = propsOpt.flatMap(_.get("layers")).map(_.arr).getOrElse(IndexedSeq.empty)
      logger.debug(s"SoilGrids parser: properties.layers count=${layers.size}")

      // Index layers by their 'name'
      val byName: Map[String, ujson.Value] = layers.flatMap { l =>
        val nameOpt = l.obj.get("name").map(_.str)
        nameOpt.map(_ -> l)
      }.toMap

      properties.map { propName =>
        val maybeLayer = byName.get(propName)
        val (unit, dFactor) = maybeLayer.flatMap { layer =>
          val um = layer.obj.get("unit_measure").map(_.obj)
          val targetUnits = um.flatMap(_.get("target_units")).map(_.str).getOrElse("")
          val mappedUnits = um.flatMap(_.get("mapped_units")).map(_.str).getOrElse("")
          val df = um.flatMap(_.get("d_factor")).map { v =>
            try v.num.toDouble catch case _: Throwable => 1.0
          }.getOrElse(1.0)
          Some((if targetUnits.nonEmpty then targetUnits else mappedUnits, df))
        }.getOrElse(("", 1.0))

        val rows: List[SGDepthValue] = maybeLayer.toList.flatMap { layer =>
          val depthArr = layer.obj.get("depths").map(_.arr).getOrElse(IndexedSeq.empty)
          // Build map label -> valuesObj for quick lookup
          val depthMap: Map[String, ujson.Value] = depthArr.flatMap { d =>
            val labelOpt = d.obj.get("label").map(_.str)
            val valuesOpt = d.obj.get("values")
            (labelOpt, valuesOpt) match
              case (Some(lbl), Some(vs)) => Some(lbl -> vs)
              case _ => None
          }.toMap

          depths.flatMap { dLabel =>
            depthMap.get(dLabel).flatMap { vObj =>
              val vMap = vObj.obj
              val rawValOpt = vMap.get(stat).map(_.num).orElse(vMap.headOption.map(_._2.num))
              rawValOpt.map { raw =>
                val scaled = try if (dFactor != 0) raw / dFactor else raw catch case _: Throwable => raw
                SGDepthValue(dLabel, unit, stat, scaled)
              }
            }
          }.toList
        }

        propName -> rows
      }.toMap
    }

    def parseLegacySimple(): Map[String, List[SGDepthValue]] = {
      val propsObj = js.obj.get("properties").map(_.obj).getOrElse(Map.empty[String, ujson.Value])
      val tuples: Seq[(String, List[SGDepthValue])] = properties.flatMap { prop =>
        propsObj.get(prop).toList.map { propVal =>
          val propObj = propVal.obj
          val unit = propObj.get("unit").map(_.str).getOrElse("")
          val valuesObj = propObj.get("values").map(_.obj).getOrElse(Map.empty[String, ujson.Value])
          val rows = depths.flatMap { d =>
            valuesObj.get(d).flatMap { depthVal =>
              val depthObj = depthVal.obj
              val v = depthObj.get(stat).map(_.num).orElse(depthObj.headOption.map(_._2.num))
              v.map(n => SGDepthValue(d, unit, stat, n))
            }
          }.toList
          prop -> rows
        }
      }
      tuples.toMap
    }

    // Prefer parsing from layers; if no layers are present, fallback to legacy shape
    val parsed = parseFromLayers()
    if parsed.nonEmpty then parsed else parseLegacySimple()
  }

  private def formatResult(lat: Double, lon: Double, values: Map[String, List[SGDepthValue]]): String = {
    val warn = Config.soilGridsConfig.usageWarning
    val docsUrl = Config.soilGridsConfig.docsUrl
    val termsUrl = Config.soilGridsConfig.termsUrl
    val header = f"SoilGrids v2.0 estimates for location: lat=$lat%.5f, lon=$lon%.5f (approx. 250 m grid)\n"
    val body = if values.isEmpty then "No values returned for the requested properties/depths."
    else values.map { case (prop, rows) =>
      val lines = if rows.nonEmpty then rows.map { r => s"- $prop ${r.depth}: ${r.value} ${r.unit} (${r.stat})" }.mkString("\n")
      else s"- $prop: no data"
      lines
    }.mkString("\n")

    s"""$header
       |$body
       |
       |Caution: $warn
       |Source: ISRIC SoilGrids v2.0 ($docsUrl)
       |Terms: $termsUrl
       |API: ${Config.soilGridsConfig.baseUrl}
       |""".stripMargin
  }

  private def doQuery(lat: Double, lon: Double, properties: Seq[String], depths: Seq[String], stat: String): String = {
    try {
      val url = buildQueryUrl(lat, lon, properties, depths, stat)
      logger.debug(s"SoilGrids query URL: $url")
      val response = requests.get(
        url = url,
        headers = Map(
          "accept" -> "application/json",
          "User-Agent" -> Config.soilGridsConfig.userAgent
        ),
        readTimeout = Config.soilGridsConfig.timeoutMs,
        connectTimeout = Config.soilGridsConfig.timeoutMs
      )
      logger.debug(s"SoilGrids HTTP status: ${response.statusCode}")
      val body = response.text()
      val preview = if (body.length > 2000) body.take(2000) + s"... [truncated ${body.length - 2000} chars]" else body
      logger.debug(s"SoilGrids raw response preview: $preview")
      if (response.statusCode != 200) then
        s"SoilGrids service returned status ${response.statusCode}. Please try again later."
      else
        val vals = parseValues(body, properties, depths, stat)
        logger.debug(s"SoilGrids parsed properties: ${vals.keys.mkString(",")} ; emptyProps=${vals.count(_._2.isEmpty)}")
        formatResult(lat, lon, vals)
    } catch
      case e: Throwable =>
        logger.error(s"SoilGrids query failed for lat=$lat lon=$lon props=${properties.mkString(",")} depths=${depths.mkString(",")} stat=$stat", e)
        "The SoilGrids service is currently unavailable or the request failed. Please try again later."
  }

  @Tool(Array(
    "Returns SoilGrids v2.0 estimated soil properties at a latitude/longitude.",
    "Use this when the user's geographic coordinates are known."
  ))
  def getSoilGridsAtLocation(
    @P("Latitude in decimal degrees (WGS84). Example: 52.0929")
    lat: Double,
    @P("Longitude in decimal degrees (WGS84). Example: 5.1045")
    lon: Double,
    @P("Optional comma-separated list of SoilGrids property codes (e.g. 'bdod,soc,clay'). If empty, use defaults from configuration.")
    propertiesCsv: String,
    @P("Optional comma-separated list of standard depths (e.g. '0-5cm,5-15cm'). If empty, use defaults from configuration.")
    depthsCsv: String,
    @P("Statistic/value to return (e.g. 'mean', 'Q0.05', 'Q0.50', 'Q0.95'). If empty, use configuration default.")
    valueStat: String
  ): String = {
    val props = Option(propertiesCsv).map(_.trim).filter(_.nonEmpty).map(_.split(',').map(_.trim).toSeq).getOrElse(Config.soilGridsConfig.defaultProperties)
    val depths = Option(depthsCsv).map(_.trim).filter(_.nonEmpty).map(_.split(',').map(_.trim).toSeq).getOrElse(Config.soilGridsConfig.defaultDepths)
    val stat = Option(valueStat).map(_.trim).filter(_.nonEmpty).getOrElse(Config.soilGridsConfig.defaultValueStat)
    doQuery(lat, lon, props, depths, stat)
  }

  @Tool(Array(
    "Parses a previously stored 'location context' JSON (as provided by the UI) and queries SoilGrids for that location.",
    "Use this when you have a JSON object containing at least 'lat' and 'lon'."
  ))
  def getSoilGridsFromLocationContext(
    @P("Location context JSON string containing 'lat' and 'lon' fields, optionally 'countryCode', 'zoom', etc.")
    locationContextJson: String
  ): String = {
    try
      val js = ujson.read(locationContextJson)
      val lat = js("lat").num
      val lon = js("lon").num
      doQuery(lat, lon, Config.soilGridsConfig.defaultProperties, Config.soilGridsConfig.defaultDepths, Config.soilGridsConfig.defaultValueStat)
    catch
      case _: Throwable => "Invalid or missing location context JSON. Provide 'lat' and 'lon' fields."
  }
}


object SoilGridsTools {
  def getSpecifications: java.util.List[ToolSpecification] =
    ToolSpecifications.toolSpecificationsFrom(classOf[SoilGridsTools])
}
