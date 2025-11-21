package nl.wur.soilcompanion

import org.scalajs.dom
import org.scalajs.dom.{HttpMethod, MessageEvent, RequestInit, WebSocket, window, fetch}

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import scala.util.{Failure, Success}
import upickle.default.*
import nl.wur.soilcompanion.domain.{QueryEvent, QueryPartialResponse, QueryRequest}

import scala.scalajs.js.annotation.JSGlobal

// --- Bridges to native JS libraries ---

@js.native
@JSGlobal
object marked extends js.Object {
  def parse(text: String): String = js.native
}

@js.native
@JSGlobal
object DOMPurify extends js.Object {
  def sanitize(text: String): String = js.native
}

@js.native
@JSGlobal
object hljs extends js.Object {
  def highlightAll(): Unit = js.native
}

// --- End of JS bridges ---

// Ensure Font Awesome processes dynamically added icons
private def refreshFaIcons(): Unit = {
  val fa = js.Dynamic.global.selectDynamic("FontAwesome")
  if (!js.isUndefined(fa) && !js.isUndefined(fa.selectDynamic("dom"))) {
    val fn = fa.selectDynamic("dom").selectDynamic("i2svg")
    if (js.typeOf(fn) == "function") fn.asInstanceOf[js.Function0[Unit]]()
  }
}

/**
 * SoilWise - Soil Companion chatbot
 *
 * @author Rob Knapen, Wageningen University & Research
 */
object SoilCompanionApp extends App {

  private var sessionId: Option[String] = None
  // Track the current server-generated questionId to correlate UI messages and feedback
  private var currentQuestionId: Option[String] = None
  // Track WebSocket connection and activity
  private var ws: Option[WebSocket] = None
  private var wsConnected: Boolean = false
  private var wsReconnectBackoffMs: Int = 1000
  private var lastWsMessageAt: Double = js.Date.now()
  // Watchdog timer to surface errors if no events arrive after sending a question
  private var pendingResponseTimer: Option[Int] = None
  
  // Share auth state with plain JS (toolbar.js) and persist across UI changes
  private def setAuthState(authed: Boolean): Unit = {
    val root = dom.document.querySelector(".app.layout").asInstanceOf[dom.html.Element]
    if (authed) {
      dom.window.localStorage.setItem("soilcompanion.auth", "1")
      if (root != null) root.setAttribute("data-auth", "1")
    } else {
      dom.window.localStorage.removeItem("soilcompanion.auth")
      if (root != null) root.removeAttribute("data-auth")
    }
  }

  // Derive backend endpoints from current origin to avoid mixed-content and hardcoded hosts
  private val location = dom.window.location
  private val httpBase = s"${location.protocol}//${location.host}"
  private val wsBase = (if (location.protocol == "https:") "wss" else "ws") + s"://${location.host}"
  // If the app is served under a path prefix (e.g., /soilcompanion), do NOT prefix API calls for JSON endpoints.
  // Static files are mounted at "/soilcompanion" on the backend, and POSTing under that path can be intercepted by the
  // static handler (which returns 405 for non-GET). Therefore, JSON APIs remain at the server root (e.g., "/location").
  dom.console.log(s"[DEBUG_LOG] API base resolved to: ${httpBase}")
  private var isAuthenticated: Boolean = false

  // execution context for futures
  given ExecutionContext = ExecutionContext.Implicits.global

  // device id persisted in localStorage
  private def getOrCreateDeviceId(): String = {
    val key = "device_id"
    val storage = dom.window.localStorage
    val existing = storage.getItem(key)
    if (existing != null && existing.nonEmpty) existing
    else {
      val id = java.util.UUID.randomUUID().toString
      storage.setItem(key, id)
      id
    }
  }

  // Generate a message id for each bot message
  private def newMessageId(): String = java.util.UUID.randomUUID().toString

  // Format current local time as HH:mm
  private def nowTimeString(): String = {
    val d = new js.Date()
    def two(n: Int): String = if (n < 10) s"0$n" else n.toString
    s"${two(d.getHours().toInt)}:${two(d.getMinutes().toInt)}"
  }

  // --- Helper: ensure there's a bot bubble to render into ---
  private def ensureBotPlaceholder(): Unit = {
    val messageContainer = dom.document.getElementById("messages")
    val last = messageContainer.lastElementChild
    val needsNew = Option(last).forall(!_.classList.contains("bot-message"))
    if (needsNew) addMessage("AI", "", allowFeedback = true)
  }

  // --- Helper: show a friendly error in the last bot message and stop spinners ---
  private def showErrorInLastBotMessage(rawDetail: Option[String]): Unit = {
    ensureBotPlaceholder()
    val messageContainer = dom.document.getElementById("messages")
    val last = messageContainer.lastElementChild
    val detail = rawDetail.getOrElse("")

    // Detect context-length style errors to tailor the message
    val lowered = detail.toLowerCase
    val isContextLen = lowered.contains("context_length_exceeded") ||
      lowered.contains("maximum context length") || lowered.contains("context length")

    val userMsg =
      if (isContextLen)
        "Sorry, your message is too long for the AI to process. Please shorten it (or remove extra context) and try again."
      else if (detail.nonEmpty)
        s"Sorry, I couldn't complete the request: $detail"
      else
        "Sorry, something went wrong while generating the answer. Please try again."

    Option(last).filter(_.classList.contains("bot-message")).foreach { el =>
      val contentEl = el.querySelector(".message-content").asInstanceOf[dom.html.Element]
      if (contentEl != null) {
        // Clear possible typing indicator and set sanitized message
        contentEl.innerHTML = ""
        el.setAttribute("data-raw-content", userMsg)
        val parsed = marked.parse(userMsg)
        val sanitized = DOMPurify.sanitize(parsed)
        contentEl.innerHTML = sanitized
        hljs.highlightAll()
        val msgContainer = dom.document.getElementById("messages")
        msgContainer.scrollTop = msgContainer.scrollHeight
      }
    }
    // Always hide the global spinner defensively
    try { hideSpinner() } catch { case _: Throwable => () }
  }

  // --- Location map state ---
  private var leafletMap: js.Dynamic = null
  private var leafletMarker: js.Dynamic = null
  private var leafletCircle: js.Dynamic = null
  private var locationInitialized: Boolean = false
  // Cache a pending location selection made before the user is authenticated
  // This ensures we can send it to the backend right after a successful login
  private var pendingLocation: Option[ujson.Obj] = None

  private def setLocationSummary(text: String): Unit = {
    val el = dom.document.getElementById("location-summary").asInstanceOf[dom.html.Element]
    if (el != null) el.textContent = text
  }

  // --- Sidebar tabs helpers ---
  // Programmatically activate a sidebar tab and its corresponding panel
  private def activateSidebarTab(tabId: String, persist: Boolean = true, focus: Boolean = false): Unit = {
    try {
      val tabs = dom.document.querySelectorAll("#sidebar .tablist [role='tab']")
      val panels = dom.document.querySelectorAll("#sidebar [role='tabpanel']")
      val target = dom.document.getElementById(tabId)
      if (tabs != null && target != null) {
        var i = 0
        while (i < tabs.length) {
          val t = tabs(i).asInstanceOf[dom.html.Element]
          val selected = (t.id == tabId)
          t.setAttribute("aria-selected", if selected then "true" else "false")
          t.setAttribute("tabindex", if selected then "0" else "-1")
          val panelId = t.getAttribute("aria-controls")
          if (panelId != null) {
            val p = dom.document.getElementById(panelId)
            if (p != null) {
              if (selected) p.removeAttribute("hidden") else p.setAttribute("hidden", "")
            }
          }
          i += 1
        }
        if (persist) {
          try dom.window.localStorage.setItem("soilcompanion.sidebarTab", tabId)
          catch { case _: Throwable => () }
        }
        // org.scalajs.dom.Element doesn't define focus(); cast to html.Element
        if (focus) target.asInstanceOf[dom.html.Element].focus()
      }
    } catch { case _: Throwable => () }
  }

