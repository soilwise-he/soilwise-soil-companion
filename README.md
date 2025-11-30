# SoilWise Soil Companion
***An AI chatbot for soil related questions***


## Introduction
The Soil Companion is an AI chatbot developed in the SoilWise project. SoilWise provides a European soil metadata
repository with aligned semantics. The chatbot integrates with the SoilWise catalog (via Solr) to search metadata and
retrieve content for publications, and it can return validated links back to the catalog UI. It also offers tools for
country and global soil data services.

What the current version does:
- Searches the SoilWise catalog (datasets and knowledge/publications) via Solr, including optional full‑text snippets
  when available.
- Creates verified back links to catalog items by identifier.
- Queries ISRIC SoilGrids v2.0 for indicative soil property estimates at a given location.
- Retrieves agricultural field information and KPIs for The Netherlands via WUR OpenAgroKPI; supports basic field lookups
  and example KPI retrievals.
- Integrates with WUR AgroDataCube v2 for NL crop parcels and soil/crop information, with session memory of the last
  field context.
- Uses optional local “knowledge” documents from a directory to complement answers.
- Provides a simple demo authentication mode suitable for local development/testing.

Notes:
- Returned values from SoilGrids are modelled estimates (not field measurements) and should be verified locally.
- Access to Solr and the NL country services requires credentials/API keys (see Configuration section below).

## Technologies
The Soil Companion is a full‑stack Scala / Scala.js application. Key technologies used:

- SBT 1.11.x (multi‑module, cross‑build JS/JVM)
- Scala 3.7.3
- Scala.js frontend with:
  - scalajs-dom 2.8.0
  - Scalatags 0.13.x
  - upickle 4.3.x (JS)
- JVM backend with:
  - Cask 0.11.x (HTTP server)
  - upickle 4.4.x
  - requests 0.9.x (HTTP client)
  - os-lib 0.11.x
  - PureConfig 0.17.9 (HOCON to case classes)
  - LangChain4j 1.9.x (core, OpenAI, agentic, embeddings, easy‑rag)
  - SLF4J + Logback 1.5.x
- JDK 17+ (tested 17–25)
- Docker for containerized runs

### Health endpoints and Kubernetes probes
- The backend exposes lightweight health endpoints at the server root:
  - `GET /healthz` — liveness: always returns 200 OK with JSON `{ status, uptimeSeconds, version, now }` while the process is running.
  - `GET /readyz` — readiness: returns 200 OK when core config is loaded and the LLM API key is present; otherwise returns 503 with JSON payload including `checks` and simple `metrics`.

Example responses:

```
GET /healthz -> 200 OK
{"status":"ok","uptimeSeconds":123,"version":"1.0.0","now":"2025-11-21T13:45:00Z"}

GET /readyz -> 200 OK or 503 Service Unavailable
{"status":"ready","uptimeSeconds":123,"version":"1.0.0","metrics":{"wsConnections":0,"sessions":0},"checks":{"configLoaded":true,"llmApiKeyPresent":true}}
```

Kubernetes probe examples:

```
livenessProbe:
  httpGet:
    path: /healthz
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 10
  timeoutSeconds: 2
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /readyz
    port: 8080
  initialDelaySeconds: 5
  periodSeconds: 10
  timeoutSeconds: 2
  failureThreshold: 3
```

Notes:
- Readiness depends on `llm-provider-config.apiKey` being non-empty (e.g., `OPENAI_API_KEY`).
- Probes are fast and do not perform external network calls.
- WebSocket keep-alive heartbeats are sent every 15s; ensure ingress timeouts are configured accordingly (see Kubernetes runbook below).

## Configuration
- An *application.conf* resource file is used to configure the application.
- An OpenAI API key is needed, and must be provided as an environment variable (OPENAI_API_KEY).
- Demo authentication: a single demo user account is configured under `demo-user` in `chatbot/jvm/src/main/resources/application.conf`.
  - Default credentials: username `demo@soilwise`, password `*******` (can be overridden via env vars `DEMO_USERNAME`, `DEMO_PASSWORD`, `DEMO_DISPLAY_NAME`).

### Environment variables and config overrides
Most settings in `application.conf` can be overridden via environment variables. Commonly used variables:

