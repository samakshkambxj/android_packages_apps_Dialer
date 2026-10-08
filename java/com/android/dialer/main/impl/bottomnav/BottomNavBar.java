/*
 * Copyright (C) 2018 The Android Open Source Project
 * Copyright (C) 2023 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.dialer.main.impl.bottomnav;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.AttrRes;
import androidx.annotation.ColorInt;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.ComposeView;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.main.impl.MainActivity;
import com.android.dialer.main.impl.bottomnav.glass.navbar.DialerGlassColors;
import com.android.dialer.main.impl.bottomnav.glass.navbar.DialerGlassNavBridge;
import com.android.dialer.main.impl.bottomnav.glass.navbar.GlassNavState;
import com.android.dialer.util.DialerUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialer Bottom Nav Bar for {@link MainActivity}.
 *
 * <p>The public contract (TabIndex, selectTab, showVoicemail, setNotificationCount, listeners) is
 * unchanged; the rendering is an OriginSU-style liquid-glass floating pill hosted in a ComposeView
 * (see {@link DialerGlassNavBridge}).
 */
public final class BottomNavBar extends FrameLayout {

  /** Index for each tab in the bottom nav. */
  @Retention(RetentionPolicy.SOURCE)
  @IntDef({
    TabIndex.NONE,
    TabIndex.SPEED_DIAL,
    TabIndex.CALL_LOG,
    TabIndex.CONTACTS,
    TabIndex.DIALPAD,
    TabIndex.VOICEMAIL,
  })
  public @interface TabIndex {
    int NONE = -1;
    int SPEED_DIAL = 0;
    int CALL_LOG = 1;
    int CONTACTS = 2;
    // Inserted after CONTACTS so glass positions match indices with
    // voicemail last. Note: persisted ints from older versions (where
    // VOICEMAIL was 3) now resolve to DIALPAD exactly once.
    int DIALPAD = 3;
    int VOICEMAIL = 4;
  }

  private final List<OnBottomNavTabSelectedListener> listeners = new ArrayList<>();

  private GlassNavState glassState;
  private @TabIndex int selectedTab = TabIndex.SPEED_DIAL;
  private boolean voicemailVisible = true;
  private View cachedCaptureRoot;

  public BottomNavBar(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    ComposeView host = findViewById(R.id.glass_nav_host);
    DialerGlassColors colors =
        new DialerGlassColors(
            resolveThemeColor(android.R.attr.colorPrimary, android.R.attr.colorAccent),
            DialerUtils.resolveColor(getContext(), android.R.attr.textColorPrimary),
            DialerUtils.resolveColor(getContext(), android.R.attr.colorBackground),
            resolveSurfaceContainer());
    glassState =
        DialerGlassNavBridge.install(
            host,
            this::findCaptureRoot,
            DialerGlassNavBridge.dialerTabs(),
            selectedTab,
            colors,
            this::selectTab);
    glassState.setVoicemailVisible(voicemailVisible);
  }

  /**
   * Returns the activity content sampled behind the glass pill. The bar host's own subtree only
   * contains the pill, so sampling it would yield a blank backdrop; the content behind the bar
   * (call list, contacts, ...) lives in the activity root.
   */
  @Nullable
  private View findCaptureRoot() {
    if (cachedCaptureRoot == null) {
      View rootView = getRootView();
      if (rootView != null) {
        View content = rootView.findViewById(R.id.root_layout);
        if (content != null) {
          cachedCaptureRoot = content;
        }
      }
      if (cachedCaptureRoot == null && getParent() instanceof View) {
        cachedCaptureRoot = (View) getParent();
      }
    }
    return cachedCaptureRoot;
  }

  @ColorInt
  private int resolveThemeColor(@AttrRes int primary, @AttrRes int fallback) {
    int color = DialerUtils.resolveColor(getContext(), primary);
    if (color == 0) {
      color = DialerUtils.resolveColor(getContext(), fallback);
    }
    return color;
  }

  @ColorInt
  private int resolveSurfaceContainer() {
    // Material3 role when the bundled material library provides it, else the floating-background
    // role, else the window background. All are resolved from the current (day/night) theme.
    int surfaceContainerAttr =
        getResources().getIdentifier("colorSurfaceContainer", "attr", getContext().getPackageName());
    if (surfaceContainerAttr != 0) {
      int color = DialerUtils.resolveColor(getContext(), surfaceContainerAttr);
      if (color != 0) {
        return color;
      }
    }
    int floating = DialerUtils.resolveColor(getContext(), android.R.attr.colorBackgroundFloating);
    if (floating != 0) {
      return floating;
    }
    return DialerUtils.resolveColor(getContext(), android.R.attr.colorBackground);
  }