  // Enable/disable the Location tab button and make it visually disabled
  private def setLocationTabEnabled(enabled: Boolean): Unit = {
    val tab = dom.document.getElementById("tab-location").asInstanceOf[dom.html.Button]
    if (tab != null) {
      tab.disabled = !enabled
      if (!enabled) {
        tab.classList.add("disabled")
        tab.setAttribute("aria-disabled", "true")
        tab.setAttribute("title", "Login required")
      } else {
        tab.classList.remove("disabled")
        tab.removeAttribute("aria-disabled")
        tab.removeAttribute("title")
      }
    }
  }

  // If unauthenticated, make sure the About tab is active
  private def ensureAboutIfUnauthed(): Unit = {
    if (!isAuthenticated) {
      activateSidebarTab("tab-about", persist = true, focus = false)
    }
  }

  private def saveLocationContext(
      lat: Double,
      lon: Double,
      zoom: Int,
      countryName: Option[String],
      countryCode: Option[String],
      displayName: Option[String] = None,
      bestLabel: Option[String] = None,
      addressJson: Option[String] = None
  ): Unit = {
    val sid = sessionId.getOrElse("")
    if (sid.isEmpty) return
    // Build payload without explicit JSON nulls, because some servers (Cask) don't bind nulls to Option[...] params
    val payload = {
      val obj = ujson.Obj(
        "sessionId" -> sid,
        "lat" -> lat,
        "lon" -> lon,
        "zoom" -> zoom,
        // Cask requires the key to exist even for Option[Boolean];
        // explicitly send clear=false when saving a selection
        "clear" -> false
      )
      countryName.foreach(v => obj("countryName") = v)
      countryCode.foreach(v => obj("countryCode") = v)
      displayName.foreach(v => obj("displayName") = v)
      bestLabel.foreach(v => obj("bestLabel") = v)
      // store rich address as a JSON string for portability across backends
      addressJson.foreach(v => obj("addressJson") = v)
      obj
    }
    // If not authenticated yet, remember this selection and send after login
    if (!isAuthenticated) {
      dom.console.log(
        s"[DEBUG_LOG] Location selected (pending auth). Caching selection: lat=${lat}, lon=${lon}, zoom=${zoom}"
      )
      pendingLocation = Some(payload)
      return
    }
    dom.console.log(
      s"[DEBUG_LOG] Saving location context: lat=${lat}, lon=${lon}, zoom=${zoom}, country=${countryCode.getOrElse("")}/${countryName.getOrElse("")}, label=${bestLabel.getOrElse(displayName.getOrElse(""))}"
    )
    // Call root-scoped API to avoid collision with static files mounted at /soilcompanion
    postJson(s"$httpBase/location", payload)({ _ =>
      // ok
      dom.console.log("[DEBUG_LOG] /location saved successfully")
    }, { e =>
      dom.console.error("[DEBUG_LOG] Failed to save location context", e)
    })
  }

  private def clearLocationContext(): Unit = {
    val sid = sessionId.getOrElse("")
    if (sid.isEmpty) return
    // If not authenticated, just clear any pending selection locally
    if (!isAuthenticated) {
      dom.console.log("[DEBUG_LOG] Clearing pending location context (not authenticated)")
      pendingLocation = None
      return
    }
    dom.console.log("[DEBUG_LOG] Clearing location context on server")
    val payload = ujson.Obj(
      "sessionId" -> sid,
      "clear" -> true
    )
    // Call root-scoped API to avoid collision with static files mounted at /soilcompanion
    postJson(s"$httpBase/location", payload)({ _ =>
      // ok
    }, { e => dom.console.error("[DEBUG_LOG] Failed to clear location context", e) })
  }

  private def reverseGeocode(
      lat: Double,
      lon: Double,
      mapZoom: Int
  )(cb: (
      countryName: Option[String],
      countryCode: Option[String],
      displayName: Option[String],
      bestLabel: Option[String],
      addressJson: Option[String]
  ) => Unit): Unit = {
    // Use Nominatim (OSM) reverse geocoding. We adjust the 'zoom' for detail and request address details.
    def clamp(n: Int, lo: Int, hi: Int): Int = Math.max(lo, Math.min(hi, n))
    // Map Leaflet zoom (0..18) to a reverse-geocode zoom preferring more detail when you zoom in
    val detailZoom = clamp(mapZoom match {
      case z if z >= 17 => 18
      case z if z >= 15 => 17
      case z if z >= 13 => 15
      case z if z >= 11 => 13
      case z if z >= 9  => 11
      case z if z >= 7  => 9
      case _            => 5
    }, 3, 18)

    val url = s"https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=${lat}&lon=${lon}&zoom=${detailZoom}&addressdetails=1&accept-language=en"
    fetch(url).toFuture
      .flatMap(_.text().toFuture)
      .foreach { txt =>
        try {
          val dyn = js.JSON.parse(txt).asInstanceOf[js.Dynamic]
          def optStr(v: js.Dynamic): Option[String] =
            if (js.isUndefined(v) || v == null) None else Option(v.asInstanceOf[String]).filter(_.nonEmpty)

          val displayName = optStr(dyn.selectDynamic("display_name"))
          val addrDyn = dyn.selectDynamic("address")
          val hasAddr = !js.isUndefined(addrDyn) && addrDyn != null

          def pick(keys: Seq[String]): Option[String] = {
            if (!hasAddr) return None
            var res: Option[String] = None
            var i = 0
            while (i < keys.length && res.isEmpty) {
              val k = keys(i)
              res = optStr(addrDyn.selectDynamic(k))
              i += 1
            }
            res
          }

          val cname = if (hasAddr) pick(Seq("country")) else None
          val ccode = if (hasAddr) pick(Seq("country_code")).map(_.toUpperCase) else None

          val cityLike = pick(Seq("city", "town", "village", "hamlet", "municipality"))
          val suburb = pick(Seq("suburb", "neighbourhood"))
          val road = pick(Seq("road", "pedestrian", "footway"))
          val house = pick(Seq("house_number"))
          val county = pick(Seq("county", "district"))
          val state = pick(Seq("state", "state_district", "province", "region"))
          val country = cname

          val roadCity = for {
            r <- road
            c <- cityLike.orElse(suburb)
          } yield s"$r, $c"
          val houseRoadCity = for {
            h <- house
            r <- road
            c <- cityLike.orElse(suburb)
          } yield s"$h $r, $c"

          val bestLabel = detailZoom match {
            case z if z >= 17 =>
              houseRoadCity
                .orElse(roadCity)
                .orElse(cityLike)
                .orElse(suburb)
                .orElse(state)
                .orElse(country)
            case z if z >= 15 =>
              roadCity.orElse(cityLike).orElse(suburb).orElse(state).orElse(country)
            case z if z >= 13 =>
              cityLike.orElse(suburb).orElse(county).orElse(state).orElse(country)
            case z if z >= 11 =>
              county.orElse(state).orElse(country)
            case z if z >= 9  =>
              state.orElse(country)
            case _ => country
          }

          val addrSerialized = if (hasAddr) Some(js.JSON.stringify(addrDyn).asInstanceOf[String]) else None

          cb(cname, ccode, displayName, bestLabel, addrSerialized)
        } catch {
          case _: Throwable => cb(None, None, None, None, None)
        }
      }
  }

