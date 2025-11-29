/**
 * DEPRECATED: This old entry point has moved to nl.wur.soilcompanion.tools.FeedbackMetrics.
 * Use sbt "chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics" instead.
 */
object FeedbackMetrics:
  def main(args: Array[String]): Unit =
    System.err.println(
      "[DEPRECATED] FeedbackMetrics has moved to nl.wur.soilcompanion.tools.FeedbackMetrics\n" +
      "Run: sbt \"chatbotJVM/runMain nl.wur.soilcompanion.tools.FeedbackMetrics\"\n" +
      "Supported flags: --feedback-dir, --file, --out"
    )
    sys.exit(1)