- Core app
  - `SOIL_COMPANION_VERSION` — app version shown in UI
  - `SOIL_COMPANION_HOST` — HTTP host to bind
  - `SOIL_COMPANION_PORT` — HTTP port to bind
  - `DEBUG_LOG_FINAL_AI_RESPONSE` — set to `true` to enable a single debug log entry with the final AI response per question
  - `CHAT_MAX_PROMPT_CHARS` — safety limit for max prompt size
  - `UPLOAD_MAX_CHARS` — safety limit for uploaded text size
  - `SESSION_EXPIRATION_MINUTES` — chat session expiration (-1 to disable)
- Authentication (demo)
  - `DEMO_USERNAME`, `DEMO_PASSWORD`, `DEMO_DISPLAY_NAME`
- Data directories
  - `KNOWLEDGE_DIR` — directory with local knowledge documents
  - `FEEDBACK_LOG_DIR` — directory for feedback logs
  - `FEEDBACK_LOG_PREFIX` — filename prefix for feedback logs
- LLM provider
  - `OPENAI_API_KEY` — API key used for chat and embeddings
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

### SoilGrids tools configuration
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

### Debug logging of user question and AI responses

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

Notes and cautions:
- SoilGrids provides modelled estimates at approx. 250 m grid resolution, not field measurements.
- Values are indicative; local conditions may vary substantially. Verify with local data and experts before decisions.
- The tool methods are:
  - `getSoilGridsAtLocation(lat, lon, propertiesCsv, depthsCsv, valueStat)`
  - `getSoilGridsFromLocationContext(locationContextJson)`
    - The UI stores a per-session location context JSON with `lat` and `lon` that can be passed to this tool.

Output includes clickable links to SoilGrids docs and Terms of Use.

### Agricultural field data (OpenAgroKPI, NL) tools configuration
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

### AgroDataCube (NL) tools configuration
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

### SoilWise Catalog tools configuration
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

## To Run
For development, the server and client components can be run separately with sbt:
- $ cd <project-root>
- $ sbt
  - project chatbotJVM
  - run
- $ sbt (in another terminal)
  - project chatbotJS
  - ~fastOptJS (to transpile and watch for changes (while developing))

Note that for final versions the fullOptJS build is recommended since it produces smaller, faster 
JavaScript bundles. Also, latest ScalaJS is renaming the commands to fastLinkJS and fullLinkJS, with
output written to folders in the target directory (adjust Dockerfile accordingly).

The chatbot will be available at http://localhost:8080/app/index.html

### Run with Docker
Build the image (from the project root):

- docker build -t soil-companion .

Run the container and publish the port to your host. Mount the local `./data` folder to `/app/data` inside the container so knowledge and logs are persisted:

- mkdir -p data/knowledge data/logs data/feedback-logs
- cp chatbot/jvm/src/main/resources/knowledge/* data/knowledge/  # optional: seed with built-in docs
- docker run --rm \
    -e OPENAI_API_KEY=... \
    -e SOIL_COMPANION_HOST=0.0.0.0 \
    -e SOIL_COMPANION_PORT=8080 \
    -v $(pwd)/data:/app/data \
    -p 8080:8080 soil-companion

Defaults inside the container (overridable via env vars):
- `KNOWLEDGE_DIR=/app/data/knowledge`
- `FEEDBACK_LOG_DIR=/app/data/feedback-logs`
- `LOG_DIR=/app/data/logs`

Notes:
- The server listens on `0.0.0.0:8080` inside the container.
- If you omit `-p 8080:8080`, your browser won’t be able to connect from the host.
- You can adjust the port via env var `SOIL_COMPANION_PORT`.

Open in your browser:
- http://localhost:8080/app/index.html


## Eval: Logs and Feedback Export
To analyze conversations and user feedback, the project includes a small Scala CLI tool that merges runtime logs with feedback JSONL entries into a single JSON file.

- Source: `chatbot/jvm/src/main/scala/nl/wur/soilcompanion/eval/LogFeedbackExporter.scala`
- Inputs (defaults, relative to project root):
  - Log file: `./data/logs/soil-companion.log`
  - Feedback directory: `./data/feedback-logs` (files like `feedback-YYYY-MM-DD.jsonl`)
- Output:
  - JSON file written to `./data/feedback-logs/feedback-export-<timestamp>.json` (overridable via `--out`)
  - CSV file written alongside the JSON by default (same basename with `.csv`), or to a custom path via `--csv-out`

CSV details
- Columns (in order): `device_id,session_id,received_ts,completed_ts,query,ai_response,feedback_vote,feedback_reason`
- One row per feedback entry; if a question has no feedback, a single row with empty feedback columns is emitted
- All values are quoted (`"..."`); embedded quotes are escaped by doubling; multi‑line text is preserved inside the quoted fields

### What it exports
For each `questionId`, it combines:
- User query (`query`)
- Final AI response (`ai_response`)
- Timestamps (`received_ts`, `completed_ts`) and `session_id`
- Any user `feedback` entries associated to the `question_id` (including `vote`, optional `reason`, `model`, `model_temp`)
- Retrieval/tool context found in the logs (`retrievals`, `span_logs`)

Minimal example of one exported record:

```
{
  "question_id": "<uuid>",
  "session_id": "<uuid>",
  "received_ts": "YYYY-MM-DD HH:MM:SS.mmm",
  "completed_ts": "YYYY-MM-DD HH:MM:SS.mmm",
  "query": "...",
  "ai_response": "...",
  "model": "gpt-4o-mini-2024-07-18",
  "model_temp": 0.1,
  "feedback": [ { "ts": "...", "vote": "up|down", "reason": null } ],
  "retrievals": [ { "file_name": "important_facts.md", "index": 4 } ],
  "span_logs": [ "..." ]
}
```

### How to run the exporter
From the project root (sbt is already configured with the required libraries):

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.LogFeedbackExporter"
```

Specify explicit paths and output filename if needed:

```
  sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.LogFeedbackExporter \
  --log ./data/logs/soil-companion.log \
  --feedback-dir ./data/feedback-logs \
  --out ./data/feedback-logs/merged.json"
  ```

Write CSV to an explicit path (otherwise a CSV is written next to the JSON output by default):

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.LogFeedbackExporter \
  --log ./data/logs/soil-companion.log \
  --feedback-dir ./data/feedback-logs \
  --out ./data/feedback-logs/feedback-export.json \
  --csv-out ./data/feedback-logs/feedback-export.csv"
```

Notes:
- The parser expects log lines like `Received query for session ..., questionId=...: ...`, `[AI_FINAL] Q:` / `[AI_FINAL] A:` blocks, and `Query for session ... completed`.
- Retrieval/tool context is collected heuristically from lines that include metadata such as `file_name` and `index` when present.

### Eval: Feedback metrics
A companion CLI computes quality metrics from the feedback JSONL files.

- Source: `chatbot/jvm/src/main/scala/nl/wur/soilcompanion/eval/FeedbackMetrics.scala`
- Inputs (defaults, relative to project root):
  - Feedback directory: `./data/feedback-logs` (files like `feedback-YYYY-MM-DD.jsonl`)
- Output:
  - Human‑readable, colorized table report printed to stdout; optionally written to a text file with `--out`
  - Optional structured JSON report written with `--json-out`

What it reports
- Overall counts and rates:
  - n (total votes), ups, downs
  - Like Rate (ups / (ups + downs))
  - NSAT ((ups − downs) / (ups + downs))
  - Wilson lower 95% bound for the like rate
- Downvote reasons distribution (share per reason)
- Per‑session like rate and the session‑weighted mean
- Per‑model slice (when `model` is present in feedback rows)

How to run (similar to LogFeedbackExporter)

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics"
```

Specify a feedback directory explicitly:

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --feedback-dir ./data/feedback-logs"
```

Run for a single JSONL file:

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --file ./data/feedback-logs/feedback-2025-11-29.jsonl"
```

Write the textual report to a file as well as stdout:

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --feedback-dir ./data/feedback-logs --out ./data/feedback-logs/metrics.txt"
```

Write a JSON metrics report (alongside the console/table output):

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --feedback-dir ./data/feedback-logs --json-out ./data/feedback-logs/metrics.json"
```

Turn off ANSI colors (useful for CI logs or when redirecting output):

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --no-color"
```

Notes:
- When no arguments are provided, the tool scans all `feedback-*.jsonl` files in the default feedback directory.
- Files are read using UTF‑8.
 - The console report uses ANSI colors; use `--no-color` if your terminal doesn’t support colors.