  // Forward geocoding using Nominatim (OSM) for country/place lookup
  private def forwardGeocode(
      query: String
  )(cb: Option[(
      lat: Double,
      lon: Double,
      zoomGuess: Int,
      countryName: Option[String],
      countryCode: Option[String],
      displayName: Option[String],
      bestLabel: Option[String],
      addressJson: Option[String],
      bbox: Option[(Double, Double, Double, Double)] // (south, west, north, east)
  )] => Unit): Unit = {
    if (query.trim.isEmpty) { cb(None); return }
    val url = s"https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=1&q=${js.URIUtils.encodeURIComponent(query.trim)}"
    fetch(url).toFuture
      .flatMap(_.json().toFuture)
      .map { data =>
        val arr = data.asInstanceOf[js.Array[js.Dynamic]]
        if (arr.length == 0) { cb(None) }
        else {
          val item = arr(0)
          val lat = item.selectDynamic("lat").asInstanceOf[String].toDouble
          val lon = item.selectDynamic("lon").asInstanceOf[String].toDouble
          val displayName = Option(item.selectDynamic("display_name")).map(_.toString)
          val clazz = Option(item.selectDynamic("class")).map(_.toString).getOrElse("")
          val typ = Option(item.selectDynamic("type")).map(_.toString).getOrElse("")
          val addr = item.selectDynamic("address")
          val hasAddr = !js.isUndefined(addr) && addr != null
          val ccode = if (hasAddr) Option(addr.selectDynamic("country_code")).map(_.toString.toUpperCase) else None
          val cname = if (hasAddr) Option(addr.selectDynamic("country")).map(_.toString) else None
          val addrSerialized = if (hasAddr) Some(js.JSON.stringify(addr).asInstanceOf[String]) else None

          // Heuristic zoom guess based on class/type
          val zoomGuess = (clazz, typ) match {
            case (c, _) if c == "boundary" || c == "place" && (typ == "country" || typ == "state") => if (typ == "country") 5 else 7
            case (_, t) if Set("city","town","municipality","village","suburb").contains(t) => 11
            case _ => 13
          }

          // Parse bbox if present
          val bboxOpt: Option[(Double, Double, Double, Double)] =
            Option(item.selectDynamic("boundingbox")).filter(bb => !js.isUndefined(bb) && bb != null).map { bb =>
              val bba = bb.asInstanceOf[js.Array[String]]
              // order: [south, north, west, east] in nominatim json
              val south = bba(0).toDouble
              val north = bba(1).toDouble
              val west = bba(2).toDouble
              val east = bba(3).toDouble
              (south, west, north, east)
            }

          val nameField = Option(item.selectDynamic("name")).map(_.toString)
          val bestLabel = nameField.orElse(displayName)

          cb(Some((lat, lon, zoomGuess, cname, ccode, displayName, bestLabel, addrSerialized, bboxOpt)))
        }
      }
      .recover { case _ => cb(None) }
  }

  private def initLocationMap(): Unit = {
    if (locationInitialized) return
    val mapEl = dom.document.getElementById("map").asInstanceOf[dom.html.Element]
    if (mapEl == null) return
    val L = js.Dynamic.global.selectDynamic("L")
    if (js.isUndefined(L)) {
      dom.console.error("Leaflet library not loaded")
      return
    }

    // Default to Europe
    val defaultLat = 54.5260
    val defaultLon = 15.2551
    val defaultZoom = 4

    leafletMap = L.applyDynamic("map")("map").asInstanceOf[js.Dynamic]
    leafletMap.applyDynamic("setView")(js.Array(defaultLat, defaultLon), defaultZoom)
    val tiles = L.applyDynamic("tileLayer")(
      "https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png",
      js.Dynamic.literal(
        attribution = "&copy; <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a> contributors"
      )
    )
    tiles.applyDynamic("addTo")(leafletMap)

    // Try geolocate the user to center
    try {
      val geoDyn = dom.window.navigator.geolocation.asInstanceOf[js.Dynamic]
      val opts = js.Dynamic.literal(timeout = 3000, maximumAge = 60000, enableHighAccuracy = false)
      geoDyn.applyDynamic("getCurrentPosition")(
        (pos: js.Dynamic) => {
          val lat = pos.selectDynamic("coords").selectDynamic("latitude").asInstanceOf[Double]
          val lon = pos.selectDynamic("coords").selectDynamic("longitude").asInstanceOf[Double]
          leafletMap.applyDynamic("setView")(js.Array(lat, lon), 6)
        },
        (err: js.Dynamic) => (),
        opts
      )
    } catch { case _: Throwable => () }

    val onMapClick: js.Function1[js.Dynamic, Unit] = (e: js.Dynamic) => {
      val lat = e.latlng.lat.asInstanceOf[Double]
      val lon = e.latlng.lng.asInstanceOf[Double]
      dom.console.log(s"[DEBUG_LOG] Map clicked at lat=${lat}, lon=${lon}")
      if (leafletMarker != null && !js.isUndefined(leafletMarker)) leafletMarker.applyDynamic("remove")()
      if (leafletCircle != null && !js.isUndefined(leafletCircle)) leafletCircle.applyDynamic("remove")()
      leafletMarker = L.applyDynamic("marker")(js.Array(lat, lon))
      leafletMarker.applyDynamic("addTo")(leafletMap)
      leafletCircle = L.applyDynamic("circle")(js.Array(lat, lon), js.Dynamic.literal(radius = 10000))
      leafletCircle.applyDynamic("addTo")(leafletMap)

      val zoom = scala.util.Try(leafletMap.selectDynamic("_zoom").asInstanceOf[Int]).getOrElse(leafletMap.applyDynamic("getZoom")().asInstanceOf[Int])
      setLocationSummary(f"Selected: $lat%.4f, $lon%.4f — detecting location…")
      reverseGeocode(lat, lon, zoom) { (cnameOpt, ccodeOpt, displayNameOpt, bestLabelOpt, addrJsonStr) =>
        dom.console.log(s"[DEBUG_LOG] Reverse geocode result: label=${bestLabelOpt.getOrElse(displayNameOpt.getOrElse(""))}, country=${ccodeOpt.getOrElse("")}/${cnameOpt.getOrElse("")}")
        val cn = cnameOpt.getOrElse("Unknown country")
        val label = bestLabelOpt.orElse(displayNameOpt).getOrElse(cn)
        // Update card summary with the most precise label available
        val coordStr = f"$lat%.4f, $lon%.4f"
        val suffix = if (cn.nonEmpty && !label.contains(cn)) s" — $cn" else ""
        setLocationSummary(s"$label ($coordStr)$suffix")
        saveLocationContext(lat, lon, zoom, cnameOpt, ccodeOpt, displayNameOpt, bestLabelOpt, addrJsonStr)
      }
    }
    leafletMap.applyDynamic("on")("click", onMapClick)

    // Invalidate size after becoming visible to fix sizing issues
    dom.window.setTimeout(() => {
      try leafletMap.applyDynamic("invalidateSize")() catch { case _: Throwable => () }
    }, 200)

    // Wire clear button
    val clearBtn = dom.document.getElementById("location-clear").asInstanceOf[dom.html.Button]
    if (clearBtn != null) {
      clearBtn.addEventListener("click", (_: dom.MouseEvent) => {
        if (leafletMarker != null) leafletMarker.applyDynamic("remove")()
        if (leafletCircle != null) leafletCircle.applyDynamic("remove")()
        setLocationSummary("No location selected")
        dom.console.log("[DEBUG_LOG] Location selection cleared by user")
        clearLocationContext()
      })
    }

    // Wire search box
    val searchInput = dom.document.getElementById("location-search-input").asInstanceOf[dom.html.Input]
    val searchBtn = dom.document.getElementById("location-search-btn").asInstanceOf[dom.html.Button]
    val doSearch: () => Unit = () => {
      val q = if (searchInput != null) searchInput.value.trim else ""
      if (q.isEmpty) return
      setLocationSummary(s"Searching: '$q' …")
      forwardGeocode(q) {
        case None =>
          setLocationSummary("Place not found. Try a different name.")
        case Some((lat, lon, zoomGuess, cnameOpt, ccodeOpt, displayNameOpt, bestLabelOpt, addrJsonOpt, bboxOpt)) =>
          // Update map view: fit bounds if available, else setView
          try {
            if (bboxOpt.nonEmpty) {
              val (south, west, north, east) = bboxOpt.get
              val southWest = js.Dynamic.literal(lat = south, lng = west)
              val northEast = js.Dynamic.literal(lat = north, lng = east)
              val bounds = js.Dynamic.global.selectDynamic("L").applyDynamic("latLngBounds")(js.Array(southWest, northEast))
              leafletMap.applyDynamic("fitBounds")(bounds, js.Dynamic.literal(padding = js.Array(20, 20)))
            } else {
              leafletMap.applyDynamic("setView")(js.Array(lat, lon), zoomGuess)
            }
          } catch { case _: Throwable => leafletMap.applyDynamic("setView")(js.Array(lat, lon), zoomGuess) }

          // Update marker + circle
          if (leafletMarker != null && !js.isUndefined(leafletMarker)) leafletMarker.applyDynamic("remove")()
          if (leafletCircle != null && !js.isUndefined(leafletCircle)) leafletCircle.applyDynamic("remove")()
          leafletMarker = L.applyDynamic("marker")(js.Array(lat, lon))
          leafletMarker.applyDynamic("addTo")(leafletMap)
          leafletCircle = L.applyDynamic("circle")(js.Array(lat, lon), js.Dynamic.literal(radius = 10000))
          leafletCircle.applyDynamic("addTo")(leafletMap)

          val label = bestLabelOpt.orElse(displayNameOpt).orElse(cnameOpt).getOrElse("Selected location")
          val coordStr = f"$lat%.4f, $lon%.4f"
          val cn = cnameOpt.getOrElse("")
          val suffix = if (cn.nonEmpty && !label.contains(cn)) s" — $cn" else ""
          setLocationSummary(s"$label ($coordStr)$suffix")

          val finalZoom = scala.util.Try(leafletMap.applyDynamic("getZoom")().asInstanceOf[Int]).getOrElse(zoomGuess)
          saveLocationContext(lat, lon, finalZoom, cnameOpt, ccodeOpt, displayNameOpt, bestLabelOpt, addrJsonOpt)
      }
    }
    if (searchBtn != null) {
      searchBtn.addEventListener("click", (_: dom.MouseEvent) => doSearch())
    }
    if (searchInput != null) {
      searchInput.addEventListener("keydown", (e: dom.KeyboardEvent) => {
        if (e.key == "Enter") doSearch()
      })
    }

    locationInitialized = true
  }

