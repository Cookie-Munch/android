package net.cookiemunch

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** True when the app is running on a television (Android TV, Google TV, Fire TV). */
fun isTelevision(configuration: Configuration): Boolean =
    configuration.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION

/**
 * The consent prompt on a television.
 *
 * A TV is read from across a room and driven by a D-pad that can only move focus and
 * press. The phone banner is a strip of small text whose buttons show focus as a faint
 * overlay — on a TV you cannot tell which one the remote is on, and nothing is focused
 * when it appears, so the first press does nothing visible. This is a centred card with
 * type sized for ten feet, a bright ring and lift on the focused choice, and focus placed
 * on the first choice as it appears. The two choices are the same size and weight, and
 * focus starts on the first in reading order rather than being steered toward "Allow all".
 *
 * [ConsentBanner] switches to this automatically on a television; call it directly to
 * force the TV layout.
 */
@Composable
fun TvConsentBanner(
    client: CookieMunchConsent,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    acceptLabel: String? = null,
    declineLabel: String? = null,
) {
    val state by client.state.collectAsState()
    if (state.hasResponse) return

    val copy = client.copy
    val titleText = title ?: copy?.title ?: "We value your privacy"
    val messageText = message ?: copy?.body
        ?: "We use cookies and similar technologies to improve your experience. You decide what we use."
    val acceptText = acceptLabel ?: copy?.acceptAll ?: "Allow all"
    val declineText = declineLabel ?: copy?.rejectAll ?: "Reject all"

    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestFocus() }

    val direction = if (copy?.rtl == true) LayoutDirection.Rtl else LayoutDirection.Ltr
    CompositionLocalProvider(LocalLayoutDirection provides direction) {
    Box(
        modifier = modifier.fillMaxSize().background(Color(0x99000000)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .background(Color(0xFF1E1E1E), RoundedCornerShape(20.dp))
                .padding(48.dp),
        ) {
            Text(titleText, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Text(
                messageText,
                color = Color(0xFFD0D0D0),
                fontSize = 20.sp,
                lineHeight = 28.sp,
                modifier = Modifier.padding(top = 16.dp),
            )
            Row(
                modifier = Modifier.padding(top = 36.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                TvChoice(declineText, Modifier.focusRequester(first)) { scope.launch { client.decline() } }
                TvChoice(acceptText) { scope.launch { client.accept() } }
            }
        }
    }
    }
}

@Composable
private fun TvChoice(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .graphicsLayer { val s = if (focused) 1.08f else 1f; scaleX = s; scaleY = s }
            .background(if (focused) Color.White else Color(0xFF3A3A3A), RoundedCornerShape(12.dp))
            .border(3.dp, if (focused) Color(0xFF4FC3F7) else Color.Transparent, RoundedCornerShape(12.dp))
            // clickable is focusable for the D-pad and answers DPAD_CENTER / Enter.
            .clickable(onClick = onClick)
            .padding(horizontal = 40.dp, vertical = 18.dp),
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
    }
}
