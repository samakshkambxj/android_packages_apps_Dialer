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
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;

import androidx.annotation.Nullable;

import com.android.dialer.R;
import com.android.dialer.common.Assert;
import com.android.dialer.common.LogUtil;
import com.android.dialer.main.impl.MainActivity;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialer Bottom Nav Bar for {@link MainActivity}.
 *
 * <p>Hosts the legacy tab strip plus, on builds that ship Compose + miuix-blur (staging Gradle
 * APK), the floating liquid-glass pill ported verbatim from packages_apps_ExactCalculator
 * (OriginSU FloatingBottomBar: liquid shaders, spring drag physics, gravity-rotated highlights,
 * live View snapshot backdrop). The pill is loaded purely via reflection so AOSP/Soong ROM builds
 * without those libs keep compiling and running the legacy strip untouched.
 */
public final class BottomNavBar extends FrameLayout {

  /** Index for each tab in the bottom nav. */
  @Retention(RetentionPolicy.SOURCE)
  @androidx.annotation.IntDef({
    TabIndex.NONE,
    TabIndex.SPEED_DIAL,
    TabIndex.CALL_LOG,
    TabIndex.CONTACTS,
    TabIndex.VOICEMAIL,
  })
  public @interface TabIndex {
    int NONE = -1;
    int SPEED_DIAL = 0;
    int CALL_LOG = 1;
    int CONTACTS = 2;
    int VOICEMAIL = 3;
  }

  private static final String GLASS_BRIDGE = "com.android.dialer.glass.navbar.GlassNavBridge";
  private static final String GLASS_LISTENER = "com.android.dialer.glass.navbar.NavSelectionListener";
  private static final String COMPOSE_VIEW = "androidx.compose.ui.platform.ComposeView";

  /** Pill geometry mirrors ExactCalculator's bottom_navigation (64dp + 12dp margin). */
  private static final int GLASS_BAR_HEIGHT_DP = 64;
  private static final int GLASS_BAR_MARGIN_BOTTOM_DP = 12;

  private final List<OnBottomNavTabSelectedListener> listeners = new ArrayList<>();

  private BottomNavItem speedDial;
  private BottomNavItem callLog;
  private BottomNavItem contacts;
  private BottomNavItem voicemail;
  private LinearLayout legacyContainer;
  private @TabIndex int selectedTab;

  // Liquid-glass pill state, all held as reflection handles so Soong javac never needs Compose.
  private View glassHost;
  private Object glassState;
  private boolean glassActive;
  private boolean showVoicemailTab = true;
  private final int[] badgeCounts = new int[4];

  public BottomNavBar(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();
    legacyContainer = findViewById(R.id.legacy_bottom_nav);
    speedDial = findViewById(R.id.speed_dial_tab);
    callLog = findViewById(R.id.call_log_tab);
    contacts = findViewById(R.id.contacts_tab);
    voicemail = findViewById(R.id.voicemail_tab);

    speedDial.setup(R.string.tab_title_speed_dial, R.drawable.quantum_ic_star_outline_vd_theme_24,
            R.drawable.quantum_ic_star_vd_theme_24);
    callLog.setup(R.string.tab_title_call_history, R.drawable.quantum_ic_access_time_vd_theme_24,
            R.drawable.quantum_ic_clock_filled_vd_theme_24);
    contacts.setup(R.string.tab_all_contacts, R.drawable.quantum_ic_people_outline_vd_theme_24,
            R.drawable.quantum_ic_people_vd_theme_24);
    voicemail.setup(R.string.tab_title_voicemail, R.drawable.quantum_ic_voicemail_vd_theme_24,
            R.drawable.quantum_ic_voicemail_vd_theme_24);

    speedDial.setOnClickListener(v -> selectTab(TabIndex.SPEED_DIAL));
    callLog.setOnClickListener(v -> selectTab(TabIndex.CALL_LOG));
    contacts.setOnClickListener(v -> selectTab(TabIndex.CONTACTS));
    voicemail.setOnClickListener(v -> selectTab(TabIndex.VOICEMAIL));

    // Defer: the activity content view is still being inflated here, so the
    // root overlay isn't findable yet.
    post(this::tryInstallGlassPill);
  }

  @Override
  public void setVisibility(int visibility) {
    super.setVisibility(visibility);
    // Search/dialpad/multiselect hide the whole bar; keep the floating pill in sync.
    if (glassHost != null) {
      glassHost.setVisibility(visibility);
    }
  }

