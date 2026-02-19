# Configuration

The application is configured via an `application.conf` resource file (HOCON format).
Most settings can be overridden via environment variables.

- **LLM Provider**: The chatbot supports both **OpenAI** (hosted) and **Ollama** (local) models.
  - For OpenAI: An API key is required and must be provided as an environment variable (`OPENAI_API_KEY`).
  - For Ollama: A running Ollama instance is required (default: `http://localhost:11434`).
- Demo authentication: a single demo user account is configured under `demo-user` in `chatbot/jvm/src/main/resources/application.conf`.
  - Default credentials: username `demo@soilwise`, password `*******` (can be overridden via env vars `DEMO_USERNAME`, `DEMO_PASSWORD`, `DEMO_DISPLAY_NAME`).

## Environment variables and config overrides

Most settings in `application.conf` can be overridden via environment variables. Commonly used variables:

- Core app
  - `SOIL_COMPANION_VERSION` — app version shown in UI
  - `SOIL_COMPANION_HOST` — HTTP host to bind
  - `SOIL_COMPANION_PORT` — HTTP port to bind
  - `DEBUG_LOG_FINAL_AI_RESPONSE` — set to `true` to enable a single debug log entry with the final AI response per question
  - `UPLOAD_MAX_CHARS` — safety limit for uploaded text size
  - `SESSION_EXPIRATION_MINUTES` — chat session expiration (-1 to disable)
- Authentication (demo)
  - `DEMO_USERNAME`, `DEMO_PASSWORD`, `DEMO_DISPLAY_NAME`
- Data directories
  - `KNOWLEDGE_DIR` — directory with local knowledge documents
  - `FEEDBACK_LOG_DIR` — directory for feedback logs
  - `FEEDBACK_LOG_PREFIX` — filename prefix for feedback logs
- LLM provider
  - `LLM_PROVIDER` — Select LLM provider: "openai" (default) or "ollama"
  - `OPENAI_API_KEY` — API key for OpenAI (required when `LLM_PROVIDER=openai`)
  - `OLLAMA_BASE_URL` — Base URL for Ollama service (default: `http://localhost:11434`, used when `LLM_PROVIDER=ollama`)
  - `OLLAMA_TIMEOUT` — Timeout for Ollama requests in milliseconds (default: 60000)
  - `CHAT_MAX_SEQUENTIAL_TOOLS` — Maximum number of sequential tool invocations allowed (default: 10)
  - `CHAT_MAX_PROMPT_CHARS` — Safety limit for max prompt size to avoid exceeding model context windows (default: 800000)
- SoilWise catalog and Solr
  - `CATALOG_TIMEOUT_MS` — HTTP timeout for catalog/Solr requests
  - `SOLR_BASE_URL` — Solr `/select` endpoint URL (records core)
  - `SOLR_USERNAME`, `SOLR_PASSWORD` — Solr basic auth credentials
- AgroDataCube (NL)
  - `AGRODATACUBE_BASE_URL` — base URL (defaults to production)
  - `AGRODATACUBE_ACCESS_TOKEN` — API token (sent in header `Token`)
  - `AGRODATACUBE_TIMEOUT_MS` — HTTP timeout
- OpenAgroKPI (NL)
  - `OPENAGRO_BASE_URL` — base URL
  - `OPENAGRO_ACCESS_TOKEN` — API key (sent in header `x-api-key`, no Bearer)
  - `OPENAGRO_TIMEOUT_MS` — HTTP timeout
- SoilGrids (ISRIC)
  - `SOILGRIDS_BASE_URL` — base API URL
  - `SOILGRIDS_QUERY_ENDPOINT` — properties query endpoint path

## LLM Provider Configuration (OpenAI vs Ollama)

The chatbot can use either OpenAI's hosted models or local models via Ollama. The provider is configured in `application.conf` under `llm-provider-config`.

