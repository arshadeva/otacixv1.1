package id.oscarmandala.otacix.ui

import android.os.Build
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import id.oscarmandala.otacix.OtacixApp
import id.oscarmandala.otacix.data.Lyrics
import id.oscarmandala.otacix.data.LyricsData
import id.oscarmandala.otacix.util.Tier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LStatus { LOADING, FOUND, NONE, FAILED }

/**
 * Layar kunci lirik: jam & tanggal besar, lirik berjalan (baris aktif terisi putih mengikuti lagu),
 * kartu pemutar kaca, dan "Geser ke atas untuk membuka". Dipakai LockLyricsActivity (tampil di atas keyguard).
 * [resumed] = layar sedang menyala; animasi/pewaktu berhenti saat layar mati supaya hemat baterai.
 */
@Composable
fun LockLyricsScreen(resumed: Boolean, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    val tier = LocalTier.current
    val player = OtacixApp.player
    val p by player.ui.collectAsStateWithLifecycle()
    val prog by player.progress.collectAsStateWithLifecycle()
    val t = p.track

    // warna latar mengikuti sampul (seperti di contoh: merah dari sampul)
    var accent by remember { mutableStateOf(BlueBright) }
    LaunchedEffect(t?.cover) {
        val url = t?.cover ?: return@LaunchedEffect
        PaletteUtil.accentFor(ctx, url)?.let { accent = it }
    }

    // lirik
    var status by remember(t?.id) { mutableStateOf(LStatus.LOADING) }
    var data by remember(t?.id) { mutableStateOf<LyricsData?>(null) }
    LaunchedEffect(t?.id) {
        val tr = t ?: return@LaunchedEffect
        status = LStatus.LOADING
        try {
            val d = Lyrics.load(tr)
            data = d
            status = if (d == null) LStatus.NONE else LStatus.FOUND
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = LStatus.FAILED
        }
    }

    // posisi lagu yang dihaluskan (progres dari pemutar hanya masuk tiap 500 ms)
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(resumed, p.playing, prog.pos, p.speed) {
        val t0 = SystemClock.elapsedRealtime()
        pos = prog.pos
        if (!resumed) return@LaunchedEffect
        while (p.playing) {
            pos = prog.pos + ((SystemClock.elapsedRealtime() - t0) * p.speed).toLong()
            delay(50)
        }
    }

    // jam
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(resumed) {
        while (resumed) { now = System.currentTimeMillis(); delay(1000) }
    }
    val loc = remember { Locale("id", "ID") }
    val clockText = remember(now / 60_000L) { SimpleDateFormat("HH.mm", loc).format(Date(now)) }
    val dateText = remember(now / 60_000L) { SimpleDateFormat("EEEE d MMMM", loc).format(Date(now)) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { if (acc < -140f) onUnlock(); acc = 0f },
                    onDragCancel = { acc = 0f },
                ) { _, d -> acc += d }
            }
    ) {
        // latar
        if (tier == Tier.FULL && Build.VERSION.SDK_INT >= 31 && t != null) {
            AsyncImage(
                model = t.cover, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(90.dp).graphicsLayer { alpha = 0.55f },
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(lerp(accent, Color.Black, 0.30f).copy(alpha = 0.92f), lerp(accent, Color.Black, 0.78f).copy(alpha = 0.97f)))
            )
        )

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Ic.Lock, null, Modifier.padding(top = 18.dp).size(20.dp), tint = Color.White.copy(alpha = 0.85f))
            Txt(dateText, 20.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.92f), modifier = Modifier.padding(top = 6.dp))
            Text(
                clockText, color = Color.White.copy(alpha = 0.93f), fontSize = 104.sp, fontWeight = FontWeight.Bold,
                letterSpacing = (-2).sp, maxLines = 1,
            )

            // ---- lirik ----
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopStart) {
                val d = data
                when {
                    t == null -> Unit
                    status == LStatus.LOADING -> Txt("Memuat lirik…", 18.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 20.dp))
                    status == LStatus.FOUND && d != null && d.synced -> LockLyricList(d, pos)
                    status == LStatus.FOUND -> Txt("Lirik lagu ini tidak bertanda waktu, jadi tidak bisa berjalan di layar kunci.", 18.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 20.dp))
                    else -> Txt("Lirik lagu ini belum ditemukan.", 18.sp, FontWeight.SemiBold, Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 20.dp))
                }
            }

            // ---- kartu pemutar ----
            if (t != null) {
                val frac = if (prog.dur > 0) (pos.toFloat() / prog.dur).coerceIn(0f, 1f) else 0f
                Column(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(36.dp), tint = accent, strength = 1.6f).padding(horizontal = 18.dp, vertical = 16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Cover(t.cover, Modifier.size(56.dp), 14.dp)
                        Column(Modifier.weight(1f)) {
                            Txt(t.title, 17.sp, FontWeight.Bold, maxLines = 1)
                            Txt(t.artist, 14.sp, color = Color.White.copy(alpha = 0.65f), maxLines = 1)
                        }
                    }
                    Box(Modifier.padding(top = 16.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                        Txt(fmtTime(pos), 12.sp, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.weight(1f))
                        Txt("-" + fmtTime((prog.dur - pos).coerceAtLeast(0L)), 12.sp, color = Color.White.copy(alpha = 0.65f))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconBtn(Ic.Prev, 56.dp, 30.dp) { player.prev() }
                        IconBtn(if (p.playing) Ic.Pause else Ic.Play, 64.dp, 38.dp) { player.toggle() }
                        IconBtn(Ic.Next, 56.dp, 30.dp) { player.next() }
                    }
                }
            }

            Txt("Geser ke atas untuk membuka", 15.sp, FontWeight.Medium, Color.White.copy(alpha = 0.7f), align = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
            Box(Modifier.padding(top = 12.dp, bottom = 10.dp).width(150.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.85f)))
        }
    }
}

/** Daftar lirik besar: satu baris sebelum baris aktif, baris aktif terisi putih per huruf mengikuti lagu, lalu baris-baris berikutnya meredup. */
@Composable
private fun LockLyricList(d: LyricsData, posMs: Long) {
    val state = rememberLazyListState()
    val cur = d.lines.indexOfLast { it.timeMs <= posMs + 150L }

    LaunchedEffect(cur) {
        state.animateScrollToItem((cur - 1).coerceAtLeast(0))
    }

    LazyColumn(
        state = state, userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 60.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(d.lines) { i, l ->
            val dim = Color.White.copy(
                alpha = when {
                    i < cur -> 0.28f
                    i == cur -> 0.42f
                    else -> (0.55f - 0.07f * (i - cur - 1)).coerceAtLeast(0.22f)
                }
            )
            if (i == cur) {
                val start = l.timeMs
                val next = d.lines.getOrNull(i + 1)?.timeMs ?: (start + 4000L)
                val span = ((next - start) * 0.85f).coerceIn(600f, 7000f)
                val frac = ((posMs - start) / span).coerceIn(0f, 1f)
                val n = (l.text.length * frac).toInt()
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = Color.White)) { append(l.text.take(n)) }
                        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.42f))) { append(l.text.drop(n)) }
                    },
                    fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp, modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(l.text, color = dim, fontSize = 31.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