  private void setSelected(View view) {
    speedDial.setSelected(view == speedDial);
    callLog.setSelected(view == callLog);
    contacts.setSelected(view == contacts);
    voicemail.setSelected(view == voicemail);
  }

  /**
   * Select tab for uesr and non-user click.
   *
   * @param tab {@link TabIndex}
   */
  public void selectTab(@TabIndex int tab) {
    if (tab == TabIndex.SPEED_DIAL) {
      selectedTab = TabIndex.SPEED_DIAL;
      setSelected(speedDial);
    } else if (tab == TabIndex.CALL_LOG) {
      selectedTab = TabIndex.CALL_LOG;
      setSelected(callLog);
    } else if (tab == TabIndex.CONTACTS) {
      selectedTab = TabIndex.CONTACTS;
      setSelected(contacts);
    } else if (tab == TabIndex.VOICEMAIL) {
      selectedTab = TabIndex.VOICEMAIL;
      setSelected(voicemail);
    } else {
      throw new IllegalStateException("Invalid tab: " + tab);
    }

    syncGlassSelection(tab);
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
    showVoicemailTab = showTab;
    updateGlassTabs();
    int voicemailpreviousVisibility = voicemail.getVisibility();
    voicemail.setVisibility(showTab ? View.VISIBLE : View.GONE);
    int voicemailcurrentVisibility = voicemail.getVisibility();

    if (voicemailpreviousVisibility != voicemailcurrentVisibility
        && voicemailpreviousVisibility == View.VISIBLE
        && getSelectedTab() == TabIndex.VOICEMAIL) {
      LogUtil.i("OldMainActivityPeer.showVoicemail", "hid VM tab and moved to speed dial tab");
      selectTab(TabIndex.SPEED_DIAL);
    }
  }

  public void setNotificationCount(@TabIndex int tab, int count) {
    if (tab == TabIndex.SPEED_DIAL) {
      speedDial.setNotificationCount(count);
    } else if (tab == TabIndex.CALL_LOG) {
      callLog.setNotificationCount(count);
    } else if (tab == TabIndex.CONTACTS) {
      contacts.setNotificationCount(count);
    } else if (tab == TabIndex.VOICEMAIL) {
      voicemail.setNotificationCount(count);
    } else {
      throw new IllegalStateException("Invalid tab: " + tab);
    }
    if (tab >= 0 && tab < badgeCounts.length) {
      badgeCounts[tab] = count;
      syncGlassBadge(tab, count);
    }
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

    /** Voicemail tab was clicked. */
    void onVoicemailSelected();
  }

  // ---- Liquid-glass pill (reflection; no-ops on Soong builds without Compose) ----

  /** Glass tab order matches {@link TabIndex} when voicemail is shown. */
  private static @TabIndex int glassIndexToTab(int glassIndex, boolean voicemailShown) {
    // Without voicemail the pill shows [favorites, recents, contacts] = indices 0..2.
    return glassIndex;
  }

  private void onGlassTabSelected(int glassIndex) {
    @TabIndex int tab = glassIndexToTab(glassIndex, showVoicemailTab);
    if (tab < TabIndex.SPEED_DIAL || tab > TabIndex.VOICEMAIL) {
      return;
    }
    if (!showVoicemailTab && tab == TabIndex.VOICEMAIL) {
      tab = TabIndex.SPEED_DIAL;
    }
    selectTab(tab);
  }

  private void syncGlassSelection(@TabIndex int tab) {
    if (!glassActive || glassState == null) {
      return;
    }
    try {
      Method select = glassState.getClass().getMethod("select", int.class);
      select.invoke(glassState, tab);
    } catch (Throwable t) {
      LogUtil.w("BottomNavBar.syncGlassSelection", "glass sync failed: " + t);
    }
  }

  private void syncGlassBadge(@TabIndex int tab, int count) {
    if (!glassActive || glassState == null) {
      return;
    }
    try {
      Method setBadge = glassState.getClass().getMethod("setBadge", int.class, int.class);
      setBadge.invoke(glassState, tab, count);
    } catch (Throwable t) {
      LogUtil.w("BottomNavBar.syncGlassBadge", "glass badge sync failed: " + t);
    }
  }

