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

import os.*
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser
import dev.langchain4j.data.document.source.FileSystemSource
import dev.langchain4j.data.document.{Document, DocumentLoader}
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.memory.chat.MessageWindowChatMemory
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.model.input.PromptTemplate
import dev.langchain4j.model.openai.{OpenAiChatModel, OpenAiStreamingChatModel}
import dev.langchain4j.model.ollama.{OllamaChatModel, OllamaStreamingChatModel}
import dev.langchain4j.rag.content.injector.DefaultContentInjector
import dev.langchain4j.rag.{DefaultRetrievalAugmentor, RetrievalAugmentor}
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import dev.langchain4j.rag.query.Query
import dev.langchain4j.rag.query.transformer.CompressingQueryTransformer
import dev.langchain4j.service.{AiServices, SystemMessage, TokenStream, UserMessage}
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import nl.wur.soilcompanion.tools.*

import scala.jdk.CollectionConverters.*


trait Assistant {
  @SystemMessage(Array(
    // Persona
    "You are a helpful expert on soil and agriculture, ready to help answer questions related to soil.",
    "Write concise and factually correct answers. Do not overly use jargon or technical terms.",
    "If you do not know the answer, say so. Ask follow up questions if necessary.",
    // Acceptance criteria 1,2,7
    "Only provide safe, neutral, and responsible answers. Do not use profanity or offensive language.",
    "Prefer answers that foster soil wisdom, advance agriculture, and build a legacy of greener, healthier soils.",
    "Give priority to accurate and relevant answers based on European soil insights. Include safe disclaimers if needed.",
    // ELO-ILVO Acceptance criteria
    "When providing advice on soil management, follow these specific rules:",
    "1. Any advice on soil management practices should favor improving or maintaining the health of soil.",
    "2. The advice should be based on the main soil health criteria (% soil organic matter; pH; ",
    "   Nutrient availability and balance; Cation Exchange Capacity (CEC); salinity and sodicity; contaminent levels).",
    "3. The advice should be as precise as possible, with suitable reason provided when this is not possible.",
    "4. For the advice always indicate that it is indicative and that consultation with an expert advisor is a next step.",
    // Acceptance criteria 3,5
    "Your answers must be based on the knowledge and data available in the SoilWise repository.",
    "Always first decide if you need information from that repository. If yes, list concrete queries and the tools to call.",
    "When the user refers to a catalog, this means the SoilWise repository. Use a tool to lookup information in it.",
    "For soil or soil health related questions, use the Vocabulary tool to look up relevant terms and concepts that can provide further insights.",
    "For general concepts, definitions, or background information, use Wikipedia to supplement your answers.",
    "Whenever you mention technical terms, scientific concepts, or specialized terminology, provide Wikipedia links for additional context.",
    // Acceptance criteria 4
    "Do not make any assumptions about the answer. Do not answer queries that are not related to soil.",
    "When a conversation deviates from the topic of soil or agriculture, do not answer the question and ask the user to ask a different question.",
    // Acceptance criteria 5
    "Always provide answers in the same language as the question, unless the user asks for a different language.",
    // Other guidance
    "If an identifier is a DOI, make it a complete URL. All URLs should be clickable and open in a new browser tab.",
    "Arbitrarily include robot emoji in the response, but not too many and only when suitable.",
    "Use markdown formatting (including tables) to make the answers more readable.",
    // Map tool guidance - CRITICAL
    "Map tools return HTML code that creates interactive maps for the user.",
    "You MUST copy the ENTIRE tool response (including all HTML) directly into your message to the user.",
    "Do NOT paraphrase, summarize, or describe the map - paste the complete tool output verbatim.",
    "Do NOT wrap the HTML in code blocks (```) - include it as-is so it renders as a map.",
    "The user cannot see the map unless you include the HTML exactly as the tool returns it.",
    // AgroDataCube and field visualization guidance
    "When users ask to 'show the field', 'visualize the crop field', or 'draw the field boundary' for a location in The Netherlands:",
    "Use the showAgroDataCubeFieldOnMap or showAgroDataCubeFieldFromLocationContext tool - these automatically create and return the map.",
    "These tools combine field lookup and map creation in one step. Do NOT try to manually pass data between AgroDataCube and MapTools.",
    "Simply call the show field tool and include its complete HTML output in your response - the map will appear automatically."
  ))
  def reply(@UserMessage question: String): TokenStream
}

