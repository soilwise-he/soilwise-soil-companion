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

import java.util.UUID

/**
 * LLM tools to create interactive maps for visualizing spatial data.
 *
 * This tool generates map placeholders (e.g. `[[MAP:map-abc123]]`) that pass through the LLM
 * and appear in the streamed response text. The actual map configuration (center, zoom, markers,
 * polygons, basemap) is emitted out-of-band via the `mapEventSink` callback as a JSON payload
 * with event type "map_data". The frontend stores those payloads keyed by map ID and, when
 * streaming is complete ("done"), replaces every `[[MAP:<id>]]` placeholder with a rendered
 * Leaflet map container.
 *
 * This approach avoids sending large HTML blobs through the LLM context window and keeps the
 * streaming text clean.
 *
 * @param mapEventSink Called for each generated map with a JSON string:
 *                     `{"mapId": "...", "config": { ... leaflet config ... }, "title": "..." }`
 *                     The server wires this to a WebSocket QueryEvent("map_data", ...) send.
 */
class MapTools(mapEventSink: String => Unit = _ => ()) {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /**
   * Generate a unique map ID for each map instance.
   */
  private def generateMapId(): String = s"map-${UUID.randomUUID().toString.take(8)}"

  /**
   * Emit map config out-of-band and return a placeholder string for the LLM response.
   */
  private def emitMapAndPlaceholder(
    mapId: String,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    markers: Seq[MapMarker],
    polygons: Seq[MapPolygon],
    basemap: String,
    title: Option[String]
  ): String = {

    val configObj = ujson.Obj(
      "center"   -> ujson.Arr(centerLat, centerLon),
      "zoom"     -> zoom,
      "basemap"  -> basemap,
      "markers"  -> ujson.Arr(markers.map { m =>
        ujson.Obj(
          "lat"   -> m.lat,
          "lon"   -> m.lon,
          "popup" -> m.popup.getOrElse(""),
          "color" -> m.color.getOrElse("#3388ff")
        )
      }: _*),
      "polygons" -> ujson.Arr(polygons.map { p =>
        ujson.Obj(
          "coordinates" -> ujson.Arr(p.coordinates.map(c => ujson.Arr(c._1, c._2)): _*),
          "fillColor"   -> p.fillColor.getOrElse("#3388ff"),
          "strokeColor" -> p.strokeColor.getOrElse("#3388ff"),
          "fillOpacity" -> p.fillOpacity.getOrElse(0.2),
          "label"       -> p.label.getOrElse("")
        )
      }: _*)
    )

    val eventPayload = ujson.Obj(
      "mapId"  -> mapId,
      "config" -> configObj,
      "title"  -> title.getOrElse("")
    )

    try {
      mapEventSink(eventPayload.render(indent = 0))
    } catch {
      case e: Throwable =>
        logger.error(s"[MapTools] Failed to emit map event for $mapId", e)
    }

    // Return a short placeholder token the LLM will copy verbatim into its response.
    // The frontend replaces this with the actual map widget after streaming completes.
    s"[[MAP:$mapId]]"
  }

  /**
   * Emit a SoilGrids WMS map config out-of-band and return a placeholder.
   */
  private def emitSoilGridsMapAndPlaceholder(
    mapId: String,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    property: String,
    depth: String,
    title: Option[String]
  ): String = {

    val wmsLayer = s"${property}_${depth}_mean"
    val mapFile  = s"/map/${property}.map"

    val configObj = ujson.Obj(
      "center"   -> ujson.Arr(centerLat, centerLon),
      "zoom"     -> zoom,
      "basemap"  -> "osm",
      "wmsLayer" -> wmsLayer,
      "wmsUrl"   -> s"/wms-proxy?map=$mapFile",
      "markers"  -> ujson.Arr(),
      "polygons" -> ujson.Arr()
    )

    val eventPayload = ujson.Obj(
      "mapId"  -> mapId,
      "config" -> configObj,
      "title"  -> title.getOrElse("")
    )

    try {
      mapEventSink(eventPayload.render(indent = 0))
    } catch {
      case e: Throwable =>
        logger.error(s"[MapTools] Failed to emit soil map event for $mapId", e)
    }

    s"[[MAP:$mapId]]"
  }

