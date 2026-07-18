package com.example

import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkPlayList
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.PlayListItem
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ServerLinkLabelFormatterTest {
    @Test
    fun `server renaming preserves playlist subtype and entries`() {
        val playlist = ExtractorLinkPlayList(
            source = "Raw Extractor",
            name = "Raw Extractor",
            playlist = listOf(PlayListItem("https://media.example/part.mp4", 1_000_000L)),
            referer = "https://player.example/",
            quality = 720,
            headers = emptyMap(),
            extractorData = null,
            type = ExtractorLinkType.VIDEO,
            audioTracks = emptyList()
        )

        assertSame(playlist, playlist.withSimpleServerName("KitaNonton"))
        assertEquals("https://media.example/part.mp4", playlist.playlist.single().url)
    }

    @Test
    fun `multipart link is rejected when a later entry fails verification`() = runBlocking {
        val good = "https://media.example/part-1.mp4"
        val dead = "https://media.example/part-2.mp4"
        val checked = Collections.synchronizedList(mutableListOf<String>())
        val playlist = ExtractorLinkPlayList(
            source = "Multipart",
            name = "Multipart",
            playlist = listOf(
                PlayListItem(good, 1_000_000L),
                PlayListItem(dead, 1_000_000L)
            ),
            referer = "https://player.example/",
            quality = 720,
            headers = emptyMap(),
            extractorData = null,
            type = ExtractorLinkType.VIDEO,
            audioTracks = emptyList()
        )

        val verified = validateExtractorPlaylist(playlist) { entry ->
            checked += entry.url
            entry.takeIf { it.url == good }
        }

        assertNull(verified)
        assertEquals(setOf(good, dead), checked.toSet())
    }

    @Suppress("DEPRECATION_ERROR")
    @Test
    fun `link is rejected when an auxiliary audio playlist is dead`() = runBlocking {
        val good = "https://media.example/audio-id.m3u8"
        val dead = "https://media.example/audio-dead.m3u8"
        val link = ExtractorLink(
            source = "IDLIX",
            name = "IDLIX 720p",
            url = "https://media.example/video.m3u8",
            referer = "https://z2.idlixku.com/movie/example",
            quality = 720,
            headers = emptyMap(),
            extractorData = null,
            type = ExtractorLinkType.M3U8,
            audioTracks = listOf(newAudioFile(good), newAudioFile(dead))
        )

        assertNull(
            validateAuxiliaryAudioTracks(link) { audio ->
                audio.takeIf { it.url == good }
            }
        )
    }

    @Test
    fun `uses player brand and appends known resolution once`() {
        assertEquals(
            "Abyss • 1080p",
            ServerLinkLabelFormatter.format(
                providerName = "KitaNonton",
                source = "KitaNonton",
                currentName = "KitaNonton KitaNonton 1080p",
                url = "https://cdn.sssrr.org/video.mp4",
                referer = "https://abyssplayer.com/embed/id",
                quality = 1080
            )
        )
    }

    @Test
    fun `removes provider repetition and generic server number`() {
        assertEquals(
            "Freeon • 720p",
            ServerLinkLabelFormatter.format(
                providerName = "Indoxxi",
                source = "Indoxxi",
                currentName = "Indoxxi Indoxxi Freeon Server 1 720p",
                url = "https://media.example/video.mp4",
                referer = "https://plyr.freeon.site/embed/id",
                quality = Qualities.Unknown.value
            )
        )
    }

    @Test
    fun `uses explicit label resolution when extractor quality is unknown`() {
        assertEquals(
            "OpenDrive • 720p",
            ServerLinkLabelFormatter.format(
                providerName = "Indoxxi",
                source = "OpenDrive",
                currentName = "OpenDrive 720p",
                url = "https://cdn.example/file",
                referer = "https://opendrive.com/embed/id",
                quality = Qualities.Unknown.value
            )
        )
    }

    @Test
    fun `does not invent resolution for unknown hls master or year`() {
        assertEquals(
            "AsiaStream",
            ServerLinkLabelFormatter.format(
                providerName = "Ngefilm",
                source = "Ngefilm",
                currentName = "Ngefilm HLS",
                url = "https://cdn.example/master.m3u8?movie=2026",
                referer = "https://watch.asiastream.cc/watch?id=1",
                quality = Qualities.Unknown.value
            )
        )
    }

    @Test
    fun `preserves meaningful extractor name`() {
        assertEquals(
            "StreamTape • 480p",
            ServerLinkLabelFormatter.format(
                providerName = "LayarKaca",
                source = "StreamTape",
                currentName = "StreamTape",
                url = "https://streamtape.com/get_video?id=2026",
                referer = "https://streamtape.com/e/id",
                quality = 480
            )
        )
    }

    @Test
    fun `provider quality label does not replace Byse server brand`() {
        assertEquals(
            "Byse • 1080p",
            ServerLinkLabelFormatter.format(
                providerName = "KitaNonton",
                source = "KitaNonton",
                currentName = "KitaNonton Full HD",
                url = "https://cdn.example/master.m3u8",
                referer = "https://bysebuho.com/embed/id",
                quality = 1080
            )
        )
    }

    @Test
    fun `quality words are removed while language qualifier is preserved`() {
        assertEquals(
            "Byse English • 1080p",
            ServerLinkLabelFormatter.format(
                providerName = "KitaNonton",
                source = "KitaNonton",
                currentName = "KitaNonton Full HD English",
                url = "https://cdn.example/master.m3u8",
                referer = "https://bysebuho.com/embed/id",
                quality = 1080
            )
        )
    }

    @Test
    fun `provider language label keeps player brand`() {
        assertEquals(
            "JustPlay English • 720p",
            ServerLinkLabelFormatter.format(
                providerName = "Filmapik",
                source = "Filmapik",
                currentName = "Filmapik English 720p",
                url = "https://cdn.example/master.m3u8",
                referer = "https://justplay.cam/e/id",
                quality = Qualities.Unknown.value
            )
        )
    }

    @Test
    fun `arbitrary ip is not mislabeled as JuicyCodes`() {
        assertEquals(
            "IP Server",
            ServerLinkLabelFormatter.format(
                providerName = "Filmapik",
                source = "Filmapik",
                currentName = "Filmapik HLS",
                url = "https://203.1.1.1/master.m3u8",
                referer = "https://203.1.1.1/embed/id",
                quality = Qualities.Unknown.value
            )
        )
    }

    @Test
    fun `explicit JuicyCodes label keeps its real server name`() {
        assertEquals(
            "JuicyCodes English • 1080p",
            ServerLinkLabelFormatter.format(
                providerName = "KitaNonton",
                source = "KitaNonton",
                currentName = "KitaNonton JuicyCodes English 1080p",
                url = "https://203.1.1.1/video.mp4",
                referer = "https://203.1.1.1/embed/id",
                quality = 1080
            )
        )
    }

    @Test
    fun `server words containing sub are not treated as subtitle qualifier`() {
        assertEquals(
            "Byse • 720p",
            ServerLinkLabelFormatter.format(
                providerName = "Filmapik",
                source = "Filmapik",
                currentName = "Filmapik Substream 720p",
                url = "https://cdn.example/master.m3u8",
                referer = "https://bysebuho.com/embed/id",
                quality = 720
            )
        )
    }

    @Test
    fun `renaming link preserves playback metadata`() = runBlocking {
        val original = newExtractorLink(
            "KitaNonton",
            "KitaNonton 1080p",
            "https://cdn.example/video.mp4",
            ExtractorLinkType.VIDEO
        ) {
            referer = "https://abyssplayer.com/embed/id"
            quality = 1080
            headers = mapOf("Referer" to "https://abyssplayer.com/embed/id")
            extractorData = "opaque-extractor-data"
        }

        val renamed = original.withSimpleServerName("KitaNonton")

        assertEquals("Abyss • 1080p", renamed.name)
        assertEquals(original.source, renamed.source)
        assertEquals(original.url, renamed.url)
        assertEquals(original.referer, renamed.referer)
        assertEquals(original.quality, renamed.quality)
        assertEquals(original.type, renamed.type)
        assertEquals(original.headers, renamed.headers)
        assertEquals(original.extractorData, renamed.extractorData)
    }

    @Test
    fun `resolution session normalizes generic extractor callback`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = KitanontonProvider(),
            subtitleCallback = {},
            callback = links::add,
            pageFetcher = { _, _ -> error("extractor emission must stop html fallback") },
            extractorLoader = { _, _, _, callback ->
                callback(
                    newExtractorLink(
                        "KitaNonton",
                        "KitaNonton KitaNonton 1080p",
                        "https://cdn.sssrr.org/video.mp4",
                        ExtractorLinkType.VIDEO
                    ) {
                        referer = "https://abyssplayer.com/embed/id"
                        quality = 1080
                        headers = mapOf("Referer" to "https://abyssplayer.com/embed/id")
                        extractorData = "opaque-extractor-data"
                    }
                )
                true
            },
            mediaLinkProbe = { it }
        )

        assertTrue(session.resolve("https://unknown.example/embed/id", "https://kitanonton2.surf/movie"))
        assertEquals("Abyss • 1080p", links.single().name)
        assertEquals("opaque-extractor-data", links.single().extractorData)
    }

    @Test
    fun `resolution session rejects unsafe external fallback without reporting loaded`() = runBlocking {
        val links = mutableListOf<ExtractorLink>()
        val session = LinkResolutionSession(
            api = FilmapikProvider(),
            subtitleCallback = {},
            callback = links::add
        )
        val unsafe = newExtractorLink(
            "Filmapik",
            "Filmapik 720p",
            "http://127.0.0.1/private.mp4",
            ExtractorLinkType.VIDEO
        ) {
            quality = 720
        }

        session.emitResolved(unsafe)

        assertFalse(session.loaded)
        assertTrue(links.isEmpty())
    }
}
