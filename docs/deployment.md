# Deployment

## Running locally (development)

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

## Running with Docker

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
- If you omit `-p 8080:8080`, your browser won't be able to connect from the host.
- You can adjust the port via env var `SOIL_COMPANION_PORT`.

Open in your browser:
- http://localhost:8080/app/index.html

## Running with Docker and Ollama

To use Ollama with the Docker container, you need to ensure the container can reach your Ollama instance:

**Option 1: Ollama on host machine**
```bash
docker run --rm \
  -e LLM_PROVIDER=ollama \
  -e OLLAMA_BASE_URL=http://host.docker.internal:11434 \
  -e SOIL_COMPANION_HOST=0.0.0.0 \
  -e SOIL_COMPANION_PORT=8080 \
  -v $(pwd)/data:/app/data \
  -p 8080:8080 soil-companion
```

**Option 2: Ollama in a separate container**
```bash
# Start Ollama container
docker run -d --name ollama -p 11434:11434 ollama/ollama
docker exec ollama ollama pull qwen2.5:7b

# Start Soil Companion container
docker run --rm \
  -e LLM_PROVIDER=ollama \
  -e OLLAMA_BASE_URL=http://ollama:11434 \
  --link ollama \
  -v $(pwd)/data:/app/data \
  -p 8080:8080 soil-companion
```

**Option 3: Using Docker Compose**
```yaml
version: '3.8'
services:
  ollama:
    image: ollama/ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama-data:/root/.ollama

  soil-companion:
    image: soil-companion
    environment:
      - LLM_PROVIDER=ollama
      - OLLAMA_BASE_URL=http://ollama:11434
      - SOIL_COMPANION_HOST=0.0.0.0
      - SOIL_COMPANION_PORT=8080
    ports:
      - "8080:8080"
    volumes:
      - ./data:/app/data
    depends_on:
      - ollama

volumes:
  ollama-data:
```

After starting the services, pull the model:
```bash
docker exec <ollama-container-name> ollama pull qwen2.5:7b
```

## Health endpoints and Kubernetes probes

The backend exposes lightweight health endpoints at the server root:
- `GET /healthz` — liveness: always returns 200 OK with JSON `{ status, uptimeSeconds, version, gitTag, now }` while the process is running.
- `GET /readyz` — readiness: returns 200 OK when core config is loaded and the LLM API key is present; otherwise returns 503 with JSON payload including `checks` and simple `metrics`.

Example responses:

```
GET /healthz -> 200 OK
{"status":"ok","uptimeSeconds":123,"version":"1.0.0","gitTag":"v1.0.0","now":"2025-11-21T13:45:00Z"}

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
- Readiness depends on the configured LLM provider:
  - For OpenAI: `OPENAI_API_KEY` must be non-empty
  - For Ollama: `ollama-base-url` must be configured
- Probes are fast and do not perform external network calls.
- WebSocket keep-alive heartbeats are sent every 15s; ensure ingress timeouts are configured accordingly.

### Health checks with different providers

The `/readyz` health endpoint adapts to the configured provider:

- **OpenAI**: Checks if `OPENAI_API_KEY` is present
- **Ollama**: Checks if `ollama-base-url` is configured

Example response:
```json
{
  "status": "ready",
  "checks": {
    "configLoaded": true,
    "llmProvider": "ollama",
    "llmProviderReady": true
  }
}
```

## Frontend version display and safe auto-update

To avoid users staying on an outdated client after a new deployment, the frontend is version-aware and politely prompts for a reload when a newer version is available.

How it works:

- On startup, the UI calls `GET /healthz` and displays a version label in the footer (element `#version-text`).
  - It prefers the `gitTag` returned by `/healthz`; if absent, it falls back to `version`.
  - The label is normalized to include a leading `v` when missing (e.g., `v1.2.3`).
- The initial value is remembered by the client.
- In the background, the client polls `/healthz` periodically and compares the current `gitTag`/`version` to the initial one.
  - Default polling interval: 60 seconds.
  - When a change is detected, a small, accessible banner appears above the footer asking the user to reload.
  - Clicking "Reload" performs a cache-busting reload by appending a query parameter to the URL to ensure fresh assets are fetched.

Operational details:

- Backend `GET /healthz` includes both `version` and `gitTag`. The server determines `gitTag` from CI-provided environment variables when available (e.g., `CI_COMMIT_TAG`, `RELEASE_TAG`, `GIT_TAG`, `SEMREL_VERSION`, `SEMVER_TAG`) and falls back to the application `version` if necessary.
- The UI logic lives in `chatbot/js/src/main/scala/nl/wur/soilcompanion/SoilCompanionApp.scala`:
  - Functions: `renderVersionFromHealthz`, `extractVersionLabel`, `startVersionPolling`, `showUpdateBanner`.
  - Polling is started during app initialization via `startVersionPolling(60000)`.
- To change the polling interval, adjust the argument to `startVersionPolling(...)` (milliseconds) in `SoilCompanionApp.scala`.
- No server configuration is required for this feature; ensure `/healthz` is reachable from the client.

Accessibility and UX:

- The update banner uses `role="status"` and `aria-live="polite"` and appears only once per update detection. It remains visible until the user reloads.
