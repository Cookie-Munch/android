package net.cookiemunch

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The banner picks its layout from the UI mode; a phone must never get the TV card. */
class TelevisionTest {
    private fun config(uiMode: Int) = Configuration().apply { this.uiMode = uiMode }

    @Test
    fun recognisesATelevision() {
        assertTrue(isTelevision(config(Configuration.UI_MODE_TYPE_TELEVISION or Configuration.UI_MODE_NIGHT_YES)))
    }

    @Test
    fun aPhoneIsNotATelevision() {
        assertFalse(isTelevision(config(Configuration.UI_MODE_TYPE_NORMAL)))
        assertFalse(isTelevision(config(Configuration.UI_MODE_TYPE_NORMAL or Configuration.UI_MODE_NIGHT_YES)))
    }
}
