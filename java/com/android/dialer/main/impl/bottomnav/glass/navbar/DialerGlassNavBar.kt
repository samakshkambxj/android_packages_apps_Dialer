// Dialer host for OriginSU's Compose FloatingBottomBar (Apache 2.0):
// dialer tabs (Favorites/Recents/Contacts/Voicemail) with selected-state
// icons, notification badges and voicemail visibility, a Material3 theme
// fed with the current Dialer theme colors resolved in the View layer, a
// Java-friendly selection state, and a bridge that installs the bar into a
// ComposeView over the View hierarchy.
//
// Structure mirrors ExactCalculator's GlassNavBar; only the tab set, the
// badge/visibility state and the color source are Dialer-specific. All
// geometry, effects, springs and gestures live in FloatingBottomBar.

package com.android.dialer.main.impl.bottomnav.glass.navbar

import android.view.View
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.dialer.R
import com.android.dialer.main.impl.bottomnav.BottomNavBar
import com.android.dialer.main.impl.bottomnav.glass.component.FloatingBottomBar
import com.android.dialer.main.impl.bottomnav.glass.component.FloatingBottomBarItem
import top.yukonga.miuix.kmp.blur.Backdrop

data class NavTab(
    @DrawableRes val iconRes: Int,
    @DrawableRes val iconResSelected: Int,
    @StringRes val labelRes: Int,
    /** BottomNavBar.TabIndex this tab selects; glass position matches it. */
    val tabIndex: Int,
)

/** Theme colors resolved from the current Dialer (View) theme by BottomNavBar. */
data class DialerGlassColors(
    @ColorInt val primary: Int,
    @ColorInt val onSurface: Int,
    @ColorInt val surface: Int,
    @ColorInt val surfaceContainer: Int,
)

fun interface NavSelectionListener {
    fun onTabSelected(index: Int)
}

/** Selection state shared between BottomNavBar and the Compose bar. */
class GlassNavState(initial: Int) {
    var selectedIndex by mutableIntStateOf(initial)
        private set
    var voicemailVisible by mutableStateOf(true)

    private val badgeTexts = mutableStateMapOf<Int, String>()

    var listener: NavSelectionListener? = null

    /** External sync (e.g. selectTab); never notifies the listener. */
    fun select(index: Int) {
        selectedIndex = index
    }

    fun setBadge(index: Int, text: String) {
        badgeTexts[index] = text
    }

    fun clearBadge(index: Int) {
        badgeTexts.remove(index)
    }

    internal fun badgeFor(index: Int): String? = badgeTexts[index]

    internal fun emit(index: Int) {
        selectedIndex = index
        listener?.onTabSelected(index)
    }
}

@Composable
private fun DialerGlassTheme(colors: DialerGlassColors, content: @Composable () -> Unit) {
    // The passed colors are resolved from the current (day/night) Dialer
    // theme, so they already match the mode; the scheme variant only fills
    // the remaining Material3 roles with sane defaults.
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(colors.primary),
            onPrimary = Color.White,
            surface = Color(colors.surface),
            onSurface = Color(colors.onSurface),
            surfaceContainer = Color(colors.surfaceContainer),
        )
    } else {
        lightColorScheme(
            primary = Color(colors.primary),
            onPrimary = Color.White,
            surface = Color(colors.surface),
            onSurface = Color(colors.onSurface),
            surfaceContainer = Color(colors.surfaceContainer),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun DialerNavBar(
    state: GlassNavState,
    tabs: List<NavTab>,
    colors: DialerGlassColors,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    DialerGlassTheme(colors) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            FloatingBottomBar(
                selectedIndex = state.selectedIndex,
                onSelected = { state.emit(it) },
                tabsCount = tabs.size,
                backdrop = backdrop,
                isBlurEnabled = true,
            ) { activateTab ->
                tabs.forEachIndexed { index, tab ->
                    val selected = index == state.selectedIndex
                    FloatingBottomBarItem(
                        selected = selected,
                        onClick = { activateTab(index) },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                    ) {
                        // Inactive tabs resolve to a dim onSurface via
                        // LocalContentColor; force white like the active tab.
                        CompositionLocalProvider(LocalContentColor provides Color.White) {
                        val contentColor = LocalContentColor.current
                        Box(contentAlignment = Alignment.TopCenter) {
                            Icon(
                                painterResource(if (selected) tab.iconResSelected else tab.iconRes),
                                stringResource(tab.labelRes),
                                tint = contentColor,
                            )
                            state.badgeFor(tab.tabIndex)?.let { badge ->
                                Text(
                                    text = badge,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 12.dp, y = (-8).dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            CircleShape,
                                        )
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    lineHeight = 13.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                        Text(
                            text = stringResource(tab.labelRes),
                            color = contentColor,
                            fontSize = 11.sp,
                            lineHeight = 14.sp,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                        )
                        }
                    }
                }
            }
        }
    }
}

object DialerGlassNavBridge {
    @JvmStatic
    fun dialerTabs(): List<NavTab> = listOf(
        NavTab(
            R.drawable.quantum_ic_star_outline_vd_theme_24,
            R.drawable.quantum_ic_star_vd_theme_24,
            R.string.tab_title_speed_dial,
            BottomNavBar.TabIndex.SPEED_DIAL,
        ),
        NavTab(
            R.drawable.quantum_ic_access_time_vd_theme_24,
            R.drawable.quantum_ic_clock_filled_vd_theme_24,
            R.string.tab_title_call_history,
            BottomNavBar.TabIndex.CALL_LOG,
        ),
        NavTab(
            R.drawable.quantum_ic_people_outline_vd_theme_24,
            R.drawable.quantum_ic_people_vd_theme_24,
            R.string.tab_all_contacts,
            BottomNavBar.TabIndex.CONTACTS,
        ),
        NavTab(
            R.drawable.quantum_ic_voicemail_vd_theme_24,
            R.drawable.quantum_ic_voicemail_vd_theme_24,
            R.string.tab_title_voicemail,
            BottomNavBar.TabIndex.VOICEMAIL,
        ),
    )

    /**
     * Installs the exact OriginSU floating bar into [host]. Returns the
     * shared selection state: call [GlassNavState.select] to sync external
     * navigation; taps arrive through [listener]. [captureRoot] supplies the
     * content sampled behind the pill (the activity root in Dialer).
     */
    @JvmStatic
    fun install(
        host: ComposeView,
        captureRoot: CaptureRootProvider?,
        tabs: List<NavTab>,
        initial: Int,
        colors: DialerGlassColors,
        listener: NavSelectionListener,
    ): GlassNavState {
        val state = GlassNavState(initial)
        state.listener = listener
        val capture = SnapshotCapture(host, captureRoot)
        val backdrop = SnapshotBackdrop()
        capture.onFrame = { frame ->
            backdrop.frame = frame
            host.invalidate()
        }
        host.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = capture.attach()
            override fun onViewDetachedFromWindow(v: View) {
                capture.detach()
                host.removeOnAttachStateChangeListener(this)
            }
        })
        if (host.isAttachedToWindow) {
            capture.attach()
        }
        host.setContent {
            // Voicemail hidden -> first three tabs only; glass positions then
            // still match TabIndex (0..2).
            val visibleTabs = if (state.voicemailVisible) tabs else tabs.dropLast(1)
            DialerNavBar(state, visibleTabs, colors, backdrop)
        }
        return state
    }
}
