package com.happycodelucky.backgrounder.gradle

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfoPlistRewriterTest {
    private val header =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" +
            "<plist version=\"1.0\">\n<dict>\n"

    @Test
    fun replacesExistingArrayPreservingTabIndentation() {
        val before =
            header +
                "\t<key>CFBundleName</key>\n\t<string>App</string>\n" +
                "\t<key>BGTaskSchedulerPermittedIdentifiers</key>\n" +
                "\t<array>\n\t\t<string>old.id</string>\n\t</array>\n" +
                "\t<key>UIBackgroundModes</key>\n\t<array>\n\t\t<string>fetch</string>\n\t</array>\n" +
                "</dict>\n</plist>\n"
        val after = InfoPlistRewriter.rewrite(before, listOf("b.id", "a.id"))
        val expected =
            header +
                "\t<key>CFBundleName</key>\n\t<string>App</string>\n" +
                "\t<key>BGTaskSchedulerPermittedIdentifiers</key>\n" +
                "\t<array>\n\t\t<string>a.id</string>\n\t\t<string>b.id</string>\n\t</array>\n" +
                "\t<key>UIBackgroundModes</key>\n\t<array>\n\t\t<string>fetch</string>\n\t</array>\n" +
                "</dict>\n</plist>\n"
        assertEquals(expected, after)
    }

    @Test
    fun replacesSelfClosingEmptyArray() {
        val before = header + "    <key>BGTaskSchedulerPermittedIdentifiers</key>\n    <array/>\n</dict>\n</plist>\n"
        val after = InfoPlistRewriter.rewrite(before, listOf("x.y"))
        assertEquals(
            header +
                "    <key>BGTaskSchedulerPermittedIdentifiers</key>\n    <array>\n        <string>x.y</string>\n    </array>\n" +
                "</dict>\n</plist>\n",
            after,
        )
    }

    @Test
    fun insertsBeforeClosingDictWhenAbsentUsingFileIndentStyle() {
        val before = header + "    <key>CFBundleName</key>\n    <string>App</string>\n</dict>\n</plist>\n"
        val after = InfoPlistRewriter.rewrite(before, listOf("x.y"))
        assertEquals(
            header +
                "    <key>CFBundleName</key>\n    <string>App</string>\n" +
                "    <key>BGTaskSchedulerPermittedIdentifiers</key>\n    <array>\n        <string>x.y</string>\n    </array>\n" +
                "</dict>\n</plist>\n",
            after,
        )
    }

    @Test
    fun emptyIdListWritesSelfClosingArray() {
        val before =
            header + "\t<key>BGTaskSchedulerPermittedIdentifiers</key>\n\t<array>\n\t\t<string>x</string>\n\t</array>\n</dict>\n</plist>\n"
        assertEquals(
            header + "\t<key>BGTaskSchedulerPermittedIdentifiers</key>\n\t<array/>\n</dict>\n</plist>\n",
            InfoPlistRewriter.rewrite(before, emptyList()),
        )
    }

    @Test
    fun escapesXmlSpecialCharacters() {
        val after = InfoPlistRewriter.rewrite(header + "</dict>\n</plist>\n", listOf("a&b<c>"))
        assertTrue(after.contains("<string>a&amp;b&lt;c&gt;</string>"), after)
    }

    @Test
    fun isIdempotent() {
        val before = header + "\t<key>Foo</key>\n\t<string>bar</string>\n</dict>\n</plist>\n"
        val once = InfoPlistRewriter.rewrite(before, listOf("a.b", "c.d"))
        assertEquals(once, InfoPlistRewriter.rewrite(once, listOf("c.d", "a.b")))
    }
}
