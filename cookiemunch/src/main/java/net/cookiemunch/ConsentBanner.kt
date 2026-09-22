package net.cookiemunch

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Jetpack Compose consent banner, wired to a [CookieMunchConsent] instance. Renders
 * only until the visitor has responded. Place it at the bottom of your root scaffold:
 *
 * ```
 * val consent = remember { CookieMunchConsent.secure(context, "cbid", "https://cmp.example.com") }
 * ConsentBanner(consent)
 * ```
 */
@Composable
fun ConsentBanner(
    client: CookieMunchConsent,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    acceptLabel: String? = null,
    declineLabel: String? = null,
) {
    // Precedence: what the caller passed, then the server's copy for this device's language,
    // then English — so a banner still asks before the first refresh has landed.
    val copy = client.copy
    val titleText = title ?: copy?.title ?: "We value your privacy"
    val messageText = message ?: copy?.body
        ?: "We use cookies and similar technologies to improve your experience. You decide what we use."
    val acceptText = acceptLabel ?: copy?.acceptAll ?: "Allow all"
    val declineText = declineLabel ?: copy?.rejectAll ?: "Reject all"
    // On a television the phone strip is unusable from a remote; hand over to the TV layout.
    if (isTelevision(LocalConfiguration.current)) {
        TvConsentBanner(client, modifier, titleText, messageText, acceptText, declineText)
        return
    }

    val state by client.state.collectAsState()
    if (state.hasResponse) return

    val scope = rememberCoroutineScope()

    Surface(modifier = modifier, shadowElevation = 8.dp, color = Color.White) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(titleText)
            Text(messageText)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { scope.launch { client.decline() } },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(declineText)
                }
                Button(
                    onClick = { scope.launch { client.accept() } },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0E6E5C)),
                ) {
                    Text(acceptText)
                }
            }
        }
    }
}