### Using OpenAI (default)

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```hocon
llm-provider-config: {
  provider: "openai"
  api-key: ${?OPENAI_API_KEY}
  chat-model: "gpt-4.1-mini"
  chat-model-temp: 0.1
  reason-model: "gpt-4.1-mini"
  reason-model-temp: 0.0
  # ... other settings
}
```

Environment variables:
- `LLM_PROVIDER=openai` (or omit, as "openai" is the default)
- `OPENAI_API_KEY=your-api-key-here`

### Using Ollama (local models)

To use local models via Ollama:

1. **Install and run Ollama**:
   ```bash
   # Install Ollama from https://ollama.ai
   # Pull a recommended model for tool calling
   ollama pull qwen2.5:7b
   ```

2. **Configure the application** by setting environment variables:
   ```bash
   export LLM_PROVIDER=ollama
   export OLLAMA_BASE_URL=http://localhost:11434  # optional, this is the default
   ```

3. **Update model names** in `application.conf` (or override via environment):
   ```hocon
   llm-provider-config: {
     provider: "ollama"
     chat-model: "qwen2.5:7b"
     reason-model: "qwen2.5:7b"
     ollama-base-url: "http://localhost:11434"
     ollama-timeout: 60000  # milliseconds
     # ... other settings
   }
   ```

### Recommended Ollama models for tool calling

The chatbot uses LangChain4j's tool/function calling capabilities. For best results with Ollama, use models that support tool calling:

- **qwen2.5:7b** (recommended) — Good balance of size (~4.7GB) and tool calling capability
- **mistral** — Strong performance with function calling
- **llama3.1:8b** — Good general performance

Note: Smaller models (< 7B parameters) may have limited tool calling capabilities.

### Configuration comparison

| Setting | OpenAI | Ollama |
|---------|--------|--------|
| `provider` | `"openai"` | `"ollama"` |
| `api-key` | Required (from env) | Not used |
| `chat-model` | e.g., `"gpt-4.1-mini"` | e.g., `"qwen2.5:7b"` |
| `reason-model` | e.g., `"gpt-4.1-mini"` | e.g., `"qwen2.5:7b"` |
| `ollama-base-url` | Not used | `"http://localhost:11434"` (default) |
| `ollama-timeout` | Not used | `60000` (ms, default) |

## Debug logging of user question and AI responses

You can enable optional debug logging of the chatbot's final output. When enabled, the server writes `DEBUG` log messages after the model finishes responding:

- First, the user question is logged.
- Immediately after, the final AI response is logged.

Both are emitted consecutively using the same configurable prefix so they are easy to grep and visually group in logs. Intermediate streaming tokens are not logged.

How to enable:

- In `chatbot/jvm/src/main/resources/application.conf` under `app-config` set:

```
debug-log-final-ai-response: true
```

- Or via environment variable:

```
DEBUG_LOG_FINAL_AI_RESPONSE=true
```

Default is disabled (`false`).

Custom prefix for question/answer lines:

When logging is enabled, the server logs both the user question and the complete model output line-by-line with a configurable prefix so these lines stand out in your application logs and are easy to grep/select.

- Configure in `app-config`:

```
ai-final-log-prefix: "[AI_FINAL] "
```

- Or via environment variable:

```
AI_FINAL_LOG_PREFIX="[AI_FINAL] "
```

Notes:
- The prefix is applied to every line of both the user question and the final AI response at `DEBUG` level.
- Only the prefixed lines are emitted; there is no additional unprefixed summary line.

Example output in logs (prefix shown as `[AI_FINAL] `):

```
[AI_FINAL] Q:
[AI_FINAL] What is soil organic carbon?
[AI_FINAL] A:
[AI_FINAL] Soil organic carbon (SOC) is the carbon component of organic compounds in the soil...
```

## SoilGrids tools configuration

