package nl.wur.soilcompanion

import scala.util.matching.Regex

/**
 * Utility for automatically linking technical terms to Wikipedia articles.
 *
 * This class processes text responses and identifies potential technical terms
 * that could benefit from Wikipedia links, then verifies and adds those links.
 * Supports multiple languages: English, Dutch, French, Spanish, Italian, Czech.
 */
object WikipediaLinker {
  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  // Language codes and their Wikipedia base URLs
  private val languageWikipediaUrls = Map(
    "en" -> "https://en.wikipedia.org",
    "nl" -> "https://nl.wikipedia.org",
    "fr" -> "https://fr.wikipedia.org",
    "es" -> "https://es.wikipedia.org",
    "it" -> "https://it.wikipedia.org",
    "cs" -> "https://cs.wikipedia.org"
  )

  // Common technical terms related to soil and agriculture by language
  private val technicalTermsByLanguage = Map(
    "en" -> Set(
      "soil", "organic matter", "humus", "carbon", "nitrogen", "phosphorus", "potassium",
      "pH", "cation exchange capacity", "CEC", "erosion", "compaction", "aggregation",
      "fertility", "nutrient", "microorganism", "bacteria", "fungi", "mycorrhiza",
      "composting", "mulch", "tillage", "no-till", "cover crop", "crop rotation",
      "biodiversity", "ecosystem", "photosynthesis", "respiration", "decomposition",
      "mineralization", "nitrification", "denitrification", "leaching", "runoff",
      "clay", "sand", "silt", "loam", "texture", "structure", "porosity", "permeability",
      "water retention", "field capacity", "wilting point", "drainage", "irrigation",
      "salinity", "sodicity", "alkalinity", "acidity", "buffering capacity",
      "organic farming", "sustainable agriculture", "agroecology", "permaculture",
      "monoculture", "polyculture", "intercropping", "agroforestry",
      "pesticide", "herbicide", "fungicide", "insecticide", "fertilizer",
      "amendment", "biochar", "vermicompost", "green manure", "biofertilizer"
    ),
    "nl" -> Set(
      "bodem", "organische stof", "humus", "koolstof", "stikstof", "fosfor", "kalium",
      "pH", "kationenuitwisselingscapaciteit", "erosie", "verdichting", "aggregatie",
      "vruchtbaarheid", "voedingsstof", "micro-organisme", "bacteriën", "schimmels",
      "compostering", "mulch", "grondbewerking", "gewasrotatie", "bodembedekker",
      "biodiversiteit", "ecosysteem", "fotosynthese", "respiratie", "ontbinding",
      "mineralisatie", "nitrificatie", "denitrificatie", "uitspoeling", "afstroom",
      "klei", "zand", "silt", "leem", "textuur", "structuur", "porositeit", "doorlatendheid",
      "waterretentie", "veldcapaciteit", "verwelkingspunt", "drainage", "irrigatie",
      "zoutgehalte", "alkaliniteit", "zuurgraad", "bufferend vermogen",
      "biologische landbouw", "duurzame landbouw", "agroecologie", "permacultuur",
      "monocultuur", "polycultuur", "mengteelt", "agroforestry",
      "pesticiden", "herbicide", "fungicide", "insecticide", "meststof"
    ),
    "fr" -> Set(
      "sol", "matière organique", "humus", "carbone", "azote", "phosphore", "potassium",
      "pH", "capacité d'échange cationique", "érosion", "compactage", "agrégation",
      "fertilité", "nutriment", "micro-organisme", "bactéries", "champignons",
      "compostage", "paillis", "travail du sol", "rotation des cultures", "culture de couverture",
      "biodiversité", "écosystème", "photosynthèse", "respiration", "décomposition",
      "minéralisation", "nitrification", "dénitrification", "lessivage", "ruissellement",
      "argile", "sable", "limon", "texture", "structure", "porosité", "perméabilité",
      "rétention d'eau", "capacité au champ", "point de flétrissement", "drainage", "irrigation",
      "salinité", "alcalinité", "acidité", "capacité tampon",
      "agriculture biologique", "agriculture durable", "agroécologie", "permaculture",
      "monoculture", "polyculture", "culture intercalaire", "agroforesterie",
      "pesticide", "herbicide", "fongicide", "insecticide", "engrais"
    ),
    "es" -> Set(
      "suelo", "materia orgánica", "humus", "carbono", "nitrógeno", "fósforo", "potasio",
      "pH", "capacidad de intercambio catiónico", "erosión", "compactación", "agregación",
      "fertilidad", "nutriente", "microorganismo", "bacterias", "hongos",
      "compostaje", "mantillo", "labranza", "rotación de cultivos", "cultivo de cobertura",
      "biodiversidad", "ecosistema", "fotosíntesis", "respiración", "descomposición",
      "mineralización", "nitrificación", "desnitrificación", "lixiviación", "escorrentía",
      "arcilla", "arena", "limo", "textura", "estructura", "porosidad", "permeabilidad",
      "retención de agua", "capacidad de campo", "punto de marchitez", "drenaje", "riego",
      "salinidad", "alcalinidad", "acidez", "capacidad tampón",
      "agricultura orgánica", "agricultura sostenible", "agroecología", "permacultura",
      "monocultivo", "policultivo", "cultivo intercalado", "agroforestería",
      "pesticida", "herbicida", "fungicida", "insecticida", "fertilizante"
    ),
    "it" -> Set(
      "suolo", "materia organica", "humus", "carbonio", "azoto", "fosforo", "potassio",
      "pH", "capacità di scambio cationico", "erosione", "compattazione", "aggregazione",
      "fertilità", "nutriente", "microrganismo", "batteri", "funghi",
      "compostaggio", "pacciamatura", "lavorazione del suolo", "rotazione delle colture", "coltura di copertura",
      "biodiversità", "ecosistema", "fotosintesi", "respirazione", "decomposizione",
      "mineralizzazione", "nitrificazione", "denitrificazione", "lisciviazione", "ruscellamento",
      "argilla", "sabbia", "limo", "tessitura", "struttura", "porosità", "permeabilità",
      "ritenzione idrica", "capacità di campo", "punto di appassimento", "drenaggio", "irrigazione",
      "salinità", "alcalinità", "acidità", "capacità tampone",
      "agricoltura biologica", "agricoltura sostenibile", "agroecologia", "permacultura",
      "monocultura", "policoltura", "coltura intercalare", "agroforestazione",
      "pesticida", "erbicida", "fungicida", "insetticida", "fertilizzante"
    ),
    "cs" -> Set(
      "půda", "organická hmota", "humus", "uhlík", "dusík", "fosfor", "draslík",
      "pH", "kationtová výměnná kapacita", "eroze", "zhutňování", "agregace",
      "úrodnost", "živina", "mikroorganismus", "bakterie", "houby",
      "kompostování", "mulčování", "zpracování půdy", "střídání pěstovaných plodin", "meziplodina",
      "biodiverzita", "ekosystém", "fotosyntéza", "respirace", "rozklad",
      "mineralizace", "nitrifikace", "denitrifikace", "vymývání", "odtok",
      "jíl", "písek", "prachovina", "textura", "struktura", "pórovitost", "propustnost",
      "retence vody", "polní vodní kapacita", "bod vadnutí", "drenáž", "zavlažování",
      "salinita", "alkalinita", "kyselost", "pufrovací kapacita",
      "ekologické zemědělství", "udržitelné zemědělství", "agroekologie", "permakultivace",
      "monokultura", "polykultura", "směsné pěstování", "agrolesokultura",
      "pesticid", "herbicid", "fungicid", "insekticid", "hnojivo"
    )
  )

