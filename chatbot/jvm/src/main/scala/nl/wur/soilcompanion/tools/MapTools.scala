package nl.wur.soilcompanion.tools

import dev.langchain4j.agent.tool.{P, Tool, ToolSpecification, ToolSpecifications}
import nl.wur.soilcompanion.Config
import upickle.default.*

import java.net.URLEncoder
import java.util.UUID

/**
 * LLM tools to create interactive maps for visualizing spatial data.
 *
 * This tool generates embedded HTML maps using Leaflet.js that can display:
 * - Markers for point locations
 * - Polygons for areas
 * - Different tile layers (satellite, terrain, etc.)
 *
 * Maps are returned as HTML with data attributes that the UI JavaScript will detect and render.
 * This approach works with DOMPurify sanitization (which strips script tags).
 */
class MapTools {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /**
   * Generate a unique map ID for each map instance
   */
  private def generateMapId(): String = s"map-${UUID.randomUUID().toString.take(8)}"

  /**
   * Build the HTML for an interactive Leaflet map using data attributes
   * The UI will detect these elements and initialize Leaflet maps dynamically
   */
  private def buildLeafletMap(
    mapId: String,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    markers: Seq[MapMarker],
    polygons: Seq[MapPolygon],
    basemap: String,
    title: Option[String],
    width: String,
    height: String
  ): String = {

    // Encode map data as JSON in data attributes for the UI to read
    val mapData = ujson.Obj(
      "center" -> ujson.Arr(centerLat, centerLon),
      "zoom" -> zoom,
      "basemap" -> basemap,
      "markers" -> ujson.Arr(markers.map { m =>
        ujson.Obj(
          "lat" -> m.lat,
          "lon" -> m.lon,
          "popup" -> m.popup.getOrElse(""),
          "color" -> m.color.getOrElse("#3388ff")
        )
      }: _*),
      "polygons" -> ujson.Arr(polygons.map { p =>
        ujson.Obj(
          "coordinates" -> ujson.Arr(p.coordinates.map(c => ujson.Arr(c._1, c._2)): _*),
          "fillColor" -> p.fillColor.getOrElse("#3388ff"),
          "strokeColor" -> p.strokeColor.getOrElse("#3388ff"),
          "fillOpacity" -> p.fillOpacity.getOrElse(0.2),
          "label" -> p.label.getOrElse("")
        )
      }: _*)
    )

    val mapDataJson = mapData.render(indent = 0)
    val escapedMapData = escapeHtmlAttribute(mapDataJson)

    // IMPORTANT: No indentation! Markdown parsers treat indented HTML as code blocks.
    // The HTML must start at column 0 to be recognized as raw HTML by marked.js
    // Blank lines before/after ensure it's treated as a block-level element
    val titleBlock = title.map(t => s"\n\n### $t\n\n").getOrElse("")
    s"""$titleBlock
<div id="$mapId" class="leaflet-map-container" data-map-config="$escapedMapData" data-leaflet-css="${Config.mapConfig.leafletCssUrl}" data-leaflet-js="${Config.mapConfig.leafletJsUrl}" style="width: $width; height: $height; border-radius: 8px; border: 2px solid #e0e0e0; background: #f5f5f5; display: flex; align-items: center; justify-content: center; color: #666; margin: 15px 0;"><div style="text-align: center;"><div style="font-size: 48px; margin-bottom: 10px;">🗺️</div><div>Loading map...</div></div></div>

"""
  }

  /**
   * Build HTML for a Leaflet map with SoilGrids WMS overlay
   */
  private def buildSoilGridsMap(
    mapId: String,
    centerLat: Double,
    centerLon: Double,
    zoom: Int,
    property: String,
    depth: String,
    title: Option[String],
    width: String,
    height: String
  ): String = {

    // SoilGrids WMS layer name format: property_depth_mean
    // Layer names use the depth WITH hyphens and "cm", e.g., "soc_0-5cm_mean"
    val wmsLayer = s"${property}_${depth}_mean"

    // Each property has its own map file at ISRIC
    val mapFile = s"/map/${property}.map"

    // Encode map data for WMS overlay
    // Use local proxy endpoint to bypass CORS restrictions
    val mapData = ujson.Obj(
      "center" -> ujson.Arr(centerLat, centerLon),
      "zoom" -> zoom,
      "basemap" -> "osm",
      "wmsLayer" -> wmsLayer,
      "wmsUrl" -> s"/wms-proxy?map=$mapFile",
      "markers" -> ujson.Arr(),
      "polygons" -> ujson.Arr()
    )

    val mapDataJson = mapData.render(indent = 0)
    val escapedMapData = escapeHtmlAttribute(mapDataJson)

    val titleBlock = title.map(t => s"\n\n### $t\n\n").getOrElse("")
    s"""$titleBlock
<div id="$mapId" class="leaflet-map-container" data-map-config="$escapedMapData" data-leaflet-css="${Config.mapConfig.leafletCssUrl}" data-leaflet-js="${Config.mapConfig.leafletJsUrl}" style="width: $width; height: $height; border-radius: 8px; border: 2px solid #e0e0e0; background: #f5f5f5; display: flex; align-items: center; justify-content: center; color: #666; margin: 15px 0;"><div style="text-align: center;"><div style="font-size: 48px; margin-bottom: 10px;">🗺️</div><div>Loading soil map...</div></div></div>

"""
  }

