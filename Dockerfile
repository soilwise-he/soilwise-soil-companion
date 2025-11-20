# Multi-stage build for Soil Companion chatbot (Scala.js frontend + JVM backend)
# This image builds the Scala.js frontend (writes to chatbot/js/static/main.js)
# and runs the JVM backend server (Cask) while serving static files from /app/chatbot/js/static
# and loading knowledge resources from a mounted volume at /app/data/knowledge.

# ---- Builder + Runtime (uses sbt to run) ----
# Using an sbt image simplifies building and running without extra plugins.
FROM sbtscala/scala-sbt:eclipse-temurin-21.0.8_9_1.11.7_3.7.3 AS app

LABEL maintainer="rob.knapen@wur.nl"

# Host settings (match application.conf environment variable names)
ENV SOIL_COMPANION_HOST=0.0.0.0
ENV SOIL_COMPANION_PORT=8080
EXPOSE 8080

# System packages that help Apache Tika parse various formats (optional but recommended)
# You may remove/comment these to reduce image size if not needed.
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
      build-essential \
      software-properties-common \
      git \
      tesseract-ocr \
      ffmpeg \
      exiftool \
      sox \
      ca-certificates \
      curl && \
    rm -rf /var/lib/apt/lists/*

# Set workdir inside the container
RUN mkdir -p /app
WORKDIR /app

# --- Dependency caching layer ---
# Copy build definition files first so `sbt update` can be cached
COPY build.sbt ./
COPY project ./project

# Pre-fetch dependencies (both JS and JVM sides)
RUN sbt "update"

# --- Copy the rest of the project ---
COPY . .

# Build the Scala.js frontend
RUN sbt "chatbotJS/fastOptJS"

# Build the JVM backend
RUN sbt "chatbotJVM/compile"

# Capture the runtime classpath for the JVM project
# This will include both dependency jars and the compiled classes directory
RUN sbt -error "export chatbotJVM/runtime:fullClasspath" > /app/runtime-classpath.raw && \
    grep -E '(^/|:/.*/)' /app/runtime-classpath.raw | tail -n 1 > /app/runtime-classpath.txt && \
    echo "Captured classpath:" && cat /app/runtime-classpath.txt

# Set main class and JVM parameters
ENV APP_MAIN=nl.wur.soilcompanion.SoilCompanionServer
ENV JVM_OPTS="-Xms256m -Xmx2g --enable-native-access=ALL-UNNAMED"

# Prepare runtime data directories and environment defaults
ENV KNOWLEDGE_DIR=/app/data/knowledge \
    FEEDBACK_LOG_DIR=/app/data/feedback-logs \
    LOG_DIR=/app/data/logs

# Create mountable data directory with expected subfolders
RUN mkdir -p /app/data/knowledge /app/data/feedback-logs /app/data/logs

# Declare a volume for persistent data (knowledge, logs, feedback)
VOLUME ["/app/data"]

# Start the JVM backend
# - serves static files from /app/chatbot/js/static
# - loads knowledge from $KNOWLEDGE_DIR (mount a host folder to /app/data)
# - writes application logs to $LOG_DIR (via logback.xml)
# - writes feedback logs to $FEEDBACK_LOG_DIR
CMD sh -lc 'CP=$(cat /app/runtime-classpath.txt); \
  CLASSES=chatbot/jvm/target/scala-3.7.3/classes; \
  if [ -d "$CLASSES" ]; then CP="$CP:$CLASSES"; fi; \
  echo "Launching $APP_MAIN with classpath: $CP"; \
  exec java $JVM_OPTS -cp "$CP" $APP_MAIN'
