This directory is intended to be mounted as a Docker volume at /app/data.

Subdirectories:
- knowledge: Place Markdown/PDF/text files to be ingested by the chatbot's RAG pipeline.
- logs: Application logs written by Logback (configurable via LOG_DIR env var).
- feedback-logs: JSONL feedback files written by FeedbackJsonlLogger (configurable via FEEDBACK_LOG_DIR env var).

Defaults inside the container:
- KNOWLEDGE_DIR=/app/data/knowledge
- LOG_DIR=/app/data/logs
- FEEDBACK_LOG_DIR=/app/data/feedback-logs

You can override these via environment variables when running the container.
