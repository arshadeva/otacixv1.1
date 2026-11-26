package id.oscarmandala.otacix.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import id.oscarmandala.otacix.OtacixApp
import id.oscarmandala.otacix.data.SearchResult
import id.oscarmandala.otacix.data.Track
import id.oscarmandala.otacix.player.PlayerUi
import id.oscarmandala.otacix.util.Device
import id.oscarmandala.otacix.util.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Status navigasi sederhana (tanpa library navigasi tambahan -> ringan untuk HP spek rendah). */
class UiState {
    var tab by mutableIntStateOf(0)
    var playerOpen by mutableStateOf(false)
    var playlist by mutableStateOf<String?>(null)     // "liked" atau id playlist
    var poster by mutableStateOf(false)
    var posterFor by mutableStateOf<String?>(null)
    var pendingSearch by mutableStateOf<String?>(null)
    var menu by mutableStateOf<Track?>(null)
    var menuExtra by mutableStateOf<Pair<String, () -> Unit>?>(null)
    var addTo by mutableStateOf<Track?>(null)

    // status pencarian disimpan di sini agar tidak hilang saat pindah tab
    var sq by mutableStateOf("")
    var sres by mutableStateOf<SearchResult?>(null)
    var sload by mutableStateOf(false)

    val lists = List(4) { LazyListState() }

    fun openMenu(t: Track, extra: Pair<String, () -> Unit>? = null) { menuExtra = extra; menu = t }
}

object PaletteUtil {
    /** Warna dominan sampul (gambar kecil 96px supaya ringan). */
    suspend fun accentFor(ctx: Context, url: String): Color? = withContext(Dispatchers.Default) {
        val res = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(url).size(96).allowHardware(false).build())
        val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@withContext null
        val p = Palette.from(bmp).maximumColorCount(12).generate()
        val sw = p.vibrantSwatch ?: p.lightVibrantSwatch ?: p.dominantSwatch ?: return@withContext null
        Color(sw.rgb)
    }
}

