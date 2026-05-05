package com.kafkasl.phonewhisper

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.kafkasl.phonewhisper.history.HistoryEntry
import com.kafkasl.phonewhisper.history.HistoryRepository
import com.kafkasl.phonewhisper.history.HistoryStatus
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

class WhisperAccessibilityService : AccessibilityService() {

    companion object {
        var instance: WhisperAccessibilityService? = null
        private const val TAG = "PhoneWhisper"
        private const val SAMPLE_RATE = 16000
        private const val BTN_DP = 44
        private const val PAD_DP = 10
        private const val MARGIN_DP = 8
        private const val TAP_THRESHOLD_DP = 10
        private const val RING_DP = 56
        // Window is wider than the ring so the button has room to grow on press
        // (visually up to PRESSED_SCALE × button size) without being clipped.
        private const val WINDOW_DP = 108
        private const val PRESSED_SCALE = 1.85f
        private const val FEEDBACK_OFFSET_DP = 64

        private const val COLOR_IDLE = 0xDD1C1C1E.toInt()
        private const val COLOR_RECORDING = 0xDDEF4444.toInt()
        private const val COLOR_PAUSED = 0xDD16A34A.toInt()  // darker green
        private const val COLOR_BUSY = 0xDD6B6B6B.toInt()
        private const val COLOR_FEEDBACK_BG = 0xEE1C1C1E.toInt()
        private const val COLOR_RING = 0xFFE8EAED.toInt()

        private const val LONG_PRESS_MS = 225L
    }

    private enum class State { IDLE, RECORDING, PAUSED, TRANSCRIBING }

    private var state = State.IDLE
    private var overlayView: FrameLayout? = null
    private var button: ImageView? = null
    private var spinner: ProgressBar? = null
    private var feedbackView: TextView? = null
    private var retryPillView: TextView? = null
    private var retryPillParams: WindowManager.LayoutParams? = null
    /** ID of the most recent STT_FAILED entry whose audio is still on disk. Null when no retry is offered. */
    private var lastFailedEntryId: Long? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var feedbackLayoutParams: WindowManager.LayoutParams? = null
    private var audioRecord: AudioRecord? = null
    private var pcmStream: ByteArrayOutputStream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val hideFeedback = Runnable {
        feedbackView?.animate()?.alpha(0f)?.setDuration(180)?.withEndAction {
            feedbackView?.visibility = View.GONE
        }?.start()
    }

    // Local transcription engine (loaded lazily)
    private var localTranscriber: LocalTranscriber? = null

    // Transcription history (Room-backed)
    private val historyRepo by lazy { HistoryRepository(this) }
    private var recordingStartedAt: Long = 0L

    // Wake lock prevents the CPU from sleeping mid-recording when the screen turns off.
    // Acquired in startRecording, released in stopAndTranscribe / onDestroy.
    private var recordingWakeLock: PowerManager.WakeLock? = null

    private val dp get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onServiceConnected() {
        instance = this
        showOverlay()
        // Try to load local model in background
        thread { initLocalModel() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        releaseRecordingWakeLock()
        removeOverlay()
        super.onDestroy()
    }

    private fun acquireRecordingWakeLock() {
        if (recordingWakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        recordingWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "PhoneWhisper:Recording"
        ).apply {
            setReferenceCounted(false)
            // 10-minute safety timeout — if something goes wrong and we never call release,
            // the OS will release the lock for us instead of draining the battery.
            acquire(10 * 60 * 1000L)
        }
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.takeIf { it.isHeld }?.release()
        recordingWakeLock = null
    }

    private fun initLocalModel() {
        val modelName = prefs().getString("model_name", "") ?: ""
        if (modelName.isBlank()) {
            // Auto-detect first available model
            val models = LocalTranscriber.availableModels(this)
            if (models.isNotEmpty()) {
                Log.i(TAG, "Auto-detected model: ${models.first()}")
                localTranscriber = LocalTranscriber.create(this, models.first())
            }
        } else {
            localTranscriber = LocalTranscriber.create(this, modelName)
        }
        if (localTranscriber != null) {
            Log.i(TAG, "Local transcription ready")
        } else {
            Log.i(TAG, "No local model found, will use API")
        }
    }

    /** Reload local model (called from MainActivity when settings change) */
    fun reloadModel() { thread { initLocalModel() } }

    // --- Overlay ---

    private fun showOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val buttonSize = (BTN_DP * dp).toInt()
        val ringSize = (RING_DP * dp).toInt()
        val windowSize = (WINDOW_DP * dp).toInt()
        val pad = (PAD_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        // Position offsets so the visible button (not the larger window) sits at the screen edge.
        val rightSnapX = screenW - margin - (windowSize + buttonSize) / 2
        val leftSnapX = margin + (buttonSize - windowSize) / 2

        val ring = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_RING)
            visibility = View.GONE
        }

