package com.micplugin.wevobridge

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.micplugin.IAudioPlugin
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

/**
 * MicUp external audio plugin.
 * Receives post-DSP mono float samples and streams raw PCM16LE/16 kHz to
 * the existing XMicInject listener on the same Android device.
 */
class WevoBridgeService : Service() {
    companion object {
        private const val TAG = "MicUpWevoBridge"
        private const val HOST = "127.0.0.1"
        private const val PORT = 38673
        private const val TARGET_RATE = 16000
        private const val QUEUE_CAPACITY = 16
    }

    private val running = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    @Volatile private var worker: Thread? = null
    @Volatile private var connected = false

    // Streaming resampler state. nextOutputPosition is expressed in input frames.
    private var currentInputRate = 0
    private var inputPosition = 0L
    private var nextOutputPosition = 0.0
    private var previousSample = 0f
    private var havePrevious = false

    private val binder = object : IAudioPlugin.Stub() {
        override fun getPluginInfo(): String =
            """{"name":"Wevo Live Bridge","version":"1.0.0","author":"MicUp Wevo Bridge","params":[]}"""

        @Synchronized
        override fun processBuffer(buffer: FloatArray, frames: Int, sampleRate: Int): Boolean {
            if (!running.get() || frames <= 0 || sampleRate < 8000 || frames > buffer.size) return false
            return try {
                val packet = resampleToPcm16(buffer, frames, sampleRate)
                if (packet.isNotEmpty() && !queue.offer(packet)) {
                    // Prefer fresh microphone audio over stale queued audio.
                    queue.poll()
                    queue.offer(packet)
                }
                true
            } catch (t: Throwable) {
                Log.e(TAG, "Audio conversion failed", t)
                false
            }
        }

        override fun setParameter(id: Int, value: Float) {}
        override fun getParameter(id: Int): Float = 0f

        @Synchronized
        override fun onStart(sampleRate: Int, framesPerBuffer: Int) {
            resetResampler(sampleRate)
            startSender()
            Log.i(TAG, "Plugin started: inputRate=$sampleRate, frames=$framesPerBuffer")
        }

        override fun onStop() {
            stopSender()
            Log.i(TAG, "Plugin stopped")
        }

        override fun getEditorClassName(): String = ""
    }

    override fun onBind(intent: Intent?): IBinder = binder

    @Synchronized
    private fun resetResampler(rate: Int) {
        currentInputRate = rate.coerceAtLeast(8000)
        inputPosition = 0L
        nextOutputPosition = 0.0
        previousSample = 0f
        havePrevious = false
    }

    private fun resampleToPcm16(input: FloatArray, frames: Int, rate: Int): ByteArray {
        if (rate != currentInputRate) resetResampler(rate)
        val step = rate.toDouble() / TARGET_RATE.toDouble()
        val capacity = (ceil(frames.toDouble() / step).toInt() + 2) * 2
        val out = ByteArray(capacity)
        var outOffset = 0

        for (i in 0 until frames) {
            val sample = input[i].takeIf { it.isFinite() }?.coerceIn(-1f, 1f) ?: 0f
            val absolutePosition = inputPosition.toDouble()

            if (!havePrevious) {
                previousSample = sample
                havePrevious = true
                while (nextOutputPosition <= absolutePosition) {
                    writePcm16(out, outOffset, sample).also { outOffset = it }
                    nextOutputPosition += step
                }
            } else {
                while (nextOutputPosition <= absolutePosition) {
                    val fraction = (nextOutputPosition - (absolutePosition - 1.0)).coerceIn(0.0, 1.0).toFloat()
                    val interpolated = previousSample + (sample - previousSample) * fraction
                    writePcm16(out, outOffset, interpolated).also { outOffset = it }
                    nextOutputPosition += step
                }
                previousSample = sample
            }
            inputPosition++
        }
        return if (outOffset == out.size) out else out.copyOf(outOffset)
    }

    private fun writePcm16(target: ByteArray, offset: Int, value: Float): Int {
        if (offset + 1 >= target.size) return offset
        val scaled = (value.coerceIn(-1f, 1f) * 32767f).toInt().coerceIn(-32768, 32767)
        target[offset] = (scaled and 0xff).toByte()
        target[offset + 1] = ((scaled ushr 8) and 0xff).toByte()
        return offset + 2
    }

    @Synchronized
    private fun startSender() {
        if (!running.compareAndSet(false, true)) return
        queue.clear()
        worker = Thread({
            var socket: Socket? = null
            var output: BufferedOutputStream? = null
            while (running.get()) {
                try {
                    if (socket == null || socket.isClosed || !socket.isConnected) {
                        socket = Socket()
                        socket.connect(InetSocketAddress(HOST, PORT), 1000)
                        socket.tcpNoDelay = true
                        output = BufferedOutputStream(socket.getOutputStream(), 8192)
                        connected = true
                        Log.i(TAG, "Connected to XMicInject $HOST:$PORT")
                    }
                    val block = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    output!!.write(block)
                    output.flush()
                } catch (t: Throwable) {
                    connected = false
                    try { socket?.close() } catch (_: Throwable) {}
                    socket = null
                    output = null
                    if (running.get()) {
                        Log.w(TAG, "TCP unavailable; retrying", t)
                        try { Thread.sleep(400) } catch (_: InterruptedException) { }
                    }
                }
            }
            connected = false
            try { socket?.close() } catch (_: Throwable) {}
        }, "MicUp-Wevo-TCP").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    @Synchronized
    private fun stopSender() {
        if (!running.compareAndSet(true, false)) return
        queue.clear()
        worker?.interrupt()
        try { worker?.join(500) } catch (_: InterruptedException) { }
        worker = null
        connected = false
    }

    override fun onDestroy() {
        stopSender()
        super.onDestroy()
    }
}
