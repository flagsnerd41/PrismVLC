package com.example.myvlc

data class Parsed(val name: String, val season: Int, val episode: Int)

private val EP1 = Regex(
    """^(.*?)[\s._\-\[\(]*(?:s(\d{1,2})[\s._\-]*e(\d{1,3})|(\d{1,2})x(\d{2,3}))(?![0-9])""",
    RegexOption.IGNORE_CASE
)
private val EP2 = Regex(
    """^(.*\D)[\s._\-]+(?:ep(?:isode)?[\s._\-]*)?(\d{1,3})\s*$""",
    RegexOption.IGNORE_CASE
)
private val SEPARATORS = Regex("[._]+")
private val SPACES = Regex("\\s+")

fun cleanName(s: String): String =
    s.replace(SEPARATORS, " ").replace(SPACES, " ").trim(' ', '-', '[', ']', '(', ')')

/** Detects "Show S01E02", "Show 1x02" or "Show - 05" style names. */
fun parseEpisode(title: String): Parsed? {
    val m1 = EP1.find(title)
    if (m1 != null) {
        val name = cleanName(m1.groupValues[1])
        if (name.isEmpty()) return null
        val season = m1.groupValues[2].ifEmpty { m1.groupValues[4] }.toInt()
        val ep = m1.groupValues[3].ifEmpty { m1.groupValues[5] }.toInt()
        return Parsed(name, season, ep)
    }
    val m2 = EP2.find(title)
    if (m2 != null) {
        val name = cleanName(m2.groupValues[1])
        if (name.length < 2) return null
        return Parsed(name, 1, m2.groupValues[2].toInt())
    }
    return null
}
