package com.bitaim.carromaim.cv;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.List;

/**
 * BoardDetector v4 — detects orange border, coins, blue striker.
 * Manual board rect can be injected via setManualBoard() to bypass CV detection.
 */
public class BoardDetector {

    private static final String TAG    = "BoardDetector";
    private static final int    PROC_W = 540;

    private float  minRadiusFrac = 0.018f;
    private float  maxRadiusFrac = 0.065f;
    private double param2        = 14.0;

    // Manual board override — set by user dragging corners on overlay
    private RectF manualBoard = null;

    private final Mat frame = new Mat();
    private final Mat small = new Mat();
    private final Mat gray  = new Mat();
    private final Mat hsv   = new Mat();

    private android.graphics.RectF manualBoardOverride = null;
    public void setManualBoard(android.graphics.RectF board) { manualBoardOverride = board; }
    public void clearManualBoard() { manualBoardOverride = null; }

    public void setMinRadiusFrac(float v) { minRadiusFrac = Math.max(0.008f, Math.min(v, 0.08f)); }
    public void setMaxRadiusFrac(float v) { maxRadiusFrac = Math.max(0.025f, Math.min(v, 0.15f)); }
    public void setParam2(double v)       { param2        = Math.max(5.0,    Math.min(v, 50.0));  }

    /** Called from overlay when user manually sets board corners. */
    public synchronized void setManualBoard(RectF r) { manualBoard = r; }
    public synchronized void clearManualBoard()      { manualBoard = null; }

    public synchronized GameState detect(Bitmap bitmap) {
        if (bitmap == null) return null;
        int srcW = bitmap.getWidth(), srcH = bitmap.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        Utils.bitmapToMat(bitmap, frame);
        float scale = (float) PROC_W / srcW;
        int   procH = Math.round(srcH * scale);
        Imgproc.resize(frame, small, new Size(PROC_W, procH), 0, 0, Imgproc.INTER_AREA);
        Imgproc.cvtColor(small, hsv,  Imgproc.COLOR_RGBA2BGR);
        Imgproc.cvtColor(hsv,   hsv,  Imgproc.COLOR_BGR2HSV);
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGBA2GRAY);
        // Bilateral filter — removes noise but preserves coin edges better than Gaussian
        Mat grayF = new Mat();
        Imgproc.bilateralFilter(gray, grayF, 7, 20, 20);

        // 1. Board rect
        RectF board;
        if (manualBoard != null) {
            board = new RectF(manualBoard); // use user-set rect
        } else {
            board = detectBoardFromOrangeBorder(PROC_W, procH, scale, srcW, srcH);
        }

        // 2. Coins
        List<Coin> all = detectCircles(grayF, PROC_W, procH, scale, board);
        grayF.release();

        // 3. Classify striker
        GameState state = new GameState();
        Coin striker = pickStriker(all, srcH);
        if (striker != null) {
            striker.isStriker = true;
            striker.color = Coin.COLOR_STRIKER;
            state.striker = striker;
            for (Coin c : all) if (c != striker) state.coins.add(c);
        } else {
            state.coins.addAll(all);
        }

        // 4. Board fallback
        if (board != null && board.width() > srcW * 0.20f) {
            state.board = board;
        } else {
            state.board = inferBoardFromCoins(all, srcW, srcH);
        }

        // 5. Pockets
        placePockets(state);