  private def postJson(url: String, payload: ujson.Value)(onOk: ujson.Value => Unit, onErr: Throwable => Unit): Unit = {
    val req = new RequestInit {
      method = HttpMethod.POST
      headers = js.Dictionary("Content-Type" -> "application/json")
      this.body = upickle.default.write(payload)
    }
    fetch(url, req).toFuture
      .flatMap { resp =>
        // Surface non-2xx as failures with status text
        val status = resp.status
        val ok = resp.ok
        resp.text().toFuture.map { txt =>
          if (!ok) throw js.JavaScriptException(s"HTTP $status from ${url}")
          txt
        }
      }
      .map(txt => ujson.read(txt))
      .onComplete {
        case Success(v) => onOk(v)
        case Failure(e) => onErr(e)
      }
  }


  /**
   * Sets up event listeners for user interactions on the UI.
   *
   * This method assigns event listeners to the input field for detecting
   * 'Enter' key presses and to the submit button for detecting click events.
   * The listeners invoke the `sendQuestion` method to handle user-submitted questions.
   *
   * @return This method does not return anything.
   */
  private def setStatus(text: String): Unit = {
    val el = dom.document.getElementById("status-text").asInstanceOf[dom.html.Element]
    if (el != null) el.textContent = text
  }

  private def setChatEnabled(enabled: Boolean): Unit = {
    val q = dom.document.getElementById("question").asInstanceOf[dom.html.TextArea]
    val send = dom.document.getElementById("send-button").asInstanceOf[dom.html.Button]
    val copy = dom.document.getElementById("btn-copy").asInstanceOf[dom.html.Button]
    val clear = dom.document.getElementById("btn-clear").asInstanceOf[dom.html.Button]
    if (q != null) q.disabled = !enabled
    if (send != null) send.disabled = !enabled
    if (copy != null) copy.disabled = !enabled
    if (clear != null) clear.disabled = !enabled
    if (!enabled) setStatus("Login required to chat.") else setStatus("Ready.")
  }

  private def isSessionReady: Boolean = sessionId.exists(_.nonEmpty)

  // Helper to set login feedback text and theme class
  private def setLoginInfo(text: String, state: Option[String] = None): Unit = {
    val info = dom.document.getElementById("login-info").asInstanceOf[dom.html.Element]
    if (info != null) {
      info.textContent = text
      // reset state classes
      info.classList.remove("ok")
      info.classList.remove("error")
      state match {
        case Some("ok")    => info.classList.add("ok")
        case Some("error") => info.classList.add("error")
        case _ => ()
      }
    }
  }

  private def updateLoginUi(): Unit = {
    val u = dom.document.getElementById("login-username").asInstanceOf[dom.html.Input]
    val p = dom.document.getElementById("login-password").asInstanceOf[dom.html.Input]
    val loginBtn = dom.document.getElementById("login-button").asInstanceOf[dom.html.Button]
    val logoutBtn = dom.document.getElementById("logout-button").asInstanceOf[dom.html.Button]
    val info = dom.document.getElementById("login-info").asInstanceOf[dom.html.Element]
    if (isAuthenticated) {
      if (u != null) u.disabled = true
      if (p != null) p.disabled = true
      if (loginBtn != null) { loginBtn.style.display = "none"; loginBtn.disabled = true }
      if (logoutBtn != null) { logoutBtn.style.display = "inline-block"; logoutBtn.disabled = false }
      setLoginInfo("Logged in.", Some("ok"))
    } else {
      val sessionReady = isSessionReady
      if (u != null) { u.disabled = false; if (u.value.isEmpty) u.placeholder = "Email or username" }
      if (p != null) { p.disabled = false; if (p.value.isEmpty) p.placeholder = "Password" }
      if (loginBtn != null) {
        loginBtn.style.display = "inline-block"
        loginBtn.disabled = !sessionReady
      }
      if (logoutBtn != null) { logoutBtn.style.display = "none"; logoutBtn.disabled = true }
      setLoginInfo(if (sessionReady) "" else "Preparing session…")
    }
  }

