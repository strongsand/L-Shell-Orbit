package io.github.strongsand.lshell

import org.json.JSONArray

/** Android's JSONTokener also accepts non-JSON syntax. Validate strict JSON and duplicate keys first. */
internal class BackupJsonSyntax(private val text: String) {
    private var at = 0
    fun validate() { value(0); space(); require(at == text.length) }
    private fun space() { while (at < text.length && text[at] in " \t\r\n") at++ }
    private fun expect(c: Char) { space(); require(at < text.length && text[at++] == c) }
    private fun take(c: Char): Boolean { space(); return if (at < text.length && text[at] == c) { at++; true } else false }
    private fun value(depth: Int) {
        require(depth <= 48); space(); require(at < text.length)
        when (text[at]) {
            '{' -> {
                at++; val keys = mutableSetOf<String>()
                if (!take('}')) do {
                    space(); require(at < text.length && text[at] == '"')
                    require(keys.add(string(true))); expect(':'); value(depth + 1)
                    if (take('}')) break
                    expect(',')
                } while (true)
            }
            '[' -> { at++; if (!take(']')) do { value(depth + 1); if (take(']')) break; expect(',') } while (true) }
            '"' -> string(false)
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }
    private fun literal(s: String) { require(text.startsWith(s, at)); at += s.length }
    private fun number() {
        if (at < text.length && text[at] == '-') at++
        require(at < text.length)
        if (text[at] == '0') at++ else { require(text[at] in '1'..'9'); while (at < text.length && text[at] in '0'..'9') at++ }
        if (at < text.length && text[at] == '.') { at++; digits() }
        if (at < text.length && text[at] in "eE") { at++; if (at < text.length && text[at] in "+-") at++; digits() }
    }
    private fun digits() { val start = at; while (at < text.length && text[at] in '0'..'9') at++; require(at > start) }
    private fun string(decode: Boolean): String {
        val start = at; var hasEscape = false; expect('"')
        while (at < text.length) {
            val c = text[at++]
            if (c == '"') return if (!decode) "" else if (!hasEscape) text.substring(start + 1, at - 1)
                else JSONArray("[${text.substring(start, at)}]").getString(0)
            require(c.code >= 32)
            if (c == '\\') {
                hasEscape = true
                require(at < text.length)
                when (text[at++]) {
                    '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> Unit
                    'u' -> { require(at + 4 <= text.length && text.substring(at, at + 4).all { it in "0123456789abcdefABCDEF" }); at += 4 }
                    else -> error("Invalid JSON escape")
                }
            }
        }
        error("Unterminated JSON string")
    }
}
