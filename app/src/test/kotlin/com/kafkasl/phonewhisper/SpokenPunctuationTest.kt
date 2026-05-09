package com.kafkasl.phonewhisper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the smoke-test suite for the Python sibling
 * (`whisper-writer/src/transcription.py::_normalize_spoken_symbols`).
 * Keep the two suites aligned when editing either implementation.
 */
class SpokenPunctuationTest {

    private fun check(input: String, expected: String, label: String) {
        assertEquals(label, expected, SpokenPunctuation.normalize(input))
    }

    @Test fun commaInline()           = check("hello comma world", "hello, world", "comma inline")
    @Test fun commaDoubleRender()     = check(", comma,", ",", "comma double-render")
    @Test fun commaEndOfUtterance()   = check("hello world comma", "hello world,", "comma EoU")
    @Test fun commaInsideWordKept()   = check("uncommas", "uncommas", "comma in word — must not match")
    @Test fun periodDoubleRender()    = check(". period.", ".", "period double-render")
    @Test fun periodEoUNotReplaced()  = check("hello world period", "hello world period", "period EoU NOT replaced")
    @Test fun periodInContent()       = check("the period of rest", "the period of rest", "period content (risky)")
    @Test fun questionMarkEoU()       = check("hello world question mark", "hello world?", "question mark EoU")
    @Test fun questionMarkInline()    = check("hello question mark world", "hello? world", "question mark inline")
    @Test fun parens()                = check("value open paren foo close paren bar", "value (foo) bar", "parens")
    @Test fun newlineInline()         = check("first thought new line second thought", "first thought[newline]second thought", "newline inline")
    @Test fun endQuoteInline()        = check("say hello end quote then leave", "say hello\" then leave", "end quote inline")
    @Test fun atSign()                = check("foo at sign bar", "foo@bar", "at sign")
    @Test fun dashNotReplaced()       = check("5 dash 10", "5 dash 10", "dash NOT replaced (risky)")
    @Test fun hyphenReplaced()        = check("5 hyphen 10", "5-10", "hyphen replaced (safe)")
    @Test fun exclamationPointEoU()   = check("say bye exclamation point", "say bye!", "exclamation point EoU")
    @Test fun periodDoubleMidText()   = check(". period. another sentence", ". another sentence", "period double-render mid-text")
    @Test fun multiDoubleRender()     = check(", comma, another, comma, item", ", another, item", "multi double-render")
    @Test fun semicolonInline()       = check("add semicolon here", "add; here", "semicolon inline")
    @Test fun colonNotReplaced()      = check("https colon slash slash example", "https colon slash slash example", "colon NOT replaced")
    @Test fun fullStopInline()        = check("hello full stop world", "hello. world", "full stop inline")
    @Test fun hyphenEmbeddedKept()    = check("hello hyphen-ated", "hello hyphen-ated", "hyphen embedded")
    @Test fun backslashInline()       = check("use back slash here", "use\\here", "backslash inline")
    @Test fun bracketsAtStart()       = check("open bracket 5 close bracket", "[5]", "brackets start-of-utterance")
    @Test fun commaAfterName()        = check("Aniket comma can you check it", "Aniket, can you check it", "comma after name")
    @Test fun asteriskInline()        = check("test asterisk here", "test*here", "asterisk inline")
}
