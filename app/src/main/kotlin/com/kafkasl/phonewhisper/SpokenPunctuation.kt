package com.kafkasl.phonewhisper

/**
 * Convert spoken punctuation commands ("comma", "period", "open paren", ...)
 * into their corresponding symbols when surrounding context indicates the
 * spoken word is a dictation command rather than legitimate content.
 *
 * Three patterns fire for safe phrases:
 *   1. Whisper double-render: `<sym><phrase><sym>` -> `<sym>`
 *      (model inserted both the symbol and the spoken word around it).
 *   2. Inline mid-sentence: `<word> <phrase> <word>` -> `<word><sym><word>`.
 *   3. End-of-utterance: `<word> <phrase>[. ! ?]?$` -> `<word><sym>`.
 *
 * Risky phrases (period, colon, dash, etc. — commonly content words like
 * "period of rest", "made a dash", "colon cancer") only fire on Pattern 1;
 * the LLM SYMBOLS rule cleans up the rest.
 *
 * Mirrors `_normalize_spoken_symbols` in the desktop WhisperWriter project's
 * `src/transcription.py`. Keep the two in sync.
 */
object SpokenPunctuation {

    private enum class Side { L, R, B }

    private data class Entry(
        val phrase: String,
        val symbol: String,
        val side: Side,
        val safeInline: Boolean,
    )

    // Order matters: longer/more-specific phrases first so "exclamation mark"
    // wins over a hypothetical "exclamation", "open parenthesis" over "open paren".
    private val ENTRIES: List<Entry> = listOf(
        Entry("""new\s+paragraph""",                            "[blank line]", Side.B, true),
        Entry("""new\s+line""",                                 "[newline]",    Side.B, true),
        Entry("""exclamation\s+(?:mark|point)""",               "!",            Side.R, true),
        Entry("""question\s+mark""",                            "?",            Side.R, true),
        Entry("""open\s+parenthesis|open\s+paren""",            "(",            Side.L, true),
        Entry("""close\s+parenthesis|close\s+paren""",          ")",            Side.R, true),
        Entry("""open\s+bracket""",                             "[",            Side.L, true),
        Entry("""close\s+bracket""",                            "]",            Side.R, true),
        Entry("""open\s+curly(?:\s+brace)?|open\s+brace""",     "{",            Side.L, true),
        Entry("""close\s+curly(?:\s+brace)?|close\s+brace""",   "}",            Side.R, true),
        Entry("""end\s+quote""",                                "\"",           Side.R, true),
        Entry("""semi[\s-]?colon""",                            ";",            Side.R, true),
        Entry("""forward\s+slash""",                            "/",            Side.B, true),
        Entry("""back[\s-]?slash""",                            "\\",           Side.B, true),
        Entry("""at\s+(?:sign|symbol)""",                       "@",            Side.B, true),
        Entry("""hash\s+(?:sign|tag)|hashtag""",                "#",            Side.B, true),
        Entry("""equals\s+sign""",                              "=",            Side.B, true),
        Entry("""ellipsis""",                                   "...",          Side.R, true),
        Entry("""asterisk""",                                   "*",            Side.B, true),
        Entry("""hyphen""",                                     "-",            Side.B, true),
        Entry("""comma""",                                      ",",            Side.R, true),
        Entry("""full[\s-]?stop""",                             ".",            Side.R, true),
        // Risky inline matches — these spoken words are commonly legitimate
        // content. Only fire on a Whisper double-render (which Whisper would
        // never produce around real content).
        Entry("""period""",                                     ".",            Side.R, false),
        Entry("""colon""",                                      ":",            Side.R, false),
        Entry("""dash""",                                       "-",            Side.B, false),
        Entry("""slash""",                                      "/",            Side.B, false),
        Entry("""hash""",                                       "#",            Side.B, false),
        Entry("""equals""",                                     "=",            Side.B, false),
        Entry("""quote""",                                      "\"",           Side.L, false),
        Entry("""star""",                                       "*",            Side.B, false),
    )

    fun normalize(text: String): String {
        var out = text
        for (entry in ENTRIES) {
            val symEsc = Regex.escape(entry.symbol)
            val phraseGrouped = "(?:${entry.phrase})"

            // 1. Whisper double-render — symbol on both sides of the spoken word.
            out = Regex(
                """${symEsc}\s*\b${phraseGrouped}\b\s*${symEsc}""",
                RegexOption.IGNORE_CASE,
            ).replace(out) { entry.symbol }

            if (!entry.safeInline) continue

            when (entry.side) {
                Side.L -> {
                    // Inline: keep leading space, drop trailing.
                    out = Regex(
                        """(?<=\w)(\s+)\b${phraseGrouped}\b\s+(?=\w)""",
                        RegexOption.IGNORE_CASE,
                    ).replace(out) { m -> m.groupValues[1] + entry.symbol }
                    // Start-of-utterance: "open bracket 5 ..." → "[5 ..."
                    out = Regex(
                        """^\s*\b${phraseGrouped}\b\s+(?=\w)""",
                        RegexOption.IGNORE_CASE,
                    ).replace(out) { entry.symbol }
                }
                Side.R -> {
                    // Inline: drop leading space, keep trailing.
                    out = Regex(
                        """(?<=\w)\s+\b${phraseGrouped}\b(?=\s+\w)""",
                        RegexOption.IGNORE_CASE,
                    ).replace(out) { entry.symbol }
                    // End-of-utterance, optional Whisper-inserted terminator absorbed.
                    out = Regex(
                        """(?<=\w)\s+\b${phraseGrouped}\b\s*[.!?]?\s*$""",
                        RegexOption.IGNORE_CASE,
                    ).replace(out) { entry.symbol }
                }
                Side.B -> {
                    // Drop both spaces.
                    out = Regex(
                        """(?<=\w)\s+\b${phraseGrouped}\b\s+(?=\w)""",
                        RegexOption.IGNORE_CASE,
                    ).replace(out) { entry.symbol }
                    out = Regex(
                        """(?<=\w)\s+\b${phraseGrouped}\b\s*$""",
                        RegexOption.IGNORE_CASE,
                    ).replace(out) { entry.symbol }
                }
            }
        }
        return out
    }
}