  // Regex to find capitalized technical terms or phrases (potential technical concepts)
  // Only match terms that are at least 2 words or start at beginning of sentence
  private val capitalizedTermPattern: Regex = """(?:^|[.!?]\s+)([A-Z][a-z]+(?:\s+[A-Z][a-z]+)+)(?![a-zA-Z])""".r

  // Common words that should never be linked, by language
  private val excludedWordsByLanguage = Map(
    "en" -> Set(
      "The", "A", "An", "In", "On", "At", "To", "For", "By", "With", "Of", "From",
      "This", "That", "These", "Those", "It", "Its", "Their", "Our", "Your",
      "However", "Therefore", "Moreover", "Furthermore", "Additionally", "Specifically",
      "Generally", "Similarly", "Consequently", "Nevertheless", "Nonetheless",
      "First", "Second", "Third", "Finally", "Last", "Next",
      "Common", "Typical", "General", "Specific", "Important", "Main", "Key", "Major",
      "Basic", "Essential", "Fundamental", "Primary", "Secondary",
      "Europe", "European", "France", "French", "Germany", "German", "Today", "Yesterday",
      "Spring", "Summer", "Autumn", "Winter", "January", "February", "March", "April",
      "May", "June", "July", "August", "September", "October", "November", "December"
    ),
    "nl" -> Set(
      "De", "Het", "Een", "In", "Op", "Aan", "Voor", "Door", "Met", "Van", "Naar",
      "Dit", "Dat", "Deze", "Die", "Het", "Zijn", "Hun", "Ons", "Jullie",
      "Echter", "Daarom", "Bovendien", "Daarbij", "Specifiek", "Over", "Algemeen",
      "Eerst", "Tweede", "Derde", "Tenslotte", "Laatste", "Volgende",
      "Algemeen", "Typisch", "Specifiek", "Belangrijk", "Hoofd", "Basis",
      "Europa", "Europees", "Frankrijk", "Frans", "Duitsland", "Duits", "Vandaag", "Gisteren",
      "Lente", "Zomer", "Herfst", "Winter", "Januari", "Februari", "Maart", "April",
      "Mei", "Juni", "Juli", "Augustus", "September", "Oktober", "November", "December"
    ),
    "fr" -> Set(
      "Le", "La", "Les", "Un", "Une", "Dans", "Sur", "À", "Pour", "Par", "Avec", "De", "Du",
      "Ce", "Cette", "Ces", "Cela", "Il", "Elle", "Leur", "Notre", "Votre",
      "Cependant", "Par conséquent", "De plus", "En outre", "Spécifiquement", "Généralement",
      "Premier", "Deuxième", "Troisième", "Finalement", "Dernier", "Suivant",
      "Commun", "Typique", "Général", "Spécifique", "Important", "Principal", "Clé",
      "Europe", "Européen", "France", "Français", "Allemagne", "Allemand", "Aujourd'hui", "Hier",
      "Printemps", "Été", "Automne", "Hiver", "Janvier", "Février", "Mars", "Avril",
      "Mai", "Juin", "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre"
    ),
    "es" -> Set(
      "El", "La", "Los", "Las", "Un", "Una", "En", "Sobre", "A", "Para", "Por", "Con", "De", "Del",
      "Este", "Esta", "Estos", "Estas", "Eso", "Su", "Sus", "Nuestro", "Vuestro",
      "Sin embargo", "Por lo tanto", "Además", "Específicamente", "Generalmente",
      "Primero", "Segundo", "Tercero", "Finalmente", "Último", "Siguiente",
      "Común", "Típico", "General", "Específico", "Importante", "Principal", "Clave",
      "Europa", "Europeo", "Francia", "Francés", "Alemania", "Alemán", "Hoy", "Ayer",
      "Primavera", "Verano", "Otoño", "Invierno", "Enero", "Febrero", "Marzo", "Abril",
      "Mayo", "Junio", "Julio", "Agosto", "Septiembre", "Octubre", "Noviembre", "Diciembre"
    ),
    "it" -> Set(
      "Il", "La", "I", "Le", "Un", "Una", "In", "Su", "A", "Per", "Da", "Con", "Di", "Del",
      "Questo", "Questa", "Questi", "Queste", "Quello", "Suo", "Loro", "Nostro", "Vostro",
      "Tuttavia", "Pertanto", "Inoltre", "Specificamente", "Generalmente",
      "Primo", "Secondo", "Terzo", "Finalmente", "Ultimo", "Prossimo",
      "Comune", "Tipico", "Generale", "Specifico", "Importante", "Principale", "Chiave",
      "Europa", "Europeo", "Francia", "Francese", "Germania", "Tedesco", "Oggi", "Ieri",
      "Primavera", "Estate", "Autunno", "Inverno", "Gennaio", "Febbraio", "Marzo", "Aprile",
      "Maggio", "Giugno", "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
    ),
    "cs" -> Set(
      "Ten", "Ta", "To", "V", "Na", "K", "Pro", "S", "Z", "Od",
      "Tento", "Tato", "Toto", "Jeho", "Jejich", "Náš", "Váš",
      "Však", "Proto", "Navíc", "Konkrétně", "Obecně",
      "První", "Druhý", "Třetí", "Nakonec", "Poslední", "Další",
      "Společný", "Typický", "Obecný", "Specifický", "Důležitý", "Hlavní", "Klíčový",
      "Evropa", "Evropský", "Francie", "Francouzský", "Německo", "Německý", "Dnes", "Včera",
      "Jaro", "Léto", "Podzim", "Zima", "Leden", "Únor", "Březen", "Duben",
      "Květen", "Červen", "Červenec", "Srpen", "Září", "Říjen", "Listopad", "Prosinec"
    )
  )

