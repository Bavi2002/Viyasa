package com.fixlocal.android.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputSanitizerTest {

    @Test
    fun testStripPrefixSureHereIs() {
        val input = "Sure! Here is the corrected sentence: I went to the store yesterday."
        val expected = "I went to the store yesterday."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripPrefixHeresTheCorrected() {
        val input = "Here's the corrected version: She does not know anything about this."
        val expected = "She does not know anything about this."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripPrefixCorrectedSentence() {
        val input = "Corrected sentence: We bought fresh milk this morning."
        val expected = "We bought fresh milk this morning."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripPrefixFixGrammarAndSpelling() {
        val input = "Fix grammar and spelling:\nShe does not know anything about this."
        val expected = "She does not know anything about this."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripFixingGrammarAndSpellingHeader() {
        val input = """
            Fixing grammar and spelling:

            i am looking forward to hearing from you soon.

            Corrected:

            i am looking forward to hearing from you soon.
        """.trimIndent()
        val original = "i am looking forward to hear from you soon"
        val expected = "I am looking forward to hearing from you soon."
        assertEquals(expected, OutputSanitizer.sanitize(input, original))
    }

    @Test
    fun testStripArrowFormat() {
        val input = "she dont like apples -> She doesn't like apples."
        val expected = "She doesn't like apples."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripSurroundingDoubleQuotes() {
        val input = "\"Yesterday I went to the shop and bought milk.\""
        val expected = "Yesterday I went to the shop and bought milk."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripSurroundingCurlyQuotes() {
        val input = "“I am looking forward to hearing from you soon.”"
        val expected = "I am looking forward to hearing from you soon."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripMarkdownBold() {
        val input = "**I am looking forward to hearing from you soon.**"
        val expected = "I am looking forward to hearing from you soon."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripTrailingExplanation() {
        val input = """
            Yesterday I went to the shop and bought milk.
            
            Explanation: Fixed verb tense from 'go' to 'went' and irregular past tense from 'buyed' to 'bought'.
        """.trimIndent()
        val expected = "Yesterday I went to the shop and bought milk."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testStripPostHocNote() {
        val input = """
            I went to the shop and got some milk.

            I changed "yesterday" to "yesterday" because "yesterday" is a common misspelling of "today" or "this week." I also changed "got some" to "got some" because "got" is the correct verb form to use in this context.
        """.trimIndent()
        val expected = "I went to the shop and got some milk."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testApologyExtractionFromModelRambling() {
        val input = """
            I'm sorry for the confusion, but as a grammar and phrasing corrector, I don't have the ability to correct or fix text. However, I can help with the following:

            "He don't know nothing about this."

            This is a grammatically correct and idiomatic expression. It means that the person doesn't know anything about the topic or subject.

            If you want to make it even more concise, you could rephrase it to:

            "He doesn't know anything about this."

            This version is more direct and to the point.
        """.trimIndent()
        val original = "He don't know nothing about this."
        val expected = "He doesn't know anything about this."
        assertEquals(expected, OutputSanitizer.sanitize(input, original))
    }

    @Test
    fun testStripConversationalIntroImGlad() {
        val input = """
            I'm glad you liked the text. Here's the corrected version:

            I'm thinking about you, and I liked spending time with you.

            I changed "I am very thinking" to "I'm thinking" to correct the spelling and grammar.
        """.trimIndent()
        val original = "I am very thinking about you i liked to spending time with you"
        val expected = "I'm thinking about you, and I liked spending time with you."
        assertEquals(expected, OutputSanitizer.sanitize(input, original))
    }

    @Test
    fun testStripCommentaryAwkwardPhraseHeresRevised() {
        val input = """
            "Where you was going when I saw you" is a grammatically correct phrase, but it's a bit awkward. Here's a revised version:

            "Where were you going when I saw you?"

            This version is more natural and grammatically correct.
        """.trimIndent()
        val original = "where you was going when i saw you"
        val expected = "Where were you going when I saw you?"
        assertEquals(expected, OutputSanitizer.sanitize(input, original))
    }

    @Test
    fun testStripDoubleQuotes() {
        val input = "\"\"She has many tasks to complete today.\"\""
        val expected = "She has many tasks to complete today."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testDeduplicateRepeatedSentences() {
        val input = "I don't know you. I don't know you. I don't know you."
        val expected = "I don't know you."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }

    @Test
    fun testRemoveRepetitiveWordLoops() {
        val input = "बात बात बात बात करें।"
        val cleaned = OutputSanitizer.removeRepetitiveLoops(input)
        assertEquals("बात करें।", cleaned)
    }

    @Test
    fun testRemoveSubTokenLoops() {
        val input = "திர்த்திர்த்திர்த்திர்த்"
        val cleaned = OutputSanitizer.removeRepetitiveLoops(input)
        assertEquals("திர்த்", cleaned)
    }

    @Test
    fun testUnwrapJsonTranslation() {
        val input = """{"translation": "Bonjour tout le monde!"}"""
        val unwrapped = OutputSanitizer.unwrapJsonIfPresent(input)
        assertEquals("Bonjour tout le monde!", unwrapped)
    }

    @Test
    fun testStripTrailingHallucinatedExamples() {
        val input = """
            Yesterday we were playing cricket in the rain.

            Original: I was going to the store
            Corrected: I was going to the store.
        """.trimIndent()
        val expected = "Yesterday we were playing cricket in the rain."
        assertEquals(expected, OutputSanitizer.sanitize(input))
    }
}