  private def handleLogin(): Unit = {
    dom.console.log("[DEBUG_LOG] Login button clicked")
    val usernameInput = dom.document.getElementById("login-username").asInstanceOf[dom.html.Input]
    val passwordInput = dom.document.getElementById("login-password").asInstanceOf[dom.html.Input]
    val loginBtn = dom.document.getElementById("login-button").asInstanceOf[dom.html.Button]
    val info = dom.document.getElementById("login-info").asInstanceOf[dom.html.Element]

    val username = Option(usernameInput).map(_.value).getOrElse("")
    val password = Option(passwordInput).map(_.value).getOrElse("")
    val sid = sessionId.getOrElse("")

    if (sid.isEmpty) {
      setLoginInfo("Preparing session…")
      setStatus("Waiting for session…")
      // Try to (re)fetch session visibly
      getSession()
      return
    }

    // Provide immediate feedback and prevent double clicks
    if (loginBtn != null) { loginBtn.disabled = true }
    setLoginInfo("Logging in…")

    val payload = ujson.Obj("sessionId" -> sid, "username" -> username, "password" -> password)
    postJson(s"$httpBase/login", payload)({ v =>
      val ok = v("ok").bool
      if (ok) {
        isAuthenticated = true
        setAuthState(true)
        setChatEnabled(true)
        val name = v.obj.get("displayName").map(_.str).getOrElse("User")
        setLoginInfo("Logged in.", Some("ok"))
        updateLoginUi()
        // Re-enable the Location tab when logged in
        setLocationTabEnabled(true)
        addMessage("AI", s"Welcome, $name! You can start chatting now.")
        // If there was a location selected before login, push it now
        pendingLocation.foreach { pl =>
          dom.console.log("[DEBUG_LOG] Pushing pending location selection after login")
          // Call root-scoped API to avoid collision with static files mounted at /soilcompanion
          postJson(s"$httpBase/location", pl)({ _ =>
            dom.console.log("[DEBUG_LOG] Pending location saved successfully after login")
            pendingLocation = None
          }, { e =>
            dom.console.error("[DEBUG_LOG] Failed to save pending location after login", e)
          })
        }
      } else {
        setLoginInfo("Invalid credentials.", Some("error"))
        setChatEnabled(false)
        if (loginBtn != null) { loginBtn.disabled = false }
      }
    }, { e =>
      dom.console.error("[DEBUG_LOG] Login request failed", e)
      setLoginInfo("Login failed. Please try again.", Some("error"))
      setStatus("Login failed.")
      if (loginBtn != null) { loginBtn.disabled = false }
    })
  }

  private def handleLogout(): Unit = {
    val sid = sessionId.getOrElse("")
    val payload = ujson.Obj("sessionId" -> sid)
    postJson(s"$httpBase/logout", payload)({ _ =>
      isAuthenticated = false
      setAuthState(false)
      setChatEnabled(false)
      updateLoginUi()
      // Disable Location tab and bring user to About after logout
      setLocationTabEnabled(false)
      activateSidebarTab("tab-about")
      addMessage("AI", "You have been logged out. Please login again to continue chatting.")
    }, { _ =>
      isAuthenticated = false
      setChatEnabled(false)
      updateLoginUi()
      setLocationTabEnabled(false)
      activateSidebarTab("tab-about")
    })
  }

  private def setupEventListeners(): Unit = {
    val questionElement = dom.document.getElementById("question").asInstanceOf[dom.html.TextArea]
    val counterEl = dom.document.getElementById("char-counter").asInstanceOf[dom.html.Element]

    // Elements for dynamic safe-area handling
    val inputContainer = dom.document.getElementById("input-container").asInstanceOf[dom.html.Element]
    val messagesEl = dom.document.getElementById("messages").asInstanceOf[dom.html.Element]

    // Constants
    val MaxChars = 400
    val NearThreshold = 40 // show warning color when <= 40 left

    // Compute and apply bottom padding so last bubble is never hidden behind the input composer
    def pxToDouble(px: String): Double =
      if (px == null) 0.0
      else
        val s = px.trim
        if (s.endsWith("px"))
          scala.util.Try(s.dropRight(2).toDouble).getOrElse(0.0)
        else scala.util.Try(s.toDouble).getOrElse(0.0)

    def updateComposerSafeArea(): Unit = {
      if (messagesEl != null && inputContainer != null) {
        val cs = dom.window.getComputedStyle(inputContainer)
        val h = inputContainer.clientHeight.toDouble
        val mt = pxToDouble(cs.marginTop)
        val mb = pxToDouble(cs.marginBottom)
        val total = h + mt + mb + 8.0 // a little extra breathing room
        messagesEl.style.paddingBottom = s"${total}px"
        // keep user scrolled to bottom if they were near bottom already
        val container = messagesEl
        val nearBottom = (container.scrollHeight - (container.scrollTop + container.clientHeight)) <= 24
        if (nearBottom) {
          container.scrollTop = container.scrollHeight
        }
      }
    }

    def updateCounter(): Unit = {
      if (counterEl != null && questionElement != null) {
        val remaining = MaxChars - questionElement.value.length
        counterEl.textContent = s"${remaining} left"
        counterEl.classList.remove("warn")
        if (remaining <= NearThreshold) counterEl.classList.add("warn")
      }
    }

    def autoResize(): Unit = {
      if (questionElement != null) {
        // Reset height to compute scrollHeight correctly
        questionElement.style.height = "auto"
        val maxPx = 140 // ~5-6 lines depending on styling
        val newH = Math.min(questionElement.scrollHeight, maxPx)
        questionElement.style.height = s"${newH}px"
      }
    }

    if (questionElement != null) {
      // Initialize sizing and counter
      autoResize()
      updateCounter()
      updateComposerSafeArea()

      // Enforce max length + update visuals on input
      questionElement.addEventListener("input", (_: dom.Event) => {
        if (questionElement.value.length > MaxChars) {
          questionElement.value = questionElement.value.substring(0, MaxChars)
        }
        updateCounter()
        autoResize()
        updateComposerSafeArea()
      })

      // Enter to send, Shift+Enter for new line
      questionElement.addEventListener("keydown", (e: dom.KeyboardEvent) => {
        if (e.key == "Enter" && !e.shiftKey) {
          e.preventDefault()
          sendQuestion()
        }
      })
    }

    // Update safe-area on window resize/orientation change
    dom.window.addEventListener("resize", (_: dom.Event) => updateComposerSafeArea())
    dom.window.addEventListener("orientationchange", (_: dom.Event) => updateComposerSafeArea())

    // Observe input container size changes for any reason (e.g., CSS, fonts, dynamic UI)
    try {
      if (inputContainer != null && !js.isUndefined(js.Dynamic.global.selectDynamic("ResizeObserver"))) {
        val ro = new dom.ResizeObserver((entries: js.Array[dom.ResizeObserverEntry], _: dom.ResizeObserver) => updateComposerSafeArea())
        ro.observe(inputContainer)
      }
    } catch { case _: Throwable => () }

    val sendButton = dom.document.getElementById("send-button").asInstanceOf[dom.html.Button]
    if (sendButton != null) {
      sendButton.addEventListener("click", (_: dom.MouseEvent) => {
        sendQuestion()
      })
    }

    // Login / Logout buttons
    Option(dom.document.getElementById("login-button")).foreach { el =>
      el.addEventListener("click", (_: dom.MouseEvent) => handleLogin())
    }
    Option(dom.document.getElementById("logout-button")).foreach { el =>
      el.addEventListener("click", (_: dom.MouseEvent) => handleLogout())
    }

    // Initialize Location tab/map lazily upon first activation
    val tabLocation = dom.document.getElementById("tab-location").asInstanceOf[dom.html.Element]
    if (tabLocation != null) {
      tabLocation.addEventListener("click", (_: dom.MouseEvent) => {
        // If not authenticated, prevent using Location and switch back to About
        if (!isAuthenticated) {
          setStatus("Login required for Location.")
          setLoginInfo("Please login to select a location.")
          // Ensure About tab stays active
          dom.window.setTimeout(() => activateSidebarTab("tab-about"), 0)
        } else {
          // The panel becomes visible via HTML tab system; init map
          dom.window.setTimeout(() => initLocationMap(), 50)
        }
      })
    }
  }