  // Common words for language detection (most frequent words per language)
  private val languageDetectionWords = Map(
    "en" -> Set("the", "is", "are", "and", "or", "in", "on", "at", "to", "for", "of", "with", "this", "that", "from"),
    "nl" -> Set("de", "het", "een", "is", "zijn", "en", "of", "in", "op", "aan", "voor", "van", "met", "dit", "dat"),
    "fr" -> Set("le", "la", "les", "est", "sont", "et", "ou", "dans", "sur", "à", "pour", "de", "avec", "ce", "cette"),
    "es" -> Set("el", "la", "los", "las", "es", "son", "y", "o", "en", "sobre", "a", "para", "de", "con", "este", "esta"),
    "it" -> Set("il", "la", "i", "le", "è", "sono", "e", "o", "in", "su", "a", "per", "di", "con", "questo", "questa"),
    "cs" -> Set("je", "jsou", "a", "nebo", "v", "na", "k", "pro", "s", "z", "tento", "tato", "toto")
  )

  /**
   * Detect the language of the text based on common words.
   *
   * @param text The text to analyze
   * @return Language code (en, nl, fr, es, it, cs), defaults to "en"
   */
  private def detectLanguage(text: String): String = {
    val lowerText = text.toLowerCase
    val words = lowerText.split("\\s+").take(200) // Analyze first 200 words for efficiency

    // Count matches for each language
    val scores = languageDetectionWords.map { case (lang, commonWords) =>
      val matchCount = words.count(w => commonWords.contains(w))
      (lang, matchCount)
    }

    // Return language with highest score, default to English
    val detected = scores.maxByOption(_._2).map(_._1).getOrElse("en")
    logger.debug(s"Detected language: $detected (scores: ${scores.mkString(", ")})")
    detected
  }

