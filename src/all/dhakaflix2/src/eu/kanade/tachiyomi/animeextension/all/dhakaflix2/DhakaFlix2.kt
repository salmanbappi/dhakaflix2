package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

// --- Constants ---
private const val PREF_TMDB_API_KEY = "tmdb_api_key"
private const val PREF_USE_TMDB_COVERS = "use_tmdb_covers"

private val IP_HTTP_REGEX = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\s*http""")
private val DOUBLE_PROTOCOL_REGEX = Regex("""http(s)?://http(s)?://""")
private val MULTI_SLASH_REGEX = Regex("""(?<!:)/{2,}""")

private val FILE_EXT_REGEX = Regex("""\.(mkv|mp4|avi|flv)$""", RegexOption.IGNORE_CASE)
private val SEPARATOR_REGEX = Regex("""[._]""", RegexOption.IGNORE_CASE)
private val EPISODE_S_E_REGEX = Regex("""\s+S\d+E\d+.*""", RegexOption.IGNORE_CASE)
private val SEASON_REGEX = Regex("""\s+S\d+.*""", RegexOption.IGNORE_CASE)
private val EPISODE_TEXT_REGEX = Regex("""\s+(?:Episode|Ep)\s*\d+.*""", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("""\s+[\[\(]?\d{4}[\]\)]?.*""", RegexOption.IGNORE_CASE)
private val QUALITY_REGEX = Regex("""\s+(720p|1080p|WEB-DL|BluRay|HDRip|HDTC|HDCAM|ESub|Dual Audio).*""", RegexOption.IGNORE_CASE)
private val DASH_REGEX = Regex("""\s+-\s+\d+\s+.*""", RegexOption.IGNORE_CASE)

class DhakaFlix2(
    override val name: String,
    override val baseUrl: String,
    override val id: Long,
    private val serverPath: String,
    private val serverCategories: Array<String>
) : ConfigurableAnimeSource, AnimeHttpSource() {

    override val lang = "all"
    override val supportsLatest = true

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    private val searchCache = mutableMapOf<String, List<SAnime>>()
    private val cacheTime = mutableMapOf<String, Long>()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PREF_TMDB_API_KEY
            title = "TMDb API Key"
            summary = "Used for high-quality covers. Get one at themoviedb.org"
            setDefaultValue("")
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_TMDB_COVERS
            title = "Use TMDb Covers"
            summary = "Fetch high-quality covers from TMDb. If disabled, NO images will load for speed."
            setDefaultValue(false)
        }.also { screen.addPreference(it) }

        SwitchPreferenceCompat(screen.context).apply {
            key = "clear_tmdb_cache"
            title = "Clear TMDb Cache"
            summary = "Clears all cached TMDb poster URLs"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    val editor = preferences.edit()
                    preferences.all.keys.filter { it.startsWith("tmdb_cover_") }.forEach { editor.remove(it) }
                    editor.apply()
                    android.widget.Toast.makeText(screen.context, "TMDb Cache Cleared", android.widget.Toast.LENGTH_SHORT).show()
                    this.isChecked = false
                }
                false
            }
        }.also { screen.addPreference(it) }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        var u = url.trim()
        val lastHttp = u.lastIndexOf("http://", ignoreCase = true)
        val lastHttps = u.lastIndexOf("https://", ignoreCase = true)
        val lastProtocol = if (lastHttp > lastHttps) lastHttp else lastHttps
        if (lastProtocol > 0) u = u.substring(lastProtocol)
        u = IP_HTTP_REGEX.replace(u, "$1/http")
        u = DOUBLE_PROTOCOL_REGEX.replace(u, "http$1://")
        u = u.replace(":://://", ":://")
        u = MULTI_SLASH_REGEX.replace(u, "/")
        return u.replace(" ", "%20").replace("&", "%26")
    }

    private val enrichmentSemaphore = Semaphore(5)

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (!useTmdb) return
        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return

        withContext(Dispatchers.IO) {
            withTimeoutOrNull(10000) {
                coroutineScope {
                    animes.take(40).map { anime ->
                        async {
                            enrichmentSemaphore.withPermit {
                                val tmdbCover = fetchTmdbImage(anime.title)
                                if (tmdbCover != null) anime.thumbnail_url = tmdbCover
                            }
                        }
                    }.awaitAll()
                }
            }
        }
    }

    private fun fetchTmdbImage(title: String): String? {
        val cacheKey = "tmdb_cover_".plus(title.hashCode())
        val cached = preferences.getString(cacheKey, null)
        if (cached != null) return cached.takeIf { it.isNotEmpty() }
        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return null

        val cleanTitle = cleanTitleForTmdb(title)
        val url = "https://api.themoviedb.org/3/search/multi?api_key=$apiKey&query=$cleanTitle".toHttpUrlOrNull() ?: return null
        
        return try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val bodyStr = response.body?.string() ?: return null
                val results = JSONObject(bodyStr).optJSONArray("results")
                if (results != null && results.length() > 0) {
                    val path = results.getJSONObject(0).optString("poster_path")
                    if (path.isNotEmpty() && path != "null") {
                        val thumb = "https://image.tmdb.org/t/p/w500$path"
                        preferences.edit().putString(cacheKey, thumb).apply()
                        thumb
                    } else null
                } else null
            }
        } catch (e: Exception) { null }
    }

    private fun cleanTitleForTmdb(title: String): String {
        var t = title.replace(FILE_EXT_REGEX, "")
        t = t.replace(SEPARATOR_REGEX, " ")
        t = t.replace(EPISODE_S_E_REGEX, "")
        t = t.replace(SEASON_REGEX, "")
        t = t.replace(EPISODE_TEXT_REGEX, "")
        t = t.replace(YEAR_REGEX, "")
        t = t.replace(QUALITY_REGEX, "")
        t = t.replace(DASH_REGEX, "")
        return t.trim()
    }

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        val response = client.newCall(popularAnimeRequest(page)).execute()
        return popularAnimeParse(response).also { enrichAnimes(it.animes) }
    }

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        val response = client.newCall(latestUpdatesRequest(page)).execute()
        return latestUpdatesParse(response).also { enrichAnimes(it.animes) }
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        if (query.isEmpty()) return super.getSearchAnime(page, query, filters)

        val now = System.currentTimeMillis()
        if (searchCache.containsKey(query) && now - (cacheTime[query] ?: 0) < 1800000) {
            return AnimesPage(searchCache[query]!!, false)
        }

        val results = withContext(Dispatchers.IO) {
            val paths = mutableListOf("/$serverPath/")
            if (serverPath == "DHAKA-FLIX-9") {
                paths.add("/$serverPath/Anime & Cartoon TV Series/")
                paths.add("/$serverPath/Anime & Cartoon Movies/")
            }
            if (serverPath == "DHAKA-FLIX-12") {
                paths.add("/$serverPath/TV-WEB-Series/")
                paths.add("/$serverPath/Hindi Movies/")
            }
            
            val deferredResults = paths.map { path ->
                async {
                    try {
                        searchSingleServer(baseUrl, serverPath, path, query)
                    } catch (e: Exception) {
                        emptyList<SAnime>()
                    }
                }
            }
            val allAnime = deferredResults.awaitAll().flatten().distinctBy { it.url }
            sortByTitle(collapseResults(allAnime), query)
        }

        if (results.isNotEmpty()) {
            searchCache[query] = results
            cacheTime[query] = now
        }

        return AnimesPage(results, false).also { enrichAnimes(it.animes) }
    }

    private fun collapseResults(list: List<SAnime>): List<SAnime> {
        val folders = list.filter { it.url.endsWith("/") }.map { it.url }.toSet()
        if (folders.isEmpty()) return list
        return list.filter {
            if (it.url.endsWith("/")) return@filter true
            folders.none { folderUrl -> it.url.startsWith(folderUrl) }
        }
    }

    private fun searchSingleServer(baseUrl: String, serverName: String, path: String, query: String): List<SAnime> {
        val searchUrl = "$baseUrl/$serverName/"
        val jsonPayload = JSONObject().apply {
            put("action", "get")
            put("search", JSONObject().apply {
                put("href", path)
                put("pattern", query)
                put("ignorecase", true)
            })
        }

        val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val fastClient = client.newBuilder()
            .readTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .build()

        val response = try {
            fastClient.newCall(POST(searchUrl, headers, body)).execute()
        } catch (e: Exception) {
            return emptyList()
        }

        val bodyString = response.body?.string() ?: return emptyList()
        response.close()

        if (baseUrl.contains("172.16.50.7")) {
            val res = mutableListOf<SAnime>()
            Server7Parser.parseServer7Response(bodyString, baseUrl, serverName, res, query)
            return res.filter {
                it.title.startsWith(query, true) || diceCoefficient(it.title.lowercase(), query.lowercase()) > 0.15 
            }
        }

        val results = mutableListOf<SAnime>()
        try {
            val json = JSONObject(bodyString)
            val searchArr = json.optJSONArray("search") ?: return emptyList()

            for (i in 0 until searchArr.length()) {
                val item = searchArr.getJSONObject(i)
                val href = item.getString("href").replace('\\', '/')
                val cleanHrefForTitle = href.trimEnd('/')
                val rawTitle = cleanHrefForTitle.substringAfterLast("/")
                val title = try { URLDecoder.decode(rawTitle, "UTF-8").trim() } catch (e: Exception) { rawTitle.trim() }

                if (title.isBlank() || isIgnored(title, query)) continue
                if (!title.startsWith(query, true) && diceCoefficient(title.lowercase(), query.lowercase()) < 0.15) continue

                val anime = SAnime.create().apply {
                    this.title = title
                    val finalHref = if (href.startsWith("/")) href else "/$href"
                    this.url = "$baseUrl$finalHref"
                    this.thumbnail_url = if (this.url.endsWith("/")) getFolderThumb(this.url) else "" 
                }
                results.add(anime)
            }
        } catch (e: Exception) {}
        return results
    }

    private fun isIgnored(text: String, query: String = ""): Boolean {
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of", "JavaScript", "powered by", "_h5ai")
        if (ignored.any { text.contains(it, ignoreCase = true) }) return true
        val uploaderTags = listOf("-LOKI", "-LOKiHD", "-TDoc", "-Tuna", "-PSA", "-Pahe", "-QxR", "-YIFY", "-RARBG")
        if (uploaderTags.any { text.endsWith(it, ignoreCase = true) || text.contains("$it.") || text.contains("$it ") }) {
            val cleanQuery = query.trim().removePrefix("-")
            if (cleanQuery.isNotEmpty() && uploaderTags.any { it.removePrefix("-").equals(cleanQuery, ignoreCase = true) }) {
                return false
            }
            return true
        }
        return false
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> {
        return list.sortedByDescending {
            var score = diceCoefficient(it.title.lowercase(), query.lowercase())
            if (it.title.startsWith(query, true)) score = 1.0
            if (it.url.endsWith("/")) score += 0.5
            score
        }
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val n1 = s1.length
        val n2 = s2.length
        if (n1 == 0 || n2 == 0) return 0.0
        val bigrams1 = HashSet<String>()
        for (i in 0 until n1 - 1) bigrams1.add(s1.substring(i, i + 2))
        var intersection = 0
        for (i in 0 until n2 - 1) {
            val bigram = s2.substring(i, i + 2)
            if (bigrams1.contains(bigram)) intersection++
        }
        return (2.0 * intersection) / (n1 + n2 - 2).coerceAtLeast(1)
    }

    private fun getFolderThumb(url: String): String {
        if (!url.endsWith("/")) return ""
        val suffix = if (baseUrl.contains("172.16.50.9")) "a11.jpg" else "a_AL_.jpg"
        return fixUrl("$url$suffix")
    }

    override fun popularAnimeRequest(page: Int): Request {
        val path = when {
            baseUrl.contains("50.14") -> "$serverPath/Hindi%20Movies/%282026%29/"
            baseUrl.contains("50.12") -> "$serverPath/TV-WEB-Series/TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20L/"
            baseUrl.contains("50.9") -> "$serverPath/Anime%20%26%20Cartoon%20TV%20Series/Anime-TV%20Series%20%E2%99%A5%20%20A%20%20%E2%80%94%20%20F/"
            baseUrl.contains("50.7") -> "$serverPath/English%20Movies/%282026%29/"
            else -> ""
        }
        return GET("$baseUrl/$path", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select("div.card")
        val animeList = if (cards.isNotEmpty()) {
            cards.mapNotNull { card ->
                val link = card.selectFirst("h5 a") ?: return@mapNotNull null
                SAnime.create().apply {
                    title = link.text().trim()
                    url = fixUrl(link.attr("abs:href"))
                    val thumbElement = card.selectFirst("img[src~=(?i)a11|a_al|poster|banner|thumb|cover|front|folder], img:not([src~=(?i)back|parent|icon|/icons/|menu|nav])")
                    val thumbUrl = thumbElement?.let { 
                        it.attr("abs:data-src").ifEmpty { it.attr("abs:data-lazy-src").ifEmpty { it.attr("abs:src") } }
                    } ?: ""
                    thumbnail_url = if (thumbUrl.isNotEmpty()) fixUrl(thumbUrl) else ""
                }
            }
        } else {
            document.select("a").mapNotNull { element ->
                val titleStr = element.text().trim()
                val href = element.attr("abs:href")
                if (titleStr.isNotEmpty() && !isIgnored(titleStr) && !href.contains("?") && !href.endsWith("../")) {
                    SAnime.create().apply {
                        title = if (titleStr.endsWith("/")) titleStr.dropLast(1) else titleStr
                        url = fixUrl(href)
                        thumbnail_url = if (url.endsWith("/")) getFolderThumb(url) else ""
                    }
                } else null
            }
        }
        return AnimesPage(animeList, false)
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    override fun getFilterList(): AnimeFilterList {
        return AnimeFilterList(
            AnimeFilter.Header("--- Category ---"),
            DhakaFlixSelect("Select Category", serverCategories),
            DhakaFlixSelect("Select Year", FilterData.YEARS),
            DhakaFlixSelect("Select Alphabet / Number", FilterData.ALPHABET),
            DhakaFlixSelect("Select Language", FilterData.LANGUAGES)
        )
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return if (query.isNotEmpty()) GET("$baseUrl/$query", headers)
        else GET(fixUrl(Filters.getUrl(baseUrl, serverPath, filters)), headers)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (useTmdb) {
            try {
                fetchTmdbImage(anime.title)?.let { anime.thumbnail_url = it }
            } catch (e: Exception) {}
        }
        return anime
    }

    override fun animeDetailsRequest(anime: SAnime): Request = GET(fixUrl(anime.url), headers)

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val html = document.select("script").html()
        val isMovie = html.contains("/m/lazyload/")
        
        return SAnime.create().apply {
            status = if (isMovie) SAnime.COMPLETED else SAnime.ONGOING
            genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
            description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""
            val thumbElement = document.selectFirst("img[src~=(?i)a11|a_al|poster|banner|thumb|cover|front|folder], img:not([src~=(?i)back|parent|icon|/icons/|menu|nav])")
            var thumbUrl = thumbElement?.let { 
                it.attr("abs:data-src").ifEmpty { it.attr("abs:data-lazy-src").ifEmpty { it.attr("abs:src") } }
            } ?: ""
            if (thumbUrl.isEmpty()) {
                thumbUrl = document.selectFirst("""a[href~=(?i)\.(jpg|jpeg|png|webp)]:not([href~=(?i)back|parent|icon|menu])""")?.attr("abs:href") ?: ""
            }
            if (thumbUrl.isEmpty() && response.request.url.toString().endsWith("/")) {
                thumbUrl = getFolderThumb(response.request.url.toString())
            }
            thumbnail_url = if (thumbUrl.isNotEmpty()) fixUrl(thumbUrl) else ""
        }
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(45000) {
            val document = client.newCall(GET(fixUrl(anime.url), headers)).execute().asJsoup()
            val html = document.select("script").html()
            val episodes = when {
                html.contains("/m/lazyload/") -> getMovieMedia(document)
                html.contains("/s/lazyload/") -> {
                    val extracted = extractEpisodes(document)
                    if (extracted.isNotEmpty()) sortEpisodes(extracted) else parseDirectoryRecursive(document)
                }
                else -> parseDirectoryRecursive(document)
            }
            if (episodes.isEmpty()) throw Exception("No results found")
            episodes
        } ?: emptyList()
    }

    private fun extractEpisodes(document: Document): List<EpisodeData> {
        return document.select("div.card, div.episode-item, div.download-link").mapNotNull { element ->
            val titleElement = element.selectFirst("h5") ?: return@mapNotNull null
            val rawName = titleElement.ownText()
            val name = rawName.split("&nbsp;", "\u00A0").first().trim()
            val url = titleElement.selectFirst("a")?.attr("abs:href") ?: ""
            val q = element.selectFirst("h5 .badge-fill")?.text()?.let { 
                Regex("""(\d+\.\d+ [GM]B|\d+ [GM]B).*""").replace(it, "$1")
            } ?: ""
            val episodeName = element.selectFirst("h4")?.ownText()?.trim() ?: ""
            val size = element.selectFirst("h4 .badge-outline")?.text()?.trim() ?: ""
            if (name.isNotEmpty() && url.isNotEmpty()) EpisodeData(name, url, q, episodeName, size) else null
        }
    }

    private fun getMovieMedia(document: Document): List<SEpisode> {
        val url = document.select("div.col-md-12 a.btn, .movie-buttons a, a[href*=/m/lazyload/], a[href*=/s/lazyload/], .download-link a").lastOrNull()?.attr("abs:href")?.replace(" ", "%20") ?: ""
        val q = document.select(".badge-wrapper .badge-fill").lastOrNull()?.text()?.replace("|", "")?.trim() ?: ""
        return listOf(SEpisode.create().apply {
            this.url = url
            this.name = "Movie"
            this.episode_number = 1f
            this.scanlator = q
        })
    }

    private val semaphore = Semaphore(5)
    private suspend fun parseDirectoryRecursive(document: Document): List<SEpisode> = parseDir(document.location(), 2, document)

    private suspend fun parseDir(url: String, depth: Int, initialDoc: Document? = null): List<SEpisode> {
        if (depth < 0) return emptyList()
        val doc = initialDoc ?: try { client.newCall(GET(url, headers)).execute().asJsoup() } catch (e: Exception) { return emptyList() }
        val currentHttpUrl = doc.location().toHttpUrlOrNull() ?: return emptyList()
        val fileEpisodes = mutableListOf<SEpisode>()
        val subDirs = mutableListOf<String>()
        doc.select("a").forEach { element ->
            val href = element.attr("href")
            val text = element.text().trim()
            if (href.contains("..") || href.startsWith("?") || isIgnored(text)) return@forEach
            val absUrl = currentHttpUrl.resolve(href)?.toString() ?: return@forEach
            if (isVideoFile(href)) {
                fileEpisodes.add(SEpisode.create().apply {
                    this.url = absUrl
                    this.name = try { URLDecoder.decode(text, "UTF-8") } catch(e:Exception) { text }
                    this.episode_number = -1f
                })
            } else if (href.endsWith("/") || absUrl.endsWith("/")) subDirs.add(absUrl)
        }
        if (fileEpisodes.isNotEmpty()) return fileEpisodes.sortedBy { it.name }.reversed()
        val subDirEpisodes = coroutineScope {
            subDirs.map { async(Dispatchers.IO) { semaphore.withPermit { parseDir(it, depth - 1) } } }.awaitAll().flatten()
        }
        return (fileEpisodes + subDirEpisodes).distinctBy { it.url }.sortedBy { it.name }.reversed()
    }

    private fun isVideoFile(href: String): Boolean {
        val h = href.lowercase()
        return listOf(".mkv", ".mp4", ".avi", ".ts", ".m4v", ".webm", ".mov").any { h.endsWith(it) || h.contains("$it?") }
    }

    private fun sortEpisodes(list: List<EpisodeData>): List<SEpisode> {
        return list.sortedWith(compareBy<EpisodeData> { parseEpisodeNumber(it.seasonEpisode) }.thenBy { it.seasonEpisode }).map {
            SEpisode.create().apply {
                url = it.videoUrl
                name = if (it.seasonEpisode.isNotEmpty()) "${it.seasonEpisode} - ${it.episodeName}".trim() else it.episodeName
                episode_number = parseEpisodeNumber(it.seasonEpisode)
                scanlator = "${it.quality} ${it.size}".trim()
            }
        }.reversed()
    }

    private fun parseEpisodeNumber(text: String): Float {
        return try {
            val res = Regex("""(?i)(?:Episode|Ep|E|Vol)\.?\s*(\d+(\.\d+)?)""").find(text)
            if (res != null) {
                res.groupValues[1].toFloatOrNull() ?: 0f
            } else {
                Regex("""(\d+(\.\d+)?)""").find(text)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
            }
        } catch (e: Exception) { 0f }
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        val url = fixUrl(episode.url)
        val httpUrl = url.toHttpUrlOrNull()
        val referer = httpUrl?.let { "${it.scheme}://${it.host}/" } ?: "$baseUrl/"
        return listOf(Video(url, "Video", url, headers = headersBuilder().add("Referer", referer).build()))
    }
    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")
    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    data class EpisodeData(val seasonEpisode: String, val videoUrl: String, val quality: String, val episodeName: String, val size: String)
}
