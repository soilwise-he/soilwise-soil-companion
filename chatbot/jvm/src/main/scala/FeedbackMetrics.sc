//> using scala "3.4.2"
//> using dep "com.lihaoyi::ujson:3.2.0"

// scala-cli run FeedbackMetrics.scala -- feedback-2025-11-03.jsonl

/**
 * Calculates metrics from feedback JSONL file.
 *
 * Let ups be count of vote=="up", downs count of vote=="down", and n = ups + downs.
 *
 * - Like Rate (LR): LR = ups / n
 * - NSAT: NSAT = (ups - downs) / n
 * - Wilson lower bound (95%)
 * - Reason mix (among downs): share of each reason
 * - Per-session LR (optional, avoids one long session dominating):
 *      for each session_id, LR_session = ups_s / (ups_s + downs_s),
 *      also report the session-weighted LR = average of LR_session.
 */

import scala.io.Source
import scala.util.Try
import ujson._

object FeedbackMetrics:

  final case class Row(
    ts: String,
    sessionId: String,
    messageId: String,
    vote: String,
    reason: Option[String],
    model: Option[String]
  )

  def parseLine(line: String): Option[Row] =
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

  final case class Totals(ups: Int, downs: Int):
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

  def main(args: Array[String]): Unit =
    if args.isEmpty then
      System.err.println("Usage: scala-cli run FeedbackMetrics.scala -- <path-to-jsonl>")
    sys.exit(1)

    val path = args(0)
    val rows  = Source.fromFile(path).getLines().flatMap(parseLine).toVector

    val ups   = rows.count(_.vote == "up")
    val downs = rows.count(_.vote == "down")
    val totals = Totals(ups, downs)

    // reason mix among downs
    val reasonCounts: Map[String, Int] =
      rows.filter(_.vote == "down").groupBy(_.reason.getOrElse("unknown")).view.mapValues(_.size).toMap
    val downTotal = downs.max(1) // avoid div/0 in pretty print

    // per-session LR
    val perSession: Vector[(String, Double, Int)] =
      rows.groupBy(_.sessionId).toVector.map { case (sid, rs) =>
        val u = rs.count(_.vote=="up"); val d = rs.count(_.vote=="down")
        val n = u + d
        val lr = if n==0 then 0.0 else u.toDouble / n
        (sid, lr, n)
      }.sortBy(-_._2)

    val sessionWeightedLR =
      if perSession.isEmpty then 0.0 else perSession.map(_._2).sum / perSession.size

    // Optional: per-model slice
    val perModel: Vector[(String, Totals)] =
      rows.groupBy(_.model.getOrElse("unknown")).toVector.map { case (m, rs) =>
        (m, Totals(rs.count(_.vote=="up"), rs.count(_.vote=="down")))
      }.sortBy{ case (_, t) => -t.likeRate }

    // ---- Output ----
    def pct(x: Double): String = f"${x*100}%.1f%%"

    println("== Overall ==")
    println(f"n=${totals.n}  ups=$ups  downs=$downs")
    println(f"Like Rate (LR): ${totals.likeRate}%.3f  (${pct(totals.likeRate)})")
    println(f"NSAT:            ${totals.nsat}%.3f")
    println(f"Wilson lower 95%%: ${totals.wilsonLower()}%.3f")
    println()

    println("== Downvote reasons ==")
    if downs==0 then println("None")
    else
      reasonCounts.toVector.sortBy(-_._2).foreach { case (r,c) =>
        val share = c.toDouble / downTotal
        println(f"- $r: $c ($share%.3f, ${pct(share)})")
      }
    println()

    println("== Per-session Like Rate ==")
    perSession.foreach { case (sid, lr, n) =>
      println(f"$sid  n=$n%2d  LR=$lr%.3f  (${pct(lr)})")
    }
    println(f"Session-weighted LR (mean over sessions): $sessionWeightedLR%.3f  (${pct(sessionWeightedLR)})")
    println()

    println("== Per-model (optional) ==")
    perModel.foreach { case (m, t) =>
      println(f"$m  n=${t.n}%2d  LR=${t.likeRate}%.3f  NSAT=${t.nsat}%.3f  WilsonLow=${t.wilsonLower()}%.3f")
    }
