package com.example.structpulse.imu

import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter

/**
 * Crash-proof-ish CSV writer:
 * - Writes header once if file is empty
 * - Buffers rows in memory
 * - Flushes periodically (called by recorder)
 */
class CsvSink(
    private val outFile: File,
    private val headerLine: String
) {
    private var writer: BufferedWriter? = null
    private val buffer = StringBuilder(64 * 1024) // 64KB start

    fun openAppend() {
        outFile.parentFile?.mkdirs()
        val w = BufferedWriter(FileWriter(outFile, /*append=*/true))
        writer = w
        if (outFile.length() == 0L) {
            w.write(headerLine)
            w.write("\n")
            w.flush()
        }
    }

    fun appendRow(row: String) {
        buffer.append(row).append('\n')
        // safety valve (if periodic flush fails for some reason)
        if (buffer.length >= 512 * 1024) { // 512KB
            flush()
        }
    }

    fun flush() {
        val w = writer ?: return
        if (buffer.isEmpty()) return
        w.write(buffer.toString())
        w.flush()
        buffer.setLength(0)
    }

    fun close() {
        try {
            flush()
        } finally {
            writer?.flush()
            writer?.close()
            writer = null
        }
    }
}
