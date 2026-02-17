# SoilWise Soil Companion
***An AI chatbot for soil related questions***


## Introduction

The Soil Companion is an AI chatbot developed in the SoilWise project. SoilWise provides a European soil metadata
repository with aligned semantics. The chatbot integrates with the SoilWise catalog (via Solr) to search metadata and
retrieve content for publications, and it can return validated links back to the catalog UI. It also offers tools for
country and global soil data services.

What the current version does:
- Searches the SoilWise catalog (datasets and knowledge/publications) via Solr, including optional full-text snippets
  when available.
- Creates verified back links to catalog items by identifier.
- Queries ISRIC SoilGrids v2.0 for indicative soil property estimates at a given location.
- Retrieves agricultural field information and KPIs for The Netherlands via WUR OpenAgroKPI; supports basic field lookups
  and example KPI retrievals.
- Integrates with WUR AgroDataCube v2 for NL crop parcels and soil/crop information, with session memory of the last
  field context.
- Searches Wikipedia for general concepts, definitions, and background information to supplement answers.
- Automatically links soil vocabulary terms to their definitions in the SoilWise vocabulary browser.
- Uses optional local "knowledge" documents from a directory to complement answers.
- Provides a simple demo authentication mode suitable for local development/testing.

Notes:
- Returned values from SoilGrids are modelled estimates (not field measurements) and should be verified locally.
- Access to Solr and the NL country services requires credentials/API keys (see [Configuration](docs/configuration.md)).

## Technologies

The Soil Companion is a full-stack Scala / Scala.js application. Key technologies used:

- SBT 1.11.x (multi-module, cross-build JS/JVM)
- Scala 3.8.1
- Scala.js frontend (scalajs-dom, Scalatags, upickle)
- JVM backend (Cask HTTP server, LangChain4j, PureConfig, Logback)
- JDK 17+ (tested 17-25)
- Docker for containerized runs

## Prerequisites

- **JDK 17+** (tested with 17–25; the Docker image uses Eclipse Temurin 21)
- **SBT 1.11+** (Scala Build Tool)
- An **OpenAI API key**, or a local **Ollama** instance for LLM inference

## Quick Start

1. **Copy the environment template** and fill in at least `OPENAI_API_KEY`:
   ```bash
   cp .env.example .env
   # edit .env and set OPENAI_API_KEY=sk-...
   ```

2. **Run the application** (two terminals):
   ```bash
   # Terminal 1: start the backend
   sbt "chatbotJVM/run"

   # Terminal 2: compile the frontend (with watch mode)
   sbt "chatbotJS/fastOptJS"
   ```

3. **Open** http://localhost:8080/app/index.html

### Docker

```bash
docker build -t soil-companion .

docker run --rm \
  -e OPENAI_API_KEY=... \
  -e SOIL_COMPANION_HOST=0.0.0.0 \
  -e SOIL_COMPANION_PORT=8080 \
  -v $(pwd)/data:/app/data \
  -p 8080:8080 soil-companion
```

See [Deployment](docs/deployment.md) for Docker Compose, Ollama integration, and Kubernetes probe setup.

## Project Structure

```
soil-companion/
├── build.sbt                  # SBT multi-module build (JS + JVM)
├── project/                   # SBT plugins (Scala.js, cross-build)
├── chatbot/
│   ├── js/                    # Scala.js frontend
│   │   ├── src/main/scala/    #   Application code (SoilCompanionApp)
│   │   └── static/            #   HTML, CSS, images, JavaScript libs
│   ├── jvm/                   # JVM backend
│   │   └── src/main/
│   │       ├── scala/         #   Server, tools, assistant, eval CLIs
│   │       └── resources/     #   application.conf, knowledge docs, logback
│   └── shared/                # Cross-compiled domain models (JS + JVM)
│       └── src/main/scala/    #   QueryRequest, QueryEvent, etc.
├── data/
│   ├── knowledge/             # Local RAG documents (loaded at startup)
│   ├── vocab/                 # SoilWise vocabulary CSV (for auto-linking)
│   └── examples/              # Sample data files
├── docs/                      # Documentation (see table below)
├── .env.example               # Environment variable template
├── Dockerfile                 # Multi-stage Docker build
├── CITATION.cff               # Citation metadata
├── LICENSE                    # Apache License 2.0
├── NOTICE                     # Attribution and funding acknowledgement
└── CHANGELOG.md               # Release history (auto-generated)
```

## Documentation

Detailed documentation is available in the [`docs/`](docs/) directory:

| Document                               | Description |
|----------------------------------------|-------------|
| [Configuration](docs/configuration.md) | Environment variables, LLM provider setup (OpenAI / Ollama), and all tool configurations (SoilGrids, AgroDataCube, OpenAgroKPI, Wikipedia, Vocabulary, Catalog) |
| [Deployment](docs/deployment.md)       | Local development, Docker, Docker Compose, Ollama integration, Kubernetes health probes, and frontend auto-update |
| [Evaluation](docs/evaluation.md)       | Log/feedback export CLI and feedback metrics CLI |
| [Design](docs/design.md)               | Architecture, design decisions, and component diagrams |

## Contributing

This project uses [conventional commits](https://www.conventionalcommits.org/) and automated
[semantic versioning](https://semver.org/). Releases are published automatically from the `main`
branch via [semantic-release](https://github.com/semantic-release/semantic-release).

Use the following commit prefixes:

| Prefix | Release | Example |
|--------|---------|---------|
| `feat:` | minor | `feat: add soil health knowledge graph tool` |
| `fix:` | patch | `fix: handle empty SoilGrids response` |
| `docs:` | — | `docs: update deployment instructions` |
| `refactor:` | — | `refactor: simplify Solr query builder` |
| `BREAKING CHANGE:` | major | (in commit body or footer) |

**Adding knowledge documents:** Place Markdown, text, or PDF files in `data/knowledge/`. They are
embedded and indexed at server startup for RAG retrieval — no code changes required. Restart the
server to pick up new files.

## License

This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

## Acknowledgements

<img src="chatbot/js/static/img/eu-funding-banner-w500.png" alt="Funded by the European Union" width="250" />

This project has received funding from the European Union's HORIZON Innovation Actions 2022 under grant agreement No. **101112838** ([SoilWise](https://soilwise-he.eu)).

Views and opinions expressed are however those of the author(s) only and do not necessarily reflect those of the European Union or the European Research Executive Agency (REA). Neither the European Union nor the granting authority can be held responsible for them.
