package com.bitaim.carromaim.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import com.bitaim.carromaim.cv.GameState;

/**
 * FloatingOverlayService  v3.1
 * Added: setSnapMode() forwarded to AimOverlayView.
 */
public class FloatingOverlayService extends Service {

    private static final String CHANNEL_ID = "bitaim_channel";
    private static final int    NOTIF_ID   = 1001;
    public static volatile FloatingOverlayService INSTANCE;

    private WindowManager windowManager;
    private View floatingBtnView;
    private AimOverlayView aimOverlayView;
    private WindowManager.LayoutParams floatingBtnParams, overlayParams;

    private float touchStartX, touchStartY;
    private int   viewStartX, viewStartY;
    private boolean overlayVisible = false;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setupFloatingButton();
        setupAimOverlay();
    }

    private void setupFloatingButton() {
        floatingBtnView = LayoutInflater.from(this)
                .inflate(R.layout.view_floating_button, null);
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        floatingBtnParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        floatingBtnParams.gravity = Gravity.TOP | Gravity.START;
        floatingBtnParams.x = 50; floatingBtnParams.y = 300;
        floatingBtnView.setOnTouchListener(new View.OnTouchListener() {
            boolean wasDrag = false;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchStartX = e.getRawX(); touchStartY = e.getRawY();
                        viewStartX = floatingBtnParams.x; viewStartY = floatingBtnParams.y;
                        wasDrag = false; return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX()-touchStartX, dy = e.getRawY()-touchStartY;
                        if (Math.abs(dx)>5||Math.abs(dy)>5) wasDrag = true;
                        floatingBtnParams.x=(int)(viewStartX+dx);
                        floatingBtnParams.y=(int)(viewStartY+dy);
                        windowManager.updateViewLayout(floatingBtnView, floatingBtnParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!wasDrag) toggleAimOverlay(); return true;
                } return false;
            }
        });
        windowManager.addView(floatingBtnView, floatingBtnParams);
    }

    private void setupAimOverlay() {
        aimOverlayView = new AimOverlayView(this);
        // Auto-swipe: when overlay computes a shot, perform the swipe gesture
        aimOverlayView.setOnAutoSwipeListener(new AimOverlayView.OnAutoSwipeListener() {
            @Override
            public void onSwipe(float fromX, float fromY, float toX, float toY) {
                performSwipeGesture(fromX, fromY, toX, toY);
            }
        });

        aimOverlayView.setOnManualBoardListener(new AimOverlayView.OnManualBoardListener() {
            @Override public void onBoardSet(android.graphics.RectF board) {
                com.bitaim.carromaim.capture.ScreenCaptureService svc =
                        com.bitaim.carromaim.capture.ScreenCaptureService.INSTANCE;
                if (svc != null) svc.setManualBoard(board);
            }
            @Override public void onBoardCleared() {
                com.bitaim.carromaim.capture.ScreenCaptureService svc =
                        com.bitaim.carromaim.capture.ScreenCaptureService.INSTANCE;
                if (svc != null) svc.clearManualBoard();
            }
        });
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                // FLAG_NOT_FOCUSABLE was incorrectly set here — it blocks touch delivery
                // to the View's onTouchEvent on Android 7. Remove it so taps/drags work.
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        aimOverlayView.setVisibility(View.GONE);
        windowManager.addView(aimOverlayView, overlayParams);
    }

    public void toggleAimOverlay() {
        overlayVisible = !overlayVisible;
        aimOverlayView.setVisibility(overlayVisible ? View.VISIBLE : View.GONE);
        ImageView icon = floatingBtnView.findViewById(R.id.floating_icon);
        if (icon != null) icon.setAlpha(overlayVisible ? 1.0f : 0.5f);
    }

    public void setShotMode(String mode)            { if (aimOverlayView!=null) aimOverlayView.setShotMode(mode); }
    public void setMarginOffset(float dx, float dy) { if (aimOverlayView!=null) aimOverlayView.setMarginOffset(dx, dy); }
    public void setSensitivity(float v)             { if (aimOverlayView!=null) aimOverlayView.setSensitivity(v); }
    public void setStrikerMoveable(boolean m)       { if (aimOverlayView!=null) aimOverlayView.setStrikerMoveable(m); }
    public void setSnapMode(boolean on)             { if (aimOverlayView!=null) aimOverlayView.setSnapMode(on); }
    public void onDetectedState(GameState s)        { if (aimOverlayView!=null) aimOverlayView.setDetectedState(s); }
    public void setManualBoardMode(boolean on)      { if (aimOverlayView!=null) aimOverlayView.setManualBoardMode(on); }
    public void clearManualBoard()                  { if (aimOverlayView!=null) aimOverlayView.clearManualBoard(); }

    public void setAutoSwipeEnabled(boolean on) {
        if (aimOverlayView != null) aimOverlayView.setAutoSwipeEnabled(on);
    }

    /**
     * Perform a swipe gesture at the REAL screen coordinates of the striker.
     * Uses AccessibilityService path (API 24+, Android 7+).
     * The gesture goes FROM the striker position TOWARD the target,
     * simulating a human swipe that launches the striker.
     */
    private void performSwipeGesture(float fromX, float fromY, float toX, float toY) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        BitAimAccessibilityService svc = BitAimAccessibilityService.INSTANCE;
        if (svc == null) return; // accessibility not enabled
        Path swipePath = new Path();
        swipePath.moveTo(fromX, fromY);
        swipePath.lineTo(toX, toY);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(swipePath, 0, 180); // 180ms swipe
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        svc.dispatchGesture(gesture, null, null);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Bit-Aim Running", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Aim assist overlay is active");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, openIntent, piFlags);
        Intent stopIntent = new Intent(this, FloatingOverlayService.class);
        stopIntent.setAction("ACTION_STOP");
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopIntent, piFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bit-Aim Running")
                .setContentText("Tap floating icon to toggle aim lines")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(openPi)
                .addAction(0, "Stop", stopPi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "ACTION_STOP".equals(intent.getAction())) stopSelf();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        INSTANCE = null;
        try { if (floatingBtnView!=null) windowManager.removeView(floatingBtnView); } catch (Exception ignored) {}
        try { if (aimOverlayView!=null)  windowManager.removeView(aimOverlayView);  } catch (Exception ignored) {}
    }
}
