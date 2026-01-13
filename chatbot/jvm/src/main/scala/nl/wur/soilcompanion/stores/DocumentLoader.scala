package nl.wur.soilcompanion.stores

import os.*
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser
import dev.langchain4j.data.document.source.FileSystemSource
import dev.langchain4j.data.document.{Document, DocumentSplitter, DocumentLoader as JDocumentLoader}
import dev.langchain4j.data.document.splitter.DocumentSplitters
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.{EmbeddingStore, EmbeddingStoreIngestor}
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion
import dev.langchain4j.internal.Utils.randomUUID
import org.testcontainers.chromadb.ChromaDBContainer

/**
 * DocumentLoader provides functionality for loading documents from a directory
 * and ingesting them into various embedding stores (InMemory or ChromaDB).
 */
class DocumentLoader(
  val contentPath: os.Path,
  val maxSegmentSizeChars: Int = 1000,
  val maxOverlapSizeChars: Int = 200,
  val embeddingModel: EmbeddingModel = new AllMiniLmL6V2EmbeddingModel()
):
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // Document parser with Tika for various formats
  private val parser = new ApacheTikaDocumentParser(true)

  // Document splitter for chunking
  private val splitter: DocumentSplitter = DocumentSplitters.recursive(
    maxSegmentSizeChars,
    maxOverlapSizeChars
  )

  /**
   * Loads documents from the content path.
   * Supports PDF, TXT, and MD files.
   *
   * @return List of loaded documents
   */
  def loadDocuments(): List[Document] =
    logger.info(s"Loading documents from path: $contentPath")
    try
      val docs = os.list(contentPath)
        .toList
        .filter(os.isFile)
        .filter { p =>
          val ext = p.last.toLowerCase
          ext.endsWith(".pdf") || ext.endsWith(".txt") || ext.endsWith(".md")
        }
        .map { p =>
          logger.debug(s"Loading document from path: $p")
          val doc = JDocumentLoader.load(FileSystemSource.from(p.toNIO), parser)
          logger.debug(s"doc metadata: ${doc.metadata()}")
          logger.debug(s"Loaded document: ${doc.metadata().getString("file_name")}")
          doc
        }
      logger.info(s"Documents loaded: ${docs.size}")
      docs
    catch
      case e: Throwable =>
        logger.error(s"Failed to load documents from $contentPath", e)
        Nil

  /**
   * Creates an in-memory embedding store and ingests documents into it.
   *
   * @param documents Documents to ingest
   * @return The populated InMemoryEmbeddingStore
   */
  def ingestToInMemory(documents: List[Document]): InMemoryEmbeddingStore[TextSegment] =
    logger.info("Creating in-memory embedding store")
    val store = new InMemoryEmbeddingStore[TextSegment]()

    val ingestor = EmbeddingStoreIngestor.builder()
      .documentSplitter(splitter)
      .embeddingModel(embeddingModel)
      .embeddingStore(store)
      .build()

    logger.info("Ingesting documents into in-memory embedding store")
    documents.foreach(ingestor.ingest)
    logger.info("Documents ingested into in-memory store")

    store

  /**
   * Creates a ChromaDB embedding store and ingests documents into it.
   * Note: This method starts a ChromaDB container that must be stopped manually.
   *
   * @param documents Documents to ingest
   * @param chromaEndpoint Optional ChromaDB endpoint URL (starts container if not provided)
   * @param collectionName Optional collection name (generates random UUID if not provided)
   * @param logRequests Whether to log HTTP requests
   * @param logResponses Whether to log HTTP responses
   * @return Tuple of (ChromaEmbeddingStore, Optional ChromaDBContainer)
   */
  def ingestToChroma(
    documents: List[Document],
    chromaEndpoint: Option[String] = None,
    collectionName: Option[String] = None,
    logRequests: Boolean = false,
    logResponses: Boolean = false
  ): (ChromaEmbeddingStore, Option[ChromaDBContainer]) =

    // Start ChromaDB container if endpoint not provided
    val (endpoint, containerOpt) = chromaEndpoint match
      case Some(url) =>
        logger.info(s"Using existing ChromaDB endpoint: $url")
        (url, None)
      case None =>
        logger.info("Starting ChromaDB container")
        val container = new ChromaDBContainer("chromadb/chroma:1.1.0")
          .withExposedPorts(8000)
        container.start()
        val url = container.getEndpoint
        logger.info(s"ChromaDB container started at: $url")
        (url, Some(container))

    // Create ChromaDB embedding store
    val collection = collectionName.getOrElse(randomUUID())
    logger.info(s"Creating ChromaDB embedding store with collection: $collection")

    val store = ChromaEmbeddingStore.builder()
      .apiVersion(ChromaApiVersion.V2)
      .baseUrl(endpoint)
      .collectionName(collection)
      .logRequests(logRequests)
      .logResponses(logResponses)
      .build()

    // Create ingestor and ingest documents
    val ingestor = EmbeddingStoreIngestor.builder()
      .documentSplitter(splitter)
      .embeddingModel(embeddingModel)
      .embeddingStore(store)
      .build()

    logger.info("Ingesting documents into ChromaDB embedding store")
    documents.foreach(ingestor.ingest)
    logger.info("Documents ingested into ChromaDB store")

    (store, containerOpt)

object DocumentLoader:

  /**
   * Creates a DocumentLoader with specified parameters.
   *
   * @param contentPath Path to directory containing documents
   * @param maxSegmentSizeChars Maximum size of document segments
   * @param maxOverlapSizeChars Maximum overlap between segments
   * @param embeddingModel Embedding model to use for vectorization
   * @return DocumentLoader instance
   */
  def apply(
    contentPath: os.Path,
    maxSegmentSizeChars: Int = 1000,
    maxOverlapSizeChars: Int = 200,
    embeddingModel: EmbeddingModel = new AllMiniLmL6V2EmbeddingModel()
  ): DocumentLoader =
    new DocumentLoader(contentPath, maxSegmentSizeChars, maxOverlapSizeChars, embeddingModel)
