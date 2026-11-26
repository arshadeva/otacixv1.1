package id.oscarmandala.otacix.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import id.oscarmandala.otacix.OtacixApp
import id.oscarmandala.otacix.data.Importer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Dialog impor playlist Spotify / YouTube dari tautan. Hasilnya menjadi playlist baru di Pustaka. */
@Composable
fun ImportDialog(ui: UiState, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var link by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var msg by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    fun close() { job?.cancel(); onDismiss() }

    GlassDialog(onDismiss = { close() }) {
        Txt("Impor playlist", 18.sp, FontWeight.Bold)
        Txt(
            "Tempel link playlist Spotify atau YouTube (harus publik). Lagu Spotify dicocokkan ke YouTube agar bisa diputar penuh.",
            12.5.sp, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
        )
        BasicTextField(
            value = link, onValueChange = { link = it; msg = null }, singleLine = true, enabled = !busy,
            textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
            cursorBrush = SolidColor(Yellow),
            modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
            decorationBox = { inner ->
                Box {
                    if (link.isEmpty()) Txt("https://open.spotify.com/playlist/…", 14.sp, color = Color.White.copy(alpha = 0.4f), maxLines = 1)
                    inner()
                }
            },
        )
        val status = when {
            busy && total > 0 -> "Mencocokkan lagu ke YouTube… $done/$total"
            busy -> "Membaca playlist…"
            else -> msg
        }
        if (status != null) {
            Txt(
                status, 13.sp,
                color = if (busy) YellowSoft else Color(0xFFFF8A80),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
            if (!busy) PillButton("Tempel") { link = clipboard.getText()?.text.orEmpty().trim(); msg = null }
            PillButton(if (busy) "Batal" else "Tutup") { close() }
            if (!busy) PillButton("Impor", primary = true) {
                busy = true; msg = null; done = 0; total = 0
                job = scope.launch {
                    try {
                        val r = Importer.importLink(link) { d, t -> done = d; total = t }
                        val id = OtacixApp.store.addPlaylistWithTracks(r.name, r.tracks)
                        toast(ctx, "${r.tracks.size} lagu dari ${r.source} diimpor" + if (r.missed > 0) " (${r.missed} tidak ditemukan)" else "")
                        ui.playlist = id
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        msg = e.message ?: "Gagal mengimpor"
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }
}
