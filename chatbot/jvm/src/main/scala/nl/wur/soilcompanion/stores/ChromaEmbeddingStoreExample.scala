package nl.wur.soilcompanion.stores

import dev.langchain4j.data.segment.TextSegment
import dev.langchain4j.internal.Utils.randomUUID
import dev.langchain4j.model.embedding.EmbeddingModel
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel
import dev.langchain4j.store.embedding.{EmbeddingMatch, EmbeddingSearchRequest, EmbeddingStore}
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion.V2
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore
import org.testcontainers.chromadb.ChromaDBContainer

import scala.jdk.CollectionConverters.*

object ChromaEmbeddingStoreExample:

  def main(args: Array[String]): Unit =
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
      println("\nChromaDB stopped")
