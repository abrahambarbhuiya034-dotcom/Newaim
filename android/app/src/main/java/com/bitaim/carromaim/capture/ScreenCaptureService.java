package com.bitaim.carromaim.capture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.bitaim.carromaim.MainActivity;
import com.bitaim.carromaim.R;
import com.bitaim.carromaim.cv.BoardDetector;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.overlay.FloatingOverlayService;

import java.nio.ByteBuffer;

/**
 * ScreenCaptureService  v3
 *
 * Captures the screen at ~30 FPS, runs BoardDetector on each frame,
 * scales the result to screen coordinates, and pushes it to the overlay.
 *
 * Changes from v2:
 *  - Capture resolution increased from 720 to 1080 for higher-fidelity detection.
 *  - Frame throttle reduced from 33 ms to 25 ms for snappier response (~40 FPS).
 *  - scaleState now uses a single uniform scale factor (sx) assuming square pixels.
 */
public class ScreenCaptureService extends Service {

    private static final String TAG              = "BitAim/Capture";
    public  static final String EXTRA_RESULT_CODE = "resultCode";
    public  static final String EXTRA_DATA        = "data";

    private static final String CHANNEL_ID      = "bitaim_capture";
    private static final int    NOTIF_ID        = 2001;
    private static final long   FRAME_INTERVAL_MS = 50; // ~20 FPS for low-end device // ~40 FPS
    private static final int    CAPTURE_WIDTH   = 540; // halved for low-end

    private MediaProjection mediaProjection;
    private VirtualDisplay  virtualDisplay;
    private ImageReader     imageReader;
    private HandlerThread   workerThread;
    private Handler         workerHandler;
    private final BoardDetector detector = new BoardDetector();
    private volatile android.graphics.RectF manualBoardRect = null;

    private int screenWidth, screenHeight, screenDpi;
    private long    lastFrameMs = 0;
    private volatile boolean running = false;