The chatbot includes LLM tools to query ISRIC SoilGrids v2.0 for estimated soil properties at a given location.

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
soilgrids-config: {
  base-url: "https://rest.isric.org/soilgrids/v2.0"
  query-endpoint: "/properties/query"
  default-properties: ["bdod", "soc", "clay", "sand", "silt", "phh2o"]
  default-depths: ["0-5cm", "5-15cm", "15-30cm", "30-60cm", "60-100cm"]
  default-value-stat: "mean"
  timeout-ms: 15000
  user-agent: "SoilCompanionBot/0.1 (+https://soilwise-he.eu)"
  usage-warning: "SoilGrids provides modelled estimates at ~250 m grid resolution; values are indicative and not field measurements. Verify with local data before decisions."
  docs-url: "https://soilgrids.org/"
  terms-url: "https://www.isric.org/utilise/soilgrids/soilgrids-terms-use"
}
```

Notes and cautions:
- SoilGrids provides modelled estimates at approx. 250 m grid resolution, not field measurements.
- Values are indicative; local conditions may vary substantially. Verify with local data and experts before decisions.
- The tool methods are:
  - `getSoilGridsAtLocation(lat, lon, propertiesCsv, depthsCsv, valueStat)`
  - `getSoilGridsFromLocationContext(locationContextJson)`
    - The UI stores a per-session location context JSON with `lat` and `lon` that can be passed to this tool.

Output includes clickable links to SoilGrids docs and Terms of Use.

## Agricultural field data (OpenAgroKPI, NL) tools configuration

The chatbot includes LLM tools to retrieve agricultural field-specific data from country services. As a first
implementation, The Netherlands is supported via WUR's OpenAgroKPI service.

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
openagro-config: {
  base-url: "https://openagrokpi.wur.nl/api/v1"        // can be overridden by env OPENAGRO_BASE_URL
  access-token: "REPLACE_ME"                             // set via env OPENAGRO_ACCESS_TOKEN
  default-country: "NL"
  timeout-ms: 15000
  user-agent: "SoilCompanionBot/0.1 (+https://soilwise-he.eu)"
  docs-url: "https://openagrokpi.wur.nl/api/v1/docs/"
  allow-unauthenticated: false                           // set to true only if endpoint is public
  // Optional curated list of supported '/fields-{layer}' layers to help the LLM when offline
  // known-layers: ["greenness", "soil", "soil-physical", "soilmap-benchmark", "reference-values", "crop-rotation"]
}
```

Notes and cautions:
- Only NL is supported at present; more countries can be added later by extending the tool.
- Authentication: OpenAgroKPI expects the API key in HTTP header `x-api-key` (no `Bearer` prefix). Provide it via env var `OPENAGRO_ACCESS_TOKEN`.
- Endpoints and schemas may evolve; the tool includes defensive parsing and logs helpful debug info.

Offline usage and guidance:
- The chatbot does not require live access to the external API docs. Use the overview tool below to see how to call the API and which layers are configured.

Available tool methods (OpenAgroKPI):
- `describeOpenAgroKpiFieldsApi()` — describes how to call `/api/v1/fields-*` endpoints, authentication, parameters, and lists configured layers if provided.
- `getOpenAgroKpiForField(fieldId, layer, year, countryCode)` — fetch KPIs for a single NL field. `layer` maps to `fields-{layer}`; parameters: `field_id`, optional `year`.
- `getOpenAgroKpiForFields(fieldIdsCsv, layer, year, countryCode)` — fetch KPIs for multiple NL fields. Uses `field_ids` CSV and optional `year`.

Usage flow:
1. Use AgroDataCube tools to resolve NL field id(s) for a location: `getAgroDataCubeFieldByLocation(...)` or `getAgroDataCubeFieldFromLocationContext(...)`.
2. Call the appropriate OpenAgroKPI tool with `countryCode = "NL"` and the desired `layer` (see `describeOpenAgroKpiFieldsApi` or `openagro-config.known-layers`).

## AgroDataCube (NL) tools configuration

