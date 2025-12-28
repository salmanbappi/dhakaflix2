package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object Filters {
    fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("--- Category ---"),
        DhakaFlixSelect("Select Category", FilterData.CATEGORIES),
        DhakaFlixSelect("Select Year", FilterData.YEARS),
        DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET),
        DhakaFlixSelect("Select Language", FilterData.LANGUAGES)
    )

    fun getUrl(query: String, filters: AnimeFilterList?): String {
        if (query.isNotEmpty()) return "http://172.16.50.14/DHAKA-FLIX-14/"

        if (filters == null) return "http://172.16.50.14/DHAKA-FLIX-14/Hindi Movies/(2025)/"

        val categoryIndex = (filters[1] as DhakaFlixSelect).state
        val yearIndex = (filters[2] as DhakaFlixSelect).state
        val alphabetIndex = (filters[3] as DhakaFlixSelect).state
        val languageIndex = (filters[4] as DhakaFlixSelect).state

        val baseUrl = when (categoryIndex) {
            0 -> "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/"
            1 -> "http://172.16.50.7/DHAKA-FLIX-7/English%20Movies/"
            2 -> return if (alphabetIndex > 0) {
                getAlphabetPath("http://172.16.50.14/DHAKA-FLIX-14/English%20Movies%20%281080p%29/", alphabetIndex)
            } else {
                getYearPath1080p("http://172.16.50.14/DHAKA-FLIX-14/English%20Movies%20%281080p%29/", yearIndex)
            }
            3 -> return if (alphabetIndex > 0) {
                getAlphabetPath("http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/South%20Movies/", alphabetIndex)
            } else {
                getYearPathSimple("http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/South%20Movies/", yearIndex)
            }
            4 -> return if (alphabetIndex > 0) {
                getAlphabetPath("http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/", alphabetIndex)
            } else {
                getYearPath("http://172.16.50.14/DHAKA-FLIX-14/SOUTH%20INDIAN%20MOVIES/Hindi%20Dubbed/", yearIndex)
            }
            5 -> "http://172.16.50.7/DHAKA-FLIX-7/Kolkata%20Bangla%20Movies/"
            6 -> "http://172.16.50.14/DHAKA-FLIX-14/Animation%20Movies/"
            7 -> if (languageIndex > 0) {
                return getLanguagePath("http://172.16.50.7/DHAKA-FLIX-7/Foreign%20Language%20Movies/", languageIndex)
            } else {
                "http://172.16.50.7/DHAKA-FLIX-7/Foreign%20Language%20Movies/"
            }
            8 -> if (alphabetIndex > 0) {
                return getSeriesPath("http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/", alphabetIndex)
            } else {
                "http://172.16.50.12/DHAKA-FLIX-12/TV-WEB-Series/"
            }
            9 -> "http://172.16.50.14/DHAKA-FLIX-14/KOREAN%20TV%20%26%20WEB%20Series/"
            10 -> if (alphabetIndex > 0) {
                return getAnimePath("http://172.16.50.9/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/", alphabetIndex)
            } else {
                "http://172.16.50.9/DHAKA-FLIX-9/Anime%20%26%20Cartoon%20TV%20Series/"
            }
            11 -> "http://172.16.50.9/DHAKA-FLIX-9/Documentary/"
            12 -> "http://172.16.50.9/DHAKA-FLIX-9/WWE%20%26%20AEW%20Wrestling/"
            13 -> "http://172.16.50.9/DHAKA-FLIX-9/Awards%20%26%20TV%20Shows/"
            14 -> "http://172.16.50.14/DHAKA-FLIX-14/IMDb%20Top-250%20Movies/"
            15 -> "http://172.16.50.7/DHAKA-FLIX-7/3D%20Movies/"
            16 -> "http://172.16.50.14/DHAKA-FLIX-14/Trending%20Movies/"
            else -> "http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%282025%29/"
        }

        return if (alphabetIndex > 0) {
            getAlphabetPath(baseUrl, alphabetIndex)
        } else {
            getYearPath(baseUrl, yearIndex)
        }
    }

    private fun getYearPath(base: String, index: Int): String {
        if (index == 0) return base
        val year = when (index) {
            1 -> "(2025)/"
            2 -> "(2024)/"
            3 -> "(2023)/"
            4 -> "(2022)/"
            5 -> "(2021)/"
            6 -> "(2020)/"
            7 -> "(2019)/"
            8 -> "(2018)/"
            9 -> "(2017)/"
            10 -> "(2016)/"
            11 -> "(2015)/"
            12 -> "(2014)/"
            13 -> "(2013)/"
            14 -> "(2012)/"
            15 -> "(2011)/"
            16 -> "(2010)/"
            17 -> "(2009) & Before/"
            else -> ""
        }
        return base + year
    }

    private fun getAlphabetPath(base: String, index: Int): String {
        val alpha = when (index) {
            1 -> "0-9/"
            2 -> "A-F/"
            3 -> "G-M/"
            4 -> "N-S/"
            5 -> "T-Z/"
            else -> ""
        }
        return base + alpha
    }

    private fun getSeriesPath(base: String, index: Int): String {
        val alpha = when (index) {
            1 -> "TV%20Series%20%E2%98%85%20%200%20%20%E2%80%94%20%209/"
            2 -> "TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/"
            3 -> "TV%20Series%20%E2%99%A6%20%20M%20%20%E2%80%94%20%20R/"
            4 -> "TV%20Series%20%E2%99%A6%20%20S%20%20%E2%80%94%20%20Z/"
            else -> ""
        }
        return base + alpha
    }

    private fun getAnimePath(base: String, index: Int): String {
        val alpha = when (index) {
            1 -> "Anime-TV%20Series%20%E2%98%85%20%200%20%20%E2%80%94%20%209/"
            2 -> "Anime-TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20F/"
            3 -> "Anime-TV%20Series%20%E2%99%A5%20%20G%20%20%E2%80%94%20%20M/"
            4 -> "Anime-TV%20Series%20%E2%99%A6%20%20N%20%20%E2%80%94%20%20S/"
            5 -> "Anime-TV%20Series%20%E2%99%A6%20%20T%20%20%E2%80%94%20%20Z/"
            else -> ""
        }
        return base + alpha
    }

    private fun getLanguagePath(base: String, index: Int): String {
        val lang = when (index) {
            1 -> "Korean/"
            2 -> "Chinese/"
            3 -> "Japanese/"
            4 -> "Spanish/"
            5 -> "French/"
            6 -> "Italian/"
            7 -> "German/"
            8 -> "Portuguese/"
            9 -> "Russian/"
            10 -> "Thai/"
            11 -> "Other/"
            else -> ""
        }
        return base + lang
    }

    private fun getYearPathSimple(base: String, index: Int): String {
        if (index == 0) return base
        val year = when (index) {
            1 -> "2025/"
            2 -> "2024/"
            3 -> "2023/"
            4 -> "2022/"
            5 -> "2021/"
            6 -> "2020/"
            7 -> "2019/"
            8 -> "2018/"
            9 -> "2017/"
            10 -> "2016/"
            11 -> "2015/"
            12 -> "2014/"
            13 -> "2013/"
            14 -> "2012/"
            15 -> "2011/"
            16 -> "2010/"
            17 -> "2009 & Before/"
            else -> ""
        }
        return base + year
    }

    private fun getYearPath1080p(base: String, index: Int): String {
        if (index == 0) return base
        val year = when (index) {
            1 -> "%282025%29%201080p/"
            2 -> "%282024%29%201080p/"
            3 -> "%282023%29%201080p/"
            4 -> "%282022%29%201080p/"
            5 -> "%282021%29%201080p/"
            6 -> "%282020%29%201080p/"
            7 -> "%282019%29%201080p/"
            8 -> "%282018%29%201080p/"
            9 -> "%282017%29%201080p/"
            10 -> "%282016%29%201080p/"
            11 -> "%282015%29%201080p/"
            12 -> "%282014%29%201080p/"
            13 -> "%282013%29%201080p/"
            14 -> "%282012%29%201080p/"
            15 -> "%282011%29%201080p/"
            16 -> "%282010%29%201080p/"
            17 -> "%282009%29%20%26%20Before/"
            else -> ""
        }
        return base + year
    }
}