  /**
   * Check if a position in the text is inside a markdown link.
   * A markdown link has the format: [text](url)
   */
  private def isInsideMarkdownLink(text: String, position: Int): Boolean = {
    // Find the nearest [ before the position
    val openBracket = text.lastIndexOf('[', position - 1)
    if (openBracket == -1) return false

    // Find the nearest ] after the open bracket
    val closeBracket = text.indexOf(']', openBracket)
    if (closeBracket == -1 || closeBracket < position) return false

    // Check if there's a ( immediately after the ]
    if (closeBracket + 1 < text.length && text.charAt(closeBracket + 1) == '(') {
      // Find the closing )
      val closeParen = text.indexOf(')', closeBracket + 2)
      if (closeParen != -1) {
        // Position is inside a markdown link if it's between [ and ]
        return position > openBracket && position < closeBracket
      }
    }

    false
  }

  /**
   * Process response text and add Wikipedia links to technical terms.
   * Only processes if auto-linking is enabled in config.
   * Detects language and uses appropriate Wikipedia edition.
   * Verifies that Wikipedia articles exist before adding links.
   *
   * @param text The response text to process
   * @return The text with Wikipedia links added, or original text if disabled
   */
  def addWikipediaLinks(text: String): String = {
    if (!Config.wikipediaConfig.autoLinkTerms) {
      return text
    }

    try {
      val minLength = Config.wikipediaConfig.minTermLength

      // Detect the language of the response
      val language = detectLanguage(text)
      val baseUrl = languageWikipediaUrls.getOrElse(language, Config.wikipediaConfig.baseUrl)

      logger.debug(s"Using Wikipedia edition: $baseUrl for language: $language")

      // Find all potential technical terms in the text
      val candidates = findCandidateTerms(text, minLength, language)

      if (candidates.isEmpty) {
        return text
      }

      logger.debug(s"Found ${candidates.size} candidate terms for Wikipedia linking: ${candidates.mkString(", ")}")

      // Verify which candidates have existing Wikipedia articles
      val verifiedTerms = candidates.filter { term =>
        val exists = wikipediaArticleExists(term, baseUrl)
        if (exists) {
          logger.debug(s"Verified Wikipedia article exists for: $term")
        } else {
          logger.debug(s"No Wikipedia article found for: $term")
        }
        exists
      }

      if (verifiedTerms.isEmpty) {
        logger.debug("No verified Wikipedia articles found for any candidate terms")
        return text
      }

      logger.debug(s"Adding links for ${verifiedTerms.size} verified terms: ${verifiedTerms.mkString(", ")}")

      // For each verified term, add a Wikipedia link
      var result = text
      verifiedTerms.foreach { term =>
        val articleTitle = term.replace(" ", "_")
        val wikiUrl = s"$baseUrl/wiki/$articleTitle"

        // Only link the first occurrence of each term to avoid clutter
        // Use a regex that matches the term as a whole word, and skip if already part of a markdown link
        val pattern = s"\\b${Regex.quote(term)}\\b".r

        // Find first match that's not already inside a markdown link
        val matches = pattern.findAllMatchIn(result).toList
        val firstValidMatch = matches.find { m =>
          !isInsideMarkdownLink(result, m.start)
        }

        firstValidMatch.foreach { m =>
          val matched = m.matched
          // Replace only the first occurrence with a markdown link, preserving original case
          val before = result.substring(0, m.start)
          val after = result.substring(m.end)
          result = before + s"[$matched]($wikiUrl)" + after
          logger.debug(s"Added Wikipedia link for term: $matched -> $wikiUrl")
        }
      }

      result
    } catch {
      case e: Throwable =>
        logger.error("Failed to add Wikipedia links to response", e)
        text // Return original text on error
    }
  }

