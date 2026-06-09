package com.example

import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Test

class AnimeTypeTest {

    @Test
    fun getAnimeType_ova() {
        assertEquals(TvType.OVA, getAnimeType("OVA"))
    }

    @Test
    fun getAnimeType_special() {
        assertEquals(TvType.OVA, getAnimeType("Special"))
    }

    @Test
    fun getAnimeType_movie() {
        assertEquals(TvType.AnimeMovie, getAnimeType("Movie"))
    }

    @Test
    fun getAnimeType_tv() {
        assertEquals(TvType.Anime, getAnimeType("TV"))
    }

    @Test
    fun getAnimeType_emptyDefault() {
        assertEquals(TvType.Anime, getAnimeType(""))
    }

    @Test
    fun getAnimeType_caseInsensitive() {
        assertEquals(TvType.OVA, getAnimeType("ova special edition"))
    }
}

class AnimeStatusTest {

    @Test
    fun getAnimeStatus_completed() {
        assertEquals(ShowStatus.Completed, getAnimeStatus("Completed"))
    }

    @Test
    fun getAnimeStatus_ongoing() {
        assertEquals(ShowStatus.Ongoing, getAnimeStatus("Ongoing"))
    }

    @Test
    fun getAnimeStatus_unknown() {
        assertEquals(ShowStatus.Completed, getAnimeStatus("Unknown"))
    }
}

class OtakudesuCompanionTest {

    @Test
    fun mirrorBlackList_contains() {
        val blacklist = OtakudesuProvider.mirrorBlackList
        assertEquals(3, blacklist.size)
        assert("Mega" in blacklist)
        assert("MegaUp" in blacklist)
        assert("Otakufiles" in blacklist)
    }
}