        val img = ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            background = circle(COLOR_IDLE)
        }

        val overlay = FrameLayout(this).apply {
            // Allow the button to scale beyond its layout bounds when pressed.
            clipChildren = false
            clipToPadding = false
            addView(ring, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.CENTER))
            addView(img, FrameLayout.LayoutParams(buttonSize, buttonSize, Gravity.CENTER))
        }

        val params = WindowManager.LayoutParams(
            windowSize, windowSize,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = rightSnapX
            y = screenH / 2 - windowSize / 2
        }

        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var longPressFired = false
        val longPressRunnable = Runnable {
            longPressFired = true
            // Tactile cue + collapse the button: transcription is beginning.
            vibrateShort()
            animateScaleTo(1f, duration = 180L)
            onLongPress()
        }

        overlay.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = ev.rawX; touchY = ev.rawY
                    longPressFired = false
                    animateScaleTo(PRESSED_SCALE)
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    if (moved >= TAP_THRESHOLD_DP * dp) {
                        // Movement disqualifies a long-press (treat it as a drag)
                        handler.removeCallbacks(longPressRunnable)
                    }
                    params.x = startX + (ev.rawX - touchX).toInt()
                    params.y = startY + (ev.rawY - touchY).toInt()
                    wm.updateViewLayout(v, params)
                    feedbackLayoutParams?.let {
                        positionFeedback(it, params)
                        wm.updateViewLayout(feedbackView, it)
                    }
                    updateRetryPillPosition()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPressRunnable)
                    val moved = abs(ev.rawX - touchX) + abs(ev.rawY - touchY)
                    when {
                        longPressFired -> {
                            // Long-press already handled — collapse already triggered there.
                        }
                        moved < TAP_THRESHOLD_DP * dp -> {
                            // Tap: collapse on release, then run the tap action.
                            animateScaleTo(1f)
                            onTap()
                        }
                        else -> {
                            // Drag finished: collapse and snap.
                            animateScaleTo(1f)
                            params.x = if (params.x + windowSize / 2 > screenW / 2) rightSnapX else leftSnapX
                            wm.updateViewLayout(v, params)
                            feedbackLayoutParams?.let {
                                positionFeedback(it, params)
                                wm.updateViewLayout(feedbackView, it)
                            }
                            updateRetryPillPosition()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPressRunnable)
                    animateScaleTo(1f)
                    true
                }
                else -> false
            }
        }

        val feedback = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
            background = pill(COLOR_FEEDBACK_BG)
            alpha = 0f
            visibility = View.GONE
        }

        val feedbackParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        positionFeedback(feedbackParams, params)

        // Retry pill: shown only after a transcription attempt failed (STT_FAILED).
        // Positioned next to the mic bubble; follows it as the user drags.
        val retryPill = TextView(this).apply {
            text = "↻ Retry"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = pill(0xDD4F46E5.toInt())  // indigo, distinct from idle / recording / paused
            visibility = View.GONE
            elevation = 4 * dp
            setOnClickListener { onRetryPillTap() }
        }
        val retryPillLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        wm.addView(overlay, params)
        wm.addView(feedback, feedbackParams)
        wm.addView(retryPill, retryPillLp)
        overlayView = overlay
        button = img
        spinner = ring
        feedbackView = feedback
        retryPillView = retryPill
        retryPillParams = retryPillLp
        layoutParams = params
        feedbackLayoutParams = feedbackParams
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let {
            wm.removeView(it)
            overlayView = null
        }
        feedbackView?.let {
            wm.removeView(it)
            feedbackView = null
        }
        retryPillView?.let {
            wm.removeView(it)
            retryPillView = null
        }
        retryPillParams = null
        button = null
        spinner = null
        layoutParams = null
        feedbackLayoutParams = null
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun pill(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16 * dp
        setColor(color)
    }

    private fun setAppearance(color: Int) {
        handler.post { button?.background = circle(color) }
    }

    private fun setBusy(visible: Boolean) {
        handler.post {
            spinner?.visibility = if (visible) View.VISIBLE else View.GONE
        }
    }

    /**
     * Animate the button's scale to the given target factor. Uses a dedicated
     * ObjectAnimator so it doesn't fight with view.animate() (which is used by
     * startPulse for the alpha breathing animation while recording).
     */
    private var scaleAnimator: ObjectAnimator? = null
    private fun animateScaleTo(target: Float, duration: Long = 100L) {
        val btn = button ?: return
        scaleAnimator?.cancel()
        scaleAnimator = ObjectAnimator.ofPropertyValuesHolder(
            btn,
            PropertyValuesHolder.ofFloat("scaleX", target),
            PropertyValuesHolder.ofFloat("scaleY", target)
        ).apply {
            this.duration = duration
            start()
        }
    }

    /** Brief haptic tap to signal a state transition the user can't see under their finger. */
    private fun vibrateShort() {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun positionFeedback(
        feedbackParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val margin = (MARGIN_DP * dp).toInt()
        val offset = (FEEDBACK_OFFSET_DP * dp).toInt()
        feedbackParams.x = maxOf(margin, bubbleParams.x - offset)
        feedbackParams.y = maxOf(margin, bubbleParams.y - margin)
    }

    /** Place the retry pill on the side of the mic bubble that has more room. */
    private fun positionRetryPill(
        pillParams: WindowManager.LayoutParams,
        bubbleParams: WindowManager.LayoutParams
    ) {
        val windowSize = (WINDOW_DP * dp).toInt()
        val margin = (MARGIN_DP * dp).toInt()
        val pillView = retryPillView ?: return
        // Force a measure so we know the pill's actual width
        pillView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pillW = pillView.measuredWidth
        val pillH = pillView.measuredHeight
        // Mic bubble is on the right half of the screen → put pill on its left.
        val micCenterX = bubbleParams.x + windowSize / 2
        pillParams.x = if (micCenterX > screenW / 2) {
            // Place pill to the left of the mic window
            (bubbleParams.x - pillW - margin).coerceAtLeast(margin)
        } else {
            // Place pill to the right of the mic window
            (bubbleParams.x + windowSize + margin).coerceAtMost(screenW - pillW - margin)
        }
        // Vertically centre on the mic
        pillParams.y = bubbleParams.y + (windowSize - pillH) / 2
    }

    /** Re-position pill against the current bubble params and push to the WindowManager. */
    private fun updateRetryPillPosition() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pill = retryPillView ?: return
        val pillP = retryPillParams ?: return
        val bubbleP = layoutParams ?: return
        positionRetryPill(pillP, bubbleP)
        if (pill.visibility == View.VISIBLE) wm.updateViewLayout(pill, pillP)
    }

    private fun showRetryPill(failedEntryId: Long) {
        lastFailedEntryId = failedEntryId
        handler.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val pill = retryPillView ?: return@post
            val pillP = retryPillParams ?: return@post
            val bubbleP = layoutParams ?: return@post
            positionRetryPill(pillP, bubbleP)
            pill.visibility = View.VISIBLE
            wm.updateViewLayout(pill, pillP)
        }
    }

    private fun hideRetryPill() {
        lastFailedEntryId = null
        handler.post {
            retryPillView?.visibility = View.GONE
        }
    }

    private fun onRetryPillTap() {
        val entryId = lastFailedEntryId ?: return
        if (state != State.IDLE) return  // ignore if currently recording/transcribing
        state = State.TRANSCRIBING
        setBusy(true)
        setAppearance(COLOR_BUSY)
        thread { retryFailedEntry(entryId) }
    }

    private fun retryFailedEntry(entryId: Long) {
        val entry = historyRepo.getById(entryId) ?: run {
            handler.post { reset("Entry not found"); hideRetryPill() }
            return
        }
        val audioPath = entry.audioPath
        if (audioPath == null) {
            handler.post { reset("Audio expired — cannot retry"); hideRetryPill() }
            return
        }
        val wav = historyRepo.loadAudio(audioPath)
        if (wav == null) {
            handler.post { reset("Audio file missing"); hideRetryPill() }
            return
        }
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            handler.post { reset("Set Groq API key in Settings") }
            return
        }
        val promptHint = prefs().getString("whisper_prompt_hint", TranscriberClient.DEFAULT_PROMPT_HINT)
            ?: TranscriberClient.DEFAULT_PROMPT_HINT

        TranscriberClient.transcribe(wav, apiKey, promptHint) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                // Got text — feed it through the same handleTranscriptionResult path so the
                // existing entry is updated (via finishAndSave) rather than a new row created.
                // But finishAndSave inserts a new entry; we want to UPDATE the failed one.
                // Simpler: call handleTranscriptionResult, which inserts a new SUCCESS row;
                // the user can clear the original STT_FAILED row from History if they want.
                handleTranscriptionResult(result.text, entry.timestamp, audioPath)
                hideRetryPill()
            } else {
                handler.post {
                    toast("Retry failed: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun showFeedback(text: String, durationMs: Long = 2000) {
        handler.post {
            val view = feedbackView ?: return@post
            val bubbleParams = layoutParams ?: return@post
            val feedbackParams = feedbackLayoutParams ?: return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager

            view.text = text
            positionFeedback(feedbackParams, bubbleParams)
            wm.updateViewLayout(view, feedbackParams)

            handler.removeCallbacks(hideFeedback)
            view.animate().cancel()
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.animate().alpha(1f).setDuration(120).start()
            handler.postDelayed(hideFeedback, durationMs)
        }
    }

    private fun startPulse() {
        button?.let {
            it.animate().alpha(0.4f).setDuration(500).withEndAction {
                it.animate().alpha(1f).setDuration(500).withEndAction {
                    if (state == State.RECORDING) startPulse()
                }.start()
            }.start()
        }
    }

    private fun stopPulse() {
        button?.animate()?.cancel()
        button?.alpha = 1f
    }

    // --- State machine ---

    private fun onTap() {
        when (state) {
            State.IDLE -> startRecording()
            State.RECORDING -> pauseRecording()
            State.PAUSED -> resumeRecording()
            State.TRANSCRIBING -> {}
        }
    }

    private fun onLongPress() {
        when (state) {
            State.RECORDING, State.PAUSED -> stopAndTranscribe()
            else -> {}  // long-press in IDLE or TRANSCRIBING is a no-op
        }
    }

    private fun pauseRecording() {
        state = State.PAUSED
        stopPulse()
        setAppearance(COLOR_PAUSED)
    }

    private fun resumeRecording() {
        state = State.RECORDING
        setAppearance(COLOR_RECORDING)
        startPulse()
    }

    /** Called from ToggleReceiver when an external app (e.g. Tasker) sends the toggle broadcast. */
    fun handleExternalToggle() {
        handler.post { onTap() }
    }

    private fun startRecording() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            toast("Grant audio permission in Phone Whisper app"); return
        }

        // Starting a fresh recording supersedes any previous failure offer.
        hideRetryPill()

        val bufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
            )
        } catch (_: SecurityException) { toast("Audio permission denied"); return }

        pcmStream = ByteArrayOutputStream()
        audioRecord!!.startRecording()
        recordingStartedAt = System.currentTimeMillis()
        acquireRecordingWakeLock()
        state = State.RECORDING
        setBusy(false)
        setAppearance(COLOR_RECORDING)
        startPulse()

        thread {
            val buf = ByteArray(bufSize)
            // Keep reading while RECORDING or PAUSED. While paused, we read but discard
            // so the AudioRecord buffer doesn't overflow; only RECORDING appends to pcmStream.
            while (state == State.RECORDING || state == State.PAUSED) {
                val n = audioRecord?.read(buf, 0, buf.size) ?: break
                if (n > 0 && state == State.RECORDING) pcmStream?.write(buf, 0, n)
            }
        }
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        stopPulse()
        setAppearance(COLOR_BUSY)
        setBusy(true)

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        releaseRecordingWakeLock()

        val pcm = pcmStream?.toByteArray() ?: ByteArray(0)
        pcmStream = null

        if (pcm.isEmpty()) { reset("No audio captured"); return }

        val useLocal = prefs().getBoolean("use_local", true)
        val local = localTranscriber

        if (useLocal && local != null) {
            transcribeLocal(pcm, local)
        } else {
            transcribeApi(pcm)
        }
    }

    private fun transcribeLocal(pcm: ByteArray, transcriber: LocalTranscriber) {
        val timestamp = recordingStartedAt
        thread {
            try {
                // Convert 16-bit PCM bytes to float samples
                val samples = FloatArray(pcm.size / 2)
                for (i in samples.indices) {
                    val lo = pcm[i * 2].toInt() and 0xFF
                    val hi = pcm[i * 2 + 1].toInt()
                    samples[i] = ((hi shl 8) or lo).toShort().toFloat() / 32768f
                }

                val t0 = System.currentTimeMillis()
                val text = transcriber.transcribe(samples, SAMPLE_RATE)
                val ms = System.currentTimeMillis() - t0
                Log.i(TAG, "Local transcription: ${ms}ms, ${samples.size / SAMPLE_RATE}s audio")

                handleTranscriptionResult(text, timestamp, audioPath = null)
            } catch (e: Exception) {
                Log.e(TAG, "Local transcription failed", e)
                saveSttFailureEntry(timestamp, audioPath = null, error = e.message)
                handler.post {
                    toast("Local error: ${e.message}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun transcribeApi(pcm: ByteArray) {
        val timestamp = recordingStartedAt
        val wav = WavWriter.encode(pcm)
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) { reset("Set API key in Phone Whisper app"); return }

        // Persist audio so the entry can be retried later (within the 14-day window)
        val audioPath = try {
            historyRepo.saveAudio(wav, timestamp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save audio file: ${e.message}")
            null
        }

        val promptHint = prefs().getString("whisper_prompt_hint", TranscriberClient.DEFAULT_PROMPT_HINT)
            ?: TranscriberClient.DEFAULT_PROMPT_HINT

        TranscriberClient.transcribe(wav, apiKey, promptHint) { result ->
            if (result.text != null && result.text.isNotBlank()) {
                handleTranscriptionResult(result.text, timestamp, audioPath)
            } else {
                saveSttFailureEntry(timestamp, audioPath, result.error ?: "empty transcript")
                handler.post {
                    toast("Error: ${result.error ?: "empty transcript"}")
                    state = State.IDLE
                    setBusy(false)
                    setAppearance(COLOR_IDLE)
                }
            }
        }
    }

    private fun handleTranscriptionResult(text: String?, timestamp: Long, audioPath: String?) {
        if (text.isNullOrBlank()) {
            saveSttFailureEntry(timestamp, audioPath, "empty transcript")
            handler.post {
                toast("No speech detected")
                state = State.IDLE
                setBusy(false)
                setAppearance(COLOR_IDLE)
            }
            return
        }

        val usePostProcessing = prefs().getBoolean("use_post_processing", false)
        val apiKey = prefs().getString("api_key", "") ?: ""

        if (usePostProcessing) {
            if (apiKey.isBlank()) {
                handler.post {
                    toast("Post-processing needs API key. Using raw text.")
                    finishAndSave(timestamp, audioPath, rawText = text, polishedText = null,
                        polishEnabled = true, polishError = "no api key", textToInject = text)
                }
                return
            }

            val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT

            PostProcessor.process(text, prompt, apiKey) { result ->
                handler.post {
                    if (result.text != null && result.text.isNotBlank()) {
                        finishAndSave(timestamp, audioPath, rawText = text, polishedText = result.text,
                            polishEnabled = true, polishError = null, textToInject = result.text)
                    } else {
                        Log.w(TAG, "Polish failed or rejected: ${result.error ?: "unknown"}")
                        finishAndSave(timestamp, audioPath, rawText = text, polishedText = null,
                            polishEnabled = true, polishError = result.error,
                            textToInject = text,
                            feedback = "Polish rejected — raw inserted", feedbackDurationMs = 3000)
                    }
                }
            }
        } else {
            handler.post {
                finishAndSave(timestamp, audioPath, rawText = text, polishedText = null,
                    polishEnabled = false, polishError = null, textToInject = text)
            }
        }
    }

    private fun reset(msg: String) {
        toast(msg)
        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    // --- History persistence ---

    /** Inject the resolved text, persist a SUCCESS / POLISH_FAILED entry, and return to IDLE. */
    private fun finishAndSave(
        timestamp: Long,
        audioPath: String?,
        rawText: String,
        polishedText: String?,
        polishEnabled: Boolean,
        polishError: String?,
        textToInject: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val targetPkg = rootInActiveWindow?.packageName?.toString()
        injectText(textToInject, feedback, feedbackDurationMs)

        val status = if (polishEnabled && polishedText == null) {
            HistoryStatus.POLISH_FAILED
        } else {
            HistoryStatus.SUCCESS
        }
        val entry = HistoryEntry(
            timestamp = timestamp,
            audioPath = audioPath,
            rawTranscript = rawText,
            polishedTranscript = polishedText,
            status = status,
            errorMessage = polishError,
            targetAppPackage = targetPkg,
            polishWasEnabled = polishEnabled
        )
        thread { historyRepo.insert(entry) }

        state = State.IDLE
        setBusy(false)
        setAppearance(COLOR_IDLE)
    }

    /** Persist a STT_FAILED entry. Audio path may be null for local-mode failures. */
    private fun saveSttFailureEntry(timestamp: Long, audioPath: String?, error: String?) {
        val polishEnabled = prefs().getBoolean("use_post_processing", false)
        val entry = HistoryEntry(
            timestamp = timestamp,
            audioPath = audioPath,
            rawTranscript = null,
            polishedTranscript = null,
            status = HistoryStatus.STT_FAILED,
            errorMessage = error,
            targetAppPackage = null,
            polishWasEnabled = polishEnabled
        )
        thread {
            val newId = historyRepo.insert(entry)
            // Only offer in-overlay retry when there's audio to replay (cloud path).
            if (audioPath != null) showRetryPill(newId)
        }
    }

    // --- Text injection ---

    private fun injectText(
        text: String,
        feedback: String? = "Copied to clipboard",
        feedbackDurationMs: Long = 2000
    ) {
        val finalText = withLeadingSpaceIfNeeded(text)

        val clip = ClipData.newPlainText("phonewhisper", finalText)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        feedback?.let { showFeedback(it, feedbackDurationMs) }

        val candidates = findInjectionCandidates()
        Log.i(TAG, "Injecting text into ${candidates.size} candidate node(s)")

        var injected = false
        try {
            for (candidate in candidates) {
                if (tryInjectIntoNode(candidate, finalText)) {
                    injected = true
                    break
                }
            }
        } finally {
            candidates.forEach { it.recycle() }
        }

        Log.i(TAG, if (injected) "Text injection action reported success" else "No injection action succeeded; clipboard fallback only")
    }

    /**
     * If the focused text field has existing text and the cursor sits right after a
     * non-whitespace character, prepend a space so the dictated text doesn't run into
     * the existing word. Returns the text unchanged if there's no focused field, no
     * existing content, or the cursor is already at a space / start of line.
     */
    private fun withLeadingSpaceIfNeeded(text: String): String {
        if (text.isEmpty() || text.startsWith(" ") || text.startsWith("\n")) return text
        val root = rootInActiveWindow ?: return text
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return text
            try {
                val current = focused.text?.toString().orEmpty()
                val cursor = focused.textSelectionStart
                if (cursor in 1..current.length) {
                    val charBefore = current[cursor - 1]
                    if (!charBefore.isWhitespace()) " $text" else text
                } else text
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun findInjectionCandidates(): List<AccessibilityNodeInfo> {
        val candidates = mutableListOf<AccessibilityNodeInfo>()

        rootInActiveWindow?.let { root ->
            Log.i(TAG, "Active root: package=${root.packageName} class=${root.className}")
            collectInjectionCandidates(root, candidates)
            root.recycle()
        }

        windows
            ?.filter { it.isActive || it.isFocused }
            ?.forEach { window ->
                val root = window.root ?: return@forEach
                Log.i(
                    TAG,
                    "Window root: type=${window.type} active=${window.isActive} focused=${window.isFocused} package=${root.packageName} class=${root.className}"
                )
                collectInjectionCandidates(root, candidates)
                root.recycle()
            }

        return candidates.sortedByDescending(::candidateScore)
    }

    private fun collectInjectionCandidates(
        root: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.let { out += it }
        root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)?.let { out += it }
        collectPotentialTargets(root, out)
    }

    private fun collectPotentialTargets(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (isPotentialInjectionTarget(node)) {
            out += AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectPotentialTargets(child, out)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isPotentialInjectionTarget(node: AccessibilityNodeInfo): Boolean {
        val className = node.className?.toString().orEmpty()
        return node.isFocused ||
            node.isEditable ||
            className.contains("EditText") ||
            className.contains("TerminalView") ||
            findCustomPasteAction(node) != null
    }

    private fun candidateScore(node: AccessibilityNodeInfo): Int {
        val className = node.className?.toString().orEmpty()
        var score = 0
        if (findCustomPasteAction(node) != null) score += 100
        if (className.contains("TerminalView")) score += 80
        if (node.isEditable) score += 60
        if (node.isFocused) score += 40
        if (className.contains("EditText")) score += 20
        return score
    }

    private fun tryInjectIntoNode(node: AccessibilityNodeInfo, text: String): Boolean {
        logNode("Trying node", node)

        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        findCustomPasteAction(node)?.let { action ->
            val ok = node.performAction(action.id)
            Log.i(TAG, "Custom action '${action.label}' (${action.id}) => $ok")
            if (ok) return true
        }

        val pasteOk = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "ACTION_PASTE => $pasteOk")
        if (pasteOk) return true

        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            val current = node.text?.toString().orEmpty()
            val start = if (node.textSelectionStart >= 0) node.textSelectionStart else current.length
            val end = if (node.textSelectionEnd >= 0) node.textSelectionEnd else start
            val replacementStart = minOf(start, end)
            val replacementEnd = maxOf(start, end)
            val updated = current.replaceRange(replacementStart, replacementEnd, text)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    updated
                )
            }
            val setTextOk = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            Log.i(TAG, "ACTION_SET_TEXT => $setTextOk")
            if (setTextOk) return true
        }

        return false
    }

    private fun findCustomPasteAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo.AccessibilityAction? =
        node.actionList.firstOrNull { action ->
            action.label?.toString()?.contains("paste", ignoreCase = true) == true
        }

    private fun logNode(prefix: String, node: AccessibilityNodeInfo) {
        val actions = node.actionList.joinToString { action ->
            action.label?.toString() ?: action.id.toString()
        }
        Log.i(
            TAG,
            "$prefix package=${node.packageName} class=${node.className} focused=${node.isFocused} editable=${node.isEditable} text=${node.text} desc=${node.contentDescription} actions=[$actions]"
        )
    }

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)
    private fun toast(msg: String) { handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
}
