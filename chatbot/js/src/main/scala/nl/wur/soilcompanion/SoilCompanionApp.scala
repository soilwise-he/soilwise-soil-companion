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
  // Track a single scheduled reconnect timer to avoid concurrent reconnect attempts
  private var wsReconnectTimer: Option[Int] = None
  private var lastWsMessageAt: Double = js.Date.now()
  // Watchdog timer to surface errors if no events arrive after sending a question
  private var pendingResponseTimer: Option[Int] = None
  // Track whether chat input/actions are enabled (separate from auth/connectivity)
  private var chatEnabled: Boolean = false
  
  // When user clicks "Clear" before a sessionId is available, remember intent
  private var pendingClearLocation: Boolean = false

  // Map configs received out-of-band during streaming, keyed by map ID.
  // On "done" these are used to replace [[MAP:<id>]] placeholders in the last bot message.
  private val pendingMapConfigs = scala.collection.mutable.Map.empty[String, ujson.Value]
  
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

  // --- App version/update polling ---
  // Keep track of the initially loaded backend app version tag to detect updates
  private var initialVersionTag: Option[String] = None
  // Handle to a periodic timer used for polling /healthz
  private var versionPollTimer: Option[Int] = None

  // --- Backend configuration ---
  // Catalog item link base URL from backend config
  private var catalogItemLinkBaseUrl: String = "https://repository.soilwise-he.eu/cat/collections/metadata:main/items/"

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

  // Ensure that all hyperlinks inside the given container open in a new tab
  // and use safe rel attributes. Call this after inserting sanitized HTML.
  private def fixExternalLinks(container: dom.Element | Null): Unit = {
    try {
      if (container == null) return
      val scope = container.asInstanceOf[dom.Element]

      // First, convert DOI text patterns to hyperlinks
      convertDoiToLinks(scope)

      // Then fix all anchor tags to open in new tab with security attributes
      val anchors = scope.querySelectorAll("a")
      var i = 0
      while (i < anchors.length) {
        val a = anchors(i).asInstanceOf[dom.html.Anchor]
        if (a != null) {
          a.setAttribute("target", "_blank")
          val existingRel = Option(a.getAttribute("rel")).getOrElse("")
          val tokens = existingRel.split("\\s+").filter(_.nonEmpty).toBuffer
          if (!tokens.contains("noopener")) tokens += "noopener"
          if (!tokens.contains("noreferrer")) tokens += "noreferrer"
          a.setAttribute("rel", tokens.mkString(" ").trim)
        }
        i += 1
      }

      // Initialize any Leaflet maps in the container
      initLeafletMaps()
    } catch { case _: Throwable => () }
  }

  // Call the global Leaflet map initialization function (defined in leaflet-init.js)
  private def initLeafletMaps(): Unit = {
    try {
      val initFn = js.Dynamic.global.initLeafletMaps
      if (!js.isUndefined(initFn) && js.typeOf(initFn) == "function") {
        initFn.asInstanceOf[js.Function0[Unit]]()
      }
    } catch { case _: Throwable => () }
  }

  // Convert DOI text patterns and SoilWise ID patterns to clickable hyperlinks
  private def convertDoiToLinks(container: dom.Element): Unit = {
    try {
      // Pattern to match DOI identifiers in various formats:
      // - doi.org/10.xxxx/yyyy
      // - doi:10.xxxx/yyyy
      // - DOI: 10.xxxx/yyyy
      val doiPattern = """(?:https?://)?(?:dx\.)?doi\.org/(10\.\S+)|(?:doi:\s*)(10\.\S+)|(?:DOI:\s*)(10\.\S+)""".r

      // Pattern to match SoilWise ID format: "SoilWise ID: 10.xxxx/yyyy"
      // Excludes trailing punctuation like ), ., ,) etc.
      val soilwiseIdPattern = """(?:SoilWise\s+ID:\s*)(10\.[^\s\),;!?]+?)(?=[\.;,\)!\?]?\s|[\.;,\)!\?]?$)""".r

      // Walk through text nodes and replace patterns
      val walker = dom.document.createTreeWalker(
        container,
        dom.NodeFilter.SHOW_TEXT,
        null,
        false
      )

      val nodesToReplace = scala.collection.mutable.ArrayBuffer[(dom.Node, String)]()
      var currentNode = walker.nextNode()

      while (currentNode != null) {
        val textContent = currentNode.textContent
        if (textContent != null && textContent.nonEmpty) {
          // Check if this text node contains a DOI or SoilWise ID pattern
          val hasDoi = doiPattern.findAllMatchIn(textContent).nonEmpty
          val hasSoilwiseId = soilwiseIdPattern.findAllMatchIn(textContent).nonEmpty
          if (hasDoi || hasSoilwiseId) {
            nodesToReplace += ((currentNode, textContent))
          }
        }
        currentNode = walker.nextNode()
      }

      // Replace text nodes with links
      nodesToReplace.foreach { case (node, text) =>
        // First replace SoilWise IDs with catalog links
        var newHtml = soilwiseIdPattern.replaceAllIn(text, m => {
          val doi = m.group(1)
          if (doi.nonEmpty) {
            val encodedDoi = js.Dynamic.global.encodeURIComponent(doi).asInstanceOf[String]
            val url = s"$catalogItemLinkBaseUrl$encodedDoi"
            s"""SoilWise ID: <a href="$url" target="_blank" rel="noopener noreferrer">$doi</a>"""
          } else {
            m.matched
          }
        })

        // Then replace DOI patterns with doi.org links
        newHtml = doiPattern.replaceAllIn(newHtml, m => {
          // Extract the DOI identifier from whichever group matched
          val doi = Option(m.group(1)).orElse(Option(m.group(2))).orElse(Option(m.group(3))).getOrElse("")
          if (doi.nonEmpty) {
            val url = s"https://doi.org/$doi"
            s"""<a href="$url" target="_blank" rel="noopener noreferrer">$doi</a>"""
          } else {
            m.matched
          }
        })

        if (newHtml != text) {
          val span = dom.document.createElement("span")
          span.innerHTML = newHtml
          node.parentNode.replaceChild(span, node)
        }
      }
    } catch {
      case e: Throwable =>
        dom.console.warn("[DEBUG_LOG] Failed to convert DOI patterns to links", e)
    }
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
        // Make links open in a new tab safely
        fixExternalLinks(contentEl)
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
  // Cache a pending question from URL parameter to be filled after successful login
  private var pendingQuestion: Option[String] = None

  // Extract optional question parameter from URL (e.g., ?question=What+is+soil)
  // Truncates to 400 characters maximum to match the textarea limit
  private def getQuestionFromUrl(): Option[String] = {
    try {
      val params = new dom.URLSearchParams(dom.window.location.search)
      val q = params.get("question")
      if (q != null && q.trim.nonEmpty) {
        val trimmed = q.trim
        val truncated = if (trimmed.length > 400) trimmed.substring(0, 400) else trimmed
        Some(truncated)
      } else None
    } catch {
      case _: Throwable => None
    }
  }

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

  // Toggle between standalone login page and main app content
  private def showLoginPage(): Unit = {
    val loginPage = dom.document.getElementById("login-page").asInstanceOf[dom.html.Element]
    val appShell = dom.document.getElementById("app-shell").asInstanceOf[dom.html.Element]
    if (loginPage != null) {
      loginPage.removeAttribute("hidden")
      // Also assert display for browsers that cache inline styles differently
      try loginPage.asInstanceOf[dom.html.Element].style.display = "" catch { case _: Throwable => () }
    }
    if (appShell != null) {
      appShell.setAttribute("hidden", "")
      try appShell.asInstanceOf[dom.html.Element].style.display = "none" catch { case _: Throwable => () }
    }
  }

  private def showAppContent(): Unit = {
    val loginPage = dom.document.getElementById("login-page").asInstanceOf[dom.html.Element]
    val appShell = dom.document.getElementById("app-shell").asInstanceOf[dom.html.Element]
    if (loginPage != null) {
      loginPage.setAttribute("hidden", "")
      try loginPage.asInstanceOf[dom.html.Element].style.display = "none" catch { case _: Throwable => () }
    }
    if (appShell != null) {
      appShell.removeAttribute("hidden")
      // Ensure it becomes visible immediately
      try appShell.asInstanceOf[dom.html.Element].style.display = "block" catch { case _: Throwable => () }
    }
    // As a defensive measure, re-assert visibility shortly after in case late code toggles attributes
    try dom.window.setTimeout(() => {
      val lp = dom.document.getElementById("login-page").asInstanceOf[dom.html.Element]
      val as = dom.document.getElementById("app-shell").asInstanceOf[dom.html.Element]
      if (lp != null) lp.setAttribute("hidden", "")
      if (as != null) as.removeAttribute("hidden")
    }, 50)
    catch { case _: Throwable => () }
  }

  // Activate the First Use tab by clicking it (triggers the JS tab system)
  private def activateFirstUseTab(): Unit = {
    try {
      val firstUseTab = dom.document.getElementById("tab-firstuse").asInstanceOf[dom.html.Element]
      if (firstUseTab != null) {
        firstUseTab.click()
      }
    } catch { case _: Throwable => () }
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
    // Try in-memory sessionId first, then fall back to persisted one
    val sid = sessionId.filter(_.nonEmpty)
      .orElse(Option(dom.window.localStorage.getItem("soilcompanion.sessionId")).filter(s => s != null && s.nonEmpty))
      .getOrElse("")
    if (sid.isEmpty) {
      // No session yet: request one and clear as soon as it arrives
      dom.console.log("[DEBUG_LOG] Clear location requested but no session yet. Will clear after session is ready.")
      pendingClearLocation = true
      getSession()
      return
    }
    // Always clear server-side location context if we have a session.
    // Also clear any locally pending selection (in case user isn't authenticated yet).
    if (!isAuthenticated) {
      dom.console.log("[DEBUG_LOG] Clearing pending location context (not authenticated) and requesting server clear as privacy-first action")
      pendingLocation = None
    } else {
      dom.console.log("[DEBUG_LOG] Clearing location context on server (authenticated)")
    }
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
      scala.util.boundary {
        val q = if (searchInput != null) searchInput.value.trim else ""
        if (q.isEmpty) scala.util.boundary.break(())
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
    // Surface message as transient activity while preserving base connectivity/auth state
    updateFooterStatus(Some(text))
  }

  // Compute and render footer status from current state; optional activity appended
  private def updateFooterStatus(activity: Option[String] = None): Unit = {
    val el = dom.document.getElementById("status-text").asInstanceOf[dom.html.Element]
    if (el == null) return
    val base =
      if (!isSessionReady) "Preparing session…"
      else if (!wsConnected) {
        if (wsReconnectTimer.nonEmpty) "Disconnected — reconnecting…" else "Offline — backend unavailable"
      } else {
        if (isAuthenticated) {
          if (chatEnabled) "Logged in — chat enabled." else "Logged in — chat disabled."
        } else "Not logged in."
      }
    val text = activity match
      case Some(act) if act.trim.nonEmpty => s"${base} • ${act}"
      case _ => base
    el.textContent = text
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
    chatEnabled = enabled
    updateFooterStatus(None)
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
      // swap to main app content
      showAppContent()
      if (u != null) u.disabled = true
      if (p != null) p.disabled = true
      if (loginBtn != null) { loginBtn.style.display = "none"; loginBtn.disabled = true }
      if (logoutBtn != null) { logoutBtn.style.display = "inline-block"; logoutBtn.disabled = false }
      setLoginInfo("Logged in.", Some("ok"))
    } else {
      // show login page exclusively
      showLoginPage()
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
        // Immediately switch to the app content to avoid any race with late init timers
        showAppContent()
        updateLoginUi()
        // Re-enable the Location tab when logged in
        setLocationTabEnabled(true)
        // Switch to the First Use tab after login
        activateFirstUseTab()
        // Ensure any previous informational bubbles (e.g., "logged out") are removed
        clearChat()
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
        // If there was a question from URL parameter, populate the question input field
        pendingQuestion.foreach { q =>
          dom.console.log(s"[DEBUG_LOG] Populating question from URL: $q")
          val questionInput = dom.document.getElementById("question").asInstanceOf[dom.html.TextArea]
          if (questionInput != null) {
            questionInput.value = q
            questionInput.focus()
            // Trigger input event to update character counter
            val event = new dom.Event("input", js.Dynamic.literal(bubbles = true).asInstanceOf[dom.EventInit])
            questionInput.dispatchEvent(event)
          }
          pendingQuestion = None
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
      clearChat()
      showLoginPage()
      addMessage("AI", "You have been logged out. Please login again to continue chatting.")
    }, { _ =>
      isAuthenticated = false
      setChatEnabled(false)
      updateLoginUi()
      setLocationTabEnabled(false)
      clearChat()
      showLoginPage()
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

    // Delegated link handling inside chat messages: ensure left-clicks on links
    // open in a new tab, even if attributes are missing or DOM was re-rendered.
    try {
      val messagesContainer = dom.document.getElementById("messages").asInstanceOf[dom.html.Element]
      if (messagesContainer != null) {
        messagesContainer.addEventListener("click", (e: dom.MouseEvent) => {
          // Only intercept primary-button clicks without modifier keys
          val isPrimary = e.button == 0
          val hasMods = e.metaKey || e.ctrlKey || e.shiftKey || e.altKey
          if (isPrimary && !hasMods) {
            // Find the closest anchor for the event target (walk up using parentNode)
            var nodeEl: dom.Element | Null = e.target match
              case t: dom.Element => t
              case _              => null
            while (nodeEl != null && nodeEl.tagName != null && nodeEl.tagName.toLowerCase() != "a") do
              val parent = nodeEl.parentNode
              nodeEl = if parent != null && parent.isInstanceOf[dom.Element] then parent.asInstanceOf[dom.Element] else null
            if (nodeEl != null) {
              val a = nodeEl.asInstanceOf[dom.html.Anchor]
              val href = a.getAttribute("href")
              if (href != null && href.trim.nonEmpty) {
                // Prevent in-page navigation and open safely in a new tab/window
                e.preventDefault()
                // Ensure attributes are set for accessibility and safety
                a.setAttribute("target", "_blank")
                val existingRel = Option(a.getAttribute("rel")).getOrElse("")
                val tokens = existingRel.split("\\s+").filter(_.nonEmpty).toBuffer
                if (!tokens.contains("noopener")) tokens += "noopener"
                if (!tokens.contains("noreferrer")) tokens += "noreferrer"
                a.setAttribute("rel", tokens.mkString(" ").trim)

                val win = dom.window.open(href, "_blank")
                try { if (win != null) win.opener = null } catch { case _: Throwable => () }
              }
            }
          }
        })
      }
    } catch { case _: Throwable => () }

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
        // If not authenticated, prevent using Location and show login page
        if (!isAuthenticated) {
          setStatus("Login required for Location.")
          setLoginInfo("Please login to select a location.")
          // Show login page
          showLoginPage()
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
      // Ensure links in AI messages open in a new tab safely
      fixExternalLinks(newDiv)
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
          // Fix links on each streaming update
          fixExternalLinks(contentEl)
        } else {
          // fallback: set entire content (older structure)
          messageElement.innerHTML = s"<div class=\"message-header\"><i class=\"fa-solid fa-robot\"></i></div><div class=\"message-time\">${nowTimeString()}</div><div class=\"message-content\">$sanitized</div>"
          fixExternalLinks(messageElement.asInstanceOf[dom.Element])
          refreshFaIcons()
        }
        hljs.highlightAll()
        // scroll to bottom of message container
        messageContainer.scrollTop = messageContainer.scrollHeight
      }
  }

  /**
   * Replace every [[MAP:<id>]] placeholder in the last bot message with an actual Leaflet
   * map container div. The map config was previously received as an out-of-band "map_data"
   * WebSocket event and stored in `pendingMapConfigs`. After inserting the containers,
   * triggers `window.initLeafletMaps()` so leaflet-init.js initialises them.
   *
   * Called when streaming is complete ("done" event) so that the full, parsed Markdown
   * is already in the DOM and we can safely do a targeted replacement.
   */
  private def replacePendingMaps(): Unit = {
    if (pendingMapConfigs.isEmpty) return

    val messages = dom.document.getElementById("messages")
    val last     = messages.lastElementChild
    Option(last).filter(_.classList.contains("bot-message")).foreach { el =>
      val contentEl = el.querySelector(".message-content").asInstanceOf[dom.html.Element]
      if (contentEl != null) {
        // Replace in innerHTML using string operations (simpler than DOM tree walking
        // because marked.js may have wrapped the placeholder in <p> tags)
        var html = contentEl.innerHTML
        pendingMapConfigs.foreach { case (mapId, payload) =>
          // marked.js wraps the placeholder in <p>; the brackets are not HTML-special so they
          // appear literally. We also handle the &#91;/&#93; encoded form just in case.
          val placeholder        = s"[[MAP:$mapId]]"
          val encodedPlaceholder = s"&#91;&#91;MAP:$mapId&#93;&#93;"

          if (html.contains(placeholder) || html.contains(encodedPlaceholder)) {
            val config     = payload("config")
            val title      = payload.obj.get("title").flatMap(v => if (v.str.isEmpty) None else Some(v.str))
            // Escape for use inside an HTML attribute value (double-quoted)
            val configJson = config.render(indent = 0)
              .replace("&", "&amp;")
              .replace("\"", "&quot;")
              .replace("'", "&#39;")
              .replace("<", "&lt;")
              .replace(">", "&gt;")

            val titleHtml = title.map(t => s"""<div class="map-title">${t.replace("<", "&lt;").replace(">", "&gt;")}</div>""").getOrElse("")
            val mapHtml   = s"""$titleHtml<div id="$mapId" class="leaflet-map-container" data-map-config="$configJson"><div style="text-align:center;padding:20px;color:#666;">Loading map...</div></div>"""

            // Replace the plain <p>[[MAP:id]]</p> wrapper with just the map div (block element)
            html = html
              .replace(s"<p>$placeholder</p>", mapHtml)
              .replace(placeholder, mapHtml)
              .replace(s"<p>$encodedPlaceholder</p>", mapHtml)
              .replace(encodedPlaceholder, mapHtml)
            dom.console.log(s"[MapTools] Replaced placeholder for $mapId")
          }
        }
        contentEl.innerHTML = html
        pendingMapConfigs.clear()

        // Trigger Leaflet initialisation for the newly inserted containers
        try {
          val initFn = js.Dynamic.global.selectDynamic("initLeafletMaps")
          if (!js.isUndefined(initFn) && js.typeOf(initFn) == "function") {
            initFn.asInstanceOf[js.Function0[Unit]]()
          }
        } catch { case _: Throwable => () }

        messages.scrollTop = messages.scrollHeight
      }
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
          updateFooterStatus(None)
          connectWebSocket(session)
          // If user clicked clear before session was ready, honor it now
          if (pendingClearLocation) {
            dom.console.log("[DEBUG_LOG] Performing deferred location clear now that session is ready")
            pendingClearLocation = false
            clearLocationContext()
          }
        case Failure(e) =>
          dom.console.log(s"Error fetching server session: $e")
          setLoginInfo("Failed to prepare session. Please reload the page.", Some("error"))
          updateFooterStatus(Some("Failed to prepare session"))
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

    def updateStatusFooter(text: String): Unit = updateFooterStatus(Some(text))

    def cancelReconnectTimer(): Unit = {
      wsReconnectTimer.foreach(dom.window.clearTimeout)
      wsReconnectTimer = None
      // after cancelling, refresh footer to reflect current state
      updateFooterStatus(None)
    }

    def scheduleReconnect(): Unit = {
      // If a reconnect is already scheduled, do not schedule another
      if (wsReconnectTimer.nonEmpty) return
      val base = wsReconnectBackoffMs
      val jitter = (math.random() * 0.3 * base).toInt // up to +30% jitter
      val delay = math.min(base + jitter, 30000)
      wsReconnectTimer = Some(dom.window.setTimeout(() => {
        wsReconnectTimer = None
        connectWebSocket(sessionId)
      }, delay))
      wsReconnectBackoffMs = math.min(base * 2, 30000)
      // reflect reconnecting state
      updateFooterStatus(None)
    }

    socket.onopen = { (_: dom.Event) =>
      wsConnected = true
      wsReconnectBackoffMs = 1000
      cancelReconnectTimer()
      updateFooterStatus(None)
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
              clearChat()
              showLoginPage()
              val msg = evt.detail.getOrElse("Login required to chat.")
              addMessage("AI", msg)
              updateFooterStatus(None)
            case "logged_out" =>
              isAuthenticated = false
              setAuthState(false)
              setChatEnabled(false)
              updateLoginUi()
              setLocationTabEnabled(false)
              clearChat()
              showLoginPage()
              val msg = evt.detail.getOrElse("You have been logged out.")
              addMessage("AI", msg)
              updateFooterStatus(None)
            case "session_expired" =>
              // Clear chat UI and require login again
              clearChat()
              isAuthenticated = false
              setAuthState(false)
              setChatEnabled(false)
              updateLoginUi()
              setLocationTabEnabled(false)
              showLoginPage()
              val msg = evt.detail.getOrElse("Your session expired due to inactivity. Please login to start a new chat.")
              addMessage("AI", msg)
              updateFooterStatus(None)
            case "map_data" =>
              // Store the map config out-of-band; placeholder replacement happens on "done"
              evt.detail.foreach { mapDataJson =>
                try {
                  val payload = ujson.read(mapDataJson)
                  val mapId   = payload("mapId").str
                  pendingMapConfigs(mapId) = payload
                  dom.console.log(s"[MapTools] Received map_data for $mapId")
                } catch {
                  case e: Throwable =>
                    dom.console.error(s"[MapTools] Failed to parse map_data: ${e.getMessage}")
                }
              }
            case "links_metadata" =>
              // Parse the metadata and update the last bot message
              val messageContainer = dom.document.getElementById("messages")
              val last = messageContainer.lastElementChild
              Option(last).filter(_.classList.contains("bot-message")).foreach { el =>
                evt.detail.foreach { metadataJson =>
                  try {
                    // Parse the LinksMetadata JSON
                    val metadata = upickle.default.read[nl.wur.soilcompanion.domain.LinksMetadata](metadataJson)

                    // Store link URLs as data attributes (never render them in the message)
                    el.setAttribute("data-wikipedia-links", metadata.wikipediaLinks.mkString(","))
                    el.setAttribute("data-vocabulary-links", metadata.vocabularyLinks.mkString(","))

                    // Update display with clean text (no markdown links)
                    el.setAttribute("data-raw-content", metadata.displayText)
                    val parsed = marked.parse(metadata.displayText)
                    val sanitized = DOMPurify.sanitize(parsed)
                    val contentEl = el.querySelector(".message-content").asInstanceOf[dom.html.Element]
                    if (contentEl != null) {
                      contentEl.innerHTML = sanitized
                      fixExternalLinks(contentEl) // For non-Wikipedia/vocab links only
                    }

                    // Trigger insight panel update to read from data attributes
                    try {
                      val updateFn = js.Dynamic.global.updateInsightLinks
                      if (!js.isUndefined(updateFn) && js.typeOf(updateFn) == "function") {
                        updateFn.asInstanceOf[js.Function0[Unit]]()
                      }
                    } catch { case _: Throwable => () }

                    hljs.highlightAll()
                    messageContainer.scrollTop = messageContainer.scrollHeight
                  } catch {
                    case e: Throwable =>
                      dom.console.error(s"[DEBUG_LOG] Failed to parse links metadata: ${e.getMessage}")
                  }
                }
              }
            case "done" =>
              // Clear activity; base state remains
              updateFooterStatus(None)
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
              // Replace any [[MAP:id]] placeholders with actual Leaflet map containers
              replacePendingMaps()
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

    window.onbeforeunload = _ => {
      cancelReconnectTimer()
      socket.close()
    }

    // Also handle transport-level errors defensively
    socket.onerror = { (_: dom.Event) =>
      wsConnected = false
      // ensure reference cleared so callers can see it's closed
      ws = None
      updateStatusFooter("Connection error")
      showErrorInLastBotMessage(Some("Connection error while generating."))
      scheduleReconnect()
    }

    socket.onclose = { (_: dom.Event) =>
      wsConnected = false
       // clear socket reference and schedule a guarded reconnect
      ws = None
      updateFooterStatus(None)
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

  /**
   * Clears the chat UI and any pending chat state. Use this when logging out or when the
   * session is automatically terminated/invalidated.
   */
  private def clearChat(): Unit =
    try
      // Cancel any watchdog timer
      pendingResponseTimer.foreach(dom.window.clearTimeout)
      pendingResponseTimer = None
    catch
      case _: Throwable => ()

    // Reset current question context
    currentQuestionId = None

    // Hide spinner if visible
    try hideSpinner() catch case _: Throwable => ()

    // Empty messages container
    try
      val container = dom.document.getElementById("messages").asInstanceOf[dom.html.Element]
      if (container != null) container.innerHTML = ""
    catch
      case _: Throwable => ()

    // Reset input and counter
    try
      val questionElement = dom.document.getElementById("question").asInstanceOf[dom.html.TextArea]
      if (questionElement != null) {
        questionElement.value = ""
        questionElement.style.height = "auto"
      }
      val counterEl = dom.document.getElementById("char-counter").asInstanceOf[dom.html.Element]
      if (counterEl != null) {
        counterEl.textContent = "400 left"
        counterEl.classList.remove("warn")
      }
    catch
      case _: Throwable => ()

    // Clear footer activity/status
    updateFooterStatus(None)

  // Helper: parse a selected version label from /healthz JSON (prefer gitTag over version)
  private def extractVersionLabel(jsonText: String): String =
    try
      val dyn = js.JSON.parse(jsonText).asInstanceOf[js.Dynamic]
      def optStr(v: js.Dynamic): Option[String] =
        if (js.isUndefined(v) || v == null) None else Option(v.asInstanceOf[String]).filter(_.trim.nonEmpty)
      val tag = optStr(dyn.selectDynamic("gitTag"))
      val ver = optStr(dyn.selectDynamic("version"))
      val chosen = tag.orElse(ver).getOrElse("")
      // normalize with leading v for display consistency
      if (chosen.nonEmpty && !chosen.startsWith("v")) s"v$chosen" else chosen
    catch
      case _: Throwable => ""

  // Fetch backend health info and render the app version (prefer gitTag over version)
  private def renderVersionFromHealthz(): Unit = {
    val el = dom.document.getElementById("version-text").asInstanceOf[dom.html.Element]
    if (el == null) return
    val url = s"$httpBase/healthz"
    fetch(url).toFuture
      .flatMap(_.text().toFuture)
      .foreach { txt =>
        try {
          val dyn = js.JSON.parse(txt).asInstanceOf[js.Dynamic]
          def optStr(v: js.Dynamic): Option[String] =
            if (js.isUndefined(v) || v == null) None else Option(v.asInstanceOf[String]).filter(_.trim.nonEmpty)

          val display = extractVersionLabel(txt)
          val llmProvider = optStr(dyn.selectDynamic("llmProvider")).getOrElse("")
          val llmModel = optStr(dyn.selectDynamic("llmModel")).getOrElse("")

          // Extract and store catalog configuration
          optStr(dyn.selectDynamic("catalogItemLinkBaseUrl")).foreach { url =>
            catalogItemLinkBaseUrl = url
            dom.console.log(s"[DEBUG_LOG] Catalog item link base URL: $catalogItemLinkBaseUrl")
          }

          // Build the full display text: "provider: model - version"
          val fullText = if (llmProvider.nonEmpty && llmModel.nonEmpty && display.nonEmpty) {
            s"$llmProvider: $llmModel - $display"
          } else if (display.nonEmpty) {
            display
          } else {
            ""
          }

          if (fullText.nonEmpty) {
            el.textContent = fullText
            if (initialVersionTag.isEmpty) initialVersionTag = Some(display)
          }
        } catch {
          case e: Throwable =>
            dom.console.error("[DEBUG_LOG] Failed to parse healthz response", e)
        }
      }
  }

  // Create and show a non-intrusive banner prompting the user to reload
  private def showUpdateBanner(newVersion: String): Unit =
    // Avoid duplicating the banner
    if (dom.document.getElementById("update-banner") != null) return

    val banner = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    banner.id = "update-banner"
    banner.setAttribute("role", "status")
    banner.setAttribute("aria-live", "polite")
    // Styling inline to avoid touching CSS files
    banner.style.position = "fixed"
    banner.style.left = "0"
    banner.style.right = "0"
    banner.style.bottom = "56px" // sit above the status footer
    banner.style.zIndex = "1000"
    banner.style.backgroundColor = "#fff3cd" // warning-like
    banner.style.color = "#533f03"
    banner.style.borderTop = "1px solid #ffe69c"
    banner.style.borderBottom = "1px solid #ffe69c"
    banner.style.boxShadow = "0 -2px 6px rgba(0,0,0,0.08)"
    banner.style.padding = "10px 12px"
    banner.style.display = "flex"
    banner.style.setProperty("align-items", "center")
    banner.style.setProperty("justify-content", "space-between")

    val text = dom.document.createElement("div").asInstanceOf[dom.html.Div]
    text.textContent = (if (newVersion.nonEmpty) s"A new version is available ($newVersion)."
                        else "A new version is available.") + " Please reload to update."

    val btn = dom.document.createElement("button").asInstanceOf[dom.html.Button]
    btn.className = "toolbar-btn"
    btn.textContent = "Reload"
    btn.title = "Reload to update to the latest version"
    btn.setAttribute("aria-label", "Reload to update to the latest version")
    btn.onclick = { (_: dom.MouseEvent) =>
      try
        // Try a cache-busting reload
        val href = dom.window.location.href
        val sep = if (href.contains("?")) "&" else "?"
        dom.window.location.href = href + sep + s"_r=${js.Date.now().toLong}"
      catch
        case _: Throwable => dom.window.location.reload()
    }

    banner.appendChild(text)
    banner.appendChild(btn)

    // Attach near the footer; append to body
    dom.document.body.appendChild(banner)

    // Also reflect new version in the footer text element if present
    try
      val verEl = dom.document.getElementById("version-text").asInstanceOf[dom.html.Element]
      if (verEl != null && newVersion.nonEmpty) verEl.textContent = newVersion
    catch
      case _: Throwable => ()

  // Periodically poll /healthz and compare with initial version
  private def startVersionPolling(intervalMs: Int = 120000): Unit =
    // Do not start if already started
    if (versionPollTimer.nonEmpty) return
    val url = s"$httpBase/healthz"
    val handle = dom.window.setInterval({ () =>
      fetch(url).toFuture
        .flatMap(_.text().toFuture)
        .foreach { txt =>
          val current = extractVersionLabel(txt)
          initialVersionTag.foreach { init =>
            if (current.nonEmpty && current != init) then
              // Stop polling and show the update banner
              versionPollTimer.foreach(dom.window.clearInterval)
              versionPollTimer = None
              showUpdateBanner(current)
          }
        }
    }, intervalMs)
    versionPollTimer = Some(handle)

  // --- Main ---

  dom.console.log("Soil Companion App is running!")
  // Render version in footer (right-aligned)
  renderVersionFromHealthz()
  // Start background polling to detect newer versions and prompt for reload
  startVersionPolling(60000)
  // Check for initial question from URL parameter
  pendingQuestion = getQuestionFromUrl()
  if (pendingQuestion.isDefined) {
    dom.console.log(s"[DEBUG_LOG] Question from URL detected: ${pendingQuestion.get}")
  }
  // Initialize session and UI
  getSession()
  setupEventListeners()
  isAuthenticated = false
  // Ensure any stale auth indicators from previous visits are cleared
  setAuthState(false)
  setChatEnabled(false)
  updateLoginUi()
  // On initial load when not authenticated: disable Location tab and show login page
  setLocationTabEnabled(false)
  // Ensure login page is visible (in case initial HTML showed content)
  dom.window.setTimeout(() => if (!isAuthenticated) showLoginPage(), 0)
  addMessage("AI", "Welcome to Soil Companion. Please login to start chatting.")
  // Expose a global hook for plain JS (toolbar.js) to refresh canonical footer status
  try {
    js.Dynamic.global.updateFooterStatus = (msg: String) => updateFooterStatus(Option(msg))
    js.Dynamic.global.SoilCompanionRefreshStatus = () => updateFooterStatus(None)
  } catch
    case _: Throwable => ()
}