  /**
   * Find candidate technical terms in the text that should be linked.
   *
   * @param text The text to analyze
   * @param minLength Minimum length for terms to consider
   * @param language The detected language code
   * @return Set of terms to link
   */
  private def findCandidateTerms(text: String, minLength: Int, language: String): Set[String] = {
    val lowerText = text.toLowerCase

    // Get language-specific term lists
    val technicalTerms = technicalTermsByLanguage.getOrElse(language, technicalTermsByLanguage("en"))
    val excludedWords = excludedWordsByLanguage.getOrElse(language, excludedWordsByLanguage("en"))

    // Find known technical terms that appear in the text
    val knownTerms = technicalTerms.filter { term =>
      term.length >= minLength && lowerText.contains(term.toLowerCase)
    }

    // Also look for capitalized multi-word phrases (like "Soil Organic Carbon")
    // which might be technical terms even if not in our predefined list
    val capitalizedTerms = capitalizedTermPattern.findAllMatchIn(text).map(_.group(1)).toSet
      .filter(_.length >= minLength)
      .filter { term =>
        // Filter out excluded words and patterns
        val words = term.split("\\s+")

        // Exclude if any word is in the excluded list
        val hasExcludedWord = words.exists(w => excludedWords.contains(w))

        // Exclude single-word capitalized terms (only keep multi-word phrases)
        val isSingleWord = words.length < 2

        // Exclude terms that are just location + preposition (e.g., "In France")
        val isLocationPattern = words.length == 2 && excludedWords.contains(words(0))

        !hasExcludedWord && !isSingleWord && !isLocationPattern
      }

    // Combine both sets, prioritize known terms, and limit to avoid too many links
    val combined = (knownTerms ++ capitalizedTerms).toList
      .sortBy { term =>
        // Sort by: known terms first, then longer terms (more specific)
        val isKnown = if (technicalTerms.contains(term.toLowerCase)) 0 else 1
        val lengthScore = -term.length
        (isKnown, lengthScore)
      }
      .take(5) // Reduced from 10 to 5 for less clutter
      .toSet

    combined
  }

  /**
   * Check if a Wikipedia article exists for the given term.
   * This is a lightweight check using the Wikipedia API.
   *
   * @param term The term to check
   * @param baseUrl The Wikipedia base URL for the appropriate language edition
   * @return true if an article exists, false otherwise
   */
  private def wikipediaArticleExists(term: String, baseUrl: String): Boolean = {
    try {
      val encoded = java.net.URLEncoder.encode(term, "UTF-8")
      val url = s"$baseUrl/w/api.php?action=query&titles=$encoded&format=json"

      val response = requests.get(
        url = url,
        headers = Map("User-Agent" -> Config.wikipediaConfig.userAgent),
        readTimeout = 2000, // Short timeout for quick check
        connectTimeout = 2000
      )

      if (response.statusCode == 200) {
        val json = ujson.read(response.text())
        val pages = json("query")("pages").obj
        // If the page exists, it won't have a "missing" field
        pages.values.forall(!_.obj.contains("missing"))
      } else {
        false
      }
    } catch {
      case e: Throwable =>
        logger.debug(s"Failed to check Wikipedia article existence for term: $term at $baseUrl", e)
        false
    }
  }
}