  /**
   * Parse location context JSON to extract coordinates.
   */
  private def parseLocationContext(locationContextJson: String): Option[(Double, Double)] = {
    try {
      val js  = ujson.read(locationContextJson)
      val lat = js("lat").num
      val lon = js("lon").num
      Some((lat, lon))
    } catch {
      case e: Throwable =>
        logger.warn(s"Failed to parse location context: ${e.getMessage}")
        None
    }
  }

  @Tool(Array(
    "Creates an interactive map centered at a specific location with optional markers and regions.",
    "Use this when the user asks to visualize a location, show a place on a map, or display spatial data.",
    "The map supports markers (points), polygons (areas), and different basemap styles.",
    "This tool emits the map out-of-band and returns a short placeholder token like [[MAP:map-abc123]].",
    "Include that placeholder token verbatim in your response — the UI will replace it with the rendered map."
  ))
  def createMap(
    @P("Latitude for map center in decimal degrees (WGS84). Example: 52.0929")
    centerLat: Double,
    @P("Longitude for map center in decimal degrees (WGS84). Example: 5.1045")
    centerLon: Double,
    @P("Zoom level (1-18). Use 5 for country, 10 for city, 15 for neighborhood, 18 for street level.")
    zoom: Int,
    @P("Optional JSON array of markers. Each marker: {\"lat\": 52.0, \"lon\": 5.0, \"popup\": \"text\", \"color\": \"#ff0000\"}. Leave empty for no markers.")
    markersJson: String,
    @P("Basemap style: 'osm' (default, OpenStreetMap), 'satellite' (aerial imagery), or 'terrain' (topographic).")
    basemap: String,
    @P("Optional title to display above the map.")
    title: String
  ): String = {
    try {
      val mapId = generateMapId()

      val markers = if (markersJson != null && markersJson.trim.nonEmpty && markersJson.trim != "null") {
        try {
          val js = ujson.read(markersJson)
          js.arr.map { m =>
            MapMarker(
              lat   = m("lat").num,
              lon   = m("lon").num,
              popup = m.obj.get("popup").map(_.str),
              color = m.obj.get("color").map(_.str)
            )
          }.toSeq
        } catch {
          case e: Throwable =>
            logger.warn(s"Failed to parse markers JSON: ${e.getMessage}")
            Seq.empty
        }
      } else Seq.empty

      val effectiveBasemap = Option(basemap).map(_.trim).filter(_.nonEmpty).getOrElse("osm")
      val effectiveTitle   = Option(title).map(_.trim).filter(_.nonEmpty)

      emitMapAndPlaceholder(
        mapId      = mapId,
        centerLat  = centerLat,
        centerLon  = centerLon,
        zoom       = zoom,
        markers    = markers,
        polygons   = Seq.empty,
        basemap    = effectiveBasemap,
        title      = effectiveTitle
      )
    } catch {
      case e: Throwable =>
        logger.error(s"[MapTools] Failed to create map at lat=$centerLat lon=$centerLon zoom=$zoom", e)
        s"Failed to create map: ${e.getMessage}. Please check the coordinates and try again."
    }
  }

  @Tool(Array(
    "Creates an interactive map using the location context stored in the session (from the UI location picker).",
    "Use this when the user asks to 'show this location on a map' or 'map this place' after selecting a location.",
    "Returns a placeholder token [[MAP:...]] that the UI replaces with the actual map after streaming."
  ))
  def createMapFromLocationContext(
    @P("Location context JSON string containing 'lat', 'lon', and optional 'zoom' fields from the UI.")
    locationContextJson: String,
    @P("Optional JSON array of additional markers to display. Format: [{\"lat\": 52.0, \"lon\": 5.0, \"popup\": \"text\"}]")
    markersJson: String,
    @P("Basemap style: 'osm' (default), 'satellite', or 'terrain'.")
    basemap: String,
    @P("Optional title to display above the map.")
    title: String
  ): String = {
    parseLocationContext(locationContextJson) match {
      case Some((lat, lon)) =>
        try {
          val js   = ujson.read(locationContextJson)
          val zoom = js.obj.get("zoom").map(_.num.toInt).getOrElse(Config.mapConfig.defaultZoom)
          createMap(lat, lon, zoom, markersJson, basemap, title)
        } catch {
          case e: Throwable =>
            logger.warn(s"Failed to extract zoom from location context, using default: ${e.getMessage}")
            createMap(lat, lon, Config.mapConfig.defaultZoom, markersJson, basemap, title)
        }
      case None =>
        "Invalid or missing location context. Please select a location using the map picker first, or provide coordinates directly."
    }
  }

