/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.keyguard;

import android.graphics.Rect;
import android.util.Slog;

import com.android.keyguard.KeyguardClockSwitch.ClockSize;
import com.android.systemui.statusbar.notification.AnimatableProperty;
import com.android.systemui.statusbar.notification.PropertyAnimator;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.StackStateAnimator;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.ViewController;

import java.util.TimeZone;

import javax.inject.Inject;

/**
 * Injectable controller for {@link KeyguardStatusView}.
 */
public class KeyguardStatusViewController extends ViewController<KeyguardStatusView> {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusViewController";

    private static final AnimationProperties CLOCK_ANIMATION_PROPERTIES =
            new AnimationProperties().setDuration(StackStateAnimator.ANIMATION_DURATION_STANDARD);

    private final KeyguardSliceViewController mKeyguardSliceViewController;
    private final KeyguardClockSwitchController mKeyguardClockSwitchController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final ConfigurationController mConfigurationController;
    private final KeyguardVisibilityHelper mKeyguardVisibilityHelper;
    private final Rect mClipBounds = new Rect();

    @Inject
    public KeyguardStatusViewController(
            KeyguardStatusView keyguardStatusView,
            KeyguardSliceViewController keyguardSliceViewController,
            KeyguardClockSwitchController keyguardClockSwitchController,
            KeyguardStateController keyguardStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            ConfigurationController configurationController,
            DozeParameters dozeParameters,
            ScreenOffAnimationController screenOffAnimationController) {
        super(keyguardStatusView);
        mKeyguardSliceViewController = keyguardSliceViewController;
        mKeyguardClockSwitchController = keyguardClockSwitchController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mConfigurationController = configurationController;
        mKeyguardVisibilityHelper = new KeyguardVisibilityHelper(mView, keyguardStateController,
                dozeParameters, screenOffAnimationController, /* animateYPos= */ true);
    }

    @Override
    public void onInit() {
        mKeyguardClockSwitchController.init();
        mView.onThemeChanged();
    }

    @Override
    protected void onViewAttached() {
        mKeyguardUpdateMonitor.registerCallback(mInfoCallback);
        mConfigurationController.addCallback(mConfigurationListener);
        mView.onThemeChanged();
    }

    @Override
    protected void onViewDetached() {
        mKeyguardUpdateMonitor.removeCallback(mInfoCallback);
        mConfigurationController.removeCallback(mConfigurationListener);
        mView.onThemeChanged();
    }

    /**
     * Updates views on doze time tick.
     */
    public void dozeTimeTick() {
        refreshTime();
        mKeyguardSliceViewController.refresh();
        mView.onThemeChanged();
    }

    /**
     * The amount we're in doze.
     */
    public void setDarkAmount(float darkAmount) {
        mView.setDarkAmount(darkAmount);
        mView.onThemeChanged();
    }

    /**
     * Set which clock should be displayed on the keyguard. The other one will be automatically
     * hidden.
     */
    public void displayClock(@ClockSize int clockSize, boolean animate) {
        mKeyguardClockSwitchController.displayClock(clockSize, animate);
        mView.onThemeChanged();
    }

    /**
     * Performs fold to aod animation of the clocks (changes font weight from bold to thin).
     * This animation is played when AOD is enabled and foldable device is fully folded, it is
     * displayed on the outer screen
     */
    public void animateFoldToAod() {
        mKeyguardClockSwitchController.animateFoldToAod();
        mView.onThemeChanged();
    }

    /**
     * If we're presenting a custom clock of just the default one.
     */
    public boolean hasCustomClock() {
        return mKeyguardClockSwitchController.hasCustomClock();
    }

    /**
     * Sets a translationY on the views on the keyguard, except on the media view.
     */
    public void setTranslationYExcludingMedia(float translationY) {
        mView.setChildrenTranslationYExcludingMediaView(translationY);
        mView.onThemeChanged();
    }

    /**
     * Set keyguard status view alpha.
     */
    public void setAlpha(float alpha) {
        if (!mKeyguardVisibilityHelper.isVisibilityAnimating()) {
            mView.setAlpha(alpha);
            mView.onThemeChanged();
        }
    }

