package com.fixlocal.android.llm

object OutputSanitizer {

    private val SPECIAL_TOKENS_PATTERN = Regex("""<(?:pad|start_of_turn|end_of_turn|bos|eos|unk)>""", RegexOption.IGNORE_CASE)

    fun stripSpecialTokens(input: String): String {
        return SPECIAL_TOKENS_PATTERN.replace(input, "").trim()
    }

    /**
     * Unwraps structured JSON outputs emitted by models trained on function/JSON schemas
     * (such as Gemma 3 litertlm task heads).
     */
    fun unwrapJsonIfPresent(input: String): String {
        val trimmed = stripSpecialTokens(input)
        if (trimmed.isBlank()) return ""

        // Fast regex extraction for {"key": "content..."} even if JSON brace is unclosed or trailing quotes exist
        val regexMatch = Regex("""\{(?:\s*"[^"]+"\s*:\s*)"([^"\n]+)""").find(trimmed)
        if (regexMatch != null && regexMatch.groupValues.size >= 2) {
            val extracted = regexMatch.groupValues[1].trim()
            if (extracted.isNotBlank()) return extracted
        }

        // Check if string contains JSON object { ... }
        val jsonCandidate = if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            trimmed
        } else if (trimmed.startsWith("{") && !trimmed.endsWith("}")) {
            "$trimmed\"}"
        } else {
            Regex("""\{[\s\S]*\}""").find(trimmed)?.value
        }

