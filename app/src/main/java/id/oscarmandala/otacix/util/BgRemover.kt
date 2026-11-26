package id.oscarmandala.otacix.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Menghapus latar foto: hasilnya bitmap seukuran foto asli dengan latar transparan (orangnya saja).
 *
 *  1. Bila pengguna mengisi kunci API remove.bg -> memakai remove.bg (bisa orang, hewan, benda).
 *  2. Jika tidak ada kunci / gagal -> ML Kit Selfie Segmentation di perangkat (gratis, tanpa internet, khusus orang).
 */
object BgRemover {
    class Result(val bitmap: Bitmap, val engine: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()

    suspend fun cutout(photo: Bitmap, apiKey: String): Result {
        if (apiKey.isNotBlank()) {
            try {
                return Result(removeBgApi(photo, apiKey.trim()), "remove.bg")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // kuota habis / jaringan gagal -> pakai ML Kit di perangkat
            }
        }
        return Result(onDevice(photo), "di perangkat")
    }

    // ------------------------------------------------------------ remove.bg (API)
    private suspend fun removeBgApi(photo: Bitmap, key: String): Bitmap = withContext(Dispatchers.IO) {
        val jpeg = ByteArrayOutputStream().also { photo.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("size", "auto")
            .addFormDataPart("image_file", "photo.jpg", jpeg.toRequestBody("image/jpeg".toMediaType()))
            .build()
        val req = Request.Builder().url("https://api.remove.bg/v1.0/removebg").header("X-Api-Key", key).post(body).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("remove.bg HTTP ${r.code}")
            val bytes = r.body?.bytes() ?: throw IOException("respons kosong")
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("gambar tidak valid")
            if (bmp.width == photo.width && bmp.height == photo.height) bmp
            else Bitmap.createScaledBitmap(bmp, photo.width, photo.height, true)
        }
    }

    // ------------------------------------------------------------ ML Kit (di perangkat)
    private suspend fun onDevice(src: Bitmap): Bitmap {
        val opts = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        val seg = Segmentation.getClient(opts)
        try {
            val mask = suspendCancellableCoroutine<SegmentationMask> { c ->
                seg.process(InputImage.fromBitmap(src, 0))
                    .addOnSuccessListener { c.resume(it) }
                    .addOnFailureListener { c.resumeWithException(it) }
            }
            return withContext(Dispatchers.Default) { applyMask(src, mask) }
        } finally {
            seg.close()
        }
    }

    private fun applyMask(src: Bitmap, mask: SegmentationMask): Bitmap {
        val mw = mask.width
        val mh = mask.height
        val sw = src.width
        val sh = src.height
        val buf = mask.buffer
        buf.rewind()
        val conf = FloatArray(mw * mh)
        for (i in conf.indices) conf[i] = buf.float

        val px = IntArray(sw * sh)
        src.getPixels(px, 0, sw, 0, 0, sw, sh)
        for (y in 0 until sh) {
            val my = ((y.toLong() * mh) / sh).toInt().coerceAtMost(mh - 1)
            for (x in 0 until sw) {
                val mx = ((x.toLong() * mw) / sw).toInt().coerceAtMost(mw - 1)
                // ambang lembut: tepi orang tidak bergerigi
                val t = ((conf[my * mw + mx] - 0.30f) / 0.45f).coerceIn(0f, 1f)
                val a = (t * t * (3f - 2f * t) * 255f).toInt()
                val i = y * sw + x
                px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        return Bitmap.createBitmap(px, sw, sh, Bitmap.Config.ARGB_8888)
    }
}