    /**
     * Set pivot x.
     */
    public void setPivotX(float pivot) {
        mView.setPivotX(pivot);
        mView.onThemeChanged();
    }

    /**
     * Set pivot y.
     */
    public void setPivotY(float pivot) {
        mView.setPivotY(pivot);
        mView.onThemeChanged();
    }

    /**
     * Get the clock text size.
     */
    public float getClockTextSize() {
        return mKeyguardClockSwitchController.getClockTextSize();
    }

    /**
     * Get the height of the keyguard status view without the notification icon area, as that's
     * only visible on AOD.
     */
    public int getLockscreenHeight() {
        return mView.getHeight() - mKeyguardClockSwitchController.getNotificationIconAreaHeight();
    }

    /**
     * Get y-bottom position of the currently visible clock.
     */
    public int getClockBottom(int statusBarHeaderHeight) {
        return mKeyguardClockSwitchController.getClockBottom(statusBarHeaderHeight);
    }

    /**
     * @return true if the currently displayed clock is top aligned (as opposed to center aligned)
     */
    public boolean isClockTopAligned() {
        return mKeyguardClockSwitchController.isClockTopAligned();
    }

    /**
     * Set whether the view accessibility importance mode.
     */
    public void setStatusAccessibilityImportance(int mode) {
        mView.setImportantForAccessibility(mode);
        mView.onThemeChanged();
    }

    /**
     * Update position of the view with an optional animation
     */
    public void updatePosition(int x, int y, float scale, boolean animate) {
        PropertyAnimator.setProperty(mView, AnimatableProperty.Y, y, CLOCK_ANIMATION_PROPERTIES,
                animate);

        mKeyguardClockSwitchController.updatePosition(x, scale, CLOCK_ANIMATION_PROPERTIES,
                animate);
    }

    /**
     * Set the visibility of the keyguard status view based on some new state.
     */
    public void setKeyguardStatusViewVisibility(
            int statusBarState,
            boolean keyguardFadingAway,
            boolean goingToFullShade,
            int oldStatusBarState) {
        mKeyguardVisibilityHelper.setViewVisibility(
                statusBarState, keyguardFadingAway, goingToFullShade, oldStatusBarState);
    }

    private void refreshTime() {
        mKeyguardClockSwitchController.refresh();
        mView.onThemeChanged();
    }

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
        @Override
        public void onLocaleListChanged() {
            refreshTime();
            mKeyguardClockSwitchController.onLocaleListChanged();
            mView.onThemeChanged();
        }

        @Override
        public void onDensityOrFontScaleChanged() {
            mKeyguardClockSwitchController.onDensityOrFontScaleChanged();
            mView.onThemeChanged();
        }

        @Override
        public void onThemeChanged() {
            mKeyguardClockSwitchController.onThemeChanged();
            mView.onThemeChanged();
        }
    };

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onTimeChanged() {
            refreshTime();
            mView.onThemeChanged();
        }

        @Override
        public void onTimeFormatChanged(String timeFormat) {
            mKeyguardClockSwitchController.refreshFormat();
            mView.onThemeChanged();
        }

        @Override
        public void onTimeZoneChanged(TimeZone timeZone) {
            mKeyguardClockSwitchController.updateTimeZone(timeZone);
            mView.onThemeChanged();
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refreshTime();
                mView.onThemeChanged();
            }
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            mKeyguardClockSwitchController.refreshFormat();
            mView.onThemeChanged();
        }
    };

    /**
     * Rect that specifies how KSV should be clipped, on its parent's coordinates.
     */
    public void setClipBounds(Rect clipBounds) {
        if (clipBounds != null) {
            mClipBounds.set(clipBounds.left, (int) (clipBounds.top - mView.getY()),
                    clipBounds.right, (int) (clipBounds.bottom - mView.getY()));
            mView.setClipBounds(mClipBounds);
        } else {
            mView.setClipBounds(null);
        }
    }
}