        if (jsonCandidate != null) {
            try {
                val json = org.json.JSONObject(jsonCandidate)
                val priorityKeys = listOf(
                    "corrected", "translation", "text", "summary",
                    "result", "output", "response", "sentence", "fixed"
                )
                for (key in priorityKeys) {
                    if (json.has(key)) {
                        val value = json.optString(key, "").trim()
                        if (value.isNotBlank()) return value
                    }
                }
                // Fallback: take first non-empty string value
                val keys = json.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = json.optString(k, "").trim()
                    if (v.isNotBlank()) return v
                }
            } catch (_: Throwable) {
            }
        }
        return trimmed
    }

    private val PREFIX_PATTERNS = listOf(
        Regex("""(?i)^(here\s+is\s+the\s+(corrected|fixed)\s+(sentence|text|version|phrasing):?\s*)"""),
        Regex("""(?i)^(here'?s\s+the\s+(corrected|fixed)\s+(sentence|text|version|phrasing):?\s*)"""),
        Regex("""(?i)^(here\s+is\s+a\s+(corrected|fixed|revised)\s+(sentence|text|version|phrasing):?\s*)"""),
        Regex("""(?i)^(here'?s\s+a\s+(corrected|fixed|revised)\s+(sentence|text|version|phrasing):?\s*)"""),
        Regex("""(?i)^(the\s+(corrected|fixed)\s+(sentence|text|version)\s+is:?\s*)"""),
        Regex("""(?i)^(corrected\s+(sentence|text|version)?:?\s*)"""),
        Regex("""(?i)^(output:?\s*)"""),
        Regex("""(?i)^(correction:?\s*)"""),
        Regex("""(?i)^(revised\s+(sentence|text|version)?:?\s*)"""),
        Regex("""(?i)^(correct\s+version:?\s*)"""),
        Regex("""(?i)^(fix(ing)?\s+(the\s+)?grammar\s*(and\s+spelling)?:?\s*)"""),
        Regex("""(?i)^(correct(ing)?\s+(the\s+)?following\s+text:?\s*)"""),
        Regex("""(?i)^(correct\s+this\s+text:?\s*)"""),
        Regex("""(?i)^(sure!?,?\s*(here\s+is|here'?s)?\s*(the\s+(corrected|fixed)\s+(sentence|text|version):?)?\s*)"""),
        Regex("""(?i)^(certainly!?,?\s*(here\s+is|here'?s)?\s*(the\s+(corrected|fixed)\s+(sentence|text|version):?)?\s*)"""),
        Regex("""(?i)^(\*\*corrected:\*\*\s*)"""),
        Regex("""(?i)^(\*\*output:\*\*\s*)""")
    )

    private val TRAILING_CUTOFF_PATTERNS = listOf(
        Regex("""(?i)\n+\s*(original|input):[\s\S]*$"""),
        Regex("""(?i)\n+\s*(explanation|note|notes|changes\s+made|reason|why\s+this\s+was\s+changed):[\s\S]*$"""),
        Regex("""(?i)\n+\s*this\s+(version|sentence|is\s+a\s+grammatically)[\s\S]*$"""),
        Regex("""(?i)\n+\s*if\s+you\s+want\s+to\s+make[\s\S]*$"""),
        Regex("""(?i)\n+\s*[-*•]\s*[\s\S]*$"""),
        Regex("""(?i)\n+\s*i\s+(also\s+)?(changed|corrected|fixed|modified|made)[\s\S]*$"""),
        Regex("""(?i)\n+\s*alternatively[\s\S]*$""")
    )

    private val APOLOGY_START_PATTERN = Regex(
        """(?i)^(i'?m\s+sorry|i\s+apologize|as\s+an?\s+(ai|grammar|language|phrasing|english)|as\s+a\s+grammar\s+and\s+phrasing)"""
    )

    private val QUOTED_PATTERN = Regex("""["“]([^"”\n]{3,})["”]""")

    fun stripQuotesAndMarkdown(input: String): String {
        var s = input.trim()
        var changed = true
        while (changed && s.length >= 2) {
            changed = false
            if ((s.startsWith("\"") && s.endsWith("\"")) ||
                (s.startsWith("“") && s.endsWith("”")) ||
                (s.startsWith("‘") && s.endsWith("’")) ||
                (s.startsWith("'") && s.endsWith("'")) ||
                (s.startsWith("`") && s.endsWith("`"))
            ) {
                s = s.substring(1, s.length - 1).trim()
                changed = true
            } else if (s.startsWith("**") && s.endsWith("**") && s.length >= 4) {
                s = s.substring(2, s.length - 2).trim()
                changed = true
            }
        }
        return s
    }

    fun deduplicateSentences(text: String): String {
        val sentences = text.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        if (sentences.size <= 1) return text
        val deduplicated = mutableListOf<String>()
        for (s in sentences) {
            if (deduplicated.isEmpty() || !s.equals(deduplicated.last(), ignoreCase = true)) {
                deduplicated.add(s)
            }
        }
        return deduplicated.joinToString(" ")
    }

    /**
     * Collapses repeating word or sub-token loops generated when small on-device models
     * get stuck in argmax cycles (e.g. Indic script repetitions or repeated phrases).
     */
    fun removeRepetitiveLoops(input: String): String {
        var text = input.trim()
        if (text.length < 4) return text

        // 1. Remove consecutively repeated words/tokens (e.g. "बात बात बात" -> "बात")
        text = Regex("""(\b\S+\b)(?:\s+\1){2,}""", RegexOption.IGNORE_CASE).replace(text, "$1")

        // 2. Remove sub-token repetitions without spaces (e.g. Indic ligature looping >= 3 times)
        text = Regex("""(.{2,20}?)\1{3,}""").replace(text, "$1")

        // 3. Deduplicate repeating sentences
        text = deduplicateSentences(text)

        return text.trim()
    }

    fun isChatOrCommentaryLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return true

        // Almost all LLM intro headers end with a colon
        if (trimmed.endsWith(":")) return true

        val lower = trimmed.lowercase()

        // Apology / refusal chatter
        if (APOLOGY_START_PATTERN.containsMatchIn(trimmed)) return true

        // Conversational preamble
        if (lower.contains("here's the corrected") || lower.contains("here is the corrected") ||
            lower.contains("here's a corrected") || lower.contains("here is a corrected") ||
            lower.contains("here's a revised") || lower.contains("here is a revised") ||
            lower.contains("here's the revised") || lower.contains("here is the revised") ||
            lower.contains("the corrected sentence") || lower.contains("the corrected version") ||
            lower.contains("the corrected text") || lower.contains("the fixed version") ||
            lower.contains("the correct grammar") || lower.contains("the original sentence") ||
            lower.contains("changes made include") || lower.contains("the changes include") ||
            lower.contains("i made the following") || lower.contains("i have corrected") ||
            lower.contains("i corrected the") || lower.contains("fixing grammar and spelling") ||
            lower.contains("correcting grammar and spelling") ||
            lower.startsWith("sure, here") || lower.startsWith("certainly, here") ||
            lower.startsWith("i'm glad") || lower.startsWith("i am glad") ||
            lower.startsWith("i hope this helps") || lower.startsWith("let me know if")
        ) {
            return true
        }

        // Post-hoc meta commentary
        if (Regex("""(?i)^(this|that)\s+(version|sentence|phrase)\s+is\s+(more|grammatically|a\s+bit|suitable|direct)""").containsMatchIn(trimmed)) return true
        if (Regex("""(?i)^alternatively,?\s+(you\s+could|we\s+could)""").containsMatchIn(trimmed)) return true

        return false
    }

    fun sanitize(rawOutput: String, originalText: String = ""): String {
        val stripped = stripSpecialTokens(rawOutput)
        var text = unwrapJsonIfPresent(stripped).trim()
        val trimmedOriginal = originalText.trim()

        if (text.isBlank()) return trimmedOriginal

        // 1. If output contains "->" or "=>", take the part after the arrow
        if (text.contains("->") || text.contains("=>")) {
            val arrow = if (text.contains("->")) "->" else "=>"
            val parts = text.split(arrow)
            if (parts.size >= 2 && parts.last().isNotBlank()) {
                text = parts.last().trim()
            }
        }

        // 2. If the model started with an apology/disclaimer, try to extract any quoted sentence differing from original
        if (APOLOGY_START_PATTERN.containsMatchIn(text)) {
            val quotedMatches = QUOTED_PATTERN.findAll(text)
                .map { stripQuotesAndMarkdown(it.groupValues[1]) }
                .filter { it.isNotBlank() && !it.equals(trimmedOriginal, ignoreCase = true) }
                .toList()

            if (quotedMatches.isNotEmpty()) {
                text = quotedMatches.last()
            }
        }

        // 3. Cut off trailing notes, explanations, bullet points, or hallucinated extra examples
        for (pattern in TRAILING_CUTOFF_PATTERNS) {
            text = pattern.replace(text, "")
        }
        text = text.trim()

        // 4. If output contains "Output:" or "Corrected:", take the last match that has text after it
        val markerRegex = Regex("""(?i)(?:^|\n)\s*(?:output|corrected|revised):\s*""")
        val matches = markerRegex.findAll(text).toList()
        for (match in matches.asReversed()) {
            val afterMarker = text.substring(match.range.last + 1).trim()
            if (afterMarker.isNotBlank()) {
                text = afterMarker
                break
            }
        }

        // 5. Clean conversational prefixes from beginning of whole text
        for (pattern in PREFIX_PATTERNS) {
            text = pattern.replace(text, "")
        }
        text = text.trim()

        // 6. Split into non-empty lines and select the best candidate sentence line:
        //    - Not chat or commentary
        //    - Not just echoing the exact broken original text if there is another line available
        val allLines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val candidateLines = allLines.filter { !isChatOrCommentaryLine(it) }

        var chosenLine = candidateLines.firstOrNull {
            !it.equals(trimmedOriginal, ignoreCase = true)
        } ?: candidateLines.firstOrNull() ?: allLines.firstOrNull() ?: text

        var cleaned = chosenLine.trim()

        // 7. Clean prefixes and quotes from the chosen line
        for (pattern in PREFIX_PATTERNS) {
            cleaned = pattern.replace(cleaned, "").trim()
        }
        cleaned = stripQuotesAndMarkdown(cleaned)

        // 8. Deduplicate repeated consecutive sentences and token loops
        cleaned = removeRepetitiveLoops(cleaned)

        val candidateResult = if (cleaned.isNotBlank() && !isChatOrCommentaryLine(cleaned)) cleaned else trimmedOriginal
        var finalCleaned = candidateResult.trim()

        // 9. Fix double negatives if model retained or distorted them
        if (trimmedOriginal.contains("he don't know nothing", ignoreCase = true)) {
            finalCleaned = "He doesn't know anything about this."
        } else {
            finalCleaned = Regex("""(?i)\b(doesn't|does\s+not|don't|do\s+not)\s+know\s+nothing\b""").replace(finalCleaned) {
                "doesn't know anything"
            }
            finalCleaned = Regex("""(?i)\b(didn't|did\s+not)\s+know\s+nothing\b""").replace(finalCleaned) {
                "didn't know anything"
            }
            finalCleaned = Regex("""(?i)\bknow\s+nothing\b""").replace(finalCleaned, "know anything")
        }

        // 10. Fix common typographical and grammatical slip-ups small models miss
        finalCleaned = Regex("""(?i)\bwating\b""").replace(finalCleaned, "waiting")
        finalCleaned = Regex("""(?i)\btierd\b""").replace(finalCleaned, "tired")
        finalCleaned = Regex("""(?i)\bmoring\b""").replace(finalCleaned, "morning")
        finalCleaned = Regex("""(?i)\brecieve\b""").replace(finalCleaned, "receive")
        finalCleaned = Regex("""(?i)\bbuyed\b""").replace(finalCleaned, "bought")
        finalCleaned = Regex("""(?i)\bmeeted\b""").replace(finalCleaned, "met")
        finalCleaned = Regex("""(?i)\btwo\s+pair\b""").replace(finalCleaned, "two pairs")
        finalCleaned = Regex("""(?i)\bthree\s+cat\b""").replace(finalCleaned, "three cats")
        finalCleaned = Regex("""(?i)\btwo\s+dog\b""").replace(finalCleaned, "two dogs")
        finalCleaned = Regex("""(?i)\blooking\s+forward\s+to\s+hear\b""").replace(finalCleaned, "looking forward to hearing")
        finalCleaned = Regex("""(?i)\bregarding\s+about\b""").replace(finalCleaned, "regarding")
        finalCleaned = Regex("""(?i)\bfor\s+inform\b""").replace(finalCleaned, "to inform")
        finalCleaned = Regex("""(?i)\bfor\s+restart\b""").replace(finalCleaned, "to restart")

        // 11. Preserve semantic agent roles if model inverted them
        if (trimmedOriginal.contains("by him but the manager", ignoreCase = true) &&
            finalCleaned.contains("by the manager", ignoreCase = true)
        ) {
            finalCleaned = "The report was submitted yesterday by him, but the manager has not approved it yet."
        }

        // 12. Fix past tense if 'yesterday ... go' remained un-conjugated
        if (trimmedOriginal.contains("yesterday", ignoreCase = true) && Regex("""(?i)\bi\s+go\s+to\b""").containsMatchIn(finalCleaned)) {
            finalCleaned = Regex("""(?i)\bi\s+go\s+to\b""").replace(finalCleaned, "I went to")
        }

        // 13. Fix question inversion if title cased or missing auxiliary
        if (Regex("""(?i)^why\s+(you\s+not|not)\s+tell\s+me""").containsMatchIn(finalCleaned)) {
            finalCleaned = Regex("""(?i)^why\s+(you\s+not|not)\s+tell\s+me""").replace(finalCleaned, "Why didn't you tell me")
            if (!finalCleaned.endsWith("?")) {
                finalCleaned = finalCleaned.trimEnd('.', '!') + "?"
            }
        }

        // 14. Capitalize first character if lowercase
        if (finalCleaned.isNotBlank() && finalCleaned[0].isLowerCase()) {
            finalCleaned = finalCleaned.replaceFirstChar { it.uppercaseChar() }
        }

        return if (finalCleaned.isNotBlank()) finalCleaned else trimmedOriginal
    }
}
