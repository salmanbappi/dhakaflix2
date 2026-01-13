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

    private val searchCache = mutableMapOf<String, List<SAnime>>()
    private val cacheTime = mutableMapOf<String, Long>()

    override val client: OkHttpClient = super.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .connectionPool(okhttp3.ConnectionPool(15, 5, TimeUnit.MINUTES))
        .addInterceptor {
            val original = chain.request()
            val requestUrl = original.url
            val referer = "${requestUrl.scheme}://${requestUrl.host}/"
            
            val newRequest = original.newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "*/*")
                .header("Referer", referer)
                .header("Connection", "keep-alive")
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
            maxRequestsPerHost = 10 // Lowered to be gentler on slow BDIX servers
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
            setOnPreferenceChangeListener {
                _, newValue ->
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

    private val enrichmentSemaphore = Semaphore(3) // Further lowered to prioritize main loading

    private suspend fun enrichAnimes(animes: List<SAnime>) {
        val useTmdb = preferences.getBoolean(PREF_USE_TMDB_COVERS, false)
        if (!useTmdb) return

        val apiKey = preferences.getString(PREF_TMDB_API_KEY, "") ?: ""
        if (apiKey.isBlank()) return

        withTimeoutOrNull(5000) {
            coroutineScope {
                animes.take(12).map { anime -> // Even fewer for faster navigation
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
        if (query.isEmpty()) return super.getSearchAnime(page, query, filters)

        val now = System.currentTimeMillis()
        if (searchCache.containsKey(query) && now - (cacheTime[query] ?: 0) < 600000) {
            return AnimesPage(searchCache[query]!!, false)
        }

        val res = withTimeoutOrNull(60000) {
            coroutineScope {
                val searchTasks = mutableListOf<Triple<String, String, String>>()
                listOf(
                    "http://172.16.50.14" to "DHAKA-FLIX-14",
                    "http://172.16.50.12" to "DHAKA-FLIX-12",
                    "http://172.16.50.9" to "DHAKA-FLIX-9",
                    "http://172.16.50.7" to "DHAKA-FLIX-7"
                ).forEach { (baseUrl, serverName) ->
                    searchTasks.add(Triple(baseUrl, serverName, "/$serverName/"))
                    // Specifically target the slow Server 9 subpaths if looking for Anime
                    if (serverName == "DHAKA-FLIX-9" && (query.contains("Doraemon", true) || query.contains("Anime", true))) {
                         searchTasks.add(Triple(baseUrl, serverName, "/$serverName/Anime & Cartoon TV Series/"))
                    }
                }

                val allResults = searchTasks.distinct().map {
                    async(Dispatchers.IO) {
                        val serverResults = mutableListOf<SAnime>()
                        try {
                            searchOnServer(it.first, it.second, query, serverResults, 25000L, it.third)
                        } catch (e: Exception) {}
                        serverResults
                    }
                }.awaitAll().flatten().distinctBy { it.url }

                val folders = allResults.filter { it.url.endsWith("/") }
                val files = allResults.filter { !it.url.endsWith("/") }
                
                val finalResults = if (folders.size >= 3) {
                    (folders + files.take(20)).take(100)
                } else {
                    allResults.take(100)
                }

                sortByTitle(finalResults, query)
            }
        } ?: emptyList()
        
        searchCache[query] = res
        cacheTime[query] = now
        
        return AnimesPage(res, false).also { enrichAnimes(it.animes) }
    }

    private fun searchOnServer(serverUrl: String, serverName: String, query: String, results: MutableList<SAnime>, timeout: Long, path: String) {
        val searchUrl = "$serverUrl/$serverName/"
        val jsonPayload = "{\"action\":\"get\",\"search\":{\"href\":\"$path\",\"pattern\":\"$query\",\"ignorecase\":true}}"
        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = POST(searchUrl, headers, body)
        client.newCall(request).execute().use {
            if (!it.isSuccessful) return
            val bodyString = it.body?.string() ?: return
            
            if (serverUrl.contains("172.16.50.7")) {
                Server7Parser.parseServer7Response(bodyString, serverUrl, serverName, results, query)
                return
            }

            val hostUrl = serverUrl.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: return
            
            val pattern = Pattern.compile("\\\"href\\\":\\\"([^\\\"]+)\\\\