The chatbot includes LLM tools to query WUR AgroDataCube v2 REST for crop parcel (field) information in The Netherlands only. It can look up the field for a given WGS84 location and retrieve soil/crop related information and KPI datapackages for that field. If data for the current year is not yet available, the tools default to the previous calendar year.

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
agrodatacube-config: {
  # Base REST URL
  #   Docs: https://agrodatacube.wur.nl/
  #   Base: https://agrodatacube.wur.nl/api/v2/rest
  base-url: "https://agrodatacube.wur.nl/api/v2/rest"
  base-url: ${?AGRODATACUBE_BASE_URL}

  # Access token for the service. Sent in HTTP header 'Token'.
  access-token: "REPLACE_ME"
  access-token: ${?AGRODATACUBE_ACCESS_TOKEN}

  # Timeouts in milliseconds for HTTP requests
  timeout-ms: 15000

  # Identify our client politely
  user-agent: "SoilCompanionBot/0.1 (+https://soilwise-he.eu)"

  # Helpful URLs
  docs-url: "https://agrodatacube.wur.nl/"
}
```

Notes and cautions:
- Scope: NL only. Do not use these tools for other countries.
- Authentication: AgroDataCube expects the token in HTTP header `Token` (no `Bearer` prefix). Provide it via env var `AGRODATACUBE_ACCESS_TOKEN`.
- Field lookup uses a WKT `POINT(lon lat)` in EPSG:4326. AgroDataCube commonly returns GeoJSON, even when `result=nogeom` is used; parsing is handled internally.
- Year handling: if no year is provided, the tools fall back to the previous calendar year automatically.
- Session memory: after a successful field lookup the assistant stores `{ fieldId, year, crop_code, crop_name }` for reuse in subsequent calls during the same chat session.

Available tool methods:
- `getAgroDataCubeFieldByLocation(lat, lon, year)` — find the field (parcel) for a WGS84 location in NL; saves field context for reuse.
- `getAgroDataCubeFieldFromLocationContext(locationContextJson, year)` — same lookup using the UI location context JSON (`lat`, `lon`).
- `getSavedAgroDataCubeFieldContext()` — returns the saved `{ fieldId, year, crop_code, crop_name }` for this session, if available.
- `getAgroDataCubeCropHistory(fieldId, limitYears)` — crop history for a field.
- `getAgroDataCubeCropRotationIndex(fieldId)` — crop rotation index for a field.
- `getAgroDataCubeFieldMarkers(fieldId, landUse)` — field markers for `bouwland` or `grasland`.
- `getAgroDataCubeCropCodeInfo(cropCode)` — crop code metadata lookup.
- `getAgroDataCubeSoilCodeInfo(soilCode)` — soil code metadata lookup.
- `getAgroDataCubeKpiSoilPhysical(fieldIdsCsv)` — KPI datapackage bodemfysisch (POST, supports `nogeom`).
- `getAgroDataCubeKpiGreenness(fieldIdsCsv)` — KPI datapackage greenness (POST).
- `getAgroDataCubeKpiSoilMapBenchmark(fieldIdsCsv)` — KPI datapackage grondsoortenkaart (POST).
- `getAgroDataCubeKpiReferenceValues(fieldIdsCsv)` — KPI datapackage referentiewaarde (POST, supports `nogeom`).
- `getAgroDataCubeKpiCropRotation(fieldIdsCsv)` — KPI datapackage croprotation (GET with `fieldids`).

Ergonomics:
- If `fieldId`/`fieldIdsCsv` is omitted for the retrieval methods, the tools will use the last field saved in session memory by a prior field lookup.
- Responses include brief previews and links to the AgroDataCube docs.

## Wikipedia tools configuration

The chatbot includes LLM tools to search Wikipedia and retrieve article content to supplement answers with general knowledge and definitions. **Supports multiple languages: English, Dutch, French, Spanish, Italian, and Czech.**

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
wikipedia-config: {
  base-url: "https://en.wikipedia.org"
  base-url: ${?WIKIPEDIA_BASE_URL}
  default-max-results: 3
  max-content-chars: 4000
  max-content-chars: ${?WIKIPEDIA_MAX_CONTENT_CHARS}
  timeout-ms: 15000
  timeout-ms: ${?WIKIPEDIA_TIMEOUT_MS}
  user-agent: "SoilCompanionBot/0.1 (+https://soilwise-he.eu)"
  license-url: "https://en.wikipedia.org/wiki/Wikipedia:Text_of_Creative_Commons_Attribution-ShareAlike_3.0_Unported_License"
  # Automatically convert technical terms in responses to Wikipedia links
  auto-link-terms: true
  auto-link-terms: ${?WIKIPEDIA_AUTO_LINK_TERMS}
  # Minimum length (characters) for terms to be considered for auto-linking
  min-term-length: 4
  min-term-length: ${?WIKIPEDIA_MIN_TERM_LENGTH}
}
```