  @Tool(Array(
    "Creates a map showing multiple locations with markers.",
    "Use this to compare locations, show multiple sites, or visualize a route or collection of points.",
    "Returns a placeholder token [[MAP:...]] that the UI replaces with the actual map after streaming."
  ))
  def createMultiLocationMap(
    @P("JSON array of locations. Each location: {\"lat\": 52.0, \"lon\": 5.0, \"label\": \"Site A\", \"color\": \"#ff0000\"}. At least 2 locations required.")
    locationsJson: String,
    @P("Basemap style: 'osm' (default), 'satellite', or 'terrain'.")
    basemap: String,
    @P("Optional title to display above the map.")
    title: String
  ): String = {
    try {
      val js        = ujson.read(locationsJson)
      val locations = js.arr.map { loc =>
        (
          loc("lat").num,
          loc("lon").num,
          loc.obj.get("label").map(_.str),
          loc.obj.get("color").map(_.str)
        )
      }.toSeq

      if (locations.isEmpty) {
        return "No locations provided. Please provide at least one location with lat/lon coordinates."
      }

      val lats      = locations.map(_._1)
      val lons      = locations.map(_._2)

      val centerLat = (lats.min + lats.max) / 2
      val centerLon = (lons.min + lons.max) / 2

      // Default zoom if frontend doesn't fit bounds (e.g. for center only)
      val latDiff = lats.max - lats.min
      val lonDiff = lons.max - lons.min
      val maxDiff = Math.max(latDiff, lonDiff)
      val zoom    = if (maxDiff > 10) 4
                    else if (maxDiff > 5) 5
                    else if (maxDiff > 2) 6
                    else if (maxDiff > 1) 7
                    else if (maxDiff > 0.5) 8
                    else if (maxDiff > 0.1) 10
                    else if (maxDiff > 0.01) 12
                    else 14

      val markersJsonArray = locations.map { case (lat, lon, label, color) =>
        val parts = Seq(
          s""""lat": $lat""",
          s""""lon": $lon""",
          label.map(l => s""""popup": "${l.replace("\"", "\\\"")}"""").getOrElse(""),
          color.map(c => s""""color": "$c"""").getOrElse("")
        ).filter(_.nonEmpty)
        s"{${parts.mkString(", ")}}"
      }.mkString("[", ", ", "]")

      createMap(centerLat, centerLon, zoom, markersJsonArray, basemap, title)
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to create multi-location map", e)
        s"Failed to create map with multiple locations: ${e.getMessage}. Please check the locations JSON format."
    }
  }

  @Tool(Array(
    "Creates a map showing soil properties at a location using SoilGrids WMS overlay.",
    "Use this when the user asks to visualize soil properties, see a soil map, or view soil data spatially.",
    "Available properties: soc (organic carbon), clay, sand, silt, bdod (bulk density), phh2o (pH), nitrogen, cec.",
    "Returns a placeholder token [[MAP:...]] that the UI replaces with the actual map after streaming."
  ))
  def createSoilPropertyMap(
    @P("Latitude for map center in decimal degrees (WGS84). Example: 52.0929")
    centerLat: Double,
    @P("Longitude for map center in decimal degrees (WGS84). Example: 5.1045")
    centerLon: Double,
    @P("Soil property to display: soc, clay, sand, silt, bdod, phh2o, nitrogen, or cec")
    property: String,
    @P("Depth layer: 0-5cm, 5-15cm, 15-30cm, 30-60cm, 60-100cm, or 100-200cm")
    depth: String,
    @P("Optional title to display above the map.")
    title: String
  ): String = {
    try {
      val mapId = generateMapId()

      val (sgProperty, layerLabel) = property.toLowerCase match {
        case "soc"             => ("soc", "Soil Organic Carbon")
        case "clay"            => ("clay", "Clay Content")
        case "sand"            => ("sand", "Sand Content")
        case "silt"            => ("silt", "Silt Content")
        case "bdod"            => ("bdod", "Bulk Density")
        case "phh2o" | "ph"   => ("phh2o", "pH (H2O)")
        case "nitrogen" | "n" => ("nitrogen", "Nitrogen")
        case "cec"             => ("cec", "Cation Exchange Capacity")
        case _                 => ("soc", "Soil Organic Carbon")
      }

      val validDepths    = Set("0-5cm", "5-15cm", "15-30cm", "30-60cm", "60-100cm", "100-200cm")
      val effectiveDepth = if (validDepths.contains(depth)) depth else "0-5cm"
      val effectiveTitle = Option(title).map(_.trim).filter(_.nonEmpty)
        .orElse(Some(s"$layerLabel at $effectiveDepth"))

      emitSoilGridsMapAndPlaceholder(
        mapId     = mapId,
        centerLat = centerLat,
        centerLon = centerLon,
        zoom      = 15,
        property  = sgProperty,
        depth     = effectiveDepth,
        title     = effectiveTitle
      )
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to create soil property map at lat=$centerLat lon=$centerLon property=$property", e)
        s"Failed to create soil property map: ${e.getMessage}. Please check the parameters and try again."
    }
  }

  @Tool(Array(
    "Creates a map showing a region or area boundary using a polygon.",
    "Use this to visualize field boundaries, administrative regions, study areas, or any polygonal area.",
    "Returns a placeholder token [[MAP:...]] that the UI replaces with the actual map after streaming."
  ))
  def createRegionMap(
    @P("JSON array of polygon coordinates: [[lat1, lon1], [lat2, lon2], ...]. Minimum 3 points required.")
    coordinatesJson: String,
    @P("Optional fill color for the polygon (hex color, e.g., '#3388ff'). Defaults to blue.")
    fillColor: String,
    @P("Optional stroke color for the polygon border (hex color). Defaults to match fill color.")
    strokeColor: String,
    @P("Optional label/description to show when clicking the polygon.")
    label: String,
    @P("Basemap style: 'osm' (default), 'satellite', or 'terrain'.")
    basemap: String,
    @P("Optional title to display above the map.")
    title: String
  ): String = {
    try {
      val js     = ujson.read(coordinatesJson)
      val coords = js.arr.map { c => (c(0).num, c(1).num) }.toSeq

      if (coords.length < 3) {
        return "A polygon requires at least 3 coordinate points. Please provide more coordinates."
      }

      val centerLat = coords.map(_._1).sum / coords.length
      val centerLon = coords.map(_._2).sum / coords.length

      // Default zoom if frontend doesn't fit bounds
      val lats    = coords.map(_._1)
      val lons    = coords.map(_._2)
      val latDiff = lats.max - lats.min
      val lonDiff = lons.max - lons.min
      val maxDiff = Math.max(latDiff, lonDiff)
      val zoom    = if (maxDiff > 1) 8 else if (maxDiff > 0.1) 10 else if (maxDiff > 0.01) 12 else 14

      val mapId               = generateMapId()
      val effectiveFillColor  = Option(fillColor).map(_.trim).filter(_.nonEmpty).getOrElse("#3388ff")
      val effectiveStrokeColor = Option(strokeColor).map(_.trim).filter(_.nonEmpty).getOrElse(effectiveFillColor)
      val effectiveLabel      = Option(label).map(_.trim).filter(_.nonEmpty)
      val effectiveBasemap    = Option(basemap).map(_.trim).filter(_.nonEmpty).getOrElse("osm")
      val effectiveTitle      = Option(title).map(_.trim).filter(_.nonEmpty)

      val polygon = MapPolygon(
        coordinates  = coords,
        fillColor    = Some(effectiveFillColor),
        strokeColor  = Some(effectiveStrokeColor),
        fillOpacity  = Some(0.3),
        label        = effectiveLabel
      )

      emitMapAndPlaceholder(
        mapId     = mapId,
        centerLat = centerLat,
        centerLon = centerLon,
        zoom      = zoom,
        markers   = Seq.empty,
        polygons  = Seq(polygon),
        basemap   = effectiveBasemap,
        title     = effectiveTitle
      )
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to create region map", e)
        s"Failed to create region map: ${e.getMessage}. Please check the coordinates JSON format."
    }
  }
}

// Data classes for map elements
private case class MapMarker(
  lat: Double,
  lon: Double,
  popup: Option[String] = None,
  color: Option[String] = None
)

private case class MapPolygon(
  coordinates: Seq[(Double, Double)],
  fillColor: Option[String] = None,
  strokeColor: Option[String] = None,
  fillOpacity: Option[Double] = None,
  label: Option[String] = None
)

object MapTools {
  def getSpecifications: java.util.List[ToolSpecification] =
    ToolSpecifications.toolSpecificationsFrom(classOf[MapTools])
}
