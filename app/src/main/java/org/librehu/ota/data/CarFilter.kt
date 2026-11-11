package org.librehu.ota.data

import java.text.Normalizer

/**
 * The search and the filters of the Cars page: a make, then one of its models, and words typed (make, model, pack),
 * without case or accents ("citroen c4" finds the Citroën C4). The list stays: only the packs that match, each with
 * the models that made it match.
 */
object CarFilter {
    /** A pack shown, with the models (make and model) that match the search or the filters. */
    data class Match(
        val pack: CarPacks.Pack,
        val models: List<Pair<String, String>> = emptyList(),
    )

    /** Lower case, accents removed. */
    fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

    /** The makes of the packs, A to Z, once each (whatever their case). */
    fun makes(packs: List<CarPacks.Pack>): List<String> =
        packs
            .flatMap { p -> p.models.keys.ifEmpty { p.makes } }
            .distinctBy(::fold)
            .sortedBy(::fold)

    /** The models of [make] in the packs, A to Z, once each. */
    fun models(
        packs: List<CarPacks.Pack>,
        make: String,
    ): List<String> =
        packs
            .flatMap { p -> p.models.entries.filter { fold(it.key) == fold(make) }.flatMap { it.value } }
            .distinctBy(::fold)
            .sortedBy(::fold)

    /**
     * The packs to show: those of [make] (and of its [model]) when chosen, whose name, makes or models hold every word
     * of [query]; the box types' pack only without a make. No filter: every pack, in the index's order.
     */
    fun filter(
        packs: List<CarPacks.Pack>,
        query: String,
        make: String?,
        model: String?,
    ): List<Match> {
        val words = fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        val fMake = make?.let(::fold)
        val fModel = model?.let(::fold)
        return packs.mapNotNull { p ->
            val pairs = p.models.flatMap { (mk, ms) -> ms.map { mk to it } }
            if (fMake != null) {
                val ofMake = p.models.keys.any { fold(it) == fMake } || p.makes.any { fold(it) == fMake }
                if (!ofMake) return@mapNotNull null
            }
            val candidates =
                pairs.filter { (mk, m) ->
                    (fMake == null || fold(mk) == fMake) && (fModel == null || fold(m) == fModel)
                }
            if (fModel != null && candidates.isEmpty()) return@mapNotNull null
            if (words.isEmpty()) return@mapNotNull Match(p, if (fMake != null) candidates else emptyList())
            val packText = fold(p.name + " " + p.id + " " + p.makes.joinToString(" "))
            val hits = candidates.filter { (mk, m) -> fold("$mk $m").let { line -> words.all { it in line } } }
            when {
                hits.isNotEmpty() -> Match(p, hits)
                // The pack itself named ("renault", "peugeot") and no model narrowed: all of it.
                fModel == null && words.all { it in packText } -> Match(p, if (fMake != null) candidates else emptyList())
                else -> null
            }
        }
    }
}
