package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.network.GET
import okhttp3.Request

// --- DhakaFlix (Hindi & South Indian) - Server 14 ---
class DhakaFlixHindi : DhakaFlixBase(
    "DhakaFlix (Hindi & South Indian)",
    "http://172.16.50.14",
    5181466391484419941L,
    "DHAKA-FLIX-14"
) {
    override fun getSearchPaths() = listOf("/$serverPath/")
    
    override fun popularAnimeRequest(page: Int): Request = 
        GET("$baseUrl/$serverPath/Hindi%20Movies/%282026%29/", headers)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        DhakaFlixSelect("Select Category", arrayOf(
            "Hindi Movies", "English Movies (1080p)", "South Indian Movies",
            "South Hindi Dubbed", "Animation Movies", "Korean TV & Web Series",
            "IMDb Top-250 Movies", "Trending Movies"
        )),
        DhakaFlixSelect("Select Year", FilterData.YEARS),
        DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET)
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) GET("$baseUrl/$query", headers)
        else GET(Filters.getUrl(baseUrl, serverPath, filters), headers)
    }
}

// --- DhakaFlix (TV & Web Series) - Server 12 ---
class DhakaFlixTV : DhakaFlixBase(
    "DhakaFlix (TV & Web Series)",
    "http://172.16.50.12",
    5181466391484419942L,
    "DHAKA-FLIX-12"
) {
    override fun getSearchPaths() = listOf("/$serverPath/", "/$serverPath/TV-WEB-Series/", "/$serverPath/Hindi Movies/")
    
    override fun popularAnimeRequest(page: Int): Request = 
        GET("$baseUrl/$serverPath/TV-WEB-Series/TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/", headers)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        DhakaFlixSelect("Select Category", arrayOf("TV Series", "Hindi Movies")),
        DhakaFlixSelect("Select Year", FilterData.YEARS),
        DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET)
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) GET("$baseUrl/$query", headers)
        else GET(Filters.getUrl(baseUrl, serverPath, filters), headers)
    }
}

// --- DhakaFlix (Anime & Documentary) - Server 9 ---
class DhakaFlixAnime : DhakaFlixBase(
    "DhakaFlix (Anime & Documentary)",
    "http://172.16.50.9",
    5181466391484419943L,
    "DHAKA-FLIX-9"
) {
    override fun getSearchPaths() = listOf("/$serverPath/", "/$serverPath/Anime & Cartoon TV Series/", "/$serverPath/Anime & Cartoon Movies/")
    
    override fun popularAnimeRequest(page: Int): Request = 
        GET("$baseUrl/$serverPath/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20F/", headers)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        DhakaFlixSelect("Select Category", arrayOf("Anime-TV Series", "Documentary", "WWE & AEW Wrestling", "Awards & TV Shows")),
        DhakaFlixSelect("Select Year", FilterData.YEARS),
        DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET)
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) GET("$baseUrl/$query", headers)
        else GET(Filters.getUrl(baseUrl, serverPath, filters), headers)
    }
}

// --- DhakaFlix (English & International) - Server 7 ---
class DhakaFlixEnglish : DhakaFlixBase(
    "DhakaFlix (English & International)",
    "http://172.16.50.7",
    5181466391484419944L,
    "DHAKA-FLIX-7"
) {
    override fun getSearchPaths() = listOf("/$serverPath/")
    
    override fun popularAnimeRequest(page: Int): Request = 
        GET("$baseUrl/$serverPath/English%20Movies/", headers)

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        DhakaFlixSelect("Select Category", arrayOf("English Movies", "Kolkata Bangla Movies", "Foreign Language Movies", "3D Movies")),
        DhakaFlixSelect("Select Year", FilterData.YEARS),
        DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET),
        DhakaFlixSelect("Select Language", FilterData.LANGUAGES)
    )

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) GET("$baseUrl/$query", headers)
        else GET(Filters.getUrl(baseUrl, serverPath, filters), headers)
    }
}