Notes and cautions:
- The tool uses Wikipedia's public API (no authentication required).
- Content is licensed under Creative Commons Attribution-ShareAlike 3.0; proper attribution is included in responses.
- Long articles are truncated at `max-content-chars` (default 4000) to avoid overwhelming the context window.
- The `base-url` can be changed to use different language editions of Wikipedia (e.g., `https://de.wikipedia.org` for German).
- Search results include snippets and direct links to Wikipedia articles.

Multilingual support:
- **Automatic language detection**: The system automatically detects the language of the chatbot's response (English, Dutch, French, Spanish, Italian, Czech).
- **Language-specific Wikipedia editions**: Links are generated to the appropriate Wikipedia edition based on the detected language (e.g., Dutch response -> `nl.wikipedia.org`).
- **Curated technical term lists**: Each supported language has a curated list of 50+ soil and agriculture-related technical terms.
- **Language-specific exclusion lists**: Common words to exclude are tailored for each language.

Auto-linking feature:
- When `auto-link-terms` is enabled (default: true), the chatbot automatically converts recognized technical terms in responses to Wikipedia links.
- The system identifies soil and agriculture-related terms from language-specific curated lists, as well as multi-word capitalized technical phrases.
- Common non-technical words are excluded from linking (e.g., English: "Common", "Typical", "In France"; Dutch: "Algemeen", "Typisch", "In Frankrijk").
- Before adding a link, the system verifies that the Wikipedia article actually exists in the appropriate language edition.
- Terms shorter than `min-term-length` (default: 4 characters) are ignored.
- A maximum of 5 terms per response are linked to avoid clutter, prioritizing known technical terms.
- Only the first occurrence of each term is linked.
- To disable auto-linking, set `auto-link-terms: false` or use the environment variable `WIKIPEDIA_AUTO_LINK_TERMS=false`.

Available tool methods:
- `searchWikipedia(searchTerm, maxResults)` — search Wikipedia and return article titles with snippets and links.
- `getWikipediaArticle(articleTitle)` — retrieve the full content of a specific Wikipedia article by exact title.
- `searchAndGetWikipediaContent(searchTerm)` — search and retrieve the most relevant article in one step.

Usage:
- The LLM will automatically use these tools when general concepts, definitions, or background information would be helpful.
- Results include the Wikipedia URL and license information for transparency and attribution.

## Vocabulary auto-linking configuration

