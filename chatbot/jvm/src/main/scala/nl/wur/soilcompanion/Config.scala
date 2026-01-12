package nl.wur.soilcompanion

import upickle.default.*
import pureconfig.*
import pureconfig.generic.derivation.*
import upickle.ReadWriter


/**
 * The `Config` object contains configuration settings for the application.
 *
 * It provides case classes and methods to load and manage different configuration
 * aspects such as application details, LLM (Large Language Model) provider settings,
 * Solr server connection configurations, and Solr search settings.
 *
 * Configurations are loaded using the `ConfigSource` utility from a source (e.g.,
 * application configuration file), and corresponding case classes are used to represent
 * and deserialize the configuration data.
 */
object Config {

  case class AppConfig(
                        name: String,
                        version: String,
                        website: String,
                        // service
                        host: String,
                        port: Int,
                        // processing of static knowledge documents (core set)
                        docSplitterMaxSegmentSizeChars: Int,
                        docSplitterMaxOverlapSizeChars: Int,
                        docRetrieverMaxResults: Int,
                        docRetrieverMinScore: Double,
                        // processing of dynamic knowledge documents (catalog resources)
                        catalogRetrieverMaxResults: Int,
                        // chat settings
                        chatMaxMemorySize: Int,
                        chatMaxIdleTimeSec: Int,
                        chatMaxTokens: Int,
                        chatMaxRetries: Int,
                        chatMaxSequentialTools: Int,
                        // optional debug logging for AI responses
                        debugLogFinalAiResponse: Boolean,
                        // optional prefix applied to every line of the final AI response in logs
                        aiFinalLogPrefix: String,
                        // safety limits
                        chatMaxPromptChars: Int,
                        uploadMaxChars: Int,
                        // session expiration in minutes (-1 = no expiration)
                        sessionExpirationMinutes: Int
                      ) derives ConfigReader

  case class KnowledgeConfig(
                              dir: String
                            ) derives ConfigReader

  case class CatalogConfig(
                          baseUrl: String,
                          itemLinkBaseUrl: String,
                          // Configurable document types used across CatalogTools use-cases
                          // Defaults preserve existing behavior
                          datasetDocTypes: List[String] = List("dataset"),
                          knowledgeDocTypes: List[String] = List("journalpaper"),
                          knowledgeContentDocTypes: List[String] = List("journalpaper"),
                          itemContentDocTypes: List[String] = List("dataset", "journalpaper"),
                          // HTTP behavior for CatalogTools
                          timeoutMs: Int = 15000,
                          userAgent: String = "SoilCompanionBot/0.1 (+https://soilwise-he.eu)"
                          ) derives ConfigReader

  case class SoilGridsConfig(
                              baseUrl: String,
                              queryEndpoint: String,
                              defaultProperties: List[String],
                              defaultDepths: List[String],
                              defaultValueStat: String,
                              timeoutMs: Int,
                              userAgent: String,
                              usageWarning: String,
                              docsUrl: String,
                              termsUrl: String
                            ) derives ConfigReader

  case class OpenAgroConfig(
                             baseUrl: String,
                             accessToken: String,
                             defaultCountry: String,
                             timeoutMs: Int,
                             userAgent: String,
                             docsUrl: String,
                             allowUnauthenticated: Boolean,
                             // Optional curated list of supported fields-* layers to help the LLM when offline
                             knownLayers: List[String] = Nil
                           ) derives ConfigReader

  case class AgroDataCubeConfig(
                                 baseUrl: String,
                                 accessToken: String,
                                 timeoutMs: Int,
                                 userAgent: String,
                                 docsUrl: String
                               ) derives ConfigReader

  case class WikipediaConfig(
                              baseUrl: String,
                              defaultMaxResults: Int,
                              maxContentChars: Int,
                              timeoutMs: Int,
                              userAgent: String,
                              licenseUrl: String,
                              autoLinkTerms: Boolean,
                              minTermLength: Int
                            ) derives ConfigReader

  case class VocabConfig(
                          baseUrl: String,
                          vocabFilePath: String,
                          autoLinkTerms: Boolean,
                          minTermLength: Int,
                          maxLinksPerResponse: Int
                        ) derives ConfigReader

