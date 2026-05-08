package com.kafkasl.phonewhisper

/**
 * Whisper STT routinely emits a small set of canned phrases when fed silence
 * or near-silent audio (training-data artefacts). Without filtering, those
 * phrases get pasted into whichever app the user was dictating into.
 *
 * Ported verbatim from the desktop WhisperWriter project's
 * `_WHISPER_HALLUCINATIONS` set + trailing "Thank you" stripper.
 * Keep the two in sync if you add new entries.
 */
object TranscriptHallucinations {

    private val PHRASES: Set<String> = setOf(
        "thank you",
        "thank you.",
        "thank you for watching",
        "thank you for watching.",
        "thank you for watching!",
        "thanks for watching.",
        "thanks for watching!",
        "société radio-canada",
        "société radio canada",
        "[ silence ]",
        "[silence]",
        "subtitles by the amara.org community",
        "sous-titres réalisés para la communauté d'amara.org",
    )

    private val TRAILING_THANK_YOU = Regex(
        """[,]?\s*\bthank you[.!]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** True when the entire transcript matches a known Whisper silence hallucination. */
    fun isHallucinatedSilence(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized in PHRASES
    }

    /**
     * Strip a trailing "Thank you" / "Thank you." / "Thank you!" that Whisper
     * sometimes appends to otherwise-real transcriptions. Returns the cleaned
     * text (which may be empty if the whole transcript was just "Thank you").
     */
    fun stripTrailingThankYou(text: String): String {
        return TRAILING_THANK_YOU.replace(text, "").trim()
    }
}
