// Dialer host for OriginSU's Compose FloatingBottomBar (Apache 2.0):
// dialer tabs + Material3 theme + Java-friendly selection state +
// bridge that installs the bar into a ComposeView over the View hierarchy.
//
// Geometry, shaders, springs and gestures are verbatim from
// packages_apps_ExactCalculator
// (src/main/java/com/android/calculator2/ui/component/FloatingBottomBar.kt
// and ui/component/{liquid,miuix}/**); only the tab list (dialer tabs,
// dynamic voicemail) and badge counts differ.

package com.android.dialer.glass.navbar

import android.view.View
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.dialer.R
import com.android.dialer.glass.component.FloatingBottomBar
import com.android.dialer.glass.component.FloatingBottomBarItem
import top.yukonga.miuix.kmp.blur.Backdrop

data class NavTab(
    @DrawableRes val iconRes: Int,
    @StringRes val labelRes: Int,
)

fun interface NavSelectionListener {
    fun onTabSelected(index: Int)
}

/** Selection state shared between Java activities and the Compose bar. */
class GlassNavState(initial: Int, tabs: List<NavTab>) {
    var selectedIndex by mutableIntStateOf(initial)
        private set
    var listener: NavSelectionListener? = null

    /** Tabs currently shown; updated when voicemail visibility changes. */
    val tabList = mutableStateListOf<NavTab>().apply { addAll(tabs) }

    /** Unread/missed badge counts keyed by glass tab index. */
    val badges = mutableStateMapOf<Int, Int>()

    /** External sync (e.g. fragment restores); never notifies the listener. */
    fun select(index: Int) {
        if (index in tabList.indices) {
            selectedIndex = index
        }
    }

    /** Replace the shown tabs (voicemail show/hide); clamps selection. */
    fun setTabs(tabs: List<NavTab>) {
        tabList.clear()
        tabList.addAll(tabs)
        if (selectedIndex !in tabList.indices) {
            selectedIndex = 0
        }
    }

    /** Update the badge count for a glass tab index (0 hides the badge). */
    fun setBadge(index: Int, count: Int) {
        if (count > 0) {
            badges[index] = count
        } else {
            badges.remove(index)
        }
    }

    internal fun emit(index: Int) {
        selectedIndex = index
        listener?.onTabSelected(index)
    }
}

private val IosGlassScheme = darkColorScheme(
    primary = Color(0xFFFF9F0A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFF9F0A),
    onPrimaryContainer = Color(0xFF000000),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceVariant = Color(0xFF3A3A3C),
    onSurfaceVariant = Color(0xFFAEAEB2),
)

private val IosGlassLightScheme = lightColorScheme(
    primary = Color(0xFFFF9F0A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFF9F0A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    surface = Color(0xFFF2F2F7),
    onSurface = Color(0xFF000000),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFE8E8ED),
    surfaceVariant = Color(0xFFD1D1D6),
    onSurfaceVariant = Color(0xFF6E6E6E),
)

@Composable
private fun DialerGlassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) IosGlassScheme else IosGlassLightScheme,
        content = content,
    )
}

@Composable
private fun DialerBadge(count: Int) {
    val text = if (count > 9) stringResource(R.string.bottom_nav_count_9_plus) else count.toString()
    Box(
        modifier = Modifier
            .offset(x = 10.dp, y = (-8).dp)
            .clip(CircleShape)
            .background(Color(0xFFE53935))
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun DialerNavBar(
    state: GlassNavState,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    DialerGlassTheme() {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            FloatingBottomBar(
                selectedIndex = state.selectedIndex,
                onSelected = { state.emit(it) },
                tabsCount = state.tabList.size,
                backdrop = backdrop,
                isBlurEnabled = true,
            ) { activateTab ->
                state.tabList.forEachIndexed { index, tab ->
                    FloatingBottomBarItem(
                        selected = index == state.selectedIndex,
                        onClick = { activateTab(index) },
                        modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                    ) {
                        val contentColor = LocalContentColor.current
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(tab.iconRes),
                                stringResource(tab.labelRes),
                                tint = contentColor,
                            )
                            val badge = state.badges[index] ?: 0
                            if (badge > 0) {
                                DialerBadge(badge)
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

object GlassNavBridge {
    /** Glass tab order matches BottomNavBar.TabIndex when voicemail is shown. */
    @JvmStatic
    fun dialerTabs(showVoicemail: Boolean): List<NavTab> {
        val tabs = mutableListOf(
            NavTab(R.drawable.quantum_ic_star_outline_vd_theme_24, R.string.tab_title_speed_dial),
            NavTab(R.drawable.quantum_ic_access_time_vd_theme_24, R.string.tab_title_call_history),
            NavTab(R.drawable.quantum_ic_people_outline_vd_theme_24, R.string.tab_all_contacts),
        )
        if (showVoicemail) {
            tabs.add(
                NavTab(
                    R.drawable.quantum_ic_voicemail_vd_theme_24,
                    R.string.tab_title_voicemail,
                ),
            )
        }
        return tabs
    }

    /**
     * Installs the exact OriginSU floating bar into [host]. Returns the
     * shared selection state: call [GlassNavState.select] to sync external
     * navigation; taps arrive through [listener]. Returns null when [host]
     * is null (layout variants without the pill).
     */
    @JvmStatic
    fun install(
        host: ComposeView?,
        tabs: List<NavTab>,
        initial: Int,
        listener: NavSelectionListener,
    ): GlassNavState? {
        if (host == null) return null
        val state = GlassNavState(initial.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)), tabs)
        state.listener = listener
        val capture = SnapshotCapture(host)
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
            DialerNavBar(state, backdrop)
        }
        return state
    }
}
