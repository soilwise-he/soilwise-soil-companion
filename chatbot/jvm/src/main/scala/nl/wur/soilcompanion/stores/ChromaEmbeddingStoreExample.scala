package nl.wur.soilcompanion.stores

import os.*
import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.internal.Utils.randomUUID
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever
import dev.langchain4j.rag.query.Query
import dev.langchain4j.store.embedding.{EmbeddingMatch, EmbeddingSearchRequest, EmbeddingStore}
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion.V2
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore
import org.testcontainers.chromadb.ChromaDBContainer

import scala.jdk.CollectionConverters.*

object ChromaEmbeddingStoreExample:

  def main(args: Array[String]): Unit =
    // Example 1: Simple embedding store with manual segments
    simpleExample()

    // Example 2: Using DocumentLoader to load and ingest documents
    documentLoaderExample()

  /**
   * Simple example: manually adding text segments to ChromaDB
   */
  def simpleExample(): Unit =
    println("=== Simple Example: Manual Segments ===")

    // Start ChromaDB using TestContainers
    val chroma = ChromaDBContainer("chromadb/chroma:1.1.0")
      .withExposedPorts(8000)

    try
      chroma.start()
      println(s"ChromaDB started at: ${chroma.getEndpoint}")

      // Create embedding store
      val embeddingStore: EmbeddingStore[TextSegment] = ChromaEmbeddingStore.builder()
        .apiVersion(V2)
        .baseUrl(chroma.getEndpoint)
        .collectionName(randomUUID())
        .logRequests(true)
        .logResponses(true)
        .build()

      // Create embedding model
      val embeddingModel: EmbeddingModel = AllMiniLmL6V2EmbeddingModel()

      // Add first segment
      val segment1 = TextSegment.from("I like football.")
      val embedding1 = embeddingModel.embed(segment1).content()
      embeddingStore.add(embedding1, segment1)

      // Add second segment
      val segment2 = TextSegment.from("The weather is good today.")
      val embedding2 = embeddingModel.embed(segment2).content()
      embeddingStore.add(embedding2, segment2)

      // Search for relevant embeddings
      val queryEmbedding = embeddingModel.embed("What is your favourite sport?").content()
      val searchRequest = EmbeddingSearchRequest.builder()
        .queryEmbedding(queryEmbedding)
        .maxResults(1)
        .build()

      val relevant: List[EmbeddingMatch[TextSegment]] =
        embeddingStore.search(searchRequest).matches().asScala.toList

      println("\nSearch Results:")
      relevant.foreach { match_ =>
        println(s"  Score: ${match_.score()}")
        println(s"  Embedded: ${match_.embedded().text()}")
      }

    finally
      chroma.stop()
      println("ChromaDB stopped\n")

  /**
   * Document loader example: using DocumentLoader to load and ingest documents
   */
  def documentLoaderExample(): Unit =
    println("=== Document Loader Example ===")

    // Find project root (go up from pwd until we find build.sbt)
    def findProjectRoot(current: os.Path): Option[os.Path] =
      if (os.exists(current / "build.sbt")) Some(current)
      else if (current == current / os.up) None
      else findProjectRoot(current / os.up)

    val projectRoot = findProjectRoot(os.pwd).getOrElse(os.pwd)
    val testDocsPath = projectRoot / "data" / "knowledge"

    if (!os.exists(testDocsPath))
      println(s"Skipping document loader example - path does not exist: $testDocsPath")
      return

    // Create document loader
    val loader = DocumentLoader(
      contentPath = testDocsPath,
      maxSegmentSizeChars = 500,
      maxOverlapSizeChars = 50
    )

    // Load documents
    val documents = loader.loadDocuments()
    if (documents.isEmpty)
      println("No documents found to load")
      return

    println(s"Loaded ${documents.size} documents")

    // Ingest to ChromaDB
    val (store, containerOpt) = loader.ingestToChroma(
      documents = documents,
      logRequests = true,
      logResponses = true
    )

    try
      // Create embedding model for retrieval (must match the one used for ingestion)
      val embeddingModel = AllMiniLmL6V2EmbeddingModel()

      // Create a content retriever
      val retriever = EmbeddingStoreContentRetriever.builder()
        .embeddingStore(store)
        .embeddingModel(embeddingModel)
        .maxResults(3)
        .minScore(0.5)
        .build()

      // Search for relevant content
      val query = "What is soil health?"
      println(s"\nSearching for: '$query'")

      val results = retriever.retrieve(Query.from(query)).asScala.toList
      println(s"Found ${results.size} relevant segments:")
      results.foreach { content =>
        val segment = content.textSegment()
        println(s"\n  Text: ${segment.text().take(100)}...")
        val metadata = segment.metadata()
        if (metadata != null && metadata.getString("file_name") != null)
          println(s"  Source: ${metadata.getString("file_name")}")
      }

    finally
      // Stop container if it was started
      containerOpt.foreach { container =>
        container.stop()
        println("\nChromaDB container stopped")
      }
