package com.bitaim.carromaim.overlay;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * BitAimAccessibilityService
 *
 * Required for dispatchGesture() — Android's only way to simulate
 * swipe gestures on another app without root.
 *
 * User enables it once in: Settings → Accessibility → Bit-Aim
 *
 * When enabled, FloatingOverlayService calls dispatchGesture() to
 * perform the swipe FROM the detected striker position toward the target.
 */
public class BitAimAccessibilityService extends AccessibilityService {

    public static volatile BitAimAccessibilityService INSTANCE;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        INSTANCE = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used — we only need gesture dispatch
    }

    @Override
    public void onInterrupt() {
        // Not used
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
    }
}
