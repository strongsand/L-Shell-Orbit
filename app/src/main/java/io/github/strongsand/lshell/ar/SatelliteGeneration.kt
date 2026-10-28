package io.github.strongsand.lshell.ar

import org.json.JSONObject
import java.util.Locale

enum class SatelliteGeneration { UNKNOWN, V1, V1_5, V2_MINI, V2_MINI_D2C, V3 }

/** Identity formatting only. These functions never infer a satellite family. */
internal object SatelliteIdentity {
    private val digits = Regex("[0-9]+")
    private val designator = Regex("([0-9]{4})\\s*[-‐‑–—]?\\s*([0-9]{1,3})\\s*([A-Z]+)")
    private val launch = Regex("([0-9]{4})\\s*[-‐‑–—]\\s*([0-9]{1,3})")
    private val spaces = Regex("\\s+")

    fun normalizeNoradId(value: Any?): String? {
        val text = when (value) {
            is String -> value.trim()
            is Int, is Long -> value.toString()
            else -> return null
        }
        if (!digits.matches(text)) return null
        val number = text.trimStart('0').toLongOrNull() ?: return null
        return number.takeIf { it in 1..999_999_999 }?.toString()
    }

    fun normalizeInternationalDesignator(value: String?): String? {
        val match = designator.matchEntire(value?.trim()?.uppercase(Locale.ROOT) ?: return null) ?: return null
        val (year, number, piece) = match.destructured
        if (year.toInt() !in 1957..9999 || number.toInt() == 0) return null
        return "$year-${number.padStart(3, '0')}$piece"
    }

    fun normalizeLaunch(value: String?): String? {
        val match = launch.matchEntire(value?.trim() ?: return null) ?: return null
        val (year, number) = match.destructured
        if (year.toInt() !in 1957..9999 || number.toInt() == 0) return null
        return "$year-${number.padStart(3, '0')}"
    }

    fun normalizeObjectName(value: String): String = spaces.replace(value.trim().uppercase(Locale.ROOT), " ")
}

data class SatelliteCatalogMetadata(
    val catalogNumber: String?,
    val internationalDesignator: String?,
    val objectName: String,
    val feed: String
) {
    internal fun normalized() = copy(
        catalogNumber = SatelliteIdentity.normalizeNoradId(catalogNumber),
        internationalDesignator = SatelliteIdentity.normalizeInternationalDesignator(internationalDesignator),
        objectName = SatelliteIdentity.normalizeObjectName(objectName),
        feed = feed.trim().lowercase(Locale.ROOT)
    )
}

data class SatelliteClassification(
    val generation: SatelliteGeneration = SatelliteGeneration.UNKNOWN,
    val verified: Boolean = false,
    val sources: List<String> = emptyList()
)

internal data class GenerationRule(
    val generation: SatelliteGeneration,
    val launch: String? = null,
    val objects: Set<String> = emptySet(),
    val sources: List<String>,
    val noradIds: Set<String> = emptySet()
)

internal fun SatelliteClassification.matchesGenerationFilter(filter: SatelliteGeneration?): Boolean =
    filter == null || generation == filter

/** Sourced, exact identities first; weaker launch evidence cannot override an exact match. */
internal class SatelliteGenerationResolver(rules: List<GenerationRule>) {
    private val verifiedRules = rules.filter { it.sources.isNotEmpty() && it.generation != SatelliteGeneration.UNKNOWN }
    private fun index(keys: (GenerationRule) -> Collection<String>) = buildMap<String, MutableList<GenerationRule>> {
        verifiedRules.forEach { rule -> keys(rule).forEach { key -> getOrPut(key) { mutableListOf() }.add(rule) } }
    }
    private val byNorad = index { it.noradIds.mapNotNull(SatelliteIdentity::normalizeNoradId) }
    private val byObject = index { it.objects.mapNotNull(SatelliteIdentity::normalizeInternationalDesignator) }
    private val byLaunch = index { listOfNotNull(SatelliteIdentity.normalizeLaunch(it.launch)) }

    fun resolve(metadata: SatelliteCatalogMetadata): SatelliteClassification {
        val identity = metadata.normalized()
        if (identity.feed !in SUPPORTED_FEEDS) return SatelliteClassification()
        val matches = identity.catalogNumber?.let(byNorad::get)
            ?: identity.internationalDesignator?.let(byObject::get)
            ?: identity.internationalDesignator?.take(8)?.let(byLaunch::get)
            ?: return SatelliteClassification()
        val generations = matches.map { it.generation }.distinct()
        // A conflict at the strongest available level is not resolved by a weaker rule.
        if (generations.size != 1) return SatelliteClassification()
        return SatelliteClassification(generations.single(), true, matches.flatMap { it.sources }.distinct())
    }

    companion object {
        private val SUPPORTED_FEEDS = setOf("celestrak_starlink", "celestrak_starlink_supgp")

        fun fromJson(raw: String): SatelliteGenerationResolver {
            val root = JSONObject(raw)
            require(root.getInt("version") == 1)
            val entries = root.getJSONArray("rules")
            val rules = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                fun values(key: String): List<String> = entry.optJSONArray(key)?.let { array ->
                    (0 until array.length()).map { array.get(it).toString() }
                }.orEmpty()
                val generation = SatelliteGeneration.valueOf(entry.getString("generation"))
                val sources = values("sources")
                require(sources.isNotEmpty() && sources.all { it.startsWith("https://") })
                val objects = values("objects").map { requireNotNull(SatelliteIdentity.normalizeInternationalDesignator(it)) }.toSet()
                val norad = values("noradIds").map { requireNotNull(SatelliteIdentity.normalizeNoradId(it)) }.toSet()
                val launch = if (entry.has("launch")) requireNotNull(SatelliteIdentity.normalizeLaunch(entry.getString("launch"))) else null
                require(generation != SatelliteGeneration.UNKNOWN && (objects.isNotEmpty() || norad.isNotEmpty() || launch != null))
                GenerationRule(generation, launch, objects, sources, norad)
            }
            return SatelliteGenerationResolver(rules)
        }
    }
}
