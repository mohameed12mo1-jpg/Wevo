from pathlib import Path

root = Path(".")
service = root / "app/src/main/java/com/micplugin/service/AudioProcessingService.kt"
manifest = root / "app/src/main/AndroidManifest.xml"
helper = root / "app/src/main/java/com/micplugin/service/WevoTcpAudioBridge.kt"

s = service.read_text(encoding="utf-8")
old = '        audioEngine.setMonitorCallback { buf: FloatArray, size: Int -> SoftwareLoopback.writeProcessed(buf, size) }\n        audioEngine.start()'
new = '''        WevoTcpAudioBridge.start()
        audioEngine.setMonitorCallback { buf: FloatArray, size: Int ->
            SoftwareLoopback.writeProcessed(buf, size)
            WevoTcpAudioBridge.offer(buf, size, audioEngine.status.value.sampleRate)
        }
        audioEngine.start()'''
if old not in s:
    raise SystemExit("PATCH_ERROR: AudioProcessingService callback anchor not found")
s = s.replace(old, new, 1)
old_stop = '''        audioEngine.stop()
        SoftwareLoopback.stop()'''
new_stop = '''        audioEngine.stop()
        WevoTcpAudioBridge.stop()
        SoftwareLoopback.stop()'''
if old_stop not in s:
    raise SystemExit("PATCH_ERROR: AudioProcessingService stop anchor not found")
s = s.replace(old_stop, new_stop, 1)
service.write_text(s, encoding="utf-8")

m = manifest.read_text(encoding="utf-8")
if 'android.permission.INTERNET' not in m:
    marker = '<manifest xmlns:android="http://schemas.android.com/apk/res/android"'
    if marker not in m:
        raise SystemExit("PATCH_ERROR: manifest root not found")
    end = m.find('>', m.find(marker)) + 1
    m = m[:end] + '\n    <uses-permission android:name="android.permission.INTERNET" />' + m[end:]
manifest.write_text(m, encoding="utf-8")

helper.parent.mkdir(parents=True, exist_ok=True)
helper.write_text(r"""package com.micplugin.service

import android.util.Log
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor

/**
 * Optional one-way PCM tap from MicUp's post-DSP monitor callback to the
 * local XMicInject TCP listener. Audio callback only copies into a bounded
 * queue; socket I/O and PCM conversion happen on this worker thread.
 */
internal object WevoTcpAudioBridge {
    private const val TAG = "MicUpWevoTcp"
    private const val HOST = "127.0.0.1"
    private const val PORT = 38673
    private const val TARGET_RATE = 16000
    private const val CAPACITY = 12

    private data class Block(val samples: FloatArray, val count: Int, val rate: Int)
    private val active = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<Block>(CAPACITY)
    @Volatile private var worker: Thread? = null

    fun start() {
        if (!active.compareAndSet(false, true)) return
        queue.clear()
        worker = Thread({ runSender() }, "MicUp-Wevo-PCM").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
        Log.i(TAG, "Bridge sender started (localhost:$PORT)")
    }

    fun offer(buffer: FloatArray, frames: Int, sampleRate: Int) {
        if (!active.get() || frames <= 0 || sampleRate < 8000) return
        val count = minOf(frames, buffer.size)
        val copy = buffer.copyOf(count)
        val block = Block(copy, count, sampleRate)
        if (!queue.offer(block)) {
            queue.poll()
            queue.offer(block)
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        queue.clear()
        worker?.interrupt()
        try { worker?.join(400) } catch (_: InterruptedException) { }
        worker = null
        Log.i(TAG, "Bridge sender stopped")
    }

    private fun runSender() {
        var socket: Socket? = null
        var output: BufferedOutputStream? = null
        var inputRate = 0
        var inputIndex = 0L
        var nextOut = 0.0
        var previous = 0f
        var havePrevious = false
        try {
            while (active.get()) {
                val block = queue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                if (block.rate != inputRate) {
                    inputRate = block.rate
                    inputIndex = 0L
                    nextOut = 0.0
                    previous = 0f
                    havePrevious = false
                }
                val step = inputRate.toDouble() / TARGET_RATE
                val pcm = ByteArray((floor(block.count / step).toInt() + 3) * 2)
                var offset = 0
                for (i in 0 until block.count) {
                    val sample = block.samples[i].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
                    val pos = inputIndex.toDouble()
                    if (!havePrevious) {
                        previous = sample
                        havePrevious = true
                        while (nextOut <= pos) {
                            offset = putSample(pcm, offset, sample)
                            nextOut += step
                        }
                    } else {
                        while (nextOut <= pos) {
                            val fraction = (nextOut - (pos - 1.0)).coerceIn(0.0, 1.0).toFloat()
                            offset = putSample(pcm, offset, previous + (sample - previous) * fraction)
                            nextOut += step
                        }
                        previous = sample
                    }
                    inputIndex++
                }
                if (offset == 0) continue
                try {
                    if (socket == null || socket!!.isClosed || !socket!!.isConnected) {
                        socket = Socket()
                        socket!!.connect(InetSocketAddress(HOST, PORT), 700)
                        socket!!.tcpNoDelay = true
                        output = BufferedOutputStream(socket!!.getOutputStream(), 8192)
                        Log.i(TAG, "Connected to XMicInject at $HOST:$PORT")
                    }
                    output!!.write(pcm, 0, offset)
                    output!!.flush()
                } catch (t: Throwable) {
                    Log.w(TAG, "Local audio bridge unavailable; retrying", t)
                    try { socket?.close() } catch (_: Throwable) { }
                    socket = null
                    output = null
                    try { Thread.sleep(350) } catch (_: InterruptedException) { }
                }
            }
        } catch (_: InterruptedException) {
            // Normal stop.
        } finally {
            try { socket?.close() } catch (_: Throwable) { }
        }
    }

    private fun putSample(out: ByteArray, offset: Int, value: Float): Int {
        if (offset + 1 >= out.size) return offset
        val v = (value.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
        out[offset] = (v and 0xff).toByte()
        out[offset + 1] = ((v ushr 8) and 0xff).toByte()
        return offset + 2
    }
}
""", encoding="utf-8")
print("PATCH_OK: post-DSP PCM bridge source and lifecycle hooks installed")
