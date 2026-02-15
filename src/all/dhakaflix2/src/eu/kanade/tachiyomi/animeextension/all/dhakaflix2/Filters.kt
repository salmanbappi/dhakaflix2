package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    fun getFilterList(baseUrl: String): AnimeFilterList {
        val categories = FilterData.getServerCategories(baseUrl)
        return AnimeFilterList(
            AnimeFilter.Header("--- Category ---"),
            DhakaFlixSelect("Select Category", categories),
            DhakaFlixSelect("Select Year", FilterData.YEARS),
            DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET),
            DhakaFlixSelect("Select Language", FilterData.LANGUAGES)
        )
    }

    fun getUrl(baseUrl: String, serverPath: String, query: String, filters: AnimeFilterList?): String {
        if (query.isNotEmpty()) return query
        
        val fullBaseUrl = "$baseUrl/$serverPath"
        if (filters == null) {
            return when {
                baseUrl.contains("50.14") -> "$fullBaseUrl/Hindi Movies/(2026)/"
                baseUrl.contains("50.12") -> "$fullBaseUrl/TV-WEB-Series/TV Series \u2665  A  \u2014  L/"
                baseUrl.contains("50.9") -> "$fullBaseUrl/Anime & Cartoon TV Series/Anime-TV Series \u2665  A  \u2014  F/"
                else -> "$fullBaseUrl/English Movies/"
            }
        }

        val categoryFilter = filters[1] as DhakaFlixSelect
        val yearFilter = filters[2] as DhakaFlixSelect
        val alphabetFilter = filters[3] as DhakaFlixSelect
        val langFilter = filters[4] as DhakaFlixSelect

        val category = categoryFilter.values[categoryFilter.state]
        val year = yearFilter.values[yearFilter.state]
        val alphabet = alphabetFilter.values[alphabetFilter.state]
        val lang = langFilter.values[langFilter.state]

        var path = category

        when (category) {
            "Hindi Movies" -> path = "Hindi Movies"
            "English Movies" -> path = "English Movies"
            "English Movies (1080p)" -> path = "English Movies (1080p)"
            "South Indian Movies" -> path = "SOUTH INDIAN MOVIES/South Movies"
            "South Hindi Dubbed" -> path = "SOUTH INDIAN MOVIES/Hindi Dubbed"
            "Kolkata Bangla Movies" -> path = "Kolkata Bangla Movies"
            "Animation Movies" -> path = "Animation Movies"
            "Foreign Language Movies" -> {
                path = "Foreign Language Movies"
                if (lang != "Any") return "$fullBaseUrl/$path/$lang/"
            }
            "TV Series" -> {
                if (alphabet != "Any") {
                    val subPath = when (alphabet) {
                        "0-9" -> "TV Series \u2605  0  \u2014  9"
                        "G-M / M-R" -> "TV Series \u2666  M  \u2014  R"
                        "N-S / S-Z" -> "TV Series \u2666  S  \u2014  Z"
                        else -> "TV Series \u2665  A  \u2014  L" 
                    }
                    return "$fullBaseUrl/TV-WEB-Series/$subPath/"
                }
                path = "TV-WEB-Series"
            }
            "Korean TV & Web Series" -> path = "KOREAN TV & WEB Series"
            "Anime-TV Series" -> {
                if (alphabet != "Any") {
                    val subPath = when (alphabet) {
                        "0-9" -> "Anime-TV Series \u2605  0  \u2014  9"
                        "G-M / M-R" -> "Anime-TV Series \u2665  G  \u2014  M"
                        "N-S / S-Z" -> "Anime-TV Series \u2666  N  \u2014  S"
                        "T-Z" -> "Anime-TV Series \u2666  T  \u2014  Z"
                        else -> "Anime-TV Series \u2665  A  \u2014  F"
                    }
                    return "$fullBaseUrl/Anime & Cartoon TV Series/$subPath/"
                }
                path = "Anime & Cartoon TV Series"
            }
            "Documentary" -> path = "Documentary"
            "WWE & AEW Wrestling" -> path = "WWE & AEW Wrestling"
            "Awards & TV Shows" -> path = "Awards & TV Shows"
            "IMDb Top-250 Movies" -> path = "IMDb Top-250 Movies"
            "3D Movies" -> path = "3D Movies"
            "Trending Movies" -> {
                path = "Hindi Movies/(2026)"
                return "$fullBaseUrl/$path/"
            }
        }

        if (alphabet != "Any") {
            val alphaPath = when (alphabet) {
                "0-9" -> "0-9"
                "A-F / A-L" -> "A-F"
                "G-M / M-R" -> "G-M"
                "N-S / S-Z" -> "N-S"
                "T-Z" -> "T-Z"
                else -> alphabet
            }
            return "$fullBaseUrl/$path/$alphaPath/"
        }

        if (year != "Any") {
            val yearPath = when {
                category == "English Movies (1080p)" -> {
                    if (year == "(2009) & Before") "%282009%29%20%26%20Before"
                    else "%28$year%29%201080p"
                }
                category == "South Indian Movies" || category == "South Hindi Dubbed" -> {
                    if (year == "(2009) & Before") "2000 & Before" else year
                }
                else -> "($year)"
            }
            return "$fullBaseUrl/$path/$yearPath/"
        }

        return "$fullBaseUrl/$path/"
    }
}