  /**
   * Select tab for uesr and non-user click.
   *
   * @param tab {@link TabIndex}
   */
  public void selectTab(@TabIndex int tab) {
    if (tab == TabIndex.VOICEMAIL && !voicemailVisible) {
      // The glass bar only lays out visible tabs; keep the invariant that the selected tab is
      // always visible, as showVoicemail() does when hiding the tab.
      tab = TabIndex.SPEED_DIAL;
    }
    if (tab != TabIndex.SPEED_DIAL
        && tab != TabIndex.CALL_LOG
        && tab != TabIndex.CONTACTS
        && tab != TabIndex.DIALPAD
        && tab != TabIndex.VOICEMAIL) {
      throw new IllegalStateException("Invalid tab: " + tab);
    }
    selectedTab = tab;
    if (glassState != null) {
      glassState.select(tab);
    }
    updateListeners(selectedTab);
  }

  /**
   * Displays or hides the voicemail tab.
   *
   * <p>In the event that the voicemail tab was earlier visible but is now no longer visible, we
   * move to the speed dial tab.
   *
   * @param showTab whether to hide or show the voicemail
   */
  public void showVoicemail(boolean showTab) {
    LogUtil.i("OldMainActivityPeer.showVoicemail", "showing Tab:%b", showTab);
    boolean wasVisible = voicemailVisible;
    if (wasVisible && !showTab && getSelectedTab() == TabIndex.VOICEMAIL) {
      LogUtil.i("OldMainActivityPeer.showVoicemail", "hid VM tab and moved to speed dial tab");
      // Reselect while all four tabs are still laid out, so the pill never points out of range.
      selectTab(TabIndex.SPEED_DIAL);
    }
    voicemailVisible = showTab;
    if (glassState != null) {
      glassState.setVoicemailVisible(showTab);
    }
  }

  public void setNotificationCount(@TabIndex int tab, int count) {
    Assert.checkArgument(count >= 0, "Invalid count: " + count);
    if (tab != TabIndex.SPEED_DIAL
        && tab != TabIndex.CALL_LOG
        && tab != TabIndex.CONTACTS
        && tab != TabIndex.DIALPAD
        && tab != TabIndex.VOICEMAIL) {
      throw new IllegalStateException("Invalid tab: " + tab);
    }
    if (glassState == null) {
      return;
    }
    if (count == 0) {
      glassState.clearBadge(tab);
      return;
    }
    String countString = Integer.toString(count);
    if (count > 9) {
      countString = getContext().getString(R.string.bottom_nav_count_9_plus);
    }
    glassState.setBadge(tab, countString);
  }

  public void addOnTabSelectedListener(OnBottomNavTabSelectedListener listener) {
    listeners.add(listener);
  }

  private void updateListeners(@TabIndex int tabIndex) {
    for (OnBottomNavTabSelectedListener listener : listeners) {
      switch (tabIndex) {
        case TabIndex.SPEED_DIAL:
          listener.onSpeedDialSelected();
          break;
        case TabIndex.CALL_LOG:
          listener.onCallLogSelected();
          break;
        case TabIndex.CONTACTS:
          listener.onContactsSelected();
          break;
        case TabIndex.DIALPAD:
          listener.onDialpadSelected();
          break;
        case TabIndex.VOICEMAIL:
          listener.onVoicemailSelected();
          break;
        default:
          throw Assert.createIllegalStateFailException("Invalid tab: " + tabIndex);
      }
    }
  }

  @TabIndex
  public int getSelectedTab() {
    return selectedTab;
  }

  /** Listener for bottom nav tab's on click events. */
  public interface OnBottomNavTabSelectedListener {

    /** Speed dial tab was clicked. */
    void onSpeedDialSelected();

    /** Call Log tab was clicked. */
    void onCallLogSelected();

    /** Contacts tab was clicked. */
    void onContactsSelected();

    /** Dialpad tab was clicked. */
    void onDialpadSelected();

    /** Voicemail tab was clicked. */
    void onVoicemailSelected();
  }
}