  private void updateGlassTabs() {
    if (!glassActive || glassState == null) {
      return;
    }
    try {
      Class<?> bridge = Class.forName(GLASS_BRIDGE);
      Method dialerTabs = bridge.getMethod("dialerTabs", boolean.class);
      Object tabs = dialerTabs.invoke(null, showVoicemailTab);
      Method setTabs = glassState.getClass().getMethod("setTabs", List.class);
      setTabs.invoke(glassState, tabs);
      for (int i = 0; i < badgeCounts.length; i++) {
        syncGlassBadge(i, badgeCounts[i]);
      }
      syncGlassSelection(selectedTab);
    } catch (Throwable t) {
      LogUtil.w("BottomNavBar.updateGlassTabs", "glass tabs sync failed: " + t);
    }
  }

  /**
   * Installs the floating pill as a bottom-centered overlay in the activity root so the live
   * snapshot backdrop samples the real content behind it. Silently keeps the legacy strip when
   * Compose/miuix-blur isn't on the classpath (AOSP/Soong ROM builds).
   */
  private void tryInstallGlassPill() {
    if (glassActive || glassHost != null) {
      return;
    }
    try {
      Class<?> composeViewClass = Class.forName(COMPOSE_VIEW);
      Class<?> bridgeClass = Class.forName(GLASS_BRIDGE);
      Class<?> listenerClass = Class.forName(GLASS_LISTENER);

      Context context = getContext();
      View root = null;
      if (context instanceof android.app.Activity) {
        root = ((android.app.Activity) context).findViewById(R.id.root_layout);
      }
      if (!(root instanceof ViewGroup)) {
        return;
      }
      ViewGroup rootGroup = (ViewGroup) root;

      Constructor<?> ctor = composeViewClass.getConstructor(Context.class);
      View host = (View) ctor.newInstance(rootGroup.getContext());

      float density = rootGroup.getResources().getDisplayMetrics().density;
      int heightPx = (int) (GLASS_BAR_HEIGHT_DP * density + 0.5f);
      int marginBottomPx = (int) (GLASS_BAR_MARGIN_BOTTOM_DP * density + 0.5f);
      RelativeLayout.LayoutParams params =
          new RelativeLayout.LayoutParams(
              RelativeLayout.LayoutParams.WRAP_CONTENT, heightPx);
      params.addRule(RelativeLayout.CENTER_HORIZONTAL);
      params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
      params.bottomMargin = marginBottomPx;

      // Insert right after this bar so the dialpad/contact overlays stay on top.
      int anchor = rootGroup.indexOfChild(this);
      if (anchor >= 0) {
        rootGroup.addView(host, anchor + 1, params);
      } else {
        rootGroup.addView(host, params);
      }

      Method dialerTabs = bridgeClass.getMethod("dialerTabs", boolean.class);
      Object tabs = dialerTabs.invoke(null, showVoicemailTab);

      Object listener =
          Proxy.newProxyInstance(
              listenerClass.getClassLoader(),
              new Class<?>[] {listenerClass},
              (proxy, method, args) -> {
                if (args != null && args.length == 1 && args[0] instanceof Integer) {
                  onGlassTabSelected((Integer) args[0]);
                }
                return null;
              });

      Method install =
          bridgeClass.getMethod(
              "install", composeViewClass, List.class, int.class, listenerClass);
      Object state = install.invoke(null, host, tabs, selectedTab, listener);
      if (state == null) {
        rootGroup.removeView(host);
        return;
      }

      glassHost = host;
      glassState = state;
      glassActive = true;

      // Legacy strip out of the way; this container collapses and the pill floats.
      if (legacyContainer != null) {
        legacyContainer.setVisibility(View.GONE);
      }
      setBackground(null);
      setElevation(0);
      glassHost.setVisibility(getVisibility());

      for (int i = 0; i < badgeCounts.length; i++) {
        if (badgeCounts[i] > 0) {
          syncGlassBadge(i, badgeCounts[i]);
        }
      }
      syncGlassSelection(selectedTab);
      LogUtil.i("BottomNavBar.tryInstallGlassPill", "liquid-glass pill installed");
    } catch (ClassNotFoundException e) {
      // Expected on AOSP/Soong ROM builds without Compose + miuix-blur: legacy strip stays.
      LogUtil.i("BottomNavBar.tryInstallGlassPill", "glass libs absent, using legacy nav");
    } catch (Throwable t) {
      LogUtil.w("BottomNavBar.tryInstallGlassPill", "glass install failed, using legacy nav: " + t);
      glassActive = false;
      glassState = null;
      glassHost = null;
    }
  }
}