  case class VocabularyToolsConfig(
                                    sparqlEndpoint: String,
                                    connectTimeoutMs: Int,
                                    readTimeoutMs: Int,
                                    maxResults: Int,
                                    redirectUrlPattern: String,
                                    llmPrefix: String,
                                    actualPrefix: String,
                                    userAgent: String
                                  ) derives ConfigReader

  case class LlmProviderConfig(
                                name: String,
                                apiKey: String,
                                chatModel: String,
                                chatModelTemp: Double,
                                reasonModel: String,
                                reasonModelTemp: Double,
                                embeddingModel: String,
                                embeddingDim: Int
                              ) derives ConfigReader

  case class FeedbackLogConfig(
                                dir: String,
                                prefix: String
                              ) derives ConfigReader

  case class DemoUserConfig(
                             username: String,
                             password: String,
                             displayName: String
                           ) derives ConfigReader

  case class SolrServerConfig(
                               baseUrl: String,
                               username: String,
                               password: String
                             ) derives ConfigReader, ReadWriter

  case class SolrSearchConfig(
                               rows: Int,
                               mm: String,
                               df: String,
                               qAlt: String,
                               ps: Double,
                               fl: List[String],
                               qOp: String,
                               sort: String,
                               tie: Double,
                               defType: String,
                               qf: List[String],
                               pf: List[String]
                             ) derives ConfigReader, ReadWriter

  // general application configuration
  val appConfig: AppConfig =
    ConfigSource.default.at("app-config").loadOrThrow[AppConfig]

  // knowledge configuration
  val knowledgeConfig: KnowledgeConfig =
    ConfigSource.default.at("knowledge-config").loadOrThrow[KnowledgeConfig]

  // catalog configuration
  val catalogConfig: CatalogConfig =
    ConfigSource.default.at("catalog-config").loadOrThrow[CatalogConfig]

  // LLM provider configuration
  val llmProviderConfig: LlmProviderConfig =
    ConfigSource.default.at("llm-provider-config").loadOrThrow[LlmProviderConfig]

  // Feedback logging configuration
  val feedbackLogConfig: FeedbackLogConfig =
    ConfigSource.default.at("feedback-log-config").loadOrThrow[FeedbackLogConfig]

  // Demo user configuration
  val demoUser: DemoUserConfig =
    ConfigSource.default.at("demo-user").loadOrThrow[DemoUserConfig]

  // Solr search server configuration
  val searchServerConfig: SolrServerConfig =
    ConfigSource.default.at("solr-config-server").loadOrThrow[SolrServerConfig]

  // Solr search configuration for basic search
  val basicSearchConfig: SolrSearchConfig =
    ConfigSource.default.at("solr-search-basic").loadOrThrow[SolrSearchConfig]

  // Solr search configuration for content search
  val contentSearchConfig: SolrSearchConfig =
    ConfigSource.default.at("solr-search-content").loadOrThrow[SolrSearchConfig]

  // Solr search configuration to retrieve content by identifier
  val retrieveContentConfig: SolrSearchConfig =
    ConfigSource.default.at("solr-retrieve-content").loadOrThrow[SolrSearchConfig]

  // SoilGrids configuration
  val soilGridsConfig: SoilGridsConfig =
    ConfigSource.default.at("soilgrids-config").loadOrThrow[SoilGridsConfig]

  // OpenAgroKPI configuration
  val openAgroConfig: OpenAgroConfig =
    ConfigSource.default.at("openagro-config").loadOrThrow[OpenAgroConfig]

  // AgroDataCube configuration
  val agroDataCubeConfig: AgroDataCubeConfig =
    ConfigSource.default.at("agrodatacube-config").loadOrThrow[AgroDataCubeConfig]

  // Wikipedia configuration
  val wikipediaConfig: WikipediaConfig =
    ConfigSource.default.at("wikipedia-config").loadOrThrow[WikipediaConfig]

  // Vocabulary configuration
  val vocabConfig: VocabConfig =
    ConfigSource.default.at("vocab-config").loadOrThrow[VocabConfig]

  // Vocabulary Tools configuration
  val vocabularyToolsConfig: VocabularyToolsConfig =
    ConfigSource.default.at("vocabulary-tools-config").loadOrThrow[VocabularyToolsConfig]
}
