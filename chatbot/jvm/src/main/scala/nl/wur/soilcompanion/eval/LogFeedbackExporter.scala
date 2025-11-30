package nl.wur.soilcompanion.eval

import java.nio.charset.StandardCharsets
import java.time.{Instant, ZoneId, ZonedDateTime}
import java.time.format.DateTimeFormatter
import scala.util.matching.Regex
import scala.util.Try
import os.{Path, RelPath}
import ujson.*

/**
 * Utility to merge chatbot runtime logs (soil-companion.log) with feedback JSONL files into a single JSON export.
 *
 * Inputs (defaults relative to project root):
 * - log file: ./data/logs/soil-companion.log
 * - feedback directory: ./data/feedback-logs
 *
 * Output:
 * - JSON file: ./data/feedback-logs/feedback-export-<timestamp>.json (can be overridden)
 *
 * Usage examples:
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.LogFeedbackExporter"
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.LogFeedbackExporter --log ./data/logs/soil-companion.log --feedback-dir ./data/feedback-logs --out ./data/feedback-logs/merged.json"
 */
object LogFeedbackExporter:
  private case class Feedback(
    ts: String,
    session_id: Option[String],
    device_id: Option[String],
    message_id: Option[String],
    question_id: Option[String],
    vote: Option[String],
    reason: Option[String],
    client_ip: Option[String],
    model: Option[String],
    model_temp: Option[Double]
  )

  private case class RetrievalHit(file: Option[String], index: Option[Int], raw: String)

  private case class QARecord(
    questionId: String,
    sessionId: Option[String],
    receivedTs: Option[String],
    completedTs: Option[String],
    query: Option[String],
    aiResponse: Option[String],
    retrievals: List[RetrievalHit],
    logsInSpan: List[String]
  )

  private val tsPrefix: Regex = raw"^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) .*".r
  private val receivedRx: Regex = raw"^.*Received query for session ([0-9a-f\-]+), questionId=([0-9a-f\-]+):\s*(.*)$$".r
  private val completedRx: Regex = raw"^.*Query for session ([0-9a-f\-]+) completed$$".r
  private val aiFinalQTag = "[AI_FINAL] Q:"
  private val aiFinalATag = "[AI_FINAL] A:"
  private val aiFinalPrefix = "[AI_FINAL] "

  private val feedbackFileNameRx = raw"feedback-\d{4}-\d{2}-\d{2}.*\.jsonl$$".r

  def main(args: Array[String]): Unit =
    val argMap = parseArgs(args.toList)
    // Resolve all relative paths against the repository root (directory containing build.sbt)
    val repoRoot = findRepoRoot()
    def resolve(p: String): Path = os.Path(p, repoRoot)
    val logPath = resolve(argMap.getOrElse("--log", "./data/logs/soil-companion.log"))
    val fbDir   = resolve(argMap.getOrElse("--feedback-dir", "./data/feedback-logs"))
    val outPath = resolve(argMap.getOrElse("--out", s"./data/feedback-logs/feedback-export-${timeStampNow()}.json"))
    // CSV output path: explicit via --csv-out, otherwise alongside JSON with .csv extension
    val csvOutPath = argMap.get("--csv-out")
      .map(resolve)
      .getOrElse {
        val base = outPath
        val name = base.last
        val csvName =
          if name.toLowerCase.endsWith(".json") then name.dropRight(5) + ".csv"
          else name + ".csv"
        base / os.up / os.up / RelPath(".") // placeholder to keep type inference happy
        (base / os.up) / csvName
      }

    if !os.exists(logPath) then
      System.err.println(s"[LogFeedbackExporter] Log file not found: $logPath")
      System.exit(2)

    if !os.isDir(fbDir) then
      System.err.println(s"[LogFeedbackExporter] Feedback dir not found: $fbDir")
      System.exit(2)

    val qaByQuestionId = parseLog(logPath)
    val feedbackByQ = readFeedbackFiles(fbDir)

    val merged = merge(qaByQuestionId, feedbackByQ)

    // write JSON
    val json = ujson.Arr.from(merged.map(ujson.Obj.from))
    os.write.over(outPath, ujson.write(json, indent = 2), createFolders = true)
    println(s"[LogFeedbackExporter] Wrote ${merged.size} record(s) to $outPath")

    // write CSV (device_id,session_id,received_ts,completed_ts,query,ai_response,feedback_vote,feedback_reason) — all values quoted
    val csv = buildCsv(merged)
    os.write.over(csvOutPath, csv, createFolders = true)
    println(s"[LogFeedbackExporter] Wrote CSV rows to $csvOutPath")

  /** Walk up from current directory to find the repo root (folder containing build.sbt).
    * Falls back to os.pwd if not found. */
  private def findRepoRoot(): os.Path =
    @annotation.tailrec
    def loop(cur: os.Path): os.Path =
      if os.exists(cur / "build.sbt") then cur
      else
        val parent = cur / os.up
        if parent == cur then os.pwd else loop(parent)
    try loop(os.pwd) catch case _: Throwable => os.pwd

  private def parseArgs(args: List[String]): Map[String, String] =
    args.sliding(2, 2).collect {
      case List(k @ ("--log" | "--feedback-dir" | "--out" | "--csv-out"), v) => k -> v
    }.toMap

  private def timeStampNow(): String =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(ZonedDateTime.now(ZoneId.systemDefault()))

  private def parseLog(logPath: Path): Map[String, QARecord] =
    val lines = os.read.lines(logPath)

    var qaById = Map.empty[String, QARecord]
    var currentBySession = Map.empty[String, String] // sessionId -> questionId
    var collectingQ: Option[String] = None // questionId currently collecting Q lines
    var collectingA: Option[String] = None // questionId currently collecting A lines
    // After a query completes, the server emits the [AI_FINAL] Q/A lines.
    // These lines are not associated to an active session in our state, because a "completed" log
    // was already printed before them. Track the last completed question so we can attribute the
    // [AI_FINAL] block correctly.
    var lastFinalQid: Option[String] = None

    var bufferQ = scala.collection.mutable.Map.empty[String, StringBuilder] // qid -> sb
    var bufferA = scala.collection.mutable.Map.empty[String, StringBuilder]

    def ensureRecord(qid: String): QARecord =
      qaById.getOrElse(qid, QARecord(qid, None, None, None, None, None, Nil, Nil))

    def appendRetrieval(qid: String, line: String): Unit =
      val hit = extractRetrieval(line)
      val rec = ensureRecord(qid)
      qaById = qaById.updated(qid, rec.copy(
        retrievals = rec.retrievals :+ hit,
        logsInSpan = rec.logsInSpan :+ line
      ))

    def appendSpanLog(qid: String, line: String): Unit =
      val rec = ensureRecord(qid)
      qaById = qaById.updated(qid, rec.copy(logsInSpan = rec.logsInSpan :+ line))

    lines.foreach {
      case line@receivedRx(sessionId, questionId, msg) =>
        // timestamp
        val ts = tsPrefix.findFirstMatchIn(line).map(_.group(1))
        val rec0 = ensureRecord(questionId)
        val rec = rec0.copy(sessionId = Some(sessionId), receivedTs = ts.orElse(rec0.receivedTs), query = rec0.query.orElse(Option(msg).filter(_.nonEmpty)))
        qaById = qaById.updated(questionId, rec)
        currentBySession = currentBySession.updated(sessionId, questionId)
        collectingQ = Some(questionId)
        collectingA = None

      case line@completedRx(sessionId) =>
        currentBySession.get(sessionId).foreach { qid =>
          val ts = tsPrefix.findFirstMatchIn(line).map(_.group(1))
          val rec = ensureRecord(qid).copy(completedTs = ts)
          qaById = qaById.updated(qid, rec)
          // done for this session's current question
          // Do NOT drop the mapping yet; [AI_FINAL] lines follow and need the qid.
          // Remember which qid just completed so we can attribute [AI_FINAL] Q/A correctly.
          lastFinalQid = Some(qid)
          collectingQ = None
          collectingA = None
        }
      case line if line.contains(aiFinalQTag) =>
        // following [AI_FINAL] lines until A-tag belong to Q
        val targetQid = lastFinalQid
          .orElse(currentBySession.values.lastOption)
        targetQid.foreach { qid =>
          collectingQ = Some(qid)
          collectingA = None
          bufferQ.getOrElseUpdate(qid, new StringBuilder)
        }

      case line if line.contains(aiFinalATag) =>
        // next [AI_FINAL] lines belong to A
        val targetQid = lastFinalQid
          .orElse(currentBySession.values.lastOption)
        targetQid.foreach { qid =>
          collectingQ = None
          collectingA = Some(qid)
          bufferA.getOrElseUpdate(qid, new StringBuilder)
        }

      case line if line.contains(aiFinalPrefix) =>
        // a generic [AI_FINAL] content line
        val content = line.drop(line.indexOf(aiFinalPrefix) + aiFinalPrefix.length)
        (collectingQ, collectingA) match
          case (Some(qid), None) =>
            val sb = bufferQ.getOrElseUpdate(qid, new StringBuilder)
            if sb.nonEmpty then sb.append('\n')
            sb.append(content.trim)
            val rec = ensureRecord(qid)
            qaById = qaById.updated(qid, rec.copy(query = Some(sb.toString)))
          case (None, Some(qid)) =>
            val sb = bufferA.getOrElseUpdate(qid, new StringBuilder)
            if sb.nonEmpty then sb.append('\n')
            sb.append(content.trim)
            val rec = ensureRecord(qid)
            qaById = qaById.updated(qid, rec.copy(aiResponse = Some(sb.toString)))
            // After we started receiving A content, it's safe to clear the session mapping for that qid
            // (if present) and reset lastFinalQid to avoid leaking into next turns.
            // Find any session ids pointing to this qid and drop them.
            currentBySession = currentBySession.filter { case (_, v) => v != qid }
            lastFinalQid = Some(qid) // keep the lastFinalQid to continue appending until A block ends
          case _ => // ignore stray lines

      case line =>
        // If within a span, collect retrieval/tool-ish lines
        currentBySession.values.lastOption.foreach { qid =>
          if isRetrievalOrToolLine(line) then appendRetrieval(qid, line)
          else if isSpanRelevant(line) then appendSpanLog(qid, line)
        }
    }

    qaById

  private def optStr(v: Option[String]): ujson.Value = v.map(ujson.Str(_)).getOrElse(ujson.Null)
  private def optNum(v: Option[Double]): ujson.Value = v.map(ujson.Num(_)).getOrElse(ujson.Null)
  private def optInt(v: Option[Int]): ujson.Value = v.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null)

  private def isSpanRelevant(line: String): Boolean =
    // Keep some helpful lines for context during the span
    line.contains("AssistantLive$") || line.contains("SoilCompanionServer$")

  private def isRetrievalOrToolLine(line: String): Boolean =
    val l = line
    l.contains("Retrieved content") ||
      l.contains("Tool") || l.contains("TOOL") ||
      l.contains("function call") || l.contains("Using in-memory embedding store")

  private def extractRetrieval(line: String): RetrievalHit =
    // Try to pull file_name and index from the Apache Tika metadata prints
    val fileName = "file_name=([^,}]+)".r.findFirstMatchIn(line).map(_.group(1))
    val idx = "index=([0-9]+)".r.findFirstMatchIn(line).flatMap(m => Try(m.group(1).toInt).toOption)
    RetrievalHit(fileName, idx, raw = line)

  private def readFeedbackFiles(dir: Path): Map[String, List[Feedback]] =
    if !os.exists(dir) then return Map.empty
    val files = os.list(dir).filter(p => p.toIO.isFile && feedbackFileNameRx.matches(p.last))
    val buf = scala.collection.mutable.Map.empty[String, List[Feedback]]
    files.sorted.foreach { f =>
      val src = scala.io.Source.fromFile(f.toIO)(using scala.io.Codec.UTF8)
      try
        src.getLines().foreach { ln =>
          val fb = parseFeedbackJsonlLine(ln)
          fb.question_id.foreach { qid =>
            val lst = buf.getOrElse(qid, Nil)
            buf.update(qid, lst :+ fb)
          }
        }
      finally src.close()
    }
    buf.toMap

  private def parseFeedbackJsonlLine(line: String): Feedback =
    val js = Try(ujson.read(line)).toOption.getOrElse(ujson.Obj())
    Feedback(
      ts = js.obj.get("ts").flatMap(_.strOpt).getOrElse(""),
      session_id = js.obj.get("session_id").flatMap(_.strOpt),
      device_id = js.obj.get("device_id").flatMap(_.strOpt),
      message_id = js.obj.get("message_id").flatMap(_.strOpt),
      question_id = js.obj.get("question_id").flatMap(_.strOpt),
      vote = js.obj.get("vote").flatMap(_.strOpt),
      reason = js.obj.get("reason").flatMap(_.strOpt),
      client_ip = js.obj.get("client_ip").flatMap(_.strOpt),
      model = js.obj.get("model").flatMap(_.strOpt),
      model_temp = js.obj.get("model_temp").flatMap(_.numOpt).map(_.toDouble)
    )

  private def merge(qaById: Map[String, QARecord], feedbackByQ: Map[String, List[Feedback]]): List[Map[String, ujson.Value]] =
    val allIds = (qaById.keySet ++ feedbackByQ.keySet).toList.sorted
    allIds.map { qid =>
      val qa = qaById.get(qid)
      val fbs = feedbackByQ.getOrElse(qid, Nil)

      val modelFromFb = fbs.flatMap(_.model).headOption
      val tempFromFb  = fbs.flatMap(_.model_temp).headOption

      val feedbackJson = ujson.Arr.from(fbs.map { fb =>
        ujson.Obj(
          "ts" -> ujson.Str(fb.ts),
          "session_id" -> optStr(fb.session_id),
          "device_id" -> optStr(fb.device_id),
          "message_id" -> optStr(fb.message_id),
          "vote" -> optStr(fb.vote),
          "reason" -> optStr(fb.reason),
          "client_ip" -> optStr(fb.client_ip),
          "model" -> optStr(fb.model),
          "model_temp" -> optNum(fb.model_temp)
        )
      })

      val retrievalsJson = ujson.Arr.from(qa.map(_.retrievals).getOrElse(Nil).map { r =>
        ujson.Obj(
          "file_name" -> optStr(r.file),
          "index" -> optInt(r.index),
          "raw" -> ujson.Str(r.raw)
        )
      })

      val logsInSpanJson = ujson.Arr.from(qa.map(_.logsInSpan).getOrElse(Nil).map(ujson.Str(_)))

      Map(
        "question_id" -> ujson.Str(qid),
        "session_id" -> optStr(qa.flatMap(_.sessionId)),
        "received_ts" -> optStr(qa.flatMap(_.receivedTs)),
        "completed_ts" -> optStr(qa.flatMap(_.completedTs)),
        "query" -> optStr(qa.flatMap(_.query)),
        "ai_response" -> optStr(qa.flatMap(_.aiResponse)),
        "model" -> optStr(modelFromFb),
        "model_temp" -> optNum(tempFromFb),
        "feedback" -> feedbackJson,
        "retrievals" -> retrievalsJson,
        "span_logs" -> logsInSpanJson
      )
    }

  // Build CSV string; one row per question/feedback pair. If no feedback, include a single row with empty feedback columns.
  private def buildCsv(records: List[Map[String, ujson.Value]]): String =
    val header = List("device_id", "session_id", "received_ts", "completed_ts", "query", "ai_response", "feedback_vote", "feedback_reason").mkString(",")

    def q(v: String): String = '"' + v.replace("\"", "\"\"") + '"'

    def strOf(js: ujson.Value): String = js match
      case ujson.Str(s) => s
      case ujson.Null => ""
      case other => other.strOpt.getOrElse("")

    val bodyLines = records.flatMap { rec =>
      val sessionId = rec.get("session_id").map(strOf).getOrElse("")
      val received  = rec.get("received_ts").map(strOf).getOrElse("")
      val completed = rec.get("completed_ts").map(strOf).getOrElse("")
      val query     = rec.get("query").map(strOf).getOrElse("")
      val answer    = rec.get("ai_response").map(strOf).getOrElse("")
      val fbs = rec.get("feedback").flatMap(_.arrOpt).getOrElse(IndexedSeq.empty)
      if fbs.isEmpty then
        val row = List("", sessionId, received, completed, query, answer, "", "").map(q).mkString(",")
        List(row)
      else
        fbs.toList.map { fb =>
          val device  = fb.obj.get("device_id").map(strOf).getOrElse("")
          val vote    = fb.obj.get("vote").map(strOf).getOrElse("")
          val reason  = fb.obj.get("reason").map(strOf).getOrElse("")
          List(device, sessionId, received, completed, query, answer, vote, reason).map(q).mkString(",")
        }
    }

    (header +: bodyLines).mkString("\n") + "\n"