  /**
   * Sends a user's question to the backend server and updates the UI with the user's message
   * and an initial response placeholder for the AI. If the input field for the question is
   * non-empty after trimming, the method performs the following steps:
   *
   * 1. Adds the user's question to the message UI.
   * 2. Displays a loading spinner while waiting for the server response.
   * 3. Sends a POST request to the backend with the session ID and user's question.
   * 4. Hides the spinner and appends an empty message placeholder for the AI response once the
   * request is successful.
   *
   * This method interacts with UI elements directly and depends on the current session ID and
   * backend URL to construct the request and handle response updates.
   *
   * @return This method does not return anything.
   */
  private def sendQuestion(): Unit = {
    if (!isAuthenticated) {
      setStatus("Login required to chat.")
      val info = dom.document.getElementById("login-info").asInstanceOf[dom.html.Element]
      if (info != null && info.textContent.trim().isEmpty) setLoginInfo("Please login to start chatting.")
      return
    }
    // Ensure WebSocket is connected; if not, try to reconnect and inform the user
    if (!wsConnected) {
      ensureBotPlaceholder()
      showErrorInLastBotMessage(Some("Realtime connection not ready. Reconnecting… Please try again in a moment."))
      // attempt reconnect if we have a session
      sessionId.foreach(connectWebSocket)
      return
    }
    val questionElement = dom.document.getElementById("question").asInstanceOf[dom.html.TextArea]
    val raw = questionElement.value
    val question = raw.take(400)
    if (question.trim().nonEmpty) {
      addMessage("user", question)
      showSpinner()

      val request = new RequestInit {
        method = HttpMethod.POST
        headers = js.Dictionary("Content-Type" -> "application/json")
        body = write(QueryRequest(sessionId.getOrElse(""), question.trim()))
      }
      fetch(s"$httpBase/query", request)
        .toFuture
        .flatMap { resp =>
          // If backend says no active session (due to WS dropped on server), surface an error and trigger reconnect
          if (!resp.ok) then
            resp.text().toFuture.map { body =>
              val lowered = Option(body).getOrElse("").toLowerCase
              if (lowered.contains("no active session")) {
                ensureBotPlaceholder()
                showErrorInLastBotMessage(Some("Session connection lost. Reconnecting… Please resend your question."))
                sessionId.foreach(connectWebSocket)
              } else {
                showErrorInLastBotMessage(Some(s"Server error (${resp.status}): ${body}"))
              }
              ()
            }
          else {
            // Start a watchdog: if no tokens or events arrive within 25s, show a helpful error
            pendingResponseTimer.foreach(dom.window.clearTimeout)
            pendingResponseTimer = Some(dom.window.setTimeout(() => {
              showErrorInLastBotMessage(Some("The answer is taking unusually long. This can happen if the connection was interrupted by the network or gateway. Please try again."))
            }, 25000))
            // Hide spinner after request is accepted; streaming comes over WebSocket
            scala.concurrent.Future.successful(hideSpinner())
          }
        }
        .recover { case _ =>
          showErrorInLastBotMessage(Some("Failed to send question. Please check your connection and try again."))
        }

      // Clear input and reset counter/size after sending
      questionElement.value = ""
      try {
        val counterEl = dom.document.getElementById("char-counter").asInstanceOf[dom.html.Element]
        if (counterEl != null) {
          counterEl.textContent = "400 left"
          counterEl.classList.remove("warn")
        }
      } catch { case _: Throwable => () }
      // Reset textarea height and update messages safe-area so last bubble remains visible
      questionElement.style.height = "auto"
      try {
        val inputContainer = dom.document.getElementById("input-container").asInstanceOf[dom.html.Element]
        val messagesEl = dom.document.getElementById("messages").asInstanceOf[dom.html.Element]
        def pxToDouble(px: String): Double =
          if (px == null) 0.0 else {
            val s = px.trim
            if (s.endsWith("px")) scala.util.Try(s.dropRight(2).toDouble).getOrElse(0.0)
            else scala.util.Try(s.toDouble).getOrElse(0.0)
          }
        if (inputContainer != null && messagesEl != null) {
          val cs = dom.window.getComputedStyle(inputContainer)
          val h = inputContainer.clientHeight.toDouble
          val mt = pxToDouble(cs.marginTop)
          val mb = pxToDouble(cs.marginBottom)
          val total = h + mt + mb + 8.0
          messagesEl.style.paddingBottom = s"${total}px"
          messagesEl.scrollTop = messagesEl.scrollHeight
        }
      } catch { case _: Throwable => () }
    }
  }

