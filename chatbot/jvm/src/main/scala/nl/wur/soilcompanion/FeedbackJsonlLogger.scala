package nl.wur.soilcompanion

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardOpenOption}
import java.time.{LocalDate, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.GZIPOutputStream

import upickle.default.*

/**
 * Simple JSON Lines logger with daily rotation and gzip compression of previous day files.
 * Thread-safe for concurrent writes.
 *
 * Core metrics:
 * - Like Rate (LR) = ups / (ups + downs)
 * - NSAT = (ups - downs) / (ups + downs)
 *
 * Wilson lower confidence interval (95%):
 * p = ups / n
 * z = 1.96  -- 95% CI
 * denom = 1 + z^2/n
 * center = p + z^2/(2n)
 * margin = z*sqrt((p*(1-p) + z^2/(4n))/n)
 * wilson_lower = (center - margin)/denom
 *
 *
 */
class FeedbackJsonlLogger(baseDir: Path, filePrefix: String = "feedback", zoneId: ZoneId = ZoneId.systemDefault()) {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)
  private val lock = new ReentrantLock()
  private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

  // ensure base directory exists
  Files.createDirectories(baseDir)

  @volatile private var currentDate: LocalDate = LocalDate.now(zoneId)
  @volatile private var currentWriter: BufferedWriter = openWriterForDate(currentDate)

  private def openWriterForDate(date: LocalDate): BufferedWriter = {
    val filename = s"$filePrefix-${date.format(dateFmt)}.jsonl"
    val path = baseDir.resolve(filename)
    logger.info(s"Opening feedback log file $path")
    Files.newBufferedWriter(
      path,
      StandardCharsets.UTF_8,
      StandardOpenOption.CREATE,
      StandardOpenOption.APPEND
    )
  }

  private def gzipIfExists(date: LocalDate): Unit = {
    val filename = s"$filePrefix-${date.format(dateFmt)}.jsonl"
    val path = baseDir.resolve(filename)
    val gzPath = baseDir.resolve(filename + ".gz")
    if (Files.exists(path) && !Files.exists(gzPath)) {
      val in = Files.newInputStream(path)
      val out = new GZIPOutputStream(Files.newOutputStream(gzPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING))
      try {
        val buf = new Array[Byte](8192)
        var r = in.read(buf)
        while (r != -1) {
          out.write(buf, 0, r)
          r = in.read(buf)
        }
      } finally {
        try out.close() catch { case _: Throwable => () }
        try in.close() catch { case _: Throwable => () }
      }
      // after successful gzip, delete original
      try Files.delete(path) catch { case _: Throwable => () }
      logger.info(s"Gzip'ed feedback log file $path")
    }
  }

  private def rotateIfNeeded(now: LocalDate): Unit = {
    if (now.isAfter(currentDate)) {
      // rotate: close current, gzip previous, open new
      try currentWriter.close() catch { case _: Throwable => () }
      val prev = currentDate
      gzipIfExists(prev)
      currentDate = now
      currentWriter = openWriterForDate(currentDate)
    }
  }

  def log(line: ujson.Value): Unit = {
    val nowDate = LocalDate.now(zoneId)
    lock.lock()
    try {
      rotateIfNeeded(nowDate)
      // write compact one-liner
      val json = writeJs(line).render()
      currentWriter.write(json)
      currentWriter.newLine()
      currentWriter.flush()
      logger.debug(s"Logged feedback: $json")
    } finally {
      lock.unlock()
    }
  }
}
