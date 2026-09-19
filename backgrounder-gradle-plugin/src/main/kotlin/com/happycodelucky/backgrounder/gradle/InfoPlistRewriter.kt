package com.happycodelucky.backgrounder.gradle

/**
 * Text-level rewrite of the `BGTaskSchedulerPermittedIdentifiers` array in
 * an XML `Info.plist`.
 *
 * Deliberately not a plist parser: a DOM round-trip or `PlistBuddy` rewrites
 * the whole file in canonical form and produces a noisy diff in the app repo.
 * This replaces exactly the one `<key>` + `<array>` pair and leaves every
 * other byte alone, matching the file's own indentation style.
 *
 * If the key is absent, the pair is inserted before the closing `</dict>` of
 * the top-level dictionary.
 */
internal object InfoPlistRewriter {
    const val KEY: String = "BGTaskSchedulerPermittedIdentifiers"

    private val existing =
        Regex(
            """^(?<indent>[ \t]*)<key>$KEY</key>[ \t]*\R?[ \t]*(?:<array>.*?</array>|<array\s*/>)[ \t]*""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
        )

    fun rewrite(
        plist: String,
        ids: Collection<String>,
    ): String {
        val sorted = ids.toSortedSet()
        val match = existing.find(plist)
        if (match != null) {
            val indent = match.groups["indent"]?.value.orEmpty()
            return plist.replaceRange(match.range, render(indent, unitFor(plist, indent), sorted))
        }

        val close = plist.lastIndexOf("</dict>")
        require(close >= 0) { "Info.plist has no </dict>; is it an XML plist?" }
        val lineStart = plist.lastIndexOf('\n', close - 1) + 1
        val closeIndent = plist.substring(lineStart, close).takeWhile { it == ' ' || it == '\t' }
        val unit = unitFor(plist, closeIndent)
        val indent = closeIndent + unit
        val block = render(indent, unit, sorted) + "\n"
        return plist.substring(0, lineStart) + block + plist.substring(lineStart)
    }

    private fun render(
        indent: String,
        unit: String,
        ids: Collection<String>,
    ): String =
        buildString {
            append(indent).append("<key>").append(KEY).append("</key>\n")
            if (ids.isEmpty()) {
                append(indent).append("<array/>")
                return@buildString
            }
            append(indent).append("<array>\n")
            ids.forEach {
                append(indent)
                    .append(unit)
                    .append("<string>")
                    .append(escape(it))
                    .append("</string>\n")
            }
            append(indent).append("</array>")
        }

    /** Tabs if the file indents with tabs (Xcode's default), else one level of spaces. */
    private fun unitFor(
        plist: String,
        indent: String,
    ): String {
        if (indent.contains('\t') || plist.contains("\n\t")) return "\t"
        val firstKeyIndent =
            Regex("""^([ ]+)<key>""", RegexOption.MULTILINE)
                .find(plist)
                ?.groupValues
                ?.get(1)
        return firstKeyIndent ?: "    "
    }

    private fun escape(s: String): String =
        buildString(s.length) {
            s.forEach { c ->
                when (c) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(c)
                }
            }
        }
}
