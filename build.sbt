ThisBuild / name := "soil-companion"
ThisBuild / organization := "nl.wur"
ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.7.3"

ThisBuild / logLevel := Level.Debug
ThisBuild / fork := true

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
      "com.lihaoyi"     %% "upickle"   % "4.4.1",
      "com.lihaoyi"     %% "os-lib"    % "0.11.6",
      "com.lihaoyi"     %% "requests"  % "0.9.0",
      // config
      "com.github.pureconfig" %% "pureconfig-core"  % "0.17.9",
      "com.github.pureconfig" %% "pureconfig-generic-base"  % "0.17.9",
      // java logging
      "org.apache.logging.log4j" % "log4j-to-slf4j"   % "3.0.0-beta2",
      "ch.qos.logback"           % "logback-classic"  % "1.5.21",
      // java langchain4j
      "dev.langchain4j" % "langchain4j"             % "1.9.1",
      "dev.langchain4j" % "langchain4j-open-ai"     % "1.9.1",
      "dev.langchain4j" % "langchain4j-agentic"     % "1.9.1-beta17",
      "dev.langchain4j" % "langchain4j-embeddings"  % "1.9.1-beta17",
      "dev.langchain4j" % "langchain4j-easy-rag"    % "1.9.1-beta17",
      // probably already pulled in by easy-rag ...
      "dev.langchain4j" % "langchain4j-document-parser-apache-tika" % "1.9.1-beta17"
    )
  )

lazy val root = (project in file("."))
  .settings(
    name := "soil-companion"
  )
