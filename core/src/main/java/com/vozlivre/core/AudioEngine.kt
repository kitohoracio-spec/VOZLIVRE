package com.vozlivre.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Captura do microfone e reprodução com um pequeno buffer de jitter.
 * Frames de 20 ms, PCM16 mono. [sink] recebe cada frame capturado.
 */
class AudioEngine(
    private val ctx: Context,
    private val rate: Int,
    private val sink: (ByteArray, Int) -> Unit
) {
    private val frameBytes = rate / 50 * 2
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val queue = ArrayBlockingQueue<ByteArray>(12)
    private val effects = ArrayList<AudioEffect>()
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var capThread: Thread? = null
    private var playThread: Thread? = null
    private var oldMode = AudioManager.MODE_NORMAL
    private var lastSeq = -1
    @Volatile private var running = false
    @Volatile var muted = false
    @Volatile var gain = 1f

    /** Devolve uma mensagem de erro, ou null se arrancou bem. */
    fun start(): String? {
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return "Sem permissão de microfone"
        try {
            oldMode = am.mode
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, frameBytes * 4)
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release(); am.mode = oldMode
                return "Microfone indisponível (outra app a usá-lo?)"
            }
            try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects.add(it) } } catch (_: Exception) {}

            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(maxOf(minOut, frameBytes * 6))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (trk.state != AudioTrack.STATE_INITIALIZED) {
                rec.release(); trk.release(); am.mode = oldMode
                return "Altifalante indisponível"
            }
            record = rec; track = trk
            queue.clear(); lastSeq = -1
            running = true
            rec.startRecording()
            trk.play()
            val silence = ByteArray(frameBytes)
            repeat(3) { trk.write(silence, 0, silence.size) }

            capThread = thread(name = "voz-capture", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                val buf = ByteArray(frameBytes)
                while (running) {
                    var off = 0
                    while (off < frameBytes && running) {
                        val n = rec.read(buf, off, frameBytes - off)
                        if (n < 0) { running = false; break }
                        off += n
                    }
                    if (running && off == frameBytes && !muted) {
                        val g = gain
                        if (g != 1f) applyGain(buf, frameBytes, g)
                        try { sink(buf, frameBytes) } catch (_: Exception) {}
                    }
                }
            }
            playThread = thread(name = "voz-play", isDaemon = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                while (running) {
                    val f = try { queue.poll(40, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
                    if (f != null) try { trk.write(f, 0, f.size) } catch (_: Exception) {}
                }
            }
            return null
        } catch (e: Exception) {
            stop()
            return "Erro de áudio: ${e.message}"
        }
    }

    private fun applyGain(buf: ByteArray, len: Int, g: Float) {
        var i = 0
        while (i + 1 < len) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            var v = (s * g).toInt()
            if (v > 32767) v = 32767
            if (v < -32768) v = -32768
            buf[i] = (v and 0xFF).toByte()
            buf[i + 1] = ((v shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    /** Frame vindo da rede. Descarta atrasados e limita a latência. */
    fun onRemoteFrame(seq: Int, pcm: ByteArray) {
        if (!running) return
        if (lastSeq >= 0) {
            val diff = (seq - lastSeq).toShort().toInt()
            if (diff <= 0 && diff > -200) return
        }
        lastSeq = seq
        if (!queue.offer(pcm)) {
            queue.poll()
            queue.offer(pcm)
        }
    }

    @Suppress("DEPRECATION")
    fun setSpeaker(on: Boolean) {
        try { am.isSpeakerphoneOn = on } catch (_: Exception) {}
    }

    fun stop() {
        running = false
        try { capThread?.join(300) } catch (_: Exception) {}
        try { playThread?.join(300) } catch (_: Exception) {}
        for (e in effects) try { e.release() } catch (_: Exception) {}
        effects.clear()
        try { record?.stop() } catch (_: Exception) {}
        try { record?.release() } catch (_: Exception) {}
        record = null
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        try { track?.release() } catch (_: Exception) {}
        track = null
        try { setSpeaker(false); am.mode = oldMode } catch (_: Exception) {}
        queue.clear()
    }
}