The chatbot automatically recognizes and links soil-related technical terms from the SoilWise vocabulary to their definitions in the vocabulary browser.

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
vocab-config: {
  base-url: "https://voc.soilwise-he.containers.wur.nl"
  base-url: ${?VOCAB_BASE_URL}
  vocab-file-path: "data/vocab/soilvoc_concepts_20260108.csv"
  vocab-file-path: ${?VOCAB_FILE_PATH}
  auto-link-terms: true
  auto-link-terms: ${?VOCAB_AUTO_LINK_TERMS}
  min-term-length: 4
  min-term-length: ${?VOCAB_MIN_TERM_LENGTH}
  max-links-per-response: 8
  max-links-per-response: ${?VOCAB_MAX_LINKS_PER_RESPONSE}
}
```

How it works:
- **Automatic term matching**: The system loads vocabulary terms from a CSV file containing prefLabels and altLabels.
- **Case-insensitive matching**: Terms are matched regardless of case (e.g., "acidifiers", "Acidifiers", "ACIDIFIERS").
- **URL-encoded links**: Links are generated to the vocabulary browser with properly encoded term IDs.
- **Visual distinction**: Vocabulary links are displayed with a green "V" badge and green color in the UI.

Configuration options:
- `base-url`: Base URL for the vocabulary browser (default: https://voc.soilwise-he.containers.wur.nl)
- `vocab-file-path`: Path to the CSV file containing vocabulary terms (default: data/vocab/soilvoc_concepts_20260108.csv)
- `auto-link-terms`: Enable/disable automatic vocabulary linking (default: true)
- `min-term-length`: Minimum character length for terms to be linked (default: 4)
- `max-links-per-response`: Maximum number of vocabulary links per response (default: 8)

Notes:
- Vocabulary terms are loaded once at application startup for performance.
- Longer terms are prioritized to avoid partial matches.
- Only the first occurrence of each term is linked to avoid clutter.
- The CSV file should contain columns: ID, prefLabel, altLabel, definition, broader, exactMatch, closeMatch.
- To disable auto-linking, set `auto-link-terms: false` or use the environment variable `VOCAB_AUTO_LINK_TERMS=false`.

## Map Tools configuration

The chatbot includes LLM tools to create interactive maps for visualizing spatial data related to soil and agriculture.

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
map-config: {
  # Leaflet library URLs (CDN)
  leaflet-css-url: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css"
  leaflet-css-url: ${?MAP_LEAFLET_CSS_URL}

  leaflet-js-url: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
  leaflet-js-url: ${?MAP_LEAFLET_JS_URL}

  # Default map dimensions
  default-width: "100%"
  default-width: ${?MAP_DEFAULT_WIDTH}

  default-height: "500px"
  default-height: ${?MAP_DEFAULT_HEIGHT}

  # Default zoom level when not specified
  default-zoom: 10
  default-zoom: ${?MAP_DEFAULT_ZOOM}
}
```

Notes and features:
- Maps are created using Leaflet.js, a leading open-source JavaScript library for interactive maps.
- Maps render directly in the chatbot UI as embedded HTML with full interactivity (pan, zoom, click).
- Supports multiple basemap styles: OpenStreetMap (default), satellite imagery, and terrain.
- Can display markers (points), polygons (areas/regions), and custom styling.
- All maps are generated server-side and delivered as sanitized HTML/JavaScript.

Available tool methods:
- `createMap(centerLat, centerLon, zoom, markersJson, basemap, title)` — create a map centered at specific coordinates with optional markers.
- `createMapFromLocationContext(locationContextJson, markersJson, basemap, title)` — create a map using the UI location picker context.
- `createMultiLocationMap(locationsJson, basemap, title)` — create a map showing multiple locations with automatic zoom/centering.
- `createRegionMap(coordinatesJson, fillColor, strokeColor, label, basemap, title)` — create a map showing a polygonal region or area boundary.

Usage examples:
- "Show me Wageningen on a map" → creates a centered map of Wageningen
- "Map these soil sample locations: ..." → creates a multi-marker map
- "Visualize this field boundary" → creates a polygon map showing the field area
- "Show satellite view of this location" → creates a map with satellite basemap

Map features:
- **Interactive controls**: Pan, zoom, and click on markers/regions
- **Multiple basemaps**: Choose between street map, satellite imagery, or terrain
- **Custom markers**: Different colors and popup labels for each point
- **Polygon regions**: Visualize field boundaries, study areas, or administrative regions
- **Scale indicator**: Metric scale bar for distance reference
- **Responsive sizing**: Maps adapt to the UI layout (default 100% width, 500px height)

Configuration options:
- `leaflet-css-url`: URL for Leaflet CSS library (default: unpkg.com CDN)
- `leaflet-js-url`: URL for Leaflet JavaScript library (default: unpkg.com CDN)
- `default-width`: Default map width (default: "100%")
- `default-height`: Default map height (default: "500px")
- `default-zoom`: Default zoom level when not specified (default: 10)

The tool automatically loads Leaflet.js from a CDN on first use, ensuring maps work without additional client-side setup.

