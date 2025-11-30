package nl.wur.soilcompanion.eval

import scala.util.Try
import ujson.*
import os.Path
import java.time.Instant

/**
 * Calculates feedback quality metrics from feedback JSONL file(s).
 *
 * Usage (similar to LogFeedbackExporter):
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics"
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics --feedback-dir ./data/feedback-logs"
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics --file ./data/feedback-logs/feedback-2025-11-29.jsonl"
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics --feedback-dir ./data/feedback-logs --out ./data/feedback-logs/metrics.txt"
 *   sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics --feedback-dir ./data/feedback-logs --json-out ./data/feedback-logs/metrics.json"
 */
object FeedbackMetrics:

  private final case class Row(
    ts: String,
    sessionId: String,
    messageId: String,
    vote: String,
    reason: Option[String],
    model: Option[String]
  )

  private def parseLine(line: String): Option[Row] =
    Try(ujson.read(line)).toOption.flatMap { js =>
      for
        ts <- js.obj.get("ts").flatMap(_.strOpt)
        session <- js.obj.get("session_id").flatMap(_.strOpt)
        message <- js.obj.get("message_id").flatMap(_.strOpt)
        vote <- js.obj.get("vote").flatMap(_.strOpt)
      yield Row(
        ts = ts,
        sessionId = session,
        messageId = message,
        vote = vote.toLowerCase,
        reason = js.obj.get("reason").flatMap(_.strOpt).filter(_.nonEmpty),
        model  = js.obj.get("model").flatMap(_.strOpt)
      )
    }

  private final case class Totals(ups: Int, downs: Int):
    def n: Int = ups + downs
    def likeRate: Double = if n==0 then 0.0 else ups.toDouble / n
    def nsat: Double = if n==0 then 0.0 else (ups - downs).toDouble / n
    def wilsonLower(z: Double = 1.96): Double =
      if n==0 then 0.0
      else
        val p = ups.toDouble / n
        val denom  = 1.0 + (z*z)/n
        val center = p + (z*z)/(2.0*n)
        val margin = z * math.sqrt((p*(1.0-p) + (z*z)/(4.0*n)) / n)
        (center - margin) / denom

  private val feedbackFileNameRx = "feedback-\\d{4}-\\d{2}-\\d{2}.*\\.jsonl$".r

  def main(args: Array[String]): Unit =
    val argMap = parseArgs(args.toList)
    // Resolve all relative paths against the repository root (directory containing build.sbt)
    val repoRoot = findRepoRoot()
    def resolve(p: String): os.Path = os.Path(p, repoRoot)
    val outPathOpt = argMap.get("--out").map(resolve)
    val jsonOutPathOpt = argMap.get("--json-out").map(resolve)
    val colorEnabled = !argMap.contains("--no-color")

    val (rows: Vector[Row], filesUsed: Vector[os.Path]) =
      argMap.get("--file") match
        case Some(filePath) =>
          val p = resolve(filePath)
          if !os.exists(p) then
            System.err.println(s"[FeedbackMetrics] File not found: $p"); System.exit(2)
          (readRowsFromFile(p), Vector(p))
        case None =>
          val dir = resolve(argMap.getOrElse("--feedback-dir", "./data/feedback-logs"))
          if !os.exists(dir) || !os.isDir(dir) then
            System.err.println(s"[FeedbackMetrics] Feedback dir not found: $dir"); System.exit(2)
          val files = os.list(dir).filter(p => p.toIO.isFile && feedbackFileNameRx.matches(p.last)).sorted
          if files.isEmpty then
            System.err.println(s"[FeedbackMetrics] No feedback-*.jsonl files found in $dir"); System.exit(2)
          (files.flatMap(readRowsFromFile).toVector, files.toVector)

    val report = buildReport(rows, colorEnabled)

    // Print to stdout
    println(report)

    // Optionally write to file
    outPathOpt.foreach { out =>
      os.write.over(out, report, createFolders = true)
      println(s"[FeedbackMetrics] Wrote metrics to $out")
    }

    // Optionally write JSON
    jsonOutPathOpt.foreach { out =>
      val json = buildJson(rows, filesUsed)
      os.write.over(out, ujson.write(json, indent = 2), createFolders = true)
      println(s"[FeedbackMetrics] Wrote JSON metrics to $out")
    }

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
    if args.isEmpty then Map("--feedback-dir" -> "./data/feedback-logs")
    else
      val kvs = args.sliding(2, 2).collect {
        case List(k @ ("--feedback-dir" | "--file" | "--out" | "--json-out"), v) => k -> v
      }.toMap
      // flags without values
      val flags = args.filter(_ == "--no-color").map(_ -> "true").toMap
      kvs ++ flags

  private def readRowsFromFile(p: Path): Vector[Row] =
    val src = scala.io.Source.fromFile(p.toIO)(using scala.io.Codec.UTF8)
    try src.getLines().flatMap(parseLine).toVector
    finally src.close()

  private def buildReport(rows: Vector[Row], color: Boolean): String =
    val ups   = rows.count(_.vote == "up")
    val downs = rows.count(_.vote == "down")
    val totals = Totals(ups, downs)

    val reasonCounts: Map[String, Int] =
      rows.filter(_.vote == "down").groupBy(_.reason.getOrElse("unknown")).view.mapValues(_.size).toMap
    val downTotal = downs.max(1)

    val perSession: Vector[(String, Double, Int)] =
      rows.groupBy(_.sessionId).toVector.map { case (sid, rs) =>
        val u = rs.count(_.vote=="up"); val d = rs.count(_.vote=="down")
        val n = u + d
        val lr = if n==0 then 0.0 else u.toDouble / n
        (sid, lr, n)
      }.sortBy(-_._2)

    val sessionWeightedLR =
      if perSession.isEmpty then 0.0 else perSession.map(_._2).sum / perSession.size

    val perModel: Vector[(String, Totals)] =
      rows.groupBy(_.model.getOrElse("unknown")).toVector.map { case (m, rs) =>
        (m, Totals(rs.count(_.vote=="up"), rs.count(_.vote=="down")))
      }.sortBy{ case (_, t) => -t.likeRate }

    // ANSI helpers
    object Ansi:
      private val esc = "\u001b["
      val reset = if color then esc + "0m" else ""
      def colorCode(code: String) = if color then esc + code + "m" else ""
      val bold = colorCode("1")
      val dim  = colorCode("2")
      val cyan = colorCode("36")
      val green = colorCode("32")
      val red = colorCode("31")
      val yellow = colorCode("33")

    def pct(x: Double): String = f"${x*100}%.1f%%"

    def title(s: String): String = s"${Ansi.cyan}${Ansi.bold}$s${Ansi.reset}"
    def hr(w: Int = 64): String = ("-" * w) + "\n"

    val sb = new StringBuilder
    sb.append(title("Overall") + "\n")
    sb.append(hr())
    val upsStr = s"${Ansi.green}$ups${Ansi.reset}"
    val downsStr = s"${Ansi.red}$downs${Ansi.reset}"
    sb.append("%-20s %6d   %-8s %-8s\n".format("Total votes (n)", totals.n, s"ups=$upsStr", s"downs=$downsStr"))
    sb.append("%-20s %6.3f   (%s)\n".format("Like Rate (LR)", totals.likeRate, pct(totals.likeRate)))
    sb.append("%-20s %6.3f\n".format("NSAT", totals.nsat))
    sb.append("%-20s %6.3f\n\n".format("Wilson 95% low", totals.wilsonLower()))

    sb.append(title("Downvote reasons") + "\n")
    sb.append(hr())
    if downs==0 then sb.append("None\n\n")
    else
      sb.append("%-28s %6s %10s %10s\n".format("Reason", "Count", "Share", "Share%"))
      reasonCounts.toVector.sortBy(-_._2).foreach { case (r,c) =>
        val share = c.toDouble / downTotal
        sb.append("%-28s %6d %10.3f %9s\n".format(r, c, share, pct(share)))
      }
      sb.append("\n")

    sb.append(title("Per-session Like Rate") + "\n")
    sb.append(hr())
    sb.append("%-38s %6s %10s %10s\n".format("Session", "n", "LR", "LR%"))
    perSession.foreach { case (sid, lr, n) =>
      val lrColor = if lr >= 0.5 then Ansi.green else Ansi.red
      val lrStr = s"$lrColor${"%.3f".format(lr)}${Ansi.reset}"
      sb.append("%-38s %6d %10s %10s\n".format(sid, n, lrStr, pct(lr)))
    }
    sb.append("\n%-38s %6s %10.3f %10s\n\n".format("Session-weighted mean", "", sessionWeightedLR, pct(sessionWeightedLR)))

    sb.append(title("Per-model (optional)") + "\n")
    sb.append(hr())
    sb.append("%-24s %6s %10s %10s %12s\n".format("Model", "n", "LR", "NSAT", "WilsonLow"))
    perModel.foreach { case (m, t) =>
      val lrColor = if t.likeRate >= 0.5 then Ansi.green else Ansi.red
      val lrStr = s"$lrColor${"%.3f".format(t.likeRate)}${Ansi.reset}"
      sb.append("%-24s %6d %10s %10.3f %12.3f\n".format(m, t.n, lrStr, t.nsat, t.wilsonLower()))
    }

    sb.toString()

  private def buildJson(rows: Vector[Row], filesUsed: Vector[os.Path]): ujson.Value =
    val ups   = rows.count(_.vote == "up")
    val downs = rows.count(_.vote == "down")
    val totals = Totals(ups, downs)

    val reasons: Map[String, Int] =
      rows.filter(_.vote == "down").groupBy(_.reason.getOrElse("unknown")).view.mapValues(_.size).toMap

    val perSession =
      rows.groupBy(_.sessionId).toVector.map { case (sid, rs) =>
        val u = rs.count(_.vote=="up"); val d = rs.count(_.vote=="down")
        val n = u + d
        val lr = if n==0 then 0.0 else u.toDouble / n
        (sid, lr, n, u, d)
      }.sortBy(-_._2)
    val sessionWeightedLR = if perSession.isEmpty then 0.0 else perSession.map(_._2).sum / perSession.size

    val perModel =
      rows.groupBy(_.model.getOrElse("unknown")).toVector.map { case (m, rs) =>
        val t = Totals(rs.count(_.vote=="up"), rs.count(_.vote=="down"))
        (m, t)
      }

    val now = Instant.now().toString

    ujson.Obj(
      "generated_at" -> now,
      "inputs" -> ujson.Arr.from(filesUsed.map(p => ujson.Str(p.toString))),
      "overall" -> ujson.Obj(
        "n" -> totals.n,
        "ups" -> ups,
        "downs" -> downs,
        "like_rate" -> totals.likeRate,
        "nsat" -> totals.nsat,
        "wilson_lower_95" -> totals.wilsonLower()
      ),
      "downvote_reasons" -> ujson.Arr.from(reasons.toVector.sortBy(-_._2).map { case (r,c) =>
        ujson.Obj("reason" -> r, "count" -> c)
      }),
      "per_session" -> ujson.Arr.from(perSession.map { case (sid, lr, n, u, d) =>
        ujson.Obj("session_id" -> sid, "n" -> n, "ups" -> u, "downs" -> d, "like_rate" -> lr)
      }),
      "session_weighted_lr" -> sessionWeightedLR,
      "per_model" -> ujson.Arr.from(perModel.map { case (m, t) =>
        ujson.Obj(
          "model" -> m,
          "n" -> t.n,
          "ups" -> t.ups,
          "downs" -> t.downs,
          "like_rate" -> t.likeRate,
          "nsat" -> t.nsat,
          "wilson_lower_95" -> t.wilsonLower()
        )
      })
    )
