package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class DhakaFlixFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        DhakaFlix2("DhakaFlix 14", "http://172.16.50.14", 5181466391484419941L),
        DhakaFlix2("DhakaFlix 12", "http://172.16.50.12", 5181466391484419942L),
        DhakaFlix2("DhakaFlix 9", "http://172.16.50.9", 5181466391484419943L),
        DhakaFlix2("DhakaFlix 7", "http://172.16.50.7", 5181466391484419944L),
    )
}