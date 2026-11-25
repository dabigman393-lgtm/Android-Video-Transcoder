@file:OptIn(UnstableApi::class)

package com.clipshrink.app

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import kotlin.math.roundToInt

data class Clip(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val fps: Float,
    val hdr: Boolean
)

data class Plan(
    val videoBps: Int,
    val audioBps: Int,
    val w: Int,
    val h: Int,
    val fps: Int,
    val scaleDown: Boolean,
    val drop: Boolean,
    val tooTight: Boolean,
    val estBytes: Long
)

object Planner {
    fun plan(c: Clip, targetMb: Int, scale: Double = 1.0): Plan {
        val secs = (c.durationMs / 1000.0).coerceAtLeast(0.5)
        val total = targetMb * 1048576.0 * 0.92 * 8.0 / secs
        val audio = 132_000
        val base = (total * 0.97 - audio).coerceAtLeast(100_000.0)
        val tierH: Int
        val tierFps: Int
        when {
            base >= 6_000_000 -> { tierH = 1080; tierFps = 60 }
            base >= 3_500_000 -> { tierH = 720; tierFps = 60 }
            base >= 1_800_000 -> { tierH = 720; tierFps = 30 }
            base >= 900_000 -> { tierH = 540; tierFps = 30 }
            else -> { tierH = 360; tierFps = 30 }
        }
        val srcH = if (c.height > 0) c.height else tierH
        val h = minOf(tierH, srcH) and 1.inv()
        val scaleDown = c.height > 0 && c.width > 0 && h < c.height
        val w = if (scaleDown) ((c.width.toDouble() * h / c.height).roundToInt() and 1.inv()) else c.width
        val srcFps = if (c.fps > 0f) c.fps else 60f
        val drop = tierFps + 1 < srcFps
        val fps = if (drop) tierFps else minOf(tierFps, srcFps.roundToInt())
        val video = (base * scale).toInt().coerceAtLeast(80_000)
        val est = ((video + audio) * secs / 8.0).toLong()
        return Plan(video, audio, w, h, fps, scaleDown, drop, base < 250_000, est)
    }
}

class ConverterViewModel(app: Application) : AndroidViewModel(app) {
    private val main = Handler(Looper.getMainLooper())

    var clip by mutableStateOf<Clip?>(null)
        private set
    var thumb by mutableStateOf<Bitmap?>(null)
        private set
    var targetMb by mutableIntStateOf(10)
    var toneMap by mutableStateOf(false)
    var brighter by mutableStateOf(false)
    var busy by mutableStateOf(false)
        private set
    var progress by mutableFloatStateOf(0f)
        private set
    var resultFile by mutableStateOf<File?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var transformer: Transformer? = null
    private val holder = ProgressHolder()
    private val poll = object : Runnable {
        override fun run() {
            val t = transformer ?: return
            if (t.getProgress(holder) != Transformer.PROGRESS_STATE_NOT_STARTED) {
                progress = (holder.progress / 100f).coerceIn(0f, 1f)
            }
            main.postDelayed(this, 250)
        }
    }

