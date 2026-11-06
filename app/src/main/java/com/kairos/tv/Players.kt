package com.kairos.tv

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/** ExoPlayer configurato per IPTV su dispositivi anche vecchi */
@OptIn(UnstableApi::class)
object Players {
    /** [soft] = decodifica software (rimedio quando il decoder hardware si blocca) */
    fun build(ctx: Context, live: Boolean, soft: Boolean = false, preview: Boolean = false): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx, http), extractors)
        val lite = App.lite
        // memoria per il video: tetto basso sulle chiavette da 1 GB, così il sistema non chiude l'app
        // anteprima nella lista canali: pochi secondi di memoria, si guarda per qualche istante
        // rete lenta o blocchi recenti: più scorta prima di ripartire, così un calo di rete non ferma l'immagine
        val slow = NetMem.slow()
        val load = if (preview) DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 5_000, 800, 1_500)
            .setTargetBufferBytes(3 shl 20)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        else DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                if (slow) 30_000 else 15_000,
                if (live) (if (lite) 30_000 else 50_000) else (if (lite) 40_000 else 90_000),
                // partenza rapida (1 s), ma dopo un blocco si riparte solo con una scorta vera: niente stop-and-go
                1_000,
                NetMem.rebufferMs(),
            )
            .setTargetBufferBytes((if (lite) 16 else if (live) 24 else 40) shl 20)
            .setPrioritizeTimeOverSizeThresholds(!lite)
            .setBackBuffer(if (live || lite) 0 else 10_000, false)
            .build()
        val renderers = KxRenderersFactory(ctx)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        if (soft) renderers.setMediaCodecSelector { mime, secure, tunneling ->
            MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
        }
        // stima iniziale prudente sulle chiavette: i canali HLS con più qualità partono subito e poi salgono
        val meter = DefaultBandwidthMeter.Builder(ctx).setInitialBitrateEstimate(NetMem.initialBitrate(lite)).build()
        // qualità adattiva prudente (come Netflix): sale solo dopo 10 s stabili, scende subito se la rete cala,
        // usa il 70% della banda misurata per lasciare margine
        val abr = androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
            10_000, 25_000, 25_000, if (slow) 0.6f else 0.7f,
        )
        val selector = DefaultTrackSelector(ctx, abr)
        // niente qualità più alta dello schermo (una TV 720p non scarica il 1080p)
        selector.setParameters(selector.buildUponParameters().setViewportSizeToPhysicalDisplaySize(ctx, true).setPreferredAudioLanguage("it"))
        // anteprima: nei canali HLS con più qualità si prende la più bassa adatta al riquadro
        if (preview) selector.setParameters(selector.buildUponParameters().setMaxVideoSize(854, 480).setMaxVideoBitrate(1_500_000))
        // «Leggerissima»: con i flussi a più qualità si resta a 720p, il decoder delle chiavette vecchie respira
        else if (App.max) selector.setParameters(selector.buildUponParameters().setMaxVideoSize(1280, 720))
        val p = ExoPlayer.Builder(ctx, renderers)
            .setBandwidthMeter(meter)
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setMediaSourceFactory(sources)
            .build()
        p.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS)
        return p
    }

    fun item(url: String): MediaItem {
        val b = MediaItem.Builder().setUri(Uri.parse(url))
        val low = url.lowercase().substringBefore('?')
        when {
            low.endsWith(".m3u8") || low.endsWith(".m3u") -> b.setMimeType(MimeTypes.APPLICATION_M3U8)
            low.endsWith(".ts") -> b.setMimeType(MimeTypes.VIDEO_MP2T)
        }
        return b.build()
    }
}

/**
 * Rimedio di KAIROS TV per alcuni Fire TV (chip MediaTek MT8695): al cambio di superficie il decoder
 * si blocca (audio che continua, immagine ferma). Su quei modelli il decoder viene ricreato.
 */
object SurfaceFix {
    private val MODELS = setOf("AFTSSS", "AFTSS", "AFTMM")
    private const val KEY = "surfaceFixLearned"
    @Volatile private var learned: Boolean? = null
    fun needed(ctx: Context): Boolean {
        if (MODELS.contains(android.os.Build.MODEL)) return true
        return learned ?: ctx.applicationContext.getSharedPreferences("lite", Context.MODE_PRIVATE).getBoolean(KEY, false).also { learned = it }
    }
    fun learn(ctx: Context) {
        if (learned == true) return
        learned = true
        try { ctx.applicationContext.getSharedPreferences("lite", Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply() } catch (_: Exception) {}
    }
}

@OptIn(UnstableApi::class)
class KxRenderersFactory(private val ctx: Context) : DefaultRenderersFactory(ctx) {
    override fun buildVideoRenderers(
        context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean, eventHandler: Handler, eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
    ) {
        if (!SurfaceFix.needed(ctx)) {
            super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, eventHandler, eventListener, allowedVideoJoiningTimeMs, out)
            return
        }
        out.add(object : MediaCodecVideoRenderer(
            context, codecAdapterFactory, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
        ) {
            override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean =
                !(name.startsWith("OMX.google") || name.startsWith("c2.android")) || super.codecNeedsSetOutputSurfaceWorkaround(name)
        })
    }
}

/**
 * Memoria della rete del dispositivo: banda misurata (media mobile) e blocchi recenti.
 * Ogni nuova riproduzione parte già dalla qualità e dalla scorta giuste per questa connessione.
 */
object NetMem {
    private const val PREF = "net"
    @Volatile private var bw = -1L
    @Volatile private var stallAt = 0L
    @Volatile private var stallsRecent = 0

    private fun prefs() = App.I.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun load() {
        if (bw >= 0) return
        val p = prefs()
        bw = p.getLong("bw", 0L)
        stallAt = p.getLong("stallAt", 0L)
        stallsRecent = p.getInt("stalls", 0)
    }

    fun observe(bitrate: Long) {
        if (bitrate <= 0) return
        load()
        bw = if (bw <= 0) bitrate else (bw * 0.8 + bitrate * 0.2).toLong()
        prefs().edit().putLong("bw", bw).apply()
    }

    fun stall() {
        load()
        val now = System.currentTimeMillis()
        stallsRecent = if (now - stallAt < 30 * 60_000L) stallsRecent + 1 else 1
        stallAt = now
        prefs().edit().putLong("stallAt", stallAt).putInt("stalls", stallsRecent).apply()
    }

    private fun recentStalls(): Int { load(); return if (System.currentTimeMillis() - stallAt < 30 * 60_000L) stallsRecent else 0 }

    fun slow(): Boolean { load(); return (bw in 1 until 4_000_000L) || recentStalls() >= 2 }

    /** scorta richiesta dopo un blocco: cresce con i blocchi recenti (2,5 s → 8 s) */
    fun rebufferMs(): Int = (2_500 + recentStalls() * 1_500).coerceAtMost(8_000)

    fun initialBitrate(lite: Boolean): Long { load(); return if (bw > 0) (bw * 0.8).toLong() else if (lite) 2_500_000L else 5_000_000L }
}
