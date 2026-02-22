package com.example.structpulse.imu

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/**
 * FUSED sampler recorder:
 * - Sensor callbacks ONLY update latest accel/gyro cache (NO writing here)
 * - A fixed-rate tick (targetHz) writes rows: ts_ns,ax,ay,az,gx,gy,gz
 *
 * Guarantees:
 * - No duplicate ts_ns rows
 * - Tick timestamps never go into the "future" relative to sensor time base
 * - If the handler is delayed, we "catch up" by writing missing ticks up to lastEventTsNs
 */
class ImuRecorder(
    context: Context,
    private val onFirstWrittenTsNs: ((Long) -> Unit)? = null,
    private val onError: ((Throwable) -> Unit)? = null
) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private var accel: Sensor? = null
    private var gyro: Sensor? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var sink: CsvSink? = null
    private val isRecording = AtomicBoolean(false)

    // Latest cached values (updated on handler looper)
    private var ax = Float.NaN
    private var ay = Float.NaN
    private var az = Float.NaN
    private var gx = Float.NaN
    private var gy = Float.NaN
    private var gz = Float.NaN

    private var hasAccel = false
    private var hasGyro = false

    // Latest sensor event timestamp (ns) in the SAME time base as SensorEvent.timestamp
    private var lastEventTsNs: Long = 0L

    // Tick config
    private var targetHz: Int = 200
    private var periodNs: Long = 5_000_000L // 200 Hz default

    // Tick state
    private var tickPosted = false
    private var tickIndex: Long = 0L
    private var baseTsNs: Long = 0L // anchor in sensor time-base
    private var firstDumpDone = false

    // Pre-roll buffer: we BUFFER first preRollNs of ticks, then dump all at once and continue streaming.
    private var preRollNs: Long = 2_000_000_000L // 2s default
    private val preRollQueue = ArrayDeque<Row>(4096)

    // Periodic flush
    private var flushEveryMs: Long = 1_000L
    private var flushPosted = false

    data class Row(
        val tsNs: Long,
        val ax: Float, val ay: Float, val az: Float,
        val gx: Float, val gy: Float, val gz: Float
    ) {
        fun toCsvLine(): String = "$tsNs,$ax,$ay,$az,$gx,$gy,$gz"
    }

    fun start(
        outputCsv: File,
        targetHz: Int = 200,
        preRollMs: Long = 2000,
        flushEveryMs: Long = 1000
    ) {
        if (!isRecording.compareAndSet(false, true)) return

        this.targetHz = targetHz.coerceAtLeast(1)
        this.periodNs = 1_000_000_000L / this.targetHz.toLong()
        this.preRollNs = preRollMs.coerceAtLeast(0) * 1_000_000L
        this.flushEveryMs = flushEveryMs.coerceAtLeast(200)

        // reset state
        ax = Float.NaN; ay = Float.NaN; az = Float.NaN
        gx = Float.NaN; gy = Float.NaN; gz = Float.NaN
        hasAccel = false
        hasGyro = false
        lastEventTsNs = 0L
        tickIndex = 0L
        baseTsNs = 0L
        firstDumpDone = false
        preRollQueue.clear()
        tickPosted = false
        flushPosted = false

        try {
            sink = CsvSink(
                outFile = outputCsv,
                headerLine = "ts_ns,ax,ay,az,gx,gy,gz"
            ).also { it.openAppend() }
        } catch (t: Throwable) {
            fail(t); return
        }

        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (accel == null || gyro == null) {
            fail(IllegalStateException("Missing sensors. accel=$accel gyro=$gyro"))
            return
        }

        thread = HandlerThread("ImuRecorderThread").apply { start() }
        handler = Handler(thread!!.looper)

        try {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, handler)
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, handler)
        } catch (t: Throwable) {
            fail(t); return
        }

        scheduleFlush()
        scheduleTick()
    }

    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return

        try { sensorManager.unregisterListener(this) } catch (_: Throwable) {}

        try { sink?.flush() } catch (t: Throwable) { onError?.invoke(t) }
        try { sink?.close() } catch (t: Throwable) { onError?.invoke(t) } finally { sink = null }

        try { thread?.quitSafely() } catch (_: Throwable) {}
        thread = null
        handler = null
        tickPosted = false
        flushPosted = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!isRecording.get()) return

        // IMPORTANT: no writing here. Only update cache.
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                ax = event.values[0]; ay = event.values[1]; az = event.values[2]
                hasAccel = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                gx = event.values[0]; gy = event.values[1]; gz = event.values[2]
                hasGyro = true
            }
            else -> return
        }

        lastEventTsNs = event.timestamp

        // Anchor baseTsNs on first ever sensor event
        if (baseTsNs == 0L) {
            baseTsNs = lastEventTsNs
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // no-op
    }

    private fun scheduleTick() {
        val h = handler ?: return
        if (tickPosted) return
        tickPosted = true

        val r = object : Runnable {
            override fun run() {
                if (!isRecording.get()) {
                    tickPosted = false
                    return
                }

                try {
                    val s = sink ?: return

                    // wait until we have a base anchor AND at least one sample from both sensors
                    if (baseTsNs == 0L || !hasAccel || !hasGyro || lastEventTsNs == 0L) {
                        h.postDelayed(this, 5)
                        return
                    }

                    // Catch-up: write ticks up to lastEventTsNs (never write future timestamps)
                    val base = baseTsNs
                    var wroteAny = false

                    while (true) {
                        val tickTs = base + tickIndex * periodNs
                        if (tickTs > lastEventTsNs) break

                        val row = Row(tickTs, ax, ay, az, gx, gy, gz)
                        tickIndex++
                        wroteAny = true

                        if (!firstDumpDone && preRollNs > 0) {
                            preRollQueue.addLast(row)

                            // Once buffer covers preRollNs, dump everything once and switch to streaming
                            val firstTs = preRollQueue.first().tsNs
                            val covered = (tickTs - firstTs) >= preRollNs
                            if (covered) {
                                firstDumpDone = true

                                // t0_ns = first row timestamp that will appear in CSV
                                onFirstWrittenTsNs?.invoke(firstTs)

                                for (pr in preRollQueue) {
                                    s.appendRow(pr.toCsvLine())
                                }
                                preRollQueue.clear()
                            }
                        } else {
                            // if preRoll disabled OR already dumped, write directly
                            if (!firstDumpDone && preRollNs == 0L) {
                                firstDumpDone = true
                                onFirstWrittenTsNs?.invoke(tickTs)
                            }
                            if (firstDumpDone) {
                                s.appendRow(row.toCsvLine())
                            }
                        }
                    }

                    // If we are still buffering pre-roll and haven't dumped yet, do nothing here.
                    // The dump happens as soon as buffer length reaches preRollNs.

                    // If handler got delayed and we wrote many rows, that's okay. CsvSink buffers + flushes.
                } catch (t: Throwable) {
                    fail(t)
                    return
                }

                // Schedule next check (keep small so catch-up is smooth)
                h.postDelayed(this, 5)
            }
        }

        h.post(r)
    }

    private fun scheduleFlush() {
        val h = handler ?: return
        if (flushPosted) return
        flushPosted = true

        val r = object : Runnable {
            override fun run() {
                if (!isRecording.get()) {
                    flushPosted = false
                    return
                }
                try {
                    sink?.flush()
                } catch (t: Throwable) {
                    fail(t)
                    return
                }
                h.postDelayed(this, flushEveryMs)
            }
        }
        h.postDelayed(r, flushEveryMs)
    }

    private fun fail(t: Throwable) {
        try { onError?.invoke(t) } catch (_: Throwable) {}
        try { stop() } catch (_: Throwable) {}
    }
}