  /**
   * Escape HTML attribute values
   */
  private def escapeHtmlAttribute(s: String): String = {
    s.replace("&", "&amp;")
     .replace("\"", "&quot;")
     .replace("'", "&#39;")
     .replace("<", "&lt;")
     .replace(">", "&gt;")
  }

  /**
   * Parse location context JSON to extract coordinates
   */
  private def parseLocationContext(locationContextJson: String): Option[(Double, Double)] = {
    try {
      val js = ujson.read(locationContextJson)
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
    "IMPORTANT: This tool returns HTML code. You MUST include the COMPLETE returned HTML in your response to the user.",
    "Copy the entire HTML string returned by this tool into your response WITHOUT any modification.",
    "Do NOT summarize or describe the map - include the actual HTML so it renders in the user's browser."
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

      // Parse markers if provided
      val markers = if (markersJson != null && markersJson.trim.nonEmpty && markersJson.trim != "null") {
        try {
          val js = ujson.read(markersJson)
          js.arr.map { m =>
            MapMarker(
              lat = m("lat").num,
              lon = m("lon").num,
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

      val polygons = Seq.empty[MapPolygon]
      val effectiveBasemap = Option(basemap).map(_.trim).filter(_.nonEmpty).getOrElse("osm")
      val effectiveTitle = Option(title).map(_.trim).filter(_.nonEmpty)

      val html = buildLeafletMap(
        mapId = mapId,
        centerLat = centerLat,
        centerLon = centerLon,
        zoom = zoom,
        markers = markers,
        polygons = polygons,
        basemap = effectiveBasemap,
        title = effectiveTitle,
        width = Config.mapConfig.defaultWidth,
        height = Config.mapConfig.defaultHeight
      )

      // Return HTML with explicit instruction for the LLM embedded in the response
      s"""Here is an interactive map. Copy the following HTML exactly into your response:

$html

The map will render automatically when the HTML is included in your message."""
    } catch {
      case e: Throwable =>
        logger.error(s"[MapTools] Failed to create map at lat=$centerLat lon=$centerLon zoom=$zoom", e)
        s"Failed to create map: ${e.getMessage}. Please check the coordinates and try again."
    }
  }

  @Tool(Array(
    "Creates an interactive map using the location context stored in the session (from the UI location picker).",
    "Use this when the user asks to 'show this location on a map' or 'map this place' after selecting a location.",
    "IMPORTANT: This tool returns HTML code that MUST be included in your response to display the map."
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
          val js = ujson.read(locationContextJson)
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
    "IMPORTANT: This tool returns HTML code that MUST be included in your response to display the map."
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
      val js = ujson.read(locationsJson)
      val locations = js.arr.map { loc =>
        (
          loc("lat").num,
          loc("lon").num,
          loc.obj.get("label").map(_.str),
          loc.obj.get("color").map(_.str)
        )
      }

      if (locations.isEmpty) {
        return "No locations provided. Please provide at least one location with lat/lon coordinates."
      }

      // Calculate center and zoom to fit all markers
      val lats = locations.map(_._1)
      val lons = locations.map(_._2)
      val centerLat = (lats.min + lats.max) / 2
      val centerLon = (lons.min + lons.max) / 2

      // Simple zoom estimation based on bounding box
      val latDiff = lats.max - lats.min
      val lonDiff = lons.max - lons.max
      val maxDiff = Math.max(latDiff, lonDiff)
      val zoom = if (maxDiff > 10) 4
                 else if (maxDiff > 5) 5
                 else if (maxDiff > 2) 6
                 else if (maxDiff > 1) 7
                 else if (maxDiff > 0.5) 8
                 else if (maxDiff > 0.1) 10
                 else if (maxDiff > 0.01) 12
                 else 14

      // Convert to markers JSON
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
    "IMPORTANT: This tool returns HTML code that MUST be included in your response to display the map."
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

      // Validate and map property to SoilGrids layer name
      val (sgProperty, layerLabel) = property.toLowerCase match {
        case "soc" => ("soc", "Soil Organic Carbon")
        case "clay" => ("clay", "Clay Content")
        case "sand" => ("sand", "Sand Content")
        case "silt" => ("silt", "Silt Content")
        case "bdod" => ("bdod", "Bulk Density")
        case "phh2o" | "ph" => ("phh2o", "pH (H2O)")
        case "nitrogen" | "n" => ("nitrogen", "Nitrogen")
        case "cec" => ("cec", "Cation Exchange Capacity")
        case _ => ("soc", "Soil Organic Carbon")
      }

      // Validate depth
      val validDepths = Set("0-5cm", "5-15cm", "15-30cm", "30-60cm", "60-100cm", "100-200cm")
      val effectiveDepth = if (validDepths.contains(depth)) depth else "0-5cm"

      val effectiveTitle = Option(title).map(_.trim).filter(_.nonEmpty)
        .orElse(Some(s"$layerLabel at $effectiveDepth"))

      val html = buildSoilGridsMap(
        mapId = mapId,
        centerLat = centerLat,
        centerLon = centerLon,
        zoom = 12,
        property = sgProperty,
        depth = effectiveDepth,
        title = effectiveTitle,
        width = Config.mapConfig.defaultWidth,
        height = Config.mapConfig.defaultHeight
      )

      s"""Here is an interactive soil property map. Copy the following HTML exactly into your response:

$html

The map shows ${layerLabel} at ${effectiveDepth} depth using data from SoilGrids (ISRIC, ~250m resolution)."""
    } catch {
      case e: Throwable =>
        logger.error(s"Failed to create soil property map at lat=$centerLat lon=$centerLon property=$property", e)
        s"Failed to create soil property map: ${e.getMessage}. Please check the parameters and try again."
    }
  }

  @Tool(Array(
    "Creates a map showing a region or area boundary using a polygon.",
    "Use this to visualize field boundaries, administrative regions, study areas, or any polygonal area.",
    "IMPORTANT: This tool returns HTML code that MUST be included in your response to display the map."
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
      val js = ujson.read(coordinatesJson)
      val coords = js.arr.map { c =>
        (c(0).num, c(1).num)
      }.toSeq

      if (coords.length < 3) {
        return "A polygon requires at least 3 coordinate points. Please provide more coordinates."
      }

      // Calculate center of polygon
      val centerLat = coords.map(_._1).sum / coords.length
      val centerLon = coords.map(_._2).sum / coords.length

      // Calculate zoom to fit polygon
      val lats = coords.map(_._1)
      val lons = coords.map(_._2)
      val latDiff = lats.max - lats.min
      val lonDiff = lons.max - lons.min
      val maxDiff = Math.max(latDiff, lonDiff)
      val zoom = if (maxDiff > 1) 8 else if (maxDiff > 0.1) 10 else if (maxDiff > 0.01) 12 else 14

      val mapId = generateMapId()
      val effectiveFillColor = Option(fillColor).map(_.trim).filter(_.nonEmpty).getOrElse("#3388ff")
      val effectiveStrokeColor = Option(strokeColor).map(_.trim).filter(_.nonEmpty).getOrElse(effectiveFillColor)
      val effectiveLabel = Option(label).map(_.trim).filter(_.nonEmpty)
      val effectiveBasemap = Option(basemap).map(_.trim).filter(_.nonEmpty).getOrElse("osm")
      val effectiveTitle = Option(title).map(_.trim).filter(_.nonEmpty)

      val polygon = MapPolygon(
        coordinates = coords,
        fillColor = Some(effectiveFillColor),
        strokeColor = Some(effectiveStrokeColor),
        fillOpacity = Some(0.3),
        label = effectiveLabel
      )

      val html = buildLeafletMap(
        mapId = mapId,
        centerLat = centerLat,
        centerLon = centerLon,
        zoom = zoom,
        markers = Seq.empty,
        polygons = Seq(polygon),
        basemap = effectiveBasemap,
        title = effectiveTitle,
        width = Config.mapConfig.defaultWidth,
        height = Config.mapConfig.defaultHeight
      )

      html
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