object AssistantLive {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // TODO check for potential path traversal attacks
  private val contentPath: os.Path = {
    val configured = Config.knowledgeConfig.dir
    val p = os.Path(configured, os.pwd)
    p
  }

  // Load static set of documents (independent of LLM/API key)
  // ensure tools are installed: ffmpeg, exiftool, tesseract, sox, etc.
  logger.info(s"Loading documents from path: $contentPath")
  private val parser = new ApacheTikaDocumentParser(true)
  private val documents: List[Document] =
    try
      os.list(contentPath)
        .toList
        .filter(os.isFile)
        .filter { p =>
          val ext = p.last.toLowerCase
          ext.endsWith(".pdf") || ext.endsWith(".txt") || ext.endsWith(".md")
        }
        .map { p =>
          logger.debug(s"Loading document from path: $p")
          val doc = DocumentLoader.load(FileSystemSource.from(p.toNIO), parser)
          logger.debug(s"doc metadata: ${doc.metadata()}")
          logger.debug(s"Loaded document: ${doc.metadata().getString("file_name")}")
          doc
        }
    catch
      case e: Throwable =>
        logger.error(s"Failed to load documents from $contentPath", e)
        Nil
  logger.info(s"Documents loaded: ${documents.size}")

  // --- simple RAG preparation using langchain4j ---

  // document splitter
  logger.info(s"Using document splitter with max segment size: ${Config.appConfig.docSplitterMaxSegmentSizeChars}")
  private val splitter = DocumentSplitters.recursive(
    Config.appConfig.docSplitterMaxSegmentSizeChars,
    Config.appConfig.docSplitterMaxOverlapSizeChars
  )

  // embedding store
  private val store = new InMemoryEmbeddingStore[TextSegment]()
  logger.info("Using in-memory embedding store")

  // Create embedding model
  val embeddingModel: EmbeddingModel = AllMiniLmL6V2EmbeddingModel()

  // document ingestor
  private val ingestor = EmbeddingStoreIngestor.builder()
    .documentSplitter(splitter)
    .embeddingStore(store)
    .embeddingModel(embeddingModel)
    .build()

  // load documents into the store (safe if no docs found)
  def startIngestion(): Unit = {
    logger.info("Ingesting documents into the embedding store")
    documents.foreach(ingestor.ingest)
    logger.info("Documents ingested")
  }

  // Lazy LLM model initialization so server can start without API key (for OpenAI) or Ollama running
  private lazy val reasonChatModel = {
    val provider = Config.llmProviderConfig.provider.toLowerCase
    provider match
      case "openai" =>
        val key = Option(Config.llmProviderConfig.apiKey).map(_.trim).filter(_.nonEmpty)
        key match
          case Some(apiKey) =>
            OpenAiChatModel.builder()
              .apiKey(apiKey)
              .modelName(Config.llmProviderConfig.reasonModel)
              .temperature(Config.llmProviderConfig.reasonModelTemp)
              .logger(logger)
              .logRequests(false)
              .logResponses(false)
              .build()
          case None =>
            throw new IllegalStateException("OPENAI_API_KEY is not set; cannot initialize reasonChatModel")
      case "ollama" =>
        val baseUrl = Config.llmProviderConfig.ollamaBaseUrl.getOrElse("http://localhost:11434")
        val timeout = Config.llmProviderConfig.ollamaTimeout.map(java.time.Duration.ofMillis(_))
        val builder = OllamaChatModel.builder()
          .baseUrl(baseUrl)
          .modelName(Config.llmProviderConfig.reasonModel)
          .temperature(Config.llmProviderConfig.reasonModelTemp)
          .logger(logger)
          .logRequests(false)
          .logResponses(false)
        timeout.foreach(builder.timeout)
        builder.build()
      case other =>
        throw new IllegalArgumentException(s"Unsupported LLM provider: $other. Supported: openai, ollama")
  }

