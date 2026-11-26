package id.oscarmandala.otacix.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import id.oscarmandala.otacix.util.Tier

val LocalTier = staticCompositionLocalOf { Tier.FULL }
val LocalTilt = compositionLocalOf<State<Offset>> { mutableStateOf(Offset.Zero) }

/**
 * Efek "liquid glass" yang jernih: isi nyaris bening, tepi seperti lensa kaca (kilau atas-kiri, pantulan bawah-kanan).
 * Di HP spek atas (Tier.FULL) kilau spekular ikut bergeser mengikuti kemiringan HP (sensor gravitasi).
 * Di HP spek rendah (Tier.LITE) isinya sedikit lebih pekat supaya teks tetap terbaca dan gambarnya ringan.
 */
@Composable
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(26.dp),
    tint: Color = Color.White,
    strength: Float = 1f,
    base: Color = Color.Transparent,
): Modifier {
    val tier = LocalTier.current
    val tilt = LocalTilt.current
    val clear = tier == Tier.FULL
    val a1 = (if (clear) 0.11f else 0.20f) * strength
    val a2 = (if (clear) 0.025f else 0.06f) * strength
    val lens = if (clear) Modifier.drawBehind {
        // pantulan lengkung seperti kaca tebal: terang di sudut kiri-atas, samar di kanan-bawah
        drawRect(
            Brush.linearGradient(
                colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.07f)),
                start = Offset.Zero, end = Offset(size.width, size.height),
            )
        )
    } else Modifier
    val sheen = if (clear) Modifier.drawWithContent {
        drawContent()
        val t = tilt.value
        val c = Offset(size.width * (0.5f + t.x * 0.7f), size.height * (0.15f + t.y * 0.6f))
        drawRect(
            Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.18f), Color.Transparent),
                center = c, radius = size.maxDimension * 0.75f,
            )
        )
    } else Modifier
    return this
        .clip(shape)
        .background(base)
        .background(Brush.linearGradient(listOf(tint.copy(alpha = a1), tint.copy(alpha = a2))))
        .then(lens)
        .then(sheen)
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.50f))
            ),
            shape,
        )
}

/**
 * Gelembung kaca bening (indikator navigasi). Tengahnya nyaris tembus pandang, hanya tepi dan kilau yang terlihat.
 * [press] mengembalikan 0..1 (0 = diam, 1 = sedang ditekan/digeser): makin ditekan makin bening dan tepinya makin terang.
 */
fun Modifier.glassBubble(shape: Shape, press: () -> Float): Modifier = this
    .clip(shape)
    .drawBehind {
        val p = press()
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f - 0.12f * p), Color.White.copy(alpha = 0.05f))))
        // kilau lengkung kiri-atas
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.34f + 0.12f * p), Color.Transparent),
                center = Offset(size.width * 0.26f, size.height * 0.16f), radius = size.minDimension * 0.95f,
            )
        )
        // pantulan balik kanan-bawah (efek refraksi)
        drawRect(
            Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.16f + 0.06f * p), Color.Transparent),
                center = Offset(size.width * 0.84f, size.height * 0.96f), radius = size.minDimension * 0.7f,
            )
        )
    }
    .border(
        1.5.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.80f))),
        shape,
    )

/** Klik dengan efek tekan (mengecil sedikit), tanpa ripple. */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "tap")
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Kemiringan HP (-1..1). Hanya aktif jika enabled (Tier.FULL). */
@Composable
fun rememberTilt(enabled: Boolean): State<Offset> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(enabled) {
        if (!enabled) {
            st.value = Offset.Zero
            return@DisposableEffect onDispose { }
        }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) return@DisposableEffect onDispose { }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = (-e.values[0] / 9.81f).coerceIn(-1f, 1f)
                val y = (e.values[2] / 9.81f - 0.35f).coerceIn(-1f, 1f)
                val o = st.value
                st.value = Offset(o.x + (x - o.x) * 0.15f, o.y + (y - o.y) * 0.15f)
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return st
}

/** Latar "aurora" biru-kuning. [accent] = warna dominan sampul lagu yang sedang diputar. */
@Composable
fun AuroraBackground(accent: Color) {
    val tier = LocalTier.current
    val acc by animateColorAsState(accent, tween(900), label = "accent")
    val phase: State<Float> = if (tier == Tier.FULL) {
        rememberInfiniteTransition(label = "aurora").animateFloat(
            0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Reverse), label = "p"
        )
    } else remember { mutableStateOf(0f) }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val p = phase.value
        drawRect(Brush.verticalGradient(listOf(Color(0xFF061435), Navy)))
        fun blob(c: Color, cx: Float, cy: Float, r: Float) =
            drawRect(Brush.radialGradient(listOf(c, Color.Transparent), center = Offset(cx, cy), radius = r))
        blob(BlueBright.copy(alpha = 0.50f), w * (0.12f + 0.10f * p), h * (0.08f + 0.05f * p), w * 0.95f)
        blob(Yellow.copy(alpha = 0.20f), w * (0.95f - 0.10f * p), h * (0.22f + 0.04f * p), w * 0.60f)
        blob(acc.copy(alpha = 0.34f), w * (0.30f + 0.12f * p), h * (0.95f - 0.06f * p), w * 0.95f)
        blob(Blue.copy(alpha = 0.55f), w * 0.90f, h * (0.80f + 0.05f * p), w * 0.70f)
    }
}
