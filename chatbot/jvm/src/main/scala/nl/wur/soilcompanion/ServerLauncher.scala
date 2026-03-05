/*
 * Copyright (c) 2024-2026 Wageningen University and Research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
