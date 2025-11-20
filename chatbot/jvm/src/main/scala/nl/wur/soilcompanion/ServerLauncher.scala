package nl.wur.soilcompanion

import java.util.concurrent.CountDownLatch

/**
 * Dedicated launcher to start the Cask HTTP server and keep the JVM process alive.
 *
 * We rely on the side-effects inside `SoilCompanionServer`'s object body to:
 * - configure the port
 * - kick off ingestion of core knowledge documents
 * - call `initialize()` to start the HTTP routes
 *
 * This launcher triggers the object initialization (thus starting the server)
 * and then blocks the main thread until the container receives a termination signal.
 */
object ServerLauncher {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("ServerLauncher: initializing SoilCompanionServer ...")

    // Touch the object to run its initialization side-effects (ingestion + initialize)
    val _ = SoilCompanionServer

    // Block the main thread to keep the process alive, with a graceful shutdown hook.
    val latch = new CountDownLatch(1)
    Runtime.getRuntime.addShutdownHook(new Thread(() => {
      logger.info("Shutdown signal received. Stopping Soil Companion service ...")
      // Cask doesn't expose a global stop here; rely on JVM termination.
      latch.countDown()
    }))

    logger.info("Soil Companion service started. Blocking main thread.")
    latch.await()
    logger.info("Soil Companion service stopped. Exiting.")
  }
}
