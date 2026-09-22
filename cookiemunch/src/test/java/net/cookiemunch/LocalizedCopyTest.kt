package net.cookiemunch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The banner's words arrive with the config, in the visitor's language. An app cannot carry
 * forty catalogues, and five SDKs each carrying their own is five chances to disagree about
 * what one banner says.
 */
class LocalizedCopyTest {
    private val json = """
        {
          "regulation": null,
          "copy": {
            "language": "ar",
            "rtl": true,
            "banner": { "title": "نحن نحترم خصوصيتك", "acceptAll": "السماح بالكل", "rejectAll": "رفض الكل" },
            "categories": { "marketing": { "label": "التسويق", "description": "وصف" } },
            "reopen": "إعدادات ملفات تعريف الارتباط"
          }
        }
    """.trimIndent()

    @Test
    fun readsTheServersCopy() {
        val copy = LocalizedCopy.fromConfigJson(json)!!
        assertEquals("ar", copy.language)
        assertTrue(copy.rtl)
        assertEquals("السماح بالكل", copy.acceptAll)
        assertEquals("التسويق", copy.categories["marketing"]?.label)
        assertEquals("إعدادات ملفات تعريف الارتباط", copy.reopen)
    }

    @Test
    fun aResponseWithoutCopyIsNotAnError() {
        assertNull(LocalizedCopy.fromConfigJson("""{"regulation":null}"""))
    }

    /** A damaged body must never crash a banner; it falls back to English. */
    @Test
    fun aMalformedBodyIsNoCopy() {
        assertNull(LocalizedCopy.fromConfigJson("{not json"))
        assertNull(LocalizedCopy.fromConfigJson(""))
    }

    @Test
    fun missingFieldsAreNullRatherThanEmptyStrings() {
        val copy = LocalizedCopy.fromConfigJson("""{"copy":{"language":"en","rtl":false,"banner":{},"categories":{},"reopen":""}}""")!!
        assertNull(copy.title)
        assertNull(copy.acceptAll)
        assertNull(copy.reopen)
        assertTrue(copy.categories.isEmpty())
    }
}
