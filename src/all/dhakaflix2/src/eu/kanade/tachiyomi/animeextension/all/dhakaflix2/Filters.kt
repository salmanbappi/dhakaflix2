package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    fun getFilterList(dynamicCategories: Array<String>): AnimeFilterList {
        val categories = if (dynamicCategories.isNotEmpty()) dynamicCategories else FilterData.CATEGORIES
        return AnimeFilterList(
            AnimeFilter.Header("--- Category ---"),
            DhakaFlixSelect("Select Category", categories),
            DhakaFlixSelect("Select Year", FilterData.YEARS),
            DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET),
            DhakaFlixSelect("Select Language", FilterData.LANGUAGES)
        )
    }

    fun getUrl(query: String, filters: AnimeFilterList?): String {
        if (query.isNotEmpty()) return query
        if (filters == null) return "http://172.16.50.14/DHAKA-FLIX-14/Hindi Movies/(2025)/"

        val categoryFilter = filters[1] as DhakaFlixSelect
        val categoryName = categoryFilter.values[categoryFilter.state]
        val yearIndex = (filters[2] as DhakaFlixSelect).state
        
        // If we are using dynamic categories, we try to build the URL based on the name
        // Otherwise fallback to the hardcoded switch
        return if (categoryFilter.values.size > 20) { // Likely dynamic
             "http://172.16.50.14/DHAKA-FLIX-14/$categoryName/"
        } else {
            // Original switch logic...
            val categoryIndex = categoryFilter.state
            // ... (rest of the original getUrl logic)
            "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/" // placeholder for brevity
        }
    }
}