package org.librehu.ota.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarFilterTest {
    private fun pack(
        id: String,
        name: String,
        models: Map<String, List<String>>,
    ) = CarPacks.Pack(id, name, 1, ">=2.0", models.keys.toList(), models.values.sumOf { it.size }, emptyList(), models, "$id.json", 1, "0")

    private val packs =
        listOf(
            CarPacks.Pack(CarPacks.BOXES, "CAN box types", 3, ">=2.7", emptyList(), 0, listOf("hiworld-generic"), emptyMap(), "boxes.json", 1, "0"),
            pack("renault", "Renault", mapOf("Dacia" to listOf("Duster", "Sandero"), "Renault" to listOf("Clio", "CLIO3", "Kadjar"))),
            pack("psa", "Peugeot, Citroën, DS", mapOf("Citroën" to listOf("C4", "C4 Cactus"), "Peugeot" to listOf("2008", "308"))),
        )

    @Test
    fun noFilterShowsEverythingInTheIndexOrder() {
        assertEquals(listOf("boxes", "renault", "psa"), CarFilter.filter(packs, "", null, null).map { it.pack.id })
    }

    @Test
    fun makesThenModelsOfTheChosenMake() {
        assertEquals(listOf("Citroën", "Dacia", "Peugeot", "Renault"), CarFilter.makes(packs))
        assertEquals(listOf("Clio", "CLIO3", "Kadjar"), CarFilter.models(packs, "renault"))
        val ofMake = CarFilter.filter(packs, "", "Dacia", null)
        assertEquals(listOf("renault"), ofMake.map { it.pack.id })
        assertEquals(listOf("Dacia" to "Duster", "Dacia" to "Sandero"), ofMake.single().models)
        assertEquals(listOf("Dacia" to "Sandero"), CarFilter.filter(packs, "", "Dacia", "Sandero").single().models)
    }

    @Test
    fun searchWithoutCaseOrAccentsFindsModelsAndPacks() {
        val c4 = CarFilter.filter(packs, "citroen c4", null, null)
        assertEquals(listOf("psa"), c4.map { it.pack.id })
        assertEquals(listOf("Citroën" to "C4", "Citroën" to "C4 Cactus"), c4.single().models)
        assertEquals(listOf("renault"), CarFilter.filter(packs, "KADJAR", null, null).map { it.pack.id })
        // A make: its models.
        val peugeot = CarFilter.filter(packs, "peugeot", null, null).single()
        assertEquals("psa", peugeot.pack.id)
        assertTrue(peugeot.models.any { it == ("Peugeot" to "2008") })
        assertEquals(emptyList<String>(), CarFilter.filter(packs, "golf", null, null).map { it.pack.id })
    }

    @Test
    fun theSearchStaysWithinTheChosenMake() {
        assertEquals(emptyList<String>(), CarFilter.filter(packs, "c4", "Renault", null).map { it.pack.id })
        assertEquals(listOf("Renault" to "Clio", "Renault" to "CLIO3"), CarFilter.filter(packs, "clio", "Renault", null).single().models)
    }
}
