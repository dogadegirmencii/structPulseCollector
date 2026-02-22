package com.example.structpulse

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.example.structpulse.export.ZipSharer
import com.example.structpulse.imu.ImuRecorder
import com.example.structpulse.session.SessionInfo
import com.example.structpulse.session.SessionLabels
import com.example.structpulse.session.SessionManager
import com.example.structpulse.storage.RecentStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {

    private lateinit var sessionManager: SessionManager
    private lateinit var recentStore: RecentStore
    private lateinit var zipSharer: ZipSharer

    private var currentSession: SessionInfo? = null

    private var imuRecorder: ImuRecorder? = null

    private enum class UiState {
        IDLE,
        RECORDING,
        READY_TO_EXPORT
    }

    private var uiState: UiState = UiState.IDLE

    private fun setState(state: UiState) {
        uiState = state
        applyState()
    }

    private fun applyState() {
        when (uiState) {
            UiState.IDLE -> {
                setInputsEnabled(true)
                btnStop.isEnabled = false
                btnExport.isEnabled = false
                txtStatus.text = "Status: Idle"
                updateStartEnabled()
            }

            UiState.RECORDING -> {
                setInputsEnabled(false)
                btnStart.isEnabled = false
                btnStop.isEnabled = true
                btnExport.isEnabled = false
                txtStatus.text = "Status: Recording"
            }

            UiState.READY_TO_EXPORT -> {
                setInputsEnabled(true)
                btnStop.isEnabled = false
                btnExport.isEnabled = true
                txtStatus.text = "Status: Ready to export"
                updateStartEnabled()
            }
        }
    }

    // Inputs
    private lateinit var inputElementType: EditText
    private lateinit var inputDamageLevel: EditText
    private lateinit var inputImpactType: EditText
    private lateinit var inputRepNumber: EditText

    // Buttons
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnExport: Button

    // Status / Recent
    private lateinit var txtStatus: TextView
    private lateinit var txtRecentList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        bindViews()

        sessionManager = SessionManager(applicationContext)
        recentStore = RecentStore(applicationContext)
        zipSharer = ZipSharer(this)

        initUi()
    }

    private fun bindViews() {
        inputElementType = findViewById(R.id.inputElementType)
        inputDamageLevel = findViewById(R.id.inputDamageLevel)
        inputImpactType = findViewById(R.id.inputImpactType)
        inputRepNumber = findViewById(R.id.inputRepNumber)

        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnExport = findViewById(R.id.btnExport)

        txtStatus = findViewById(R.id.txtStatus)
        txtRecentList = findViewById(R.id.txtRecentList)
    }

    private fun initUi() {
        renderRecents()
        setState(UiState.IDLE)

        inputElementType.doAfterTextChanged { updateStartEnabled() }
        inputDamageLevel.doAfterTextChanged { updateStartEnabled() }
        inputImpactType.doAfterTextChanged { updateStartEnabled() }
        inputRepNumber.doAfterTextChanged { updateStartEnabled() }

        inputElementType.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                inputDamageLevel.requestFocus()
                true
            } else false
        }

        inputDamageLevel.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                inputImpactType.requestFocus()
                true
            } else false
        }

        inputImpactType.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) {
                inputRepNumber.requestFocus()
                true
            } else false
        }

        inputRepNumber.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                inputRepNumber.clearFocus()
                hideKeyboard()
                true
            } else false
        }

        btnStart.setOnClickListener {
            hideKeyboard()

            val labels = SessionLabels(
                elementType = inputElementType.text.toString(),
                damageLevel = inputDamageLevel.text.toString(),
                impactType = inputImpactType.text.toString(),
                repNumber = inputRepNumber.text.toString()
            )

            val session = sessionManager.createNewSession(labels, targetHz = 200)
            currentSession = session

            recentStore.add(session.sessionId)
            renderRecents()

            imuRecorder = ImuRecorder(
                context = this,
                onFirstWrittenTsNs = { ts0 ->
                    try {
                        sessionManager.updateT0Ns(session.metadataFile, ts0)
                        Log.i("ImuRecorder", "t0_ns written: $ts0")
                    } catch (t: Throwable) {
                        Log.e("ImuRecorder", "Failed to write t0_ns", t)
                    }
                },
                onError = { err ->
                    Log.e("ImuRecorder", "IMU error", err)
                }
            )

            imuRecorder?.start(
                outputCsv = session.imuCsvFile,
                targetHz = 200,
                preRollMs = 2000,
                flushEveryMs = 1000
            )

            setState(UiState.RECORDING)
        }

        btnStop.setOnClickListener {
            imuRecorder?.stop()
            imuRecorder = null

            val session = currentSession
            if (session != null) {
                sessionManager.finalizeMetadata(
                    metadataFile = session.metadataFile,
                    endWallTimeIso = nowIso8601()
                )
            }

            setState(UiState.READY_TO_EXPORT)
        }

        btnExport.setOnClickListener {
            val session = currentSession
            if (session == null) {
                txtStatus.text = "Status: No session to export"
                return@setOnClickListener
            }

            try {
                zipSharer.zipAndShareSession(
                    sessionDir = session.sessionDir,
                    sessionId = session.sessionId
                )

                // ✅ DO NOT switch to IDLE (Drive upload might fail)
                txtStatus.text = "Status: Shared (if upload fails, press Export again)"
                // keep READY_TO_EXPORT
                setState(UiState.READY_TO_EXPORT)

            } catch (t: Throwable) {
                txtStatus.text = "Status: Export failed (check Logcat)"
                Log.e("ZipSharer", "Export failed", t)
                setState(UiState.READY_TO_EXPORT)
            }
        }

        updateStartEnabled()
    }

    private fun renderRecents() {
        val recents = recentStore.load()
        txtRecentList.text = if (recents.isEmpty()) "(none)" else recents.joinToString("\n")
    }

    private fun isFormValid(): Boolean {
        val elementType = inputElementType.text?.toString()?.trim().orEmpty()
        val damageLevel = inputDamageLevel.text?.toString()?.trim().orEmpty()
        val impactType = inputImpactType.text?.toString()?.trim().orEmpty()
        val repNumber = inputRepNumber.text?.toString()?.trim().orEmpty()

        return elementType.isNotEmpty() &&
                damageLevel.isNotEmpty() &&
                impactType.isNotEmpty() &&
                repNumber.isNotEmpty()
    }

    private fun updateStartEnabled() {
        btnStart.isEnabled = (uiState == UiState.IDLE) && isFormValid()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        val view = currentFocus ?: window.decorView
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun setInputsEnabled(enabled: Boolean) {
        inputElementType.isEnabled = enabled
        inputDamageLevel.isEnabled = enabled
        inputImpactType.isEnabled = enabled
        inputRepNumber.isEnabled = enabled
    }

    private fun nowIso8601(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Europe/Istanbul")
        return sdf.format(Date())
    }

    override fun onDestroy() {
        imuRecorder?.stop()
        imuRecorder = null
        super.onDestroy()
    }
}