    fun loadClip(uri: Uri) {
        if (busy) return
        val ctx = getApplication<Application>()
        Thread {
            var name = "video"
            var size = 0L
            try {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (ni >= 0) name = c.getString(ni) ?: name
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (_: Exception) {
            }
            var w = 0
            var h = 0
            var fps = 0f
            var hdr = false
            try {
                val ex = MediaExtractor()
                try {
                    ex.setDataSource(ctx, uri, null)
                    for (i in 0 until ex.trackCount) {
                        val f = ex.getTrackFormat(i)
                        val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                        if (!mime.startsWith("video/")) continue
                        w = f.getInteger(MediaFormat.KEY_WIDTH)
                        h = f.getInteger(MediaFormat.KEY_HEIGHT)
                        if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                            fps = f.getNumber(MediaFormat.KEY_FRAME_RATE)?.toFloat() ?: 0f
                        }
                        if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                            val t = f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                            hdr = t == MediaFormat.COLOR_TRANSFER_ST2084 || t == MediaFormat.COLOR_TRANSFER_HLG
                        }
                        if (!hdr && f.containsKey(MediaFormat.KEY_PROFILE)) {
                            val p = f.getInteger(MediaFormat.KEY_PROFILE)
                            hdr = p == CodecProfileLevel.VP9Profile2 ||
                                p == CodecProfileLevel.VP9Profile2HDR ||
                                p == CodecProfileLevel.HEVCProfileMain10HDR10
                        }
                        break
                    }
                } finally {
                    ex.release()
                }
            } catch (_: Exception) {
            }
            var dur = 0L
            var bmp: Bitmap? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                bmp = r.getScaledFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
            } catch (_: Exception) {
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
            val fName = name; val fSize = size; val fW = w; val fH = h
            val fFps = fps; val fHdr = hdr; val fDur = dur; val fBmp = bmp
            main.post {
                if (fDur <= 0L || fW <= 0 || fH <= 0) {
                    error = "Couldn't read that video."
                } else {
                    clip = Clip(uri, fName, fSize, fDur, fW, fH, fFps, fHdr)
                    thumb = fBmp
                    toneMap = fHdr
                    resultFile = null
                    error = null
                }
            }
        }.start()
    }

    fun convert() {
        val c = clip ?: return
        if (busy) return
        getApplication<Application>().cacheDir.listFiles()?.forEach {
            if (it.name.startsWith("ClipShrink_")) it.delete()
        }
        resultFile = null
        error = null
        progress = 0f
        busy = true
        val mode = if (toneMap) Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC
        else Composition.HDR_MODE_KEEP_HDR
        startExport(c, 1.0, 1, mode)
    }

    fun cancel() {
        transformer?.cancel()
        transformer = null
        main.removeCallbacks(poll)
        busy = false
    }

    private fun fail(msg: String) {
        main.removeCallbacks(poll)
        transformer = null
        busy = false
        error = msg
    }

    private fun startExport(c: Clip, scale: Double, n: Int, mode: Int) {
        try {
            val ctx = getApplication<Application>()
            val p = Planner.plan(c, targetMb, scale)
            val limit = targetMb * 1048576L
            val out = File(ctx.cacheDir, "ClipShrink_${System.currentTimeMillis()}.mp4")

            val fx = ArrayList<Effect>()
            if (p.drop) fx.add(FrameDropEffect.createDefaultFrameDropEffect(p.fps.toFloat()))
            if (p.scaleDown) fx.add(Presentation.createForWidthAndHeight(p.w, p.h, Presentation.LAYOUT_SCALE_TO_FIT))
            if (toneMap && brighter) fx.add(Brightness(0.08f))

            val item = EditedMediaItem.Builder(MediaItem.fromUri(c.uri))
                .setEffects(Effects(emptyList(), fx))
                .build()
            val composition = Composition.Builder(listOf(EditedMediaItemSequence(listOf(item))))
                .setHdrMode(mode)
                .build()

            val encoder = DefaultEncoderFactory.Builder(ctx)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(p.videoBps).build())
                .setEnableFallback(true)
                .build()

            val t = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoder)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        val size = out.length()
                        if (size > limit && n < 3) {
                            out.delete()
                            val next = scale * (limit * 0.90 / size)
                            main.post { startExport(c, next, n + 1, mode) }
                        } else {
                            main.removeCallbacks(poll)
                            transformer = null
                            progress = 1f
                            resultFile = out
                            busy = false
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        out.delete()
                        if (mode == Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC) {
                            main.post { startExport(c, scale, n, Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL) }
                        } else {
                            val hint = if (c.hdr && mode == Composition.HDR_MODE_KEEP_HDR) " Turn on tone-mapping for HDR clips." else ""
                            fail("Conversion failed (${exportException.errorCodeName}).$hint")
                        }
                    }
                })
                .build()

            transformer = t
            t.start(composition, out.absolutePath)
            main.removeCallbacks(poll)
            main.post(poll)
        } catch (e: Exception) {
            fail("Couldn't start conversion: ${e.message}")
        }
    }

    override fun onCleared() {
        main.removeCallbacks(poll)
        transformer?.cancel()
    }
}