        Log.d(TAG, "coins=" + all.size() + " striker=" + (striker != null)
                + " manual=" + (manualBoard != null));
        return state;
    }

    // ── Orange border ────────────────────────────────────────────────────────

    private RectF detectBoardFromOrangeBorder(int procW, int procH, float scale, int srcW, int srcH) {
        try {
            Mat orange = new Mat();
            // Bright orange H=8-26, S>140, V>130
            Core.inRange(hsv, new Scalar(7, 130, 120), new Scalar(26, 255, 255), orange);
            Mat k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(14, 14));
            Imgproc.morphologyEx(orange, orange, Imgproc.MORPH_CLOSE,  k);
            Imgproc.morphologyEx(orange, orange, Imgproc.MORPH_DILATE, k);

            List<MatOfPoint> contours = new ArrayList<>();
            Mat hier = new Mat();
            Imgproc.findContours(orange, contours, hier, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

            double maxA = 0; Rect best = null;
            for (MatOfPoint cnt : contours) {
                double a = Imgproc.contourArea(cnt);
                if (a > maxA) { maxA = a; best = Imgproc.boundingRect(cnt); }
            }
            orange.release(); k.release(); hier.release();
            if (best == null || maxA < procW * procH * 0.07) return null;

            float l = best.x / scale, t = best.y / scale;
            float r = (best.x + best.width) / scale, b = (best.y + best.height) / scale;
            float cx = (l + r) / 2f, cy = (t + b) / 2f;
            float side = Math.max(r - l, b - t) * 1.02f;
            RectF res = new RectF(cx - side/2f, cy - side/2f, cx + side/2f, cy + side/2f);
            if (res.left < 0) res.left = 0; if (res.top < 0) res.top = 0;
            if (res.right > srcW) res.right = srcW; if (res.bottom > srcH) res.bottom = srcH;
            return res;
        } catch (Exception e) { Log.w(TAG, "border detect: " + e.getMessage()); return null; }
    }

    // ── Circle detection — 3 thresholds, best result ─────────────────────────

    private List<Coin> detectCircles(Mat grayIn, int procW, int procH, float scale, RectF board) {
        int minR    = Math.round(procW * minRadiusFrac);
        int maxR    = Math.round(procW * maxRadiusFrac);
        int minDist = (int) (minR * 1.4f);

        List<Coin> best = new ArrayList<>();
        double[] thresh = { param2, param2 * 0.80, param2 * 0.65 };

        for (double th : thresh) {
            Mat c = new Mat();
            Imgproc.HoughCircles(grayIn, c, Imgproc.HOUGH_GRADIENT,
                    1.0, minDist, 75, th, minR, maxR);
            List<Coin> found = parseCircles(c, scale, board);
            c.release();
            if (found.size() > best.size() && found.size() <= 32) best = found;
        }
        return best;
    }

    private List<Coin> parseCircles(Mat c, float scale, RectF board) {
        List<Coin> list = new ArrayList<>();
        if (c.empty()) return list;
        for (int i = 0; i < c.cols(); i++) {
            double[] d = c.get(0, i);
            if (d == null || d.length < 3) continue;
            float cx = (float) d[0], cy = (float) d[1], cr = (float) d[2];
            // Skip if outside board (with margin)
            if (board != null) {
                float sx = cx / scale, sy = cy / scale, margin = cr / scale * 2f;
                if (sx < board.left - margin || sx > board.right  + margin) continue;
                if (sy < board.top  - margin || sy > board.bottom + margin) continue;
            }
            int color = classifyColor((int) cx, (int) cy, (int) cr);
            if (color < 0) continue;
            list.add(new Coin(cx / scale, cy / scale, cr / scale, color, false));
        }
        return list;
    }

    // ── HSV color classifier — tuned for this carrom game ────────────────────

    private int classifyColor(int x, int y, int r) {
        if (r < 3) return -1;
        int rows = hsv.rows(), cols = hsv.cols();
        if (x-r < 0 || y-r < 0 || x+r >= cols || y+r >= rows) return -1;
        int s = Math.max(2, (int)(r * 0.45f));
        Mat patch = hsv.submat(Math.max(0,y-s), Math.min(rows-1,y+s),
                               Math.max(0,x-s), Math.min(cols-1,x+s));
        Scalar mean = Core.mean(patch);
        patch.release();
        double h = mean.val[0], sat = mean.val[1], val = mean.val[2];

        // Blue striker: H 95-135, sat>90, val>50
        if (h >= 95 && h <= 135 && sat > 90 && val > 50) return Coin.COLOR_STRIKER;
        // Black coin: very dark
        if (val < 85) return Coin.COLOR_BLACK;
        // Red queen: red hue, saturated
        if (sat > 100 && val > 80 && (h < 14 || h > 163)) return Coin.COLOR_RED;
        // White coin: bright + low sat
        if (val > 145 && sat < 100) return Coin.COLOR_WHITE;
        // Reject board surface (tan/beige)
        if (h > 11 && h < 40 && sat < 140 && val > 90 && val < 235) return -1;
        // Reject orange border
        if (h >= 7 && h <= 27 && sat > 130 && val > 120) return -1;
        return -1;
    }

    // ── Striker picker ───────────────────────────────────────────────────────

    private Coin pickStriker(List<Coin> all, int srcH) {
        // First: any coin already classified blue
        for (Coin c : all) if (c.color == Coin.COLOR_STRIKER) return c;
        // Fallback: largest white coin in lower 50%
        Coin best = null; float bestScore = -1;
        for (Coin c : all) {
            if (c.color != Coin.COLOR_WHITE) continue;
            float yf = c.pos.y / (float) srcH;
            if (yf < 0.45f) continue;
            float score = c.radius * (0.2f + 0.8f * yf);
            if (score > bestScore) { bestScore = score; best = c; }
        }
        return best;
    }

    // ── Fallbacks ────────────────────────────────────────────────────────────

    private RectF inferBoardFromCoins(List<Coin> all, int w, int h) {
        if (all.size() < 2) {
            float side = w * 0.88f, cx = w/2f, cy = h * 0.48f;
            return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        }
        float minX=Float.MAX_VALUE, minY=Float.MAX_VALUE, maxX=-Float.MAX_VALUE, maxY=-Float.MAX_VALUE;
        for (Coin c : all) {
            if (c.pos.x<minX) minX=c.pos.x; if (c.pos.y<minY) minY=c.pos.y;
            if (c.pos.x>maxX) maxX=c.pos.x; if (c.pos.y>maxY) maxY=c.pos.y;
        }
        float pad = Math.max(45f, (maxX-minX)*0.12f);
        float side = Math.max(maxX-minX, maxY-minY) + pad*2f;
        float cx = (minX+maxX)/2f, cy = (minY+maxY)/2f;
        RectF r = new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
        if (r.left<0) r.left=0; if (r.top<0) r.top=0;
        if (r.right>w) r.right=w; if (r.bottom>h) r.bottom=h;
        return r;
    }

    private void placePockets(GameState s) {
        float in = s.board.width() * 0.05f;
        s.pockets.add(new PointF(s.board.left+in,  s.board.top+in));
        s.pockets.add(new PointF(s.board.right-in, s.board.top+in));
        s.pockets.add(new PointF(s.board.left+in,  s.board.bottom-in));
        s.pockets.add(new PointF(s.board.right-in, s.board.bottom-in));
    }
}