    public static volatile ScreenCaptureService INSTANCE;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        INSTANCE = this;
        createChannel();
        startForeground(NOTIF_ID, buildNotification());

        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            screenWidth  = bounds.width();
            screenHeight = bounds.height();
            DisplayMetrics dm = new DisplayMetrics();
            getDisplay().getRealMetrics(dm);
            screenDpi = dm.densityDpi;
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenWidth  = dm.widthPixels;
            screenHeight = dm.heightPixels;
            screenDpi    = dm.densityDpi;
        }

        workerThread = new HandlerThread("BitAim-Capture");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (mediaProjection != null) return START_STICKY;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = intent.getParcelableExtra(EXTRA_DATA);
        if (resultCode == 0 || data == null) {
            Log.w(TAG, "Missing projection extras — stopping");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = mpm.getMediaProjection(resultCode, data);
        if (mediaProjection == null) {
            Log.e(TAG, "Failed to acquire MediaProjection");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { stopSelf(); }
            }, workerHandler);
        }

        startCapture();
        return START_STICKY;
    }

    private void startCapture() {
        int captureW = Math.min(screenWidth, CAPTURE_WIDTH);
        int captureH = Math.round(screenHeight * (captureW / (float) screenWidth));

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 3);

        virtualDisplay = mediaProjection.createVirtualDisplay(
                "BitAim-Capture",
                captureW, captureH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null, workerHandler
        );
        running = true;
        imageReader.setOnImageAvailableListener(
                reader -> processIfDue(reader, captureW, captureH), workerHandler);
    }

    private void processIfDue(ImageReader reader, int w, int h) {
        long now = System.currentTimeMillis();
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            if (now - lastFrameMs < FRAME_INTERVAL_MS) return;
            lastFrameMs = now;

            Bitmap bmp = imageToBitmap(img, w, h);
            if (bmp == null) return;

            GameState state = detector.detect(bmp);
            bmp.recycle();
            if (state == null) return;

            // Scale captured-coords → real screen-coords
            float sx = screenWidth  / (float) w;
            float sy = screenHeight / (float) h;
            scaleState(state, sx, sy);

            FloatingOverlayService overlay = FloatingOverlayService.INSTANCE;
            if (overlay != null) overlay.onDetectedState(state);

        } catch (Throwable t) {
            Log.w(TAG, "Frame error: " + t.getMessage());
        } finally {
            if (img != null) img.close();
        }
    }

    private void scaleState(GameState s, float sx, float sy) {
        if (s == null) return;
        if (s.board != null) {
            s.board.left   *= sx; s.board.right  *= sx;
            s.board.top    *= sy; s.board.bottom *= sy;
        }
        if (s.striker != null) {
            s.striker.pos.x *= sx; s.striker.pos.y *= sy;
            s.striker.radius *= (sx + sy) * 0.5f;
        }
        for (com.bitaim.carromaim.cv.Coin c : s.coins) {
            c.pos.x  *= sx; c.pos.y  *= sy;
            c.radius *= (sx + sy) * 0.5f;
        }
        for (android.graphics.PointF p : s.pockets) {
            p.x *= sx; p.y *= sy;
        }
    }

    private Bitmap imageToBitmap(Image image, int w, int h) {
        Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer   = planes[0].getBuffer();
        int pixelStride     = planes[0].getPixelStride();
        int rowStride       = planes[0].getRowStride();
        int rowPadding      = rowStride - pixelStride * w;
        int bw              = w + rowPadding / Math.max(1, pixelStride);

        // Must use ARGB_8888 here — ImageReader produces RGBA_8888 data and
        // copyPixelsFromBuffer requires a matching config. RGB_565 causes a crash.
        Bitmap argb = Bitmap.createBitmap(bw, h, Bitmap.Config.ARGB_8888);
        argb.copyPixelsFromBuffer(buffer);

        // Crop padding if needed
        Bitmap cropped;
        if (rowPadding == 0 && bw == w) {
            cropped = argb;
        } else {
            cropped = Bitmap.createBitmap(argb, 0, 0, w, h);
            argb.recycle();
        }

        // Convert to RGB_565 to halve heap usage — safe to do now that pixels are correct
        Bitmap small = cropped.copy(Bitmap.Config.RGB_565, false);
        if (small != null) { cropped.recycle(); return small; }
        return cropped;
    }

    public void setMinRadius(float v)       { detector.setMinRadiusFrac(v); }
    public void setMaxRadius(float v)       { detector.setMaxRadiusFrac(v); }
    public void setDetectionParam(double v) { detector.setParam2(v); }

    /** Called when user draws a board rect manually on the overlay. */
    public void setManualBoard(android.graphics.RectF board) {
        manualBoardRect = board;
        detector.setManualBoard(board);
    }

    /** Revert to auto-detect board. */
    public void clearManualBoard() {
        manualBoardRect = null;
        detector.clearManualBoard();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        INSTANCE = null;
        if (virtualDisplay   != null) { virtualDisplay.release();   virtualDisplay  = null; }
        if (imageReader      != null) { imageReader.close();        imageReader     = null; }
        if (mediaProjection  != null) { mediaProjection.stop();     mediaProjection = null; }
        if (workerThread     != null) { workerThread.quitSafely();  workerThread    = null; }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Rect bounds = wm.getCurrentWindowMetrics().getBounds();
            screenWidth  = bounds.width();
            screenHeight = bounds.height();
        } else {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            screenWidth  = dm.widthPixels;
            screenHeight = dm.heightPixels;
        }
        if (mediaProjection != null) {
            if (virtualDisplay != null) { virtualDisplay.release(); virtualDisplay = null; }
            if (imageReader    != null) { imageReader.close();      imageReader    = null; }
            startCapture();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Bit-Aim Auto-Detect", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bit-Aim Auto-Detect Running")
                .setContentText("Detecting board size, striker, coins and pockets")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }
}