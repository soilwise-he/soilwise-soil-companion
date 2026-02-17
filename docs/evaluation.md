# Evaluation: Logs and Feedback

## Log and Feedback Export

To analyze conversations and user feedback, the project includes a small Scala CLI tool that merges runtime logs with feedback JSONL entries into a single JSON file.

- Source: `chatbot/jvm/src/main/scala/nl/wur/soilcompanion/eval/LogFeedbackExporter.scala`
- Inputs (defaults, relative to project root):
  - Logs directory: `./data/logs` (reads `soil-companion.log` plus rollover files, including `.gz`)
  - Feedback directory: `./data/feedback-logs` (files like `feedback-YYYY-MM-DD.jsonl` or `feedback-YYYY-MM-DD.jsonl.gz`)
- Output:
  - JSON file written to `./data/feedback-logs/feedback-export-<timestamp>.json` (overridable via `--out`)
  - CSV file written alongside the JSON by default (same basename with `.csv`), or to a custom path via `--csv-out`

CSV details
- Columns (in order): `device_id,session_id,received_ts,completed_ts,query,ai_response,feedback_vote,feedback_reason`
- One row per feedback entry; if a question has no feedback, a single row with empty feedback columns is emitted
- All values are quoted (`"..."`); embedded quotes are escaped by doubling; multi-line text is preserved inside the quoted fields

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
  --log ./data/logs \
  --feedback-dir ./data/feedback-logs \
  --out ./data/feedback-logs/merged.json"
```

Write CSV to an explicit path (otherwise a CSV is written next to the JSON output by default):

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.LogFeedbackExporter \
  --log ./data/logs \
  --feedback-dir ./data/feedback-logs \
  --out ./data/feedback-logs/feedback-export.json \
  --csv-out ./data/feedback-logs/feedback-export.csv"
```

Notes:
- The exporter accepts `--log` as either a single file path or a directory. When a directory is provided (default), it scans files whose names start with `soil-companion.log` (including gzipped rollovers ending with `.gz`) and processes them in chronological order.
- Feedback files in `--feedback-dir` may be plain `*.jsonl` or gzipped `*.jsonl.gz`.
- The parser expects log lines like `Received query for session ..., questionId=...: ...`, `[AI_FINAL] Q:` / `[AI_FINAL] A:` blocks, and `Query for session ... completed`.
- Retrieval/tool context is collected heuristically from lines that include metadata such as `file_name` and `index` when present.

## Feedback Metrics

A companion CLI computes quality metrics from the feedback JSONL files.

- Source: `chatbot/jvm/src/main/scala/nl/wur/soilcompanion/eval/FeedbackMetrics.scala`
- Inputs (defaults, relative to project root):
  - Feedback directory: `./data/feedback-logs` (files like `feedback-YYYY-MM-DD.jsonl` or `feedback-YYYY-MM-DD.jsonl.gz`)
- Output:
  - Human-readable, colorized table report printed to stdout; optionally written to a text file with `--out`
  - Optional structured JSON report written with `--json-out`

### What it reports

- Overall counts and rates:
  - n (total votes), ups, downs
  - Like Rate (ups / (ups + downs))
  - NSAT ((ups - downs) / (ups + downs))
  - Wilson lower 95% bound for the like rate
- Downvote reasons distribution (share per reason)
- Per-session like rate and the session-weighted mean
- Per-model slice (when `model` is present in feedback rows)

### How to run

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics"
```

Examples:

- Directory with mixed plain and gzipped feedback files:

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --feedback-dir ./data/feedback-logs"
```

- Single gzipped feedback file:

```
sbt "chatbotJVM/runMain nl.wur.soilcompanion.eval.FeedbackMetrics --file ./data/feedback-logs/feedback-2025-11-29.jsonl.gz"
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
- Files are read using UTF-8.
- The console report uses ANSI colors; use `--no-color` if your terminal doesn't support colors.