@Composable
fun OtacixRoot() {
    val ctx = LocalContext.current
    val db by OtacixApp.store.db.collectAsStateWithLifecycle()
    val tier = remember(db.perf) { Device.resolve(ctx, db.perf).also { OtacixApp.tier = it } }
    val tilt = rememberTilt(tier == Tier.FULL)
    val ui = remember { UiState() }
    var splash by rememberSaveable { mutableStateOf(true) }
    var support by rememberSaveable { mutableStateOf(true) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(splash) {
        if (!splash && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    CompositionLocalProvider(LocalTier provides tier, LocalTilt provides tilt) {
        OtacixTheme {
            Box(Modifier.fillMaxSize().background(Navy)) {
                AppContent(ui)
                if (splash) SplashOverlay { splash = false }
                if (!splash && support) SupportDialog { support = false }
            }
        }
    }
}

@Composable
private fun AppContent(ui: UiState) {
    val ctx = LocalContext.current
    val tier = LocalTier.current
    val player = OtacixApp.player
    val p by player.ui.collectAsStateWithLifecycle()
    var accent by remember { mutableStateOf(BlueBright) }

    LaunchedEffect(p.track?.cover, tier) {
        val url = p.track?.cover
        accent = if (tier == Tier.FULL && url != null) {
            PaletteUtil.accentFor(ctx, url)?.let { lerp(it, Blue, 0.3f) } ?: accent
        } else BlueBright
    }

    // urutan BackHandler: yang terakhir didaftarkan menang -> pemutar paling atas
    BackHandler(enabled = ui.playlist != null) { ui.playlist = null }
    BackHandler(enabled = ui.poster) { ui.poster = false }
    BackHandler(enabled = ui.playerOpen) { ui.playerOpen = false }

    var lastPlaylist by remember { mutableStateOf("liked") }
    ui.playlist?.let { lastPlaylist = it }

    Box(Modifier.fillMaxSize()) {
        AuroraBackground(accent)

        Box(Modifier.fillMaxSize()) {
            when (ui.tab) {
                0 -> HomeScreen(ui)
                1 -> SearchScreen(ui)
                2 -> LibraryScreen(ui)
                else -> ProfileScreen(ui)
            }
        }

        AnimatedVisibility(
            visible = ui.playlist != null,
            enter = slideInHorizontally(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(260)) { it } + fadeOut(tween(200)),
        ) {
            Box(Modifier.fillMaxSize().background(Navy)) {
                AuroraBackground(accent)
                PlaylistScreen(ui, lastPlaylist)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MiniPlayer(ui, p)
            NavBar(ui)
        }

        AnimatedVisibility(
            visible = ui.poster,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
        ) {
            Box(Modifier.fillMaxSize().background(Navy)) {
                AuroraBackground(accent)
                PosterScreen(ui)
            }
        }

        AnimatedVisibility(
            visible = ui.playerOpen,
            enter = slideInVertically(tween(420)) { it },
            exit = slideOutVertically(tween(300)) { it },
        ) { PlayerScreen(ui) }

        ui.menu?.let { TrackMenu(it, ui) }
        ui.addTo?.let { AddToPlaylistDialog(it, ui) }
    }
}

@Composable
private fun NavBar(ui: UiState) {
    val tier = LocalTier.current
    val items = listOf("Beranda" to Ic.Home, "Cari" to Ic.Search, "Pustaka" to Ic.Library, "Kamu" to Ic.Person)
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    BoxWithConstraints(Modifier.padding(horizontal = 14.dp).widthIn(max = 560.dp).fillMaxWidth()) {
        // badan bar: kaca bening (tidak dipotong, supaya gelembung boleh membesar keluar dari bar)
        Box(Modifier.matchParentSize().glass(RoundedCornerShape(34.dp), base = Navy.copy(alpha = if (tier == Tier.LITE) 0.82f else 0.28f)))

        val cellDp = (maxWidth - 12.dp) / 4
        val cellPx = with(density) { cellDp.toPx() }
        val dropPx = with(density) { 16.dp.toPx() }

        val xA = remember { Animatable(0f) }          // posisi kiri gelembung saat tidak digeser (px)
        val drop = remember { Animatable(0f) }        // offset vertikal: negatif = terangkat, lalu jatuh memantul ke 0
        val splash = remember { Animatable(1f) }      // 0..1 percikan tetesan setelah dilepas (1 = selesai)
        var dragging by remember { mutableStateOf(false) }
        var dragX by remember { mutableFloatStateOf(0f) }
        var splashCx by remember { mutableFloatStateOf(0f) }
        var stretchT by remember { mutableFloatStateOf(0f) }

        // membesar seperti gelembung saat disentuh; pegas longgar = bergoyang kenyal saat dilepas
        val bulge = animateFloatAsState(if (dragging) 1.2f else 1f, spring(dampingRatio = 0.38f, stiffness = 320f), label = "bulge")
        // memanjang tipis searah geseran (seperti tetesan yang ditarik)
        val stretch = animateFloatAsState(stretchT, spring(dampingRatio = 0.5f, stiffness = 420f), label = "stretch")

        LaunchedEffect(dragX) { delay(70); stretchT = 0f }
        LaunchedEffect(ui.tab, cellPx) {
            if (!dragging && cellPx > 0f) {
                xA.animateTo(cellPx * ui.tab, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
            }
        }

        Box(
            Modifier.padding(6.dp).fillMaxWidth().height(58.dp)
                .pointerInput(cellPx) {
                    detectDragGestures(
                        onDragStart = { o ->
                            dragX = (o.x - cellPx / 2f).coerceIn(0f, cellPx * 3f)
                            stretchT = 0f
                            dragging = true
                        },
                        onDrag = { change, amt ->
                            change.consume()
                            dragX = (dragX + amt.x).coerceIn(0f, cellPx * 3f)
                            stretchT = (abs(amt.x) / (cellPx * 0.30f)).coerceAtMost(0.16f)
                        },
                        onDragEnd = {
                            val from = dragX
                            val idx = ((from + cellPx / 2f) / cellPx).toInt().coerceIn(0, 3)
                            scope.launch {
                                xA.snapTo(from)
                                dragging = false
                                stretchT = 0f
                                splashCx = from + cellPx / 2f
                                // gelembung "jatuh": terangkat lalu jatuh dan memantul, disertai percikan tetesan
                                launch { splash.snapTo(0f); splash.animateTo(1f, tween(620, easing = LinearOutSlowInEasing)) }
                                launch { drop.snapTo(-dropPx); drop.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 170f)) }
                                if (idx != ui.tab) { ui.tab = idx; ui.playlist = null }
                                xA.animateTo(cellPx * idx, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
                            }
                        },
                        onDragCancel = { dragging = false; stretchT = 0f },
                    )
                }
                .drawBehind {
                    val s = splash.value
                    if (s < 1f) {
                        val fade = 1f - s
                        val vx = floatArrayOf(-1.0f, -0.55f, -0.15f, 0.3f, 0.7f, 1.05f)
                        val vy = floatArrayOf(0.8f, 1.2f, 0.6f, 1.3f, 0.9f, 0.7f)
                        val rr = floatArrayOf(5f, 7f, 4f, 6f, 4.5f, 5.5f)
                        val lift = 26.dp.toPx()
                        val fall = 90.dp.toPx()
                        for (i in vx.indices) {
                            val c = Offset(
                                splashCx + vx[i] * cellPx * 0.42f * s,
                                size.height - vy[i] * lift * s + fall * s * s,
                            )
                            val r = rr[i].dp.toPx() * (1f - 0.4f * s)
                            drawCircle(Color.White.copy(alpha = 0.40f * fade), radius = r, center = c)
                            drawCircle(Color.White.copy(alpha = 0.85f * fade), radius = r, center = c, style = Stroke(1.dp.toPx()))
                        }
                    }
                }
        ) {
            // gelembung kaca bening
            Box(
                Modifier
                    .offset { IntOffset((if (dragging) dragX else xA.value).roundToInt(), drop.value.roundToInt()) }
                    .width(cellDp).height(58.dp)
                    .graphicsLayer {
                        val b = bulge.value
                        scaleX = b * (1f + stretch.value)
                        scaleY = b * (1f - stretch.value * 0.6f)
                    }
                    .glassBubble(RoundedCornerShape(29.dp)) { ((bulge.value - 1f) / 0.2f).coerceIn(0f, 1f) }
            )
            Row(Modifier.fillMaxWidth()) {
                val hover = if (dragging) ((dragX + cellPx / 2f) / cellPx).toInt().coerceIn(0, 3) else ui.tab
                items.forEachIndexed { i, (label, icon) ->
                    val on = hover == i
                    // ikon yang sedang ada di bawah gelembung ikut "terzoom" sedikit
                    val zoom by animateFloatAsState(if (on && dragging) 1.28f else 1f, spring(dampingRatio = 0.5f, stiffness = 420f), label = "zoom")
                    Column(
                        Modifier.weight(1f).height(58.dp)
                            .clickable(remember { MutableInteractionSource() }, null) { ui.tab = i; ui.playlist = null },
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            icon, label, Modifier.size(24.dp).graphicsLayer { scaleX = zoom; scaleY = zoom },
                            tint = if (on) Yellow else Color.White.copy(alpha = 0.65f),
                        )
                        Txt(label, 11.sp, FontWeight.SemiBold, if (on) Yellow else Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniPlayer(ui: UiState, p: PlayerUi) {
    val t = p.track ?: return
    val player = OtacixApp.player
    val store = OtacixApp.store
    val db by store.db.collectAsStateWithLifecycle()
    val prog by player.progress.collectAsStateWithLifecycle()
    val liked = store.isLiked(db, t.id)
    val frac = if (prog.dur > 0) (prog.pos.toFloat() / prog.dur).coerceIn(0f, 1f) else 0f

    Box(
        Modifier.padding(horizontal = 14.dp).widthIn(max = 560.dp).fillMaxWidth()
            .tap { ui.playerOpen = true }
            .glass(RoundedCornerShape(30.dp), base = Navy.copy(alpha = if (LocalTier.current == Tier.LITE) 0.82f else 0.32f))
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Cover(t.cover, Modifier.size(48.dp), 16.dp)
            Column(Modifier.weight(1f)) {
                Txt(t.title, 14.sp, FontWeight.SemiBold, maxLines = 1)
                Txt(if (p.buffering) "Memuat…" else t.artist, 12.sp, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
            }
            IconBtn(if (liked) Ic.Heart else Ic.HeartOutline, 40.dp, 21.dp, if (liked) Yellow else Color.White.copy(alpha = 0.85f)) { store.toggleLike(t) }
            IconBtn(if (p.playing) Ic.Pause else Ic.Play, 46.dp, 22.dp, Navy, solid = Yellow) { player.toggle() }
            IconBtn(Ic.Next, 40.dp, 22.dp) { player.next() }
        }
        Box(
            Modifier.align(Alignment.BottomStart).padding(horizontal = 24.dp).padding(bottom = 4.dp)
                .fillMaxWidth().height(3.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Yellow))
        }
    }
}