  /**
   * Adds a new message to the message container on the UI. The message can be either
   * from the user or from the bot, and it will be styled and sanitized accordingly.
   *
   * @param sender         A string indicating the sender of the message (e.g., "user" or "bot").
   *                       Determines the CSS styling and how the message is displayed.
   * @param text           The content of the message to be displayed. For bot messages, it is
   *                       parsed, sanitized, and syntax-highlighted if applicable.
   * @param allowFeedback  If true, render thumb up/down feedback UI on the bubble. This should only
   *                       be enabled for actual LLM generated responses, not for initial greetings
   *                       or system notifications. Defaults to false.
   * @return This method does not return anything.
   */
  private def addMessage(sender: String, text: String, allowFeedback: Boolean = false): Unit = {
    val messageContainer = dom.document.getElementById("messages")
    val newDiv = dom.document.createElement("div")
    val cssClasses = List(
      "message",
      if (sender == "user") "user-message" else "bot-message"
    )
    cssClasses.foreach(newDiv.classList.add)

    if (sender == "user") {
      val safeText = DOMPurify.sanitize(text)
      val time = nowTimeString()
      newDiv.innerHTML =
        s"""
           |<div class=\"message-header\">
           |  <i class=\"fa-solid fa-user\" aria-hidden=\"true\"></i>
           |</div>
           |<div class=\"message-time\">$time</div>
           |<div class=\"message-content\">%s</div>
           |""".stripMargin.format(safeText)
    } else {
      // keep original text to support streaming data
      newDiv.setAttribute("data-raw-content", text)
      // assign a unique message id for feedback
      val msgId = newMessageId()
      newDiv.setAttribute("data-message-id", msgId)
      // attach current questionId if available (used to correlate feedback/logs)
      if (allowFeedback) {
        currentQuestionId.foreach(qid => newDiv.setAttribute("data-question-id", qid))
      }
      // parse, sanitize, and syntax-highlight if applicable
      val parsed = marked.parse(text)
      val sanitized = DOMPurify.sanitize(parsed)
      // Build inner HTML with optional feedback controls
      val baseContent =
        s"""
           |<div class=\"message-header\"> 
           |  <i class=\"fa-solid fa-robot\" aria-hidden=\"true\"></i>
           |</div>
           |<div class=\"message-time\">${nowTimeString()}</div>
           |<div class=\"message-content\">%s</div>
           |""".stripMargin.format(sanitized)

      val feedbackHtml =
        s"""
           |<div class=\"feedback\" role=\"group\" aria-label=\"Rate response\"> 
           |  <button class=\"thumb-up\" title=\"Helpful\" aria-label=\"Mark helpful\"> 
           |    <i class=\"fa-solid fa-thumbs-up\"></i>
           |  </button>
           |  <button class=\"thumb-down\" title=\"Not helpful\" aria-label=\"Mark not helpful\"> 
           |    <i class=\"fa-solid fa-thumbs-down\"></i>
           |  </button>
           |</div>
           |""".stripMargin

      newDiv.innerHTML = if (allowFeedback) baseContent + feedbackHtml else baseContent
      hljs.highlightAll()

      // Wire feedback handlers (only if feedback UI is present)
      val feedbackEl = newDiv.querySelector(".feedback").asInstanceOf[dom.html.Element]
      val upBtn = if (feedbackEl != null) feedbackEl.querySelector(".thumb-up").asInstanceOf[dom.html.Button] else null
      val downBtn = if (feedbackEl != null) feedbackEl.querySelector(".thumb-down").asInstanceOf[dom.html.Button] else null

      // manage visible vote state on the bubble
      def setVoteState(voteOpt: Option[String]): Unit = {
        voteOpt match {
          case Some("up") =>
            newDiv.setAttribute("data-vote", "up")
            feedbackEl.classList.remove("voted-down")
            feedbackEl.classList.add("voted-up")
            upBtn.setAttribute("aria-pressed", "true")
            downBtn.setAttribute("aria-pressed", "false")
          case Some("down") =>
            newDiv.setAttribute("data-vote", "down")
            feedbackEl.classList.remove("voted-up")
            feedbackEl.classList.add("voted-down")
            upBtn.setAttribute("aria-pressed", "false")
            downBtn.setAttribute("aria-pressed", "true")
          case _ =>
            newDiv.setAttribute("data-vote", "none")
            feedbackEl.classList.remove("voted-up")
            feedbackEl.classList.remove("voted-down")
            upBtn.setAttribute("aria-pressed", "false")
            downBtn.setAttribute("aria-pressed", "false")
        }
      }

      def currentVote(): Option[String] =
        Option(newDiv.getAttribute("data-vote")).flatMap {
          case s if s == null || s.isEmpty || s == "none" => None
          case s if s == "up" || s == "down" => Some(s)
          case _ => None
        }

      // initialize state
      if (feedbackEl != null) setVoteState(None)

      def setButtonsDisabled(disabled: Boolean): Unit = {
        if (upBtn != null) upBtn.disabled = disabled
        if (downBtn != null) downBtn.disabled = disabled
      }

      def sendFeedback(vote: String, reason: Option[String]): Unit = {
        if (feedbackEl == null) return
        (sessionId, Option(getOrCreateDeviceId())) match {
          case (Some(sid), Some(did)) =>
            val qidAttr = Option(newDiv.getAttribute("data-question-id")).filter(s => s != null && s.nonEmpty)
            val body = ujson.Obj(
              "sessionId" -> sid,
              "deviceId" -> did,
              "messageId" -> msgId,
              "vote" -> vote,
              "reason" -> reason.map(ujson.Str.apply).getOrElse(ujson.Null),
              // Optional, helps correlate with server application logs
              "questionId" -> qidAttr.map(ujson.Str.apply).getOrElse(ujson.Null)
            )
            setButtonsDisabled(true)
            postJson(s"$httpBase/feedback", body)(
              onOk = resp => {
                val flip = resp.obj.get("flipAllowed").exists(_.bool)
                if (flip) setButtonsDisabled(false)
                // keep the chosen visible state regardless; flipping allowed means user can change later
              },
              onErr = _ => {
                // on error, re-enable so user can retry; keep state visible as user intent
                setButtonsDisabled(false)
              }
            )
          case _ => dom.console.warn("[DEBUG_LOG] No session/device id; cannot send feedback")
        }
      }

      def renderReasons(): Unit = {
        // avoid duplicating the reasons panel
        if (feedbackEl.querySelector(".feedback-reasons") != null) return
        val reasons = List("incorrect", "too generic", "off-topic", "tone", "other")
        val container = dom.document.createElement("div").asInstanceOf[dom.html.Div]
        container.classList.add("feedback-reasons")
        container.setAttribute("role", "group")
        container.setAttribute("aria-label", "Select a reason")
        reasons.foreach { r =>
          val b = dom.document.createElement("button").asInstanceOf[dom.html.Button]
          b.textContent = r
          b.classList.add("reason-option")
          b.addEventListener("click", (_: dom.MouseEvent) => {
            // once a reason is chosen, set visible state and send the downvote with reason, then remove panel
            setVoteState(Some("down"))
            container.remove()
            sendFeedback("down", Some(r))
          })
          container.appendChild(b)
        }
        feedbackEl.appendChild(container)
      }

      if (upBtn != null) {
        upBtn.addEventListener("click", (_: dom.MouseEvent) => {
          // set visible state immediately
          setVoteState(Some("up"))
          sendFeedback("up", None)
        })
      }

      if (downBtn != null) {
        downBtn.addEventListener("click", (_: dom.MouseEvent) => {
          // show one-tap reasons UI; do not send yet
          renderReasons()
        })
      }
    }

    messageContainer.appendChild(newDiv)
    // ensure FA icons render for dynamically inserted nodes
    refreshFaIcons()
    // scroll to bottom of message container
    messageContainer.scrollTop = messageContainer.scrollHeight
  }

  /**
   * Processes a partial message received as a `QueryPartialResponse` and updates the last bot message
   * in the message container on the UI. The method combines the new content with the existing bot
   * message content, sanitizes and parses it, applies syntax highlighting, and scrolls the container
   * to the bottom.
   *
   * @param partial The `QueryPartialResponse` containing the partial content to be added to the
   *                most recent bot message in the UI.
   * @return This method does not return anything.
   */
  private def processPartialMessage(partial: QueryPartialResponse): Unit = {
    val messageContainer = dom.document.getElementById("messages")
    // assume last message is from bot and has been added
    val lastMessage = messageContainer.lastElementChild
    Option(lastMessage)
      .filter(_.classList.contains("bot-message"))
      .foreach { messageElement =>
        val rawContent = Option(messageElement.getAttribute("data-raw-content")).getOrElse("")
        val newContent = rawContent + partial.content
        // keep updated original text to support streaming data
        messageElement.setAttribute("data-raw-content", newContent)
        // parse, sanitize, and syntax-highlight if applicable
        val parsed = marked.parse(newContent)
        val sanitized = DOMPurify.sanitize(parsed)
        val contentEl = messageElement.querySelector(".message-content").asInstanceOf[dom.html.Element]
        if (contentEl != null) {
          contentEl.innerHTML = sanitized
        } else {
          // fallback: set entire content (older structure)
          messageElement.innerHTML = s"<div class=\"message-header\"><i class=\"fa-solid fa-robot\"></i></div><div class=\"message-time\">${nowTimeString()}</div><div class=\"message-content\">$sanitized</div>"
          refreshFaIcons()
        }
        hljs.highlightAll()
        // scroll to bottom of message container
        messageContainer.scrollTop = messageContainer.scrollHeight
      }
  }

  /**
   * Fetches the current server session and establishes a WebSocket connection
   * using the fetched session ID. The method sends an HTTP GET request to the
   * backend server to retrieve a session ID, handles the server response, and
   * logs the outcome. If the session is successfully retrieved, it initializes
   * a WebSocket connection to subscribe to updates associated with the session ID.
   *
   * The request is sent as JSON to the server URL built using the `backendUrl`
   * field of the class. On successful retrieval of the session ID, the session
   * is stored, and a WebSocket connection is established using the `connectWebSocket`
   * method. On failure, an error message is logged to the browser console.
   *
   * Side effects:
   * - Logs messages to the browser console for debugging purposes.
   * - Updates the `sessionId` field of the class with the fetched session ID.
   * - Initiates a WebSocket connection through the `connectWebSocket` method.
   */
  def getSession() = {
    val request = new RequestInit {
      method = HttpMethod.GET
      headers = js.Dictionary("Content-Type" -> "application/json")
    }
    fetch(s"$httpBase/session", request).toFuture
      .flatMap(_.text().toFuture)
      .map(read[String](_))
      .onComplete {
        case Success(session) =>
          dom.console.log(s"Server session fetched: $session")
          sessionId = Some(session)
          // persist for other scripts (e.g., toolbar.js)
          dom.window.localStorage.setItem("soilcompanion.sessionId", session)
          // session is ready: enable login UI if previously disabled
          updateLoginUi()
          connectWebSocket(session)
        case Failure(e) =>
          dom.console.log(s"Error fetching server session: $e")
          setLoginInfo("Failed to prepare session. Please reload the page.", Some("error"))
      }
  }