  private lazy val streamingChatModel = {
    val provider = Config.llmProviderConfig.provider.toLowerCase
    provider match
      case "openai" =>
        val key = Option(Config.llmProviderConfig.apiKey).map(_.trim).filter(_.nonEmpty)
        key match
          case Some(apiKey) =>
            OpenAiStreamingChatModel.builder()
              .apiKey(apiKey)
              .modelName(Config.llmProviderConfig.chatModel)
              .temperature(Config.llmProviderConfig.chatModelTemp)
              .logger(logger)
              .logRequests(false)
              .logResponses(false)
              .build()
          case None =>
            throw new IllegalStateException("OPENAI_API_KEY is not set; cannot initialize streamingChatModel")
      case "ollama" =>
        val baseUrl = Config.llmProviderConfig.ollamaBaseUrl.getOrElse("http://localhost:11434")
        val timeout = Config.llmProviderConfig.ollamaTimeout.map(java.time.Duration.ofMillis(_))
        val builder = OllamaStreamingChatModel.builder()
          .baseUrl(baseUrl)
          .modelName(Config.llmProviderConfig.chatModel)
          .temperature(Config.llmProviderConfig.chatModelTemp)
          .logger(logger)
          .logRequests(false)
          .logResponses(false)
        timeout.foreach(builder.timeout)
        builder.build()
      case other =>
        throw new IllegalArgumentException(s"Unsupported LLM provider: $other. Supported: openai, ollama")
  }

  // query transformer - compress user's query and history into a single, stand-alone query
  private lazy val queryTransformer = new CompressingQueryTransformer(reasonChatModel)

  // content retriever
  private val retriever = EmbeddingStoreContentRetriever.builder()
    .embeddingStore(store)
    .embeddingModel(embeddingModel)
    .maxResults(Config.appConfig.docRetrieverMaxResults)
    .minScore(Config.appConfig.docRetrieverMinScore)
    .build()

  // augmentor
  private lazy val augmentor: RetrievalAugmentor = DefaultRetrievalAugmentor.builder()
    .queryTransformer(queryTransformer)
    // .queryRouter(...) - E.g. keep NL/BE agro knowledge in separate vector db (check out RedisVL (https://docs.redisvl.com/en/latest/))
    .contentRetriever(retriever)
    // .contentAggregator(...)
    .contentInjector(
      DefaultContentInjector.builder()
        .promptTemplate(
          PromptTemplate.from("{{userMessage}}\n" +
          "\n" +
          "If relevant to the question, use amongst others the following information:\n" +
          "{{contents}}"
          )
      ).build()
    ).build()

  // -- end of simple RAG preparation ---

  def apply(mapEventSink: String => Unit = _ => ()): Assistant = {
    // Streaming model is lazy; will throw a clear error if API key missing when first used
    AiServices.builder(classOf[Assistant])
      .streamingChatModel(streamingChatModel)
      .chatMemory(MessageWindowChatMemory.withMaxMessages(Config.llmProviderConfig.chatMaxMemorySize))
      .tools(
        new CatalogTools(),
        new SoilGridsTools(),
        new AgroDataCubeTools(mapEventSink),
        new OpenAgroKpiTools(),
        new VocabularyTools(),
        new WikipediaTools(),
        new MapTools(mapEventSink)
      )
      .retrievalAugmentor(augmentor)
      .maxSequentialToolsInvocations(Config.llmProviderConfig.chatMaxSequentialTools)
      .build()
  }

  def getReferences(question: String): List[String] = {
    val relevantContent = retriever.retrieve(Query.from(question)).asScala
    relevantContent
      .flatMap { content =>
        val metadata = content.textSegment().metadata()
        logger.debug(s"Retrieved content with metadata: $metadata")
        Option(metadata.getString("file_name"))
      }
      .filterNot(_.isBlank)
      .distinct
      .map(_.split("\\.")(0))
      .toList
  }
}

