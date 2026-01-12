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
        if (filters == null) return "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%282025%29/"

        val categoryFilter = filters[1] as DhakaFlixSelect
        val yearFilter = filters[2] as DhakaFlixSelect
        val alphabetFilter = filters[3] as DhakaFlixSelect

        val category = categoryFilter.values[categoryFilter.state]
        val year = yearFilter.values[yearFilter.state]
        val alphabet = alphabetFilter.values[alphabetFilter.state]

        var baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
        var path = "Hindi Movies"

        when (category) {
            "Hindi Movies" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "Hindi Movies"
            }
            "English Movies" -> {
                baseUrl = "http://172.16.50.7/DHAKA-FLIX-7"
                path = "English Movies"
            }
            "English Movies (1080p)" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "English Movies (1080p)"
            }
            "South Indian Movies" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "SOUTH INDIAN MOVIES/South Movies"
            }
            "South Hindi Dubbed" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "SOUTH INDIAN MOVIES/Hindi Dubbed"
            }
            "Kolkata Bangla Movies" -> {
                baseUrl = "http://172.16.50.7/DHAKA-FLIX-7"
                path = "Kolkata Bangla Movies"
            }
            "Animation Movies" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "Animation Movies"
            }
            "Foreign Language Movies" -> {
                baseUrl = "http://172.16.50.7/DHAKA-FLIX-7"
                path = "Foreign Language Movies"
            }
            "TV Series" -> {
                baseUrl = "http://172.16.50.12/DHAKA-FLIX-12"
                // Default to A-L if "Any" is selected to show content
                val subPath = when (alphabet) {
                    "0-9" -> "TV Series \u2605  0  \u2014  9"
                    "G-M / M-R" -> "TV Series \u2666  M  \u2014  R"
                    "N-S / S-Z" -> "TV Series \u2666  S  \u2014  Z"
                    else -> "TV Series \u2665  A  \u2014  L" // Default/A-L
                }
                path = "TV-WEB-Series/$subPath"
            }
            "Korean TV & Web Series" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "KOREAN TV & WEB Series"
            }
            "Anime-TV Series" -> {
                baseUrl = "http://172.16.50.9/DHAKA-FLIX-9"
                // Default to A-F if "Any" is selected
                val subPath = when (alphabet) {
                    "0-9" -> "Anime-TV Series \u2605  0  \u2014  9"
                    "G-M / M-R" -> "Anime-TV Series \u2665  G  \u2014  M"
                    "N-S / S-Z" -> "Anime-TV Series \u2666  N  \u2014  S"
                    "T-Z" -> "Anime-TV Series \u2666  T  \u2014  Z"
                    else -> "Anime-TV Series \u2665  A  \u2014  F" // Default/A-F
                }
                path = "Anime & Cartoon TV Series/$subPath"
            }
            "Documentary" -> {
                baseUrl = "http://172.16.50.9/DHAKA-FLIX-9"
                path = "Documentary"
            }
            "WWE & AEW Wrestling" -> {
                baseUrl = "http://172.16.50.9/DHAKA-FLIX-9"
                path = "WWE & AEW Wrestling"
            }
            "Awards & TV Shows" -> {
                baseUrl = "http://172.16.50.9/DHAKA-FLIX-9"
                path = "Awards & TV Shows"
            }
            "IMDb Top-250 Movies" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "IMDb Top-250 Movies"
            }
            "3D Movies" -> {
                baseUrl = "http://172.16.50.7/DHAKA-FLIX-7"
                path = "3D Movies"
            }
            "Trending Movies" -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = "Hindi Movies/(2026)"
            }
            else -> {
                baseUrl = "http://172.16.50.14/DHAKA-FLIX-14"
                path = category
            }
        }

        // Append year if applicable
        val supportsYear = when (category) {
            "Hindi Movies", "English Movies (1080p)", "Animation Movies", "IMDb Top-250 Movies" -> true
            "English Movies", "Kolkata Bangla Movies", "Foreign Language Movies", "3D Movies" -> {
                // Server 7 categories - don't allow 2026 yet
                year != "Any" && year != "2026"
            }
            "South Indian Movies", "South Hindi Dubbed" -> true
            else -> false
        }

        if (year != "Any" && supportsYear) {
            val yearPath = if (year == "(2009) & Before") "(2009) & Before" else "($year)"
            path += "/$yearPath"
        }

        return "$baseUrl/$path/"
    }
}