package eu.kanade.tachiyomi.animeextension.all.dhakaflix2

import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.AnimeSourceFactory

class DhakaFlixFactory : AnimeSourceFactory {
    override fun createSources(): List<AnimeSource> = listOf(
        DhakaFlixHindi(),
        DhakaFlixTV(),
        DhakaFlixAnime(),
        DhakaFlixEnglish()
    )
}
