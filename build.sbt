ThisBuild / organization := "nl.wur"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.8.1"

ThisBuild / logLevel := Level.Debug
ThisBuild / fork := true

val langchain4jVersion = "1.10.0"
val langchain4jBetaVersion = "1.10.0-beta18"
val pureconfigVersion = "0.17.9"

lazy val chatbot = (crossProject(JSPlatform, JVMPlatform) in file("chatbot"))
  .jsSettings(
    name := "frontend",
    Compile / fastOptJS / artifactPath := baseDirectory.value / "static/main.js",
    scalaJSUseMainModuleInitializer    := true,
    libraryDependencies ++= Seq(
      "org.scala-js"  %%% "scalajs-dom" % "2.8.0",
      "com.lihaoyi"   %%% "scalatags"   % "0.13.1",
      "com.lihaoyi"   %%% "upickle"     % "4.3.0",
      // Provides java.security.SecureRandom for Scala.js (needed by java.util.UUID)
      ("org.scala-js"  %%% "scalajs-java-securerandom" % "1.0.0").cross(CrossVersion.for3Use2_13),
    )
  )
  .jvmSettings(
    name := "backend",
    libraryDependencies ++= Seq(
      // scala libraries
      "com.lihaoyi"     %% "cask"      % "0.11.3",
      "com.lihaoyi"     %% "upickle"   % "4.4.2",
      "com.lihaoyi"     %% "os-lib"    % "0.11.8",
      "com.lihaoyi"     %% "requests"  % "0.9.3",
      // config
      "com.github.pureconfig" %% "pureconfig-core"  % pureconfigVersion,
      "com.github.pureconfig" %% "pureconfig-generic-base"  % pureconfigVersion,
      // java logging
      "org.apache.logging.log4j" % "log4j-to-slf4j"   % "3.0.0-beta2",
      "ch.qos.logback"           % "logback-classic"  % "1.5.27",
      // java langchain4j
      "dev.langchain4j" % "langchain4j"             % langchain4jVersion,
      "dev.langchain4j" % "langchain4j-open-ai"     % langchain4jVersion,
      "dev.langchain4j" % "langchain4j-agentic"     % langchain4jBetaVersion,
      "dev.langchain4j" % "langchain4j-embeddings"  % langchain4jBetaVersion,
      "dev.langchain4j" % "langchain4j-embeddings-all-minilm-l6-v2" % langchain4jBetaVersion,
      "dev.langchain4j" % "langchain4j-chroma"      % langchain4jBetaVersion,
      "dev.langchain4j" % "langchain4j-easy-rag"    % langchain4jBetaVersion,
      // probably already pulled in by easy-rag ...
      "dev.langchain4j" % "langchain4j-document-parser-apache-tika" % langchain4jBetaVersion,
      // java testcontainers
      "org.testcontainers" % "chromadb" % "1.21.4"
    )
  )

lazy val root = (project in file("."))
  .settings(
    name := "soil-companion"
  )
