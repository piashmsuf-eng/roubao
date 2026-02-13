package com.roubao.autopilot.voice

/**
 * BanglishParser - Normalize romanized Bangla into simpler English cues.
 * This helps the assistant understand mixed Bangla-English queries.
 */
object BanglishParser {
    fun normalize(input: String): String {
        var text = input.lowercase()
        val map = mapOf(
            "koro" to "do",
            "korbo" to "do",
            "korchi" to "do",
            "korcho" to "do",
            "koren" to "do",
            "kholo" to "open",
            "khul" to "open",
            "khule" to "open",
            "bolo" to "tell",
            "bol" to "tell",
            "diyo" to "give",
            "dao" to "give",
            "lagbe" to "need",
            "bujhi" to "understand",
            "bujte" to "understand",
            "valo" to "good",
            "bhalo" to "good",
            "kisu" to "something",
            "kichu" to "something",
            "cholo" to "go",
            "jao" to "go",
            "chalao" to "play",
            "bajao" to "play",
            "bondho" to "close",
            "on" to "turn on",
            "off" to "turn off",
            "jarvis" to "jarvis",
            "boss" to "boss"
        )

        map.forEach { (bn, en) ->
            text = text.replace(bn, en)
        }
        return text.trim()
    }
}