## Vocabulary Tools configuration

The chatbot includes LLM tools to query the SoilWise vocabulary SPARQL endpoint for detailed concept information, including broader, narrower, related, and exact match concepts from the SKOS vocabulary hierarchy.

Configuration block (in `chatbot/jvm/src/main/resources/application.conf`):

```
vocabulary-tools-config: {
  sparql-endpoint: "https://repository.soilwise-he.eu/sparql/"
  sparql-endpoint: ${?VOCAB_SPARQL_ENDPOINT}
  connect-timeout-ms: 5000
  connect-timeout-ms: ${?VOCAB_CONNECT_TIMEOUT_MS}
  read-timeout-ms: 10000
  read-timeout-ms: ${?VOCAB_READ_TIMEOUT_MS}
  max-results: 100
  max-results: ${?VOCAB_MAX_RESULTS}
  redirect-url-pattern: "voc.soilwise-he"
  redirect-url-pattern: ${?VOCAB_REDIRECT_URL_PATTERN}
  user-agent: "SoilCompanionBot/0.1 (+https://soilwise-he.eu)"
}
```

Configuration options:
- `sparql-endpoint`: SPARQL endpoint URL for querying vocabulary concepts (default: https://repository.soilwise-he.eu/sparql/)
- `connect-timeout-ms`: HTTP connection timeout in milliseconds (default: 5000)
- `read-timeout-ms`: HTTP read timeout in milliseconds (default: 10000)
- `max-results`: Maximum number of results to return from SPARQL queries (default: 100)
- `redirect-url-pattern`: URL pattern to identify vocabulary service redirect URLs (default: "voc.soilwise-he")
- `user-agent`: User-Agent header for HTTP requests

Available tool methods:
- `getVocabConceptInfo(conceptUri)` — retrieve detailed information about a specific vocabulary concept including:
  - Preferred label and definition
  - Broader concepts (parent terms in the hierarchy)
  - Narrower concepts (child terms in the hierarchy)
  - Related concepts (associated terms)
  - Exact matches (equivalent terms from other vocabularies)
- `batchGetVocabConcepts(conceptUris)` — retrieve information for multiple concepts in one call (comma-separated URIs)

How it works:
- **SPARQL queries**: The tool constructs SPARQL queries to retrieve concept information from the vocabulary SPARQL endpoint.
- **URI handling**: Automatically extracts actual concept URIs from vocabulary service redirect URLs (e.g., `https://voc.soilwise-he.containers.wur.nl/id/https%3A%2F%2F...`).
- **Definition retrieval**: Fetches definitions for broader, narrower, and related concepts when available.
- **Language filtering**: Returns English labels and definitions (with fallback to no language tag).
- **JSON output**: Returns structured JSON with all concept relationships for easy parsing and display.

Notes:
- The tool queries the SoilWise vocabulary SPARQL endpoint which contains SKOS-based vocabulary data.
- Broader and narrower concepts help users explore the vocabulary hierarchy and discover related terms.
- The UI uses this tool to populate the knowledge panel with related vocabulary concepts.
- Response includes tooltips with definitions when available for enhanced user experience.

## SoilWise Catalog tools configuration

The chatbot includes LLM tools to search the SoilWise catalog (via Solr) for metadata and document content, and to
create verified catalog links for items by identifier.

Configuration blocks (in `chatbot/jvm/src/main/resources/application.conf`):

```
catalog-config: {
  base-url: "https://repository.soilwise-he.eu/"
  item-link-base-url: "https://repository.soilwise-he.eu/cat/collections/metadata:main/items/"
  // New UI: https://client.soilwise-he-test.containers.wur.nl/
  // dataset-doc-types: used by getAllDatasetRecords (metadata search)
  // knowledge-doc-types: used by getAllKnowledgeRecords (metadata search)
  // knowledge-content-doc-types: used by getAllKnowledgeContent (full text content search)
  // item-content-doc-types: used by getItemContent (identifier-based content retrieval)
}
```

**Key configuration:**
- `base-url`: Base URL for the SoilWise repository
- `item-link-base-url`: Base URL for catalog item links (used by both backend tools and frontend for creating direct links to catalog items)
  - The frontend fetches this URL from the backend via the `/healthz` endpoint on startup
  - This ensures catalog links (e.g., "SoilWise ID: 10.xxxx/yyyy") use the correct URL configured in the backend
  - Default: `https://repository.soilwise-he.eu/cat/collections/metadata:main/items/`

**Frontend catalog link generation:**
- When the frontend detects "SoilWise ID: 10.xxxx/yyyy" patterns in AI responses, it automatically converts them to clickable links
- The link URL is constructed using `item-link-base-url` + URL-encoded DOI
- Example: `SoilWise ID: 10.1016/j.agee.2011.01.005` becomes a link to `https://repository.soilwise-he.eu/cat/collections/metadata:main/items/10.1016%2Fj.agee.2011.01.005`

**Configuration updates:**

solr-config-server: {
  base-url: "https://solr.soilwise-he.containers.wur.nl/solr/records/select"
  base-url: ${?SOLR_BASE_URL}
  username: "REPLACE_ME"
  username: ${?SOLR_USERNAME}
  password: "REPLACE_ME"
  password: ${?SOLR_PASSWORD}
}

solr-search-basic: {
  rows: 10
  mm: "2<75%"
  df: "title"
  q-alt: "*:*"
  ps: 2.0
  q-op: "OR"
  fl: ["identifier", "score", "type", "title", "keywords", "abstract" ]
  sort: "score desc"
  tie: 0.1
  def-type: "edismax"
  qf: [ "keywords^4", "title^2", "abstract^2" ]
  pf: [ "keywords^8", "title^8", "abstract^4" ]
}

solr-search-content: {
  rows: 10
  mm: "2<75%"
  df: "title"
  q-alt: "*:*"
  ps: 2.0
  q-op: "OR"
  fl: ["identifier", "score", "type", "title", "keywords", "abstract", "pdf_content" ]
  sort: "score desc"
  tie: 0.1
  def-type: "edismax"
  qf: [ "keywords^4", "title^2", "abstract^2", "pdf_content^1" ]
  pf: [ "keywords^8", "title^8", "abstract^4", "pdf_content^1" ]
}

solr-retrieve-content: {
  rows: 1
  mm: "2<75%"
  df: "identifier"
  q-alt: "*:*"
  ps: 2.0
  q-op: "OR"
  fl: ["identifier", "score", "type", "title", "keywords", "abstract", "pdf_content" ]
  sort: "score desc"
  tie: 0.1
  def-type: "edismax"
  qf: [ "identifier" ]
  pf: [ "identifier" ]
}
```

Notes and cautions:
- Access to the Solr server requires credentials; set `SOLR_USERNAME` and `SOLR_PASSWORD` environment variables.
- Basic search returns metadata fields (title, keywords, abstract). Content search also returns `pdf_content` excerpts when available.
- Item links are built using `item-link-base-url` and validated with an HTTP 200 check before returning to the user.
- The query builder ANDs words within a term and ANDs multiple terms, which narrows results; provide focused, domain-relevant terms (avoid adding document types like "report", "paper" as search terms — filtering by type is server-side).
- Returned lists are limited by the `rows` setting (keep it >= `catalog-retriever-max-results`).

Available tool methods:
- `getAllDatasetRecords(searchTerms: Array[String])` — search SoilWise metadata for DATASETS (not publications). Use for datasets, data collections, services, or APIs.
- `getAllKnowledgeRecords(searchTerms: Array[String])` — search SoilWise KNOWLEDGE records (publications/documents) such as journal papers and reports.
- `getAllKnowledgeContent(searchTerms: Array[String])` — return content snippets for matching KNOWLEDGE items.
- `getItemContent(identifier: String)` — retrieve content and metadata for a specific catalog item by identifier.
- `createVerifiedCatalogURL(identifier: String)` — create and verify a back link to the item in the SoilWise catalog UI.
