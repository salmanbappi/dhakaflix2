package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.concurrent.thread
import kotlin.text.RegexOption

class DhakaFlix2 : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "DhakaFlix 2"

    override val baseUrl = "http://172.16.50.9"

    override val lang = "all"

    override val supportsLatest = true

    override val id: Long = 5181466391484419841L

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0)
    }

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val requestUrl = original.url
            val referer = "${requestUrl.scheme}://${requestUrl.host}/"
            
            val newRequest = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Referer", referer)
                .build()
            
            chain.proceed(newRequest)
        }
        .cookieJar(object : okhttp3.CookieJar {
            private val cookieStore = mutableMapOf<String, List<okhttp3.Cookie>>()
            override fun saveFromResponse(url: HttpUrl, cookies: List<okhttp3.Cookie>) {
                cookieStore[url.host] = cookies
            }
            override fun loadForRequest(url: HttpUrl): List<okhttp3.Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        })
        .dispatcher(okhttp3.Dispatcher().apply {
            maxRequests = 40
            maxRequestsPerHost = 20
        })
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val tmdbKeyPref = EditTextPreference(screen.context).apply {
            key = PREF_TMDB_API_KEY
            title = "TMDb API Key"
            summary = "Used for high-quality covers. Get one at themoviedb.org"
            setDefaultValue("")
        }
        screen.addPreference(tmdbKeyPref)

        val useTmdbPref = SwitchPreferenceCompat(screen.context).apply {
            key = PREF_USE_TMDB_COVERS
            title = "Use TMDb Covers"
            summary = "Fetch high-quality covers from TMDb (Requires API Key). Note: Loads asynchronously to maintain speed."
            setDefaultValue(false)
        }
        screen.addPreference(useTmdbPref)

        SwitchPreferenceCompat(screen.context).apply {
            key = "clear_tmdb_cache"
            title = "Clear TMDb Cache"
            summary = "Clears all cached TMDb poster URLs"
            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    val editor = preferences.edit()
                    preferences.all.keys.filter { it.startsWith("tmdb_cover_") }.forEach {
                        editor.remove(it)
                    }
                    editor.apply()
                    android.widget.Toast.makeText(screen.context, "TMDb Cache Cleared", android.widget.Toast.LENGTH_SHORT).show()
                    this.isChecked = false
                }
                false
            }
            screen.addPreference(this)
        }
    }

    private fun fixUrl(url: String): String {
        if (url.isBlank()) return url
        var u = url.trim()
        
        val lastHttp = u.lastIndexOf("http://", ignoreCase = true)
        val lastHttps = u.lastIndexOf("https://", ignoreCase = true)
        val lastProtocol = if (lastHttp > lastHttps) lastHttp else lastHttps
        
        if (lastProtocol > 0) {
            u = u.substring(lastProtocol)
        }
        
        u = IP_HTTP_REGEX.replace(u, "$1/http")
        u = DOUBLE_PROTOCOL_REGEX.replace(u, "http$1://")
        u = u.replace(":://://", ":://")
        u = MULTI_SLASH_REGEX.replace(u, "/")
        
        return u.replace(" ", "%20").replace("&", "%26")
    }

    private val enrichmentSemaphore = Semaphore(10)

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (!useTmdb) return

        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return

        withTimeoutOrNull(5000) {
            coroutineScope {
                animes.map { anime ->
                    async {
                        enrichmentSemaphore.withPermit {
                            val tmdbCover = fetchTmdbImage(anime.title)
                            if (tmdbCover != null) {
                                anime.thumbnail_url = tmdbCover
                            }
                        }
                    }
                }.awaitAll()
            }
        }
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
        val res = if (query.isNotEmpty()) {
            withTimeoutOrNull(30000) {
                coroutineScope {
                    val servers = listOf(
                        "http://172.16.50.14" to "DHAKA-FLIX-14",
                        "http://172.16.50.12" to "DHAKA-FLIX-12",
                        "http://172.16.50.9" to "DHAKA-FLIX-9",
                        "http://172.16.50.7" to "DHAKA-FLIX-7"
                    )

                    val queries = listOf(query, "$query movie").distinct()

                    val results = servers.flatMap { server ->
                        queries.map { q ->
                            async(Dispatchers.IO) {
                                val serverResults = mutableListOf<SAnime>()
                                val timeout = 8000L
                                try {
                                    searchOnServerWithRetry(server.first, server.second, q, serverResults, timeout)
                                } catch (e: Exception) {}
                                serverResults
                            }
                        }
                    }.awaitAll().flatten().distinctBy { it.url }.take(50)

                    AnimesPage(sortByTitle(results, query), false)
                }
            } ?: AnimesPage(emptyList(), false)
        } else {
            super.getSearchAnime(page, query, filters)
        }
        
        return res.also { enrichAnimes(it.animes) }
    }

    private fun searchOnServerWithRetry(serverUrl: String, serverName: String, query: String, results: MutableList<SAnime>, timeout: Long, retries: Int = 1) {
        var lastException: Exception? = null
        for (i in 0 until retries) {
            try {
                searchOnServer(serverUrl, serverName, query, results, timeout)
                return
            } catch (e: Exception) {
                lastException = e
                if (i < retries - 1) {
                    Thread.sleep(500)
                }
            }
        }
        lastException?.let { throw it }
    }

    private fun searchOnServer(serverUrl: String, serverName: String, query: String, results: MutableList<SAnime>, timeout: Long) {
        val searchClient = client.newBuilder()
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()

        val searchUrl = "$serverUrl/$serverName/"
        val jsonPayload = "{\"action\":\"get\",\"search\":{\"href\":\"/$serverName/\",\"pattern\":\"$query\",\"ignorecase\":true}}"
        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = POST(searchUrl, headers, body)
        searchClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val bodyString = response.body?.string() ?: return
            
            if (serverUrl.contains("172.16.50.7")) {
                Server7Parser.parseServer7Response(bodyString, serverUrl, serverName, results)
                return
            }

            val hostUrl = serverUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return
            
            val pattern = Pattern.compile("\\\"href\\\":\\\"([^\\\"]+)\\\"[^}]*\\\"size\\\":(null|\\\\d+)", Pattern.CASE_INSENSITIVE)
            val matcher = pattern.matcher(bodyString)
            
            while (matcher.find()) {
                var href = matcher.group(1).replace('\\', '/').trim()
                href = href.replace(Regex("/+"), "/")
                
                var cleanHrefForTitle = href
                while (cleanHrefForTitle.endsWith("/")) {
                    cleanHrefForTitle = cleanHrefForTitle.dropLast(1)
                }
                
                val rawTitle = cleanHrefForTitle.substringAfterLast("/")
                val title = try {
                    URLDecoder.decode(rawTitle, "UTF-8").trim()
                } catch (e: Exception) {
                    rawTitle.trim()
                }
                
                if (title.isEmpty() || isIgnored(title)) continue
                
                val anime = SAnime.create().apply {
                    this.title = title
                    val finalHref = if (href.endsWith("/")) href else "$href/"
                    this.url = "$hostUrl$finalHref"
                    
                    val thumbSuffix = if (serverName.contains("9")) "a11.jpg" else "a_AL_.jpg"
                    this.thumbnail_url = (this.url + thumbSuffix).replace(" ", "%20").replace("&", "%26")
                }
                
                synchronized(results) {
                    if (results.none { it.url == anime.url }) {
                        results.add(anime)
                    }
                }
            }
        }
    }

    private fun isIgnored(text: String): Boolean {
        val ignored = listOf("Parent Directory", "modern browsers", "Name", "Last modified", "Size", "Description", "Index of", "JavaScript", "powered by", "_h5ai", "Subtitle", "Extras", "Sample", "Trailer")
        return ignored.any { text.contains(it, ignoreCase = true) }
    }

    private fun sortByTitle(list: List<SAnime>, query: String): List<SAnime> {
        val lowerQuery = query.lowercase()
        return list.sortedWith(compareByDescending<SAnime> {
            val lowerTitle = it.title.lowercase()
            when {
                lowerTitle == lowerQuery -> 100.0
                lowerTitle.startsWith(lowerQuery) -> 90.0 + diceCoefficient(it.title, query)
                else -> diceCoefficient(it.title, query)
            }
        }.thenBy { it.title.length })
    }

    private fun diceCoefficient(s1: String, s2: String): Double {
        val str1 = s1.lowercase()
        val str2 = s2.lowercase()
        if (str1.length < 2 || str2.length < 2) return 0.0
        
        val pairs1 = (0 until str1.length - 1).map { str1.substring(it, it + 2) }.toSet()
        val pairs2 = (0 until str2.length - 1).map { str2.substring(it, it + 2) }
        
        var intersection = 0
        for (pair in pairs2) {
            if (pair in pairs1) intersection++
        }
        
        return 2.0 * intersection / (str1.length + str2.length - 2)
    }

    override fun popularAnimeRequest(page: Int): Request {
        return GET("http://172.16.50.14/DHAKA-FLIX-14/Hindi%20Movies/%282025%29/", headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val cards = document.select("div.card")
        val animeList = if (cards.isNotEmpty()) {
            cards.mapNotNull { card ->
                val link = card.selectFirst("h5 a")
                val title = link?.text() ?: ""
                val url = link?.attr("abs:href") ?: ""
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    SAnime.create().apply {
                        this.title = title
                        this.url = fixUrl(url)
                        val img = card.selectFirst("img[src~=(?i)a11|a_al|poster|banner|thumb], img:not([src~=(?i)back|folder|parent|icon|/icons/])")
                        this.thumbnail_url = (img?.attr("abs:data-src")?.takeIf { it.isNotEmpty() } 
                            ?: img?.attr("abs:data-lazy-src")?.takeIf { it.isNotEmpty() }
                            ?: img?.attr("abs:src") ?: "").replace(" ", "%20")
                    }
                } else null
            }
        } else {
            document.select("a").mapNotNull { element ->
                val title = element.text().trim()
                val url = element.attr("abs:href")
                if (title.isNotEmpty() && isValidDirectoryItem(title, url)) {
                    SAnime.create().apply {
                        this.title = if (title.endsWith("/")) title.dropLast(1) else title
                        this.url = fixUrl(url)
                        val finalUrl = if (url.endsWith("/")) url else "$url/"
                        this.thumbnail_url = (finalUrl + "a_AL_.jpg").replace(" ", "%20").replace("&", "%26")
                    }
                } else null
            }
        }
        return AnimesPage(animeList, false)
    }

    private fun isValidDirectoryItem(title: String, url: String): Boolean {
        val lowerTitle = title.lowercase()
        if (isIgnored(lowerTitle)) return false
        if (url.contains("../") || url.contains("?")) return false
        return true
    }

    override fun latestUpdatesRequest(page: Int) = popularAnimeRequest(page)
    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    private var dynamicCategories: Array<String> = emptyArray()

    override fun getFilterList(): AnimeFilterList {
        if (dynamicCategories.isEmpty()) {
            fetchFilters()
        }
        return Filters.getFilterList(dynamicCategories)
    }

    private fun fetchFilters() {
        thread {
            try {
                val response = client.newCall(GET("$baseUrl/", headers)).execute()
                val doc = response.asJsoup()
                val sidebar = doc.select("ul.nav-sidebar a, .sidebar-menu a")
                dynamicCategories = sidebar.map { it.text().trim() }.filter { it.isNotEmpty() }.toTypedArray()
            } catch (e: Exception) {}
        }
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        return GET(fixUrl(Filters.getUrl(query, filters)), headers)
    }
    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    override suspend fun getAnimeDetails(anime: SAnime): SAnime {
        val parsedAnime = super.getAnimeDetails(anime)
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (useTmdb) {
            try {
                val tmdbCover = fetchTmdbImage(anime.title)
                if (tmdbCover != null) {
                    parsedAnime.thumbnail_url = tmdbCover
                    anime.thumbnail_url = tmdbCover
                }
            } catch (e: Exception) {}
        }
        return parsedAnime
    }

    private fun fetchTmdbImage(title: String): String? {
        val cacheKey = "tmdb_cover_${title.hashCode()}"
        val cached = preferences.getString(cacheKey, null)
        if (cached != null) return cached.takeIf { it.isNotEmpty() }

        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return null

        val cleanTitle = title.replace(Regex("(?i)\\(\\d{4}\\)|\\[\\d{4}\\]|\\d{3,4}p|HDTC|HDRip|WEB-DL|BluRay|HC|HQ|x264|x265|HEVC|H\\.264|H\\.265"), "").trim()
        val url = "https://api.themoviedb.org/3/search/multi".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("query", cleanTitle)
            .build()
        val request = Request.Builder().url(url).build()
        
        try {
            client.newCall(request).execute().use {
                val body = it.body?.string() ?: return null
                val json = JSONObject(body)
                val results = json.getJSONArray("results")
                if (results.length() > 0) {
                    val first = results.getJSONObject(0)
                    val posterPath = first.optString("poster_path")
                    if (posterPath.isNotEmpty() && posterPath != "null") {
                        val thumb = "https://image.tmdb.org/t/p/w500$posterPath"
                        preferences.edit().putString(cacheKey, thumb).apply()
                        return thumb
                    }
                }
            }
        } catch (e: Exception) {}
        
        preferences.edit().putString(cacheKey, "").apply()
        return null
    }

    override fun animeDetailsRequest(anime: SAnime): Request {
        return GET(fixUrl(anime.url), headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val mediaType = getMediaType(document)
        
        return if (mediaType != null) {
            if (mediaType == "m") getMovieDetails(document) else getSeriesDetails(document)
        } else {
            SAnime.create().apply {
                status = SAnime.COMPLETED
                val img = document.selectFirst("img[src~=(?i)a11|a_al|poster|banner|thumb], img:not([src~=(?i)back|folder|parent|icon|/icons/])")
                var thumb = img?.attr("abs:src") ?: ""
                if (thumb.isEmpty()) {
                    thumb = document.selectFirst("a[href~=(?i)\\\\.(jpg|jpeg|png|webp)]:not([href~=(?i)back|folder|parent|icon])")?.attr("abs:href") ?: ""
                }
                thumbnail_url = thumb.replace(" ", "%20")
            }
        }
    }

    private fun getMediaType(document: Document): String? {
        val html = document.select("script").html()
        return when {
            html.contains("/m/lazyload/") -> "m"
            html.contains("/s/lazyload/") -> "s"
            html.contains("/s/view/") -> "s"
            else -> null
        }
    }

    private fun getMovieDetails(document: Document) = SAnime.create().apply {
        status = SAnime.COMPLETED
        thumbnail_url = document.selectFirst("figure.movie-detail-banner img, .movie-detail-banner img, .col-md-3 img, .poster img")
            ?.attr("abs:src")?.replace(" ", "%20") ?: ""
        genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
        description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""
    }

    private fun getSeriesDetails(document: Document) = SAnime.create().apply {
        status = SAnime.ONGOING
        thumbnail_url = document.selectFirst("figure.movie-detail-banner img, .movie-detail-banner img, .col-md-3 img, .poster img")
            ?.attr("abs:src")?.replace(" ", "%20") ?: ""
        genre = document.select("div.ganre-wrapper a").joinToString { it.text().replace(",", "").trim() }
        description = document.selectFirst("p.storyline")?.text()?.trim() ?: ""
    }

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(30000) {
                val response = client.newCall(GET(fixUrl(anime.url), headers)).execute()
                val document = response.asJsoup()
                val mediaType = getMediaType(document)

                val episodes = when (mediaType) {
                    "s" -> {
                        val extracted = extractEpisodes(document)
                        if (extracted.isNotEmpty()) sortEpisodes(extracted) else parseDirectoryParallel(document)
                    }
                    "m" -> getMovieMedia(document)
                    else -> parseDirectoryParallel(document)
                }

                if (episodes.isEmpty()) throw Exception("No results found")
                episodes
            } ?: emptyList()
        }
    }

    private fun extractEpisodes(document: Document): List<EpisodeData> {
        return document.select("div.card, div.episode-item, div.download-link").mapNotNull { element ->
            val titleElement = element.selectFirst("h5") ?: return@mapNotNull null
            val rawTitle = titleElement.ownText().trim()
            val name = rawTitle.split("&nbsp;").first().trim()
            val url = element.selectFirst("h5 a")?.attr("abs:href")?.trim() ?: ""
            val qualityText = element.selectFirst("h5 .badge-fill")?.text() ?: ""
            val quality = sizeRegex.replace(qualityText, "$1").trim()
            val epName = element.selectFirst("h4")?.ownText()?.trim() ?: ""
            val size = element.selectFirst("h4 .badge-outline")?.text()?.trim() ?: ""
            
            if (name.isNotEmpty() && url.isNotEmpty()) {
                EpisodeData(name, url, quality, epName, size)
            } else null
        }
    }

    private fun getMovieMedia(document: Document): List<SEpisode> {
        val linkElement = document.select("div.col-md-12 a.btn, .movie-buttons a, a[href*=/m/lazyload/], a[href*=/s/lazyload/], .download-link a").lastOrNull()
        val url = linkElement?.attr("abs:href")?.let { it.replace(" ", "%20") } ?: ""
        val quality = document.select(".badge-wrapper .badge-fill").lastOrNull()?.text()?.replace("|", "")?.trim() ?: ""
        
        return listOf(SEpisode.create().apply {
            this.url = url
            this.name = "Movie"
            this.episode_number = 1f
            this.scanlator = quality
        })
    }

    private val semaphore = Semaphore(10)

    private suspend fun parseDirectoryParallel(document: Document): List<SEpisode> {
        return parseDirectory(document.location() ?: "", 3, document)
    }

    private suspend fun parseDirectory(url: String, depth: Int = 3, initialDocument: Document? = null): List<SEpisode> {
        if (depth < 0) return emptyList()

        return try {
            val document: Document
            val currentHttpUrl: HttpUrl

            if (initialDocument != null) {
                document = initialDocument
                currentHttpUrl = document.location()?.toHttpUrlOrNull() ?: return emptyList()
            } else {
                val response = client.newCall(GET(url, headers)).execute()
                document = response.asJsoup()
                currentHttpUrl = response.request.url
            }

            val normalizedBaseUrl = if (url.endsWith("/")) url else "$url/"

            val fileEpisodes = mutableListOf<SEpisode>()
            val subDirs = mutableListOf<String>()

            document.select("a").forEach { element ->
                val href = element.attr("href")
                val text = element.text().trim()

                if (href.contains("..") || href.startsWith("?") || href.contains("_h5ai") || 
                    href.contains("?C=") || href.contains("?O=") || isIgnored(text)) return@forEach

                val absHttpUrl = currentHttpUrl.resolve(href) ?: return@forEach
                val absUrl = absHttpUrl.toString()

                if (!absUrl.startsWith(normalizedBaseUrl)) return@forEach

                if (isVideoFile(href)) {
                    fileEpisodes.add(SEpisode.create().apply {
                        this.url = absUrl
                        this.name = try { URLDecoder.decode(text, "UTF-8") } catch(e:Exception) { text }
                        this.episode_number = -1f
                    })
                } else if (href.endsWith("/") || absUrl.endsWith("/")) {
                     subDirs.add(absUrl)
                }
            }

            if (fileEpisodes.isNotEmpty()) {
                return fileEpisodes.sortedBy { it.name }.reversed()
            }

            coroutineScope {
                subDirs.map { dir ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            parseDirectory(dir, depth - 1)
                        }
                    }
                }.awaitAll().flatten()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isVideoFile(href: String): Boolean {
        val h = href.lowercase()
        return listOf(".mkv", ".mp4", ".avi", ".ts", ".m4v", ".webm", ".mov").any { h.endsWith(it) || h.contains("$it?") }
    }

    private fun sortEpisodes(list: List<EpisodeData>): List<SEpisode> {
        var episodeCount = 0f
        return list.map {
            SEpisode.create().apply {
                url = it.videoUrl
                name = "${it.seasonEpisode} - ${it.episodeName}".trim()
                episode_number = ++episodeCount
                scanlator = "${it.quality}  ${it.size}"
            }
        }.reversed()
    }

    override suspend fun getVideoList(episode: SEpisode): List<Video> {
        return listOf(Video(episode.url, "Video", episode.url))
    }

    override fun episodeListParse(response: Response): List<SEpisode> = throw Exception("Not used")
    override fun videoListParse(response: Response): List<Video> = throw Exception("Not used")

    data class EpisodeData(
        val seasonEpisode: String,
        val videoUrl: String,
        val quality: String,
        val episodeName: String,
        val size: String
    )

    companion object {
        private const val PREF_TMDB_API_KEY = "tmdb_api_key"
        private const val PREF_USE_TMDB_COVERS = "use_tmdb_covers"
        private val sizeRegex = Regex("(\\d+\\.\\d+ [GM]B|\\d+ [GM]B).*")
        private val IP_HTTP_REGEX = Regex("(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\s*http", RegexOption.IGNORE_CASE)
        private val DOUBLE_PROTOCOL_REGEX = Regex("http(s)?://http(s)?://", RegexOption.IGNORE_CASE)
        private val MULTI_SLASH_REGEX = Regex("(?<!:)/{2,}")
    }
}