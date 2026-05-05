package com.kafkasl.phonewhisper.history

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.kafkasl.phonewhisper.PostProcessor
import com.kafkasl.phonewhisper.TranscriberClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class HistoryActivity : AppCompatActivity() {

    private val repo by lazy { HistoryRepository(this) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    private lateinit var listContainer: LinearLayout
    private lateinit var emptyLabel: TextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var normalBar: View
    private lateinit var selectionBar: View
    private lateinit var selectionCountLabel: TextView

    /** IDs of currently-selected entries. Multi-select is active iff this is non-empty. */
    private val selectedIds = mutableSetOf<Long>()
    private var cachedEntries: List<HistoryEntry> = emptyList()

    private fun inSelectionMode(): Boolean = selectedIds.isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Transcription history"
        setContentView(buildLayout())
        loadEntries()
    }

    override fun onBackPressed() {
        if (inSelectionMode()) {
            clearSelection()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload on every resume so navigating back from Settings shows fresh entries.
        // Use loadEntries() rather than refresh() so we don't show the spinner.
        loadEntries()
    }

    // --- Layout ---

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
        }

        normalBar = buildTopAppBar()
        selectionBar = buildSelectionTopBar().apply { visibility = View.GONE }
        root.addView(normalBar)
        root.addView(selectionBar)

        emptyLabel = TextView(this).apply {
            text = "No transcriptions yet.\nDictate something and it will appear here."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(64), dp(24), dp(64))
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            setLineSpacing(0f, 1.3f)
            visibility = View.GONE
        }
        root.addView(emptyLabel)

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(listContainer)
        }

        // Wrap the scroll view with SwipeRefreshLayout so the user can pull down to reload —
        // necessary because the floating-button transcription happens in an AccessibilityService
        // overlay that doesn't pause this activity, so onResume() doesn't fire on each new entry.
        swipeRefresh = SwipeRefreshLayout(this).apply {
            addView(scroll)
            setOnRefreshListener { refreshEntries() }
        }
        root.addView(swipeRefresh, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        return root
    }

    /** Pull-to-refresh entry point: shows the spinner and stops it once data is loaded. */
    private fun refreshEntries() {
        thread {
            val entries = repo.getAll()
            mainHandler.post {
                renderEntries(entries)
                swipeRefresh.isRefreshing = false
            }
        }
    }

    /** Material 3 small top app bar: title + trailing icon actions. */
    private fun buildTopAppBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(8), dp(8))
            elevation = dp(2).toFloat()
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
        }
        bar.addView(TextView(this).apply {
            text = "History"
            textSize = 22f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(iconActionButton("Clear") { confirmClearAll() })
        bar.addView(iconActionButton("Settings") {
            startActivity(Intent(this@HistoryActivity, com.kafkasl.phonewhisper.MainActivity::class.java))
        })
        return bar
    }

    private fun iconActionButton(label: String, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { onClick() }
        }
    }

    /**
     * Contextual top bar shown when one or more entries are selected. Replaces the
     * normal bar (Cancel | "N selected" | Select all | Copy).
     */
    private fun buildSelectionTopBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            elevation = dp(2).toFloat()
            setBackgroundColor(attrColor(android.R.attr.colorBackground))
        }
        bar.addView(iconActionButton("Cancel") { clearSelection() })
        selectionCountLabel = TextView(this).apply {
            text = "0 selected"
            textSize = 16f
            setPadding(dp(12), 0, dp(12), 0)
            setTextColor(attrColor(android.R.attr.textColorPrimary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        bar.addView(selectionCountLabel)
        bar.addView(iconActionButton("Select all") { selectAll() })
        bar.addView(iconActionButton("Copy") { copySelectedToClipboard() })
        return bar
    }

    // --- Selection state ---

    private fun toggleSelection(entry: HistoryEntry) {
        if (selectedIds.contains(entry.id)) selectedIds.remove(entry.id)
        else selectedIds.add(entry.id)
        updateChromeForSelection()
        renderEntries(cachedEntries)
    }

    private fun clearSelection() {
        if (selectedIds.isEmpty()) return
        selectedIds.clear()
        updateChromeForSelection()
        renderEntries(cachedEntries)
    }

    private fun selectAll() {
        cachedEntries.forEach { selectedIds.add(it.id) }
        updateChromeForSelection()
        renderEntries(cachedEntries)
    }

    private fun updateChromeForSelection() {
        val selecting = inSelectionMode()
        normalBar.visibility = if (selecting) View.GONE else View.VISIBLE
        selectionBar.visibility = if (selecting) View.VISIBLE else View.GONE
        if (selecting) {
            selectionCountLabel.text = "${selectedIds.size} selected"
        }
    }

    /**
     * Build the merged-clipboard text for the selected entries. Sorted by timestamp
     * ascending (oldest first) so the merged text reads chronologically. Each entry
     * uses its polished text if available, else its raw text.
     */
    private fun buildMergedText(): String {
        val ordered = cachedEntries
            .filter { it.id in selectedIds }
            .sortedBy { it.timestamp }
        return ordered.mapNotNull { entry ->
            entry.polishedTranscript?.takeIf { it.isNotBlank() }
                ?: entry.rawTranscript?.takeIf { it.isNotBlank() }
        }.joinToString(separator = "\n")
    }

    private fun copySelectedToClipboard() {
        val merged = buildMergedText()
        if (merged.isBlank()) {
            Toast.makeText(this, "Selected entries have no text to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phonewhisper-merged", merged))
        Toast.makeText(this, "${selectedIds.size} entries merged to clipboard", Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    // --- Data loading ---

    private fun loadEntries() {
        thread {
            val entries = repo.getAll()
            mainHandler.post { renderEntries(entries) }
        }
    }

    private fun renderEntries(entries: List<HistoryEntry>) {
        cachedEntries = entries
        // Drop selections that no longer correspond to any entry (e.g. after Clear all)
        if (selectedIds.isNotEmpty()) {
            val ids = entries.map { it.id }.toSet()
            if (selectedIds.removeAll { it !in ids }) updateChromeForSelection()
        }
        listContainer.removeAllViews()
        if (entries.isEmpty()) {
            emptyLabel.visibility = View.VISIBLE
            return
        }
        emptyLabel.visibility = View.GONE
        for (entry in entries) listContainer.addView(buildEntryCard(entry))
    }

    // --- Per-entry card ---

    private fun buildEntryCard(entry: HistoryEntry): View {
        val isSelected = entry.id in selectedIds
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setContentPadding(dp(16), dp(14), dp(16), dp(14))
            useCompatPadding = false
            // Material's built-in checkable card pattern: shows a checkmark + tinted
            // surface when isChecked. We drive isChecked from selectedIds and isCheckable
            // is always true so taps don't toggle implicitly.
            isCheckable = true
            isChecked = isSelected
            strokeWidth = if (isSelected) dp(2) else 0
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(4), dp(6), dp(4), dp(6))
            layoutParams = lp

            setOnLongClickListener {
                toggleSelection(entry); true
            }
            setOnClickListener {
                if (inSelectionMode()) toggleSelection(entry)
                else copyEntryText(entry)
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header row: timestamp + status chip
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(this).apply {
            text = timeFormat.format(Date(entry.timestamp))
            textSize = 12f
            letterSpacing = 0.04f
            setTextColor(attrColor(android.R.attr.textColorSecondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(buildStatusChip(entry.status))
        content.addView(header)

        // Transcript body (Material body-large size; raw is dimmed; error is italic)
        val displayText = when {
            !entry.polishedTranscript.isNullOrBlank() -> entry.polishedTranscript
            !entry.rawTranscript.isNullOrBlank() -> entry.rawTranscript
            else -> "No transcript — ${entry.errorMessage ?: "error"}"
        }
        val isErrorState = entry.polishedTranscript.isNullOrBlank() && entry.rawTranscript.isNullOrBlank()
        val isRawOnly = entry.polishedTranscript.isNullOrBlank() && !entry.rawTranscript.isNullOrBlank()
        content.addView(TextView(this).apply {
            text = displayText
            textSize = 16f
            setLineSpacing(0f, 1.25f)
            setPadding(0, dp(10), 0, dp(10))
            setTextColor(
                if (isErrorState) attrColor(android.R.attr.textColorSecondary)
                else attrColor(android.R.attr.textColorPrimary)
            )
            if (isErrorState) setTypeface(typeface, Typeface.ITALIC)
            if (isRawOnly) {
                // Mark raw-only with a subtle prefix so users know polish wasn't applied
                text = "Raw · $displayText"
            }
        })

        // Action row
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val retryButton = retryButtonFor(entry)
        if (retryButton != null) {
            actionRow.addView(retryButton)
        }
        actionRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 0, 1f)
        })
        actionRow.addView(MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = "More"
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { showEntryMenu(it, entry) }
        })
        content.addView(actionRow)

        card.addView(content)
        return card
    }

    private fun buildStatusChip(status: String): TextView {
        val (label, fg, bg) = when (status) {
            HistoryStatus.SUCCESS -> Triple("Success", 0xFF1B5E20.toInt(), 0x331B5E20)
            HistoryStatus.POLISH_FAILED -> Triple("Polish failed", 0xFF8B5A00.toInt(), 0x33B76E00)
            HistoryStatus.STT_FAILED -> Triple("STT failed", 0xFFB71C1C.toInt(), 0x33C62828)
            HistoryStatus.FLAGGED_BAD -> Triple("Flagged", 0xFF6A1B9A.toInt(), 0x336A1B9A)
            else -> Triple(status, attrColor(android.R.attr.textColorSecondary), 0x14808080)
        }
        return TextView(this).apply {
            text = label
            textSize = 11f
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.NORMAL)
            setTextColor(fg)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(bg)
            }
        }
    }

    private fun retryButtonFor(entry: HistoryEntry): View? {
        return when (entry.status) {
            HistoryStatus.STT_FAILED -> {
                if (entry.audioPath == null) {
                    null  // local-mode failure or audio expired; cannot retry
                } else {
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "Retry transcribe"
                        textSize = 13f
                        isAllCaps = false
                        setOnClickListener { retryTranscribe(entry) }
                    }
                }
            }
            HistoryStatus.POLISH_FAILED -> MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "Retry polish"
                textSize = 13f
                isAllCaps = false
                setOnClickListener { retryPolish(entry) }
            }
            else -> null
        }
    }

    // --- Per-entry actions ---

    private fun showEntryMenu(anchor: View, entry: HistoryEntry) {
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Copy text")
        if (entry.status != HistoryStatus.STT_FAILED && !entry.rawTranscript.isNullOrBlank()) {
            menu.menu.add("Re-polish with current settings")
        }
        if (entry.status == HistoryStatus.FLAGGED_BAD) {
            menu.menu.add("Unflag")
        } else {
            menu.menu.add("Flag as bad")
        }
        menu.menu.add("Delete this entry")
        menu.setOnMenuItemClickListener { item ->
            when (item.title) {
                "Copy text" -> copyEntryText(entry)
                "Re-polish with current settings" -> retryPolishWithCurrent(entry)
                "Flag as bad" -> setFlag(entry, HistoryStatus.FLAGGED_BAD)
                "Unflag" -> setFlag(entry, HistoryStatus.SUCCESS)
                "Delete this entry" -> deleteEntry(entry)
            }
            true
        }
        menu.show()
    }

    private fun copyEntryText(entry: HistoryEntry) {
        val text = entry.polishedTranscript ?: entry.rawTranscript ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("phonewhisper", text))
        Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun setFlag(entry: HistoryEntry, newStatus: String) {
        thread {
            repo.update(entry.copy(status = newStatus))
            mainHandler.post { loadEntries() }
        }
    }

    private fun deleteEntry(entry: HistoryEntry) {
        thread {
            repo.deleteById(entry.id)
            mainHandler.post { loadEntries() }
        }
    }

    private fun confirmClearAll() {
        thread {
            val count = repo.getAll().size
            mainHandler.post {
                if (count == 0) {
                    Toast.makeText(this, "History is already empty", Toast.LENGTH_SHORT).show()
                    return@post
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("Clear all history?")
                    .setMessage("Delete all $count entries and their saved audio? This cannot be undone.")
                    .setPositiveButton("Delete all") { _, _ ->
                        thread {
                            repo.deleteAll()
                            mainHandler.post { loadEntries() }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    // --- Retry actions ---

    private fun retryTranscribe(entry: HistoryEntry) {
        val audioPath = entry.audioPath ?: return
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Set the Groq API key first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Retrying transcription…", Toast.LENGTH_SHORT).show()

        thread {
            val wav = repo.loadAudio(audioPath)
            if (wav == null) {
                mainHandler.post {
                    Toast.makeText(this, "Audio file is missing or expired", Toast.LENGTH_SHORT).show()
                }
                return@thread
            }
            val promptHint = prefs().getString("whisper_prompt_hint", TranscriberClient.DEFAULT_PROMPT_HINT)
                ?: TranscriberClient.DEFAULT_PROMPT_HINT
            TranscriberClient.transcribe(wav, apiKey, promptHint) { result ->
                if (result.text != null && result.text.isNotBlank()) {
                    // STT succeeded; either polish or finalise
                    afterRetryTranscribeSucceeded(entry, result.text)
                } else {
                    // Still STT failed; just update the error
                    thread {
                        repo.update(entry.copy(errorMessage = result.error ?: "empty transcript"))
                        mainHandler.post {
                            Toast.makeText(this, "Transcription still failed: ${result.error}", Toast.LENGTH_SHORT).show()
                            loadEntries()
                        }
                    }
                }
            }
        }
    }

    private fun afterRetryTranscribeSucceeded(entry: HistoryEntry, rawText: String) {
        val polishEnabled = entry.polishWasEnabled
        if (!polishEnabled) {
            thread {
                repo.update(entry.copy(
                    rawTranscript = rawText,
                    polishedTranscript = null,
                    status = HistoryStatus.SUCCESS,
                    errorMessage = null
                ))
                mainHandler.post {
                    Toast.makeText(this, "Re-transcribed", Toast.LENGTH_SHORT).show()
                    loadEntries()
                }
            }
            return
        }
        val apiKey = prefs().getString("api_key", "") ?: ""
        val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        PostProcessor.process(rawText, prompt, apiKey) { polishResult ->
            val polished = polishResult.text?.takeIf { it.isNotBlank() }
            val status = if (polished != null) HistoryStatus.SUCCESS else HistoryStatus.POLISH_FAILED
            thread {
                repo.update(entry.copy(
                    rawTranscript = rawText,
                    polishedTranscript = polished,
                    status = status,
                    errorMessage = if (polished == null) polishResult.error else null
                ))
                mainHandler.post {
                    val msg = if (polished != null) "Re-transcribed and polished" else "Transcribed; polish failed"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    loadEntries()
                }
            }
        }
    }

    private fun retryPolish(entry: HistoryEntry) = retryPolishInternal(entry, useCurrentSettings = false)
    private fun retryPolishWithCurrent(entry: HistoryEntry) = retryPolishInternal(entry, useCurrentSettings = true)

    private fun retryPolishInternal(entry: HistoryEntry, useCurrentSettings: Boolean) {
        val rawText = entry.rawTranscript
        if (rawText.isNullOrBlank()) {
            Toast.makeText(this, "No raw transcript to polish", Toast.LENGTH_SHORT).show()
            return
        }
        val apiKey = prefs().getString("api_key", "") ?: ""
        if (apiKey.isBlank()) {
            Toast.makeText(this, "Set the Groq API key first", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, if (useCurrentSettings) "Re-polishing…" else "Retrying polish…", Toast.LENGTH_SHORT).show()

        val prompt = prefs().getString("post_processing_prompt", PostProcessor.DEFAULT_PROMPT) ?: PostProcessor.DEFAULT_PROMPT
        PostProcessor.process(rawText, prompt, apiKey) { result ->
            val polished = result.text?.takeIf { it.isNotBlank() }
            val status = if (polished != null) HistoryStatus.SUCCESS else HistoryStatus.POLISH_FAILED
            thread {
                repo.update(entry.copy(
                    polishedTranscript = polished,
                    status = status,
                    errorMessage = if (polished == null) result.error else null,
                    polishWasEnabled = true
                ))
                mainHandler.post {
                    val msg = if (polished != null) "Polished" else "Polish failed: ${result.error ?: "unknown"}"
                    Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    loadEntries()
                }
            }
        }
    }

    // --- Helpers ---

    private fun prefs() = getSharedPreferences("phonewhisper", MODE_PRIVATE)

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun attrColor(attr: Int): Int {
        val ta = obtainStyledAttributes(intArrayOf(attr))
        val color = ta.getColor(0, 0)
        ta.recycle()
        return color
    }

    companion object { private const val TAG = "PhoneWhisperHistory" }
}
