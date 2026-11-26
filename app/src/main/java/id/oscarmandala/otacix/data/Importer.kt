package id.oscarmandala.otacix.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Impor playlist dari tautan.
 *  - YouTube / YouTube Music: langsung (NewPipeExtractor), id lagunya sudah id YouTube.
 *  - Spotify: daftar lagu dibaca dari halaman "embed" Spotify (tanpa login/kunci API, maksimal sekitar 100 lagu),
 *    lalu tiap lagu dicocokkan ke YouTube (artis + judul + durasi) karena audio Otacix berasal dari YouTube.
 */
object Importer {
    class Imported(val name: String, val tracks: List<Track>, val missed: Int, val source: String)

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true).build()

    /** [onProgress] (selesai, total): dipanggil saat mencocokkan lagu Spotify ke YouTube. */
    suspend fun importLink(input: String, onProgress: (Int, Int) -> Unit = { _, _ -> }): Imported {
        val s = input.trim()
        if (s.isEmpty()) throw IOException("Tempel link playlist dulu")
        return when {
            s.contains("spotify", ignoreCase = true) -> spotify(s, onProgress)
            s.contains("youtu", ignoreCase = true) || Regex("^(PL|OLAK5uy|UU|FL)[A-Za-z0-9_-]{10,}$").matches(s) -> youtube(s)
            else -> throw IOException("Link tidak dikenali. Pakai link playlist Spotify atau YouTube.")
        }
    }

    // ------------------------------------------------------------------ YouTube
    private suspend fun youtube(s: String): Imported {
        val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(s)?.groupValues?.get(1)
            ?: s.takeIf { Regex("^[A-Za-z0-9_-]{12,}$").matches(it) }
            ?: throw IOException("Link YouTube ini bukan playlist (harus ada list=…)")
        if (id.startsWith("RD")) throw IOException("Mix/radio otomatis YouTube tidak bisa diimpor. Pakai playlist biasa.")
        if (id == "WL" || id == "LL") throw IOException("Playlist pribadi (Tonton Nanti / Disukai) tidak bisa diimpor. Jadikan playlist publik dulu.")
        val (name, tracks) = try {
            Yt.playlistTracks(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gagal membaca playlist YouTube (pastikan publik atau tidak publik-terdaftar): " + (e.message ?: "").lineSequence().firstOrNull().orEmpty().take(80))
        }
        if (tracks.isEmpty()) throw IOException("Playlist kosong atau tidak bisa dibaca")
        return Imported(name, tracks, 0, "YouTube")
    }

    // ------------------------------------------------------------------ Spotify
    private class SpTrack(val title: String, val artist: String, val durSec: Int)

    private suspend fun fetch(url: String): Pair<String, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "en").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Spotify HTTP ${r.code}")
            (r.request.url.toString()) to (r.body?.string().orEmpty())
        }
    }

    private suspend fun spotify(input: String, onProgress: (Int, Int) -> Unit): Imported {
        var link = input
        val idRe = Regex("(playlist|album)[/:]([A-Za-z0-9]{22})")
        if (idRe.find(link) == null && link.startsWith("http")) {
            // link pendek (spotify.link / spotify.app.link): ikuti pengalihan untuk mendapat link aslinya
            link = try { fetch(link).first } catch (e: CancellationException) { throw e } catch (e: Exception) { link }
        }
        val m = idRe.find(link) ?: throw IOException("Link Spotify tidak dikenali. Pakai link playlist (open.spotify.com/playlist/…)")
        val type = m.groupValues[1]
        val id = m.groupValues[2]

        val html = try {
            fetch("https://open.spotify.com/embed/$type/$id").second
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("Gagal membuka Spotify: " + (e.message ?: "jaringan"))
        }
        val json = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1)
            ?: throw IOException("Halaman Spotify tidak bisa dibaca (playlist pribadi, atau format Spotify berubah)")
        val root = JSONObject(json)
        val entity = root.optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity")
        val name = entity?.optString("name").orEmpty().ifBlank { entity?.optString("title").orEmpty() }
            .ifBlank { if (type == "album") "Album Spotify" else "Playlist Spotify" }

        val arr = entity?.optJSONArray("trackList") ?: findTrackArray(root)
            ?: throw IOException("Daftar lagu tidak ditemukan. Pastikan playlist-nya publik.")
        val sp = ArrayList<SpTrack>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            val artist = o.optString("subtitle").replace('\u00a0', ' ').trim()
            val ms = o.optLong("duration", 0L)
            sp += SpTrack(title, artist, (ms / 1000L).toInt())
        }
        if (sp.isEmpty()) throw IOException("Playlist kosong atau tidak bisa dibaca")

        // cocokkan ke YouTube, 3 pencarian paralel
        val done = AtomicInteger(0)
        val gate = Semaphore(3)
        onProgress(0, sp.size)
        val matched: List<Track?> = coroutineScope {
            sp.map { t ->
                async {
                    val r = gate.withPermit { match(t) }
                    onProgress(done.incrementAndGet(), sp.size)
                    r
                }
            }.awaitAll()
        }
        val tracks = matched.filterNotNull().distinctBy { it.id }
        if (tracks.isEmpty()) throw IOException("Tidak ada lagu yang cocok di YouTube")
        return Imported(name, tracks, sp.size - matched.count { it != null }, "Spotify")
    }

    private suspend fun match(t: SpTrack): Track? {
        val hits = try {
            Yt.search("${t.artist} ${t.title}".trim()).items
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        if (hits.isEmpty()) return null
        // pilih hasil yang durasinya paling dekat dengan versi Spotify (menghindari video musik berintro / versi live)
        val best = if (t.durSec > 0) hits.take(6).minByOrNull { abs(it.dur - t.durSec) } ?: hits.first() else hits.first()
        return Track(best.id, t.title, t.artist, if (t.durSec > 0) t.durSec else best.dur, best.cover)
    }

    /** Cadangan bila struktur JSON Spotify berubah: cari array objek yang punya "title" dan "subtitle". */
    private fun findTrackArray(o: Any?): JSONArray? {
        when (o) {
            is JSONObject -> {
                val keys = o.keys()
                while (keys.hasNext()) findTrackArray(o.opt(keys.next()))?.let { return it }
            }
            is JSONArray -> {
                val first = o.optJSONObject(0)
                if (first != null && first.has("title") && first.has("subtitle")) return o
                for (i in 0 until o.length()) findTrackArray(o.opt(i))?.let { return it }
            }
        }
        return null
    }
}
