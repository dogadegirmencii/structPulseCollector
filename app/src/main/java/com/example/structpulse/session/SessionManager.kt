package com.example.structpulse.session

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class SessionLabels(
    val elementType: String,
    val damageLevel: String,
    val impactType: String,
    val repNumber: String
)

data class SessionInfo(
    val sessionId: String,
    val sessionDir: File,
    val imuCsvFile: File,
    val metadataFile: File
)

class SessionManager(private val appContext: Context) {

    private val tz = TimeZone.getTimeZone("Europe/Istanbul")

    fun createNewSession(labels: SessionLabels, targetHz: Int): SessionInfo {
        val sessionId = buildSessionId(labels)
        val sessionDir = File(getSessionsRootDir(), sessionId)

        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }

        val imuCsvFile = File(sessionDir, "imu.csv")
        val metadataFile = File(sessionDir, "metadata.json")



        writeInitialMetadata(
            metadataFile = metadataFile,
            sessionId = sessionId,
            labels = labels,
            targetHz = targetHz
        )

        return SessionInfo(
            sessionId = sessionId,
            sessionDir = sessionDir,
            imuCsvFile = imuCsvFile,
            metadataFile = metadataFile
        )
    }

    private fun getSessionsRootDir(): File {
        // App-specific external storage (no extra permission needed)
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val sessions = File(base, "sessions")
        if (!sessions.exists()) sessions.mkdirs()
        return sessions
    }

    private fun buildSessionId(labels: SessionLabels): String {
        val ts = formatLocalTimestampForId(System.currentTimeMillis())
        val element = sanitizeForId(labels.elementType)
        val damage = sanitizeForId(labels.damageLevel)
        val impact = sanitizeForId(labels.impactType)
        val rep = labels.repNumber.trim().padStart(2, '0')

        return "${ts}__type-${element}__damage-${damage}__impact-${impact}__rep-${rep}"
    }

    private fun sanitizeForId(raw: String): String {
        return raw.trim()
            .replace("\\s+".toRegex(), "_")
            .replace(Regex("[^A-Za-z0-9_\\-]"), "")
            .take(32)
            .ifEmpty { "unknown" }
    }

    private fun formatLocalTimestampForId(epochMs: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        sdf.timeZone = tz
        return sdf.format(Date(epochMs))
    }

    private fun writeInitialMetadata(
        metadataFile: File,
        sessionId: String,
        labels: SessionLabels,
        targetHz: Int
    ) {
        val root = JSONObject()

        root.put("session_id", sessionId)

        val labelsJson = JSONObject()
        labelsJson.put("element_type", labels.elementType)
        labelsJson.put("damage_level", labels.damageLevel)
        labelsJson.put("impact_type", labels.impactType)
        labelsJson.put("rep_number", labels.repNumber)
        root.put("labels", labelsJson)

        val timingJson = JSONObject()
        timingJson.put("start_wall_time", nowIso8601())
        timingJson.put("end_wall_time", JSONObject.NULL)
        timingJson.put("t0_ns", JSONObject.NULL) // will be filled when recording starts
        timingJson.put("target_hz", targetHz)
        root.put("timing", timingJson)

        val deviceJson = JSONObject()
        deviceJson.put("manufacturer", Build.MANUFACTURER ?: "")
        deviceJson.put("model", Build.MODEL ?: "")
        deviceJson.put("android_sdk", Build.VERSION.SDK_INT)
        root.put("device", deviceJson)

        metadataFile.writeText(root.toString(2))
    }

    private fun nowIso8601(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = tz
        return sdf.format(Date())
    }

    fun finalizeMetadata(metadataFile: File, endWallTimeIso: String) {
        // MVP: read, update end_wall_time, write back.
        val json = JSONObject(metadataFile.readText())
        val timing = json.getJSONObject("timing")
        timing.put("end_wall_time", endWallTimeIso)
        metadataFile.writeText(json.toString(2))
    }

    /**
     * ✅ D4: Fill timing.t0_ns once (first written IMU timestamp).
     * - If already set, leave it untouched.
     */
    fun updateT0Ns(metadataFile: File, t0Ns: Long) {
        val json = JSONObject(metadataFile.readText())
        val timing = json.getJSONObject("timing")

        // Only write if it's currently null/missing
        val current = timing.opt("t0_ns")
        val isNull = (current == null) || (current == JSONObject.NULL)
        if (!isNull) return

        timing.put("t0_ns", t0Ns)
        metadataFile.writeText(json.toString(2))
    }
}
