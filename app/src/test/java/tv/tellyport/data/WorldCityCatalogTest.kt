package tv.tellyport.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldCityCatalogTest {

    @Test
    fun catalog_loadsOver750ValidWorldCitiesAboveOneMillionPopulationWithUniqueIds() {
        val cities = WorldCityCatalog.cities
        assertTrue("Expected at least 750 world cities >= 1M, got ${cities.size}", cities.size >= 750)

        val uniqueIds = cities.map { it.id }.toSet()
        assertEquals("Expected every city to have a unique ID", cities.size, uniqueIds.size)

        for (city in cities) {
            assertTrue("${city.name} population ${city.population} < 1M", city.population >= WorldCityCatalog.MIN_POPULATION)
            assertTrue("${city.name} lat out of range: ${city.latitude}", city.latitude in -90.0..90.0)
            assertTrue("${city.name} lon out of range: ${city.longitude}", city.longitude in -180.0..180.0)
            assertTrue("${city.name} missing countryCode", city.countryCode.length == 2)
        }

        // Verify sorted descending by population when query is blank
        assertEquals(cities, WorldCityCatalog.search(""))
        assertTrue(cities.first().population >= cities.last().population)
    }

    @Test
    fun catalog_containsMajorGlobalCitiesAndAccurateElevations() {
        val byAscii = WorldCityCatalog.cities.groupBy { it.asciiName.lowercase() }
        val expectedCities = listOf(
            "tokyo", "new york", "london", "sydney", "melbourne", "brisbane", "perth", "auckland",
            "toronto", "vancouver", "san francisco", "los angeles", "chicago", "miami",
            "paris", "berlin", "amsterdam", "dublin", "madrid",
            "sao paulo", "mexico city", "bogota", "buenos aires",
            "singapore", "hong kong", "seoul", "mumbai", "delhi",
            "cairo", "johannesburg", "durban", "nairobi"
        )
        for (city in expectedCities) {
            assertTrue("Missing major 1M+ city: $city", city in byAscii)
        }

        val mexicoCity = WorldCityCatalog.search("Mexico City", limit = 1).single()
        assertTrue("Expected Mexico City altitude > 2000m, got ${mexicoCity.altitudeMeters}", mexicoCity.altitudeMeters > 2000.0)

        val bogota = WorldCityCatalog.search("Bogota", limit = 1).single()
        assertTrue("Expected Bogota altitude > 2400m, got ${bogota.altitudeMeters}", bogota.altitudeMeters > 2400.0)
    }

    @Test
    fun search_supportsDiacriticsFuzzySubsequenceTyposAliasesAndMultiTokenQueries() {
        // Diacritic-folded search
        assertEquals("São Paulo", WorldCityCatalog.search("sao paulo", limit = 1).first().name)
        assertEquals("Bogotá", WorldCityCatalog.search("bogota", limit = 1).first().name)

        // Ordered fuzzy subsequence
        assertEquals("Melbourne", WorldCityCatalog.search("mlbrn", limit = 1).first().name)
        assertEquals("Amsterdam", WorldCityCatalog.search("amstrdm", limit = 1).first().name)

        // Single-edit typo tolerance
        assertEquals("Tokyo", WorldCityCatalog.search("tokoy", limit = 1).first().name)
        assertEquals("London", WorldCityCatalog.search("lodnon", limit = 1).first().name)
        assertEquals("Sydney", WorldCityCatalog.search("sydeny", limit = 1).first().name)

        // Common abbreviations & aliases
        assertEquals("New York", WorldCityCatalog.search("nyc", limit = 1).first().name)
        assertEquals("Mexico City", WorldCityCatalog.search("cdmx", limit = 1).first().name)
        assertEquals("San Francisco", WorldCityCatalog.search("sf", limit = 1).first().name)

        // Multi-token city + country/region
        val londonUk = WorldCityCatalog.search("london uk", limit = 1).first()
        assertEquals("London", londonUk.name)
        assertEquals("GB", londonUk.countryCode)
        assertEquals("UK", londonUk.regionCode)

        val sydneyAu = WorldCityCatalog.search("sydney australia", limit = 1).first()
        assertEquals("Sydney", sydneyAu.name)
        assertEquals("AU", sydneyAu.countryCode)

        // Non-matching query returns empty
        assertTrue(WorldCityCatalog.search("zzznosuchcity999").isEmpty())
    }

    @Test
    fun findNearestCity_matchesPresetCoordinates() {
        val nearLondon = WorldCityCatalog.findNearestCity(51.5074, -0.1278)
        assertNotNull(nearLondon)
        assertEquals("London", nearLondon?.name)

        val nearSydney = WorldCityCatalog.findNearestCity(-33.8688, 151.2093)
        assertNotNull(nearSydney)
        assertEquals("Sydney", nearSydney?.name)
    }
}