  /**
   * Establishes a WebSocket connection to subscribe to updates for a given session.
   * The method initializes a WebSocket connection to the backend URL using the session ID.
   * Incoming WebSocket messages are parsed as `QueryPartialResponse` and logged to the console.
   *
   * @param sessionId The unique identifier for the session used to establish the WebSocket connection.
   */
  def connectWebSocket(sessionId: String): Unit = {
    // Close any previous socket first
    ws.foreach(s => scala.util.Try(s.close()))
    val socket = new WebSocket(s"$wsBase/subscribe/$sessionId")
    ws = Some(socket)

    def updateStatusFooter(text: String): Unit = {
      val el = dom.document.getElementById("status-text").asInstanceOf[dom.html.Element]
      if (el != null) el.textContent = text
    }

    def scheduleReconnect(): Unit = {
      val delay = math.min(wsReconnectBackoffMs, 30000)
      dom.window.setTimeout(() => connectWebSocket(sessionId), delay)
      wsReconnectBackoffMs = math.min(delay * 2, 30000)
    }

    socket.onopen = { (_: dom.Event) =>
      wsConnected = true
      wsReconnectBackoffMs = 1000
      updateStatusFooter("Connected.")
    }

    socket.onmessage = { (e: MessageEvent) =>
      lastWsMessageAt = js.Date.now()
      val data = e.data.toString
      // Try to decode as QueryEvent first; if that fails, treat as token chunk
      scala.util.Try(read[QueryEvent](data)).toOption match {
        case Some(evt) =>
          evt.event match {
            case "received" =>
              // Capture server-provided questionId for correlating the next bot message
              currentQuestionId = evt.questionId.orElse(currentQuestionId)
              updateStatusFooter(evt.detail.getOrElse("Received."))
            case "thinking" =>
              // Ensure we keep the questionId as soon as available
              currentQuestionId = evt.questionId.orElse(currentQuestionId)
              ensureBotPlaceholder()
              updateStatusFooter(evt.detail.getOrElse("AI is thinking…"))
              // Cancel pending watchdog once activity starts
              pendingResponseTimer.foreach(dom.window.clearTimeout)
              pendingResponseTimer = None
              // show typing indicator inside the bot bubble (content empty)
              val messages = dom.document.getElementById("messages")
              val last = messages.lastElementChild
              Option(last).filter(_.classList.contains("bot-message")).foreach { el =>
                val contentEl = el.querySelector(".message-content").asInstanceOf[dom.html.Element]
                if (contentEl != null && contentEl.innerHTML.trim.isEmpty) {
                  contentEl.innerHTML = """
                    |<div class='typing-indicator' aria-label='Assistant is thinking'>
                    |  <span class='dot'></span><span class='dot'></span><span class='dot'></span>
                    |</div>
                    |""".stripMargin
                }
              }
            case "retrieving_context" =>
              ensureBotPlaceholder()
              updateStatusFooter(evt.detail.getOrElse("Searching SoilWise catalog via Solr…"))
            case "generating" => updateStatusFooter(evt.detail.getOrElse("Generating answer…"))
            case "heartbeat" => () // ignore in UI
            case "unauthorized" =>
              isAuthenticated = false
              setAuthState(false)
              setChatEnabled(false)
              updateLoginUi()
              setLocationTabEnabled(false)
              activateSidebarTab("tab-about")
              val msg = evt.detail.getOrElse("Login required to chat.")
              addMessage("AI", msg)
            case "logged_out" =>
              isAuthenticated = false
              setAuthState(false)
              setChatEnabled(false)
              updateLoginUi()
              setLocationTabEnabled(false)
              activateSidebarTab("tab-about")
              val msg = evt.detail.getOrElse("You have been logged out.")
              addMessage("AI", msg)
            case "session_expired" =>
              // Clear chat UI and require login again
              val container = dom.document.getElementById("messages").asInstanceOf[dom.html.Element]
              if (container != null) container.innerHTML = ""
              isAuthenticated = false
              setAuthState(false)
              setChatEnabled(false)
              updateLoginUi()
              setLocationTabEnabled(false)
              activateSidebarTab("tab-about")
              val msg = evt.detail.getOrElse("Your session expired due to inactivity. Please login to start a new chat.")
              addMessage("AI", msg)
            case "done" =>
              updateStatusFooter(evt.detail.getOrElse("Ready."))
              // Defensive: if a typing indicator is still visible with no content, clear it
              val messages = dom.document.getElementById("messages")
              val last = messages.lastElementChild
              Option(last).filter(_.classList.contains("bot-message")).foreach { el =>
                val contentEl = el.querySelector(".message-content").asInstanceOf[dom.html.Element]
                if (contentEl != null && contentEl.querySelector(".typing-indicator") != null) {
                  contentEl.innerHTML = ""
                  el.setAttribute("data-raw-content", "")
                }
              }
              // Clear currentQuestionId after finishing, so a next question will set a new one
              currentQuestionId = None
              pendingResponseTimer.foreach(dom.window.clearTimeout)
              pendingResponseTimer = None
            case "error" =>
              // Stop spinner/typing and show a friendly message in the bubble
              updateStatusFooter(evt.detail.getOrElse("An error occurred."))
              showErrorInLastBotMessage(evt.detail)
              pendingResponseTimer.foreach(dom.window.clearTimeout)
              pendingResponseTimer = None
            case other => updateStatusFooter(evt.detail.getOrElse(other))
          }
        case None =>
          val qpr = read[QueryPartialResponse](data)
          // First real token: ensure bot placeholder present and clear typing indicator if needed
          ensureBotPlaceholder()
          dom.console.log(s"${qpr.content}")
          // If the last bot message has a typing indicator and empty raw content, clear it
          val messageContainer = dom.document.getElementById("messages")
          val last = messageContainer.lastElementChild
          Option(last).filter(_.classList.contains("bot-message")).foreach { el =>
            val contentEl = el.querySelector(".message-content").asInstanceOf[dom.html.Element]
            if (contentEl != null && contentEl.querySelector(".typing-indicator") != null) {
              contentEl.innerHTML = ""
              el.setAttribute("data-raw-content", "")
            }
          }
          // We received content; cancel watchdog
          pendingResponseTimer.foreach(dom.window.clearTimeout)
          pendingResponseTimer = None
          processPartialMessage(qpr)
      }
    }

    window.onbeforeunload = _ => socket.close()

    // Also handle transport-level errors defensively
    socket.onerror = { (_: dom.Event) =>
      wsConnected = false
      updateStatusFooter("Connection error while generating.")
      showErrorInLastBotMessage(Some("Connection error while generating."))
      scheduleReconnect()
    }

    socket.onclose = { (_: dom.Event) =>
      wsConnected = false
      updateStatusFooter("Connection closed. Reconnecting…")
      scheduleReconnect()
    }
  }

  /**
   * Displays the loading spinner by setting its `style.display` property to "flex".
   * The spinner element is retrieved from the DOM using its ID "spinner".
   *
   * @return This method does not return anything.
   */
  private def showSpinner(): Unit =
    dom.document.getElementById("spinner").asInstanceOf[dom.html.Div].style.display = "flex"

  /**
   * Hides the loading spinner by setting its `style.display` property to "none".
   * The spinner element is retrieved from the DOM using its ID "spinner".
   *
   * @return This method does not return anything.
   */
  private def hideSpinner(): Unit =
    dom.document.getElementById("spinner").asInstanceOf[dom.html.Div].style.display = "none"

  // --- Main ---

  dom.console.log("Soil Companion App is running!")
  // Initialize session and UI
  getSession()
  setupEventListeners()
  isAuthenticated = false
  setChatEnabled(false)
  updateLoginUi()
  // On initial load when not authenticated: disable Location tab and force About tab
  setLocationTabEnabled(false)
  // Defer tab activation to ensure it wins over inline tab persistence script
  dom.window.setTimeout(() => ensureAboutIfUnauthed(), 0)
  addMessage("AI", "Welcome to Soil Companion. Please login in the left sidebar to start chatting.")
}
