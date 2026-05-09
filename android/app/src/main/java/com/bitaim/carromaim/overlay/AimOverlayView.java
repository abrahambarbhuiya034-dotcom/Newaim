package com.bitaim.carromaim.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;

import com.bitaim.carromaim.cv.Coin;
import com.bitaim.carromaim.cv.GameState;
import com.bitaim.carromaim.cv.TrajectorySimulator;

import java.util.ArrayList;
import java.util.List;

/**
 * AimOverlayView  v5
 *
 * FIXED:
 *  1. Lines always show — TrajectorySimulator now returns ALL paths including
 *     wall bounces. Overlay draws every segment.
 *  2. Multi-line display exactly like reference video:
 *       - Cyan  line = striker path (with wall bounces)
 *       - Orange line = each coin deflection path
 *       - Green dashed = coin → best pocket (snap mode)
 *  3. Auto-swipe: detects striker position from CV, performs swipe FROM striker
 *     in the aim direction using AccessibilityService via FloatingOverlayService.
 *  4. Striker touch fixed — correct radius-based hit area.
 *  5. No setShadowLayer (crashes Android 7 GPU drivers).
 */
public class AimOverlayView extends View {

    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_AI     = "AI";
    public static final String MODE_GOLDEN = "GOLDEN";
    public static final String MODE_LUCKY  = "LUCKY";
    public static final String MODE_ALL    = "ALL";

    private static final int TOUCH_AIM           = 0;
    private static final int TOUCH_DRAG_STRIKER  = 1;
    private static final int TOUCH_SET_BOARD     = 2;

    private final TrajectorySimulator simulator = new TrajectorySimulator();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private String  shotMode        = MODE_ALL;
    private boolean strikerMoveable = true;
    private boolean snapMode        = false;
    private boolean manualBoardMode = false;
    private boolean autoSwipeEnabled = false;

    private PointF  targetPos;
    private PointF  manualStriker;
    private int     touchMode    = TOUCH_AIM;

    // Manual board
    private boolean boardSet      = false;
    private RectF   manualBoard   = null;
    private int     dragCornerIdx = -1;
    private boolean drawingBoard  = false;
    private PointF  boardDragStart;

    // Snap/auto-aim
    private Coin   snapCoin;
    private PointF snapPocket;

    private GameState detected;
    private float marginOffsetX = 0f, marginOffsetY = 0f;
    private float sensitivity   = 1.0f;
    private final float dp;

    // Callback for manual board and auto-swipe
    public interface OnManualBoardListener { void onBoardSet(RectF board); void onBoardCleared(); }
    public interface OnAutoSwipeListener   { void onSwipe(float fromX, float fromY, float toX, float toY); }

    private OnManualBoardListener boardListener;
    private OnAutoSwipeListener   swipeListener;
    public void setOnManualBoardListener(OnManualBoardListener l) { boardListener = l; }
    public void setOnAutoSwipeListener(OnAutoSwipeListener l)     { swipeListener = l; }

    // ── Paints ────────────────────────────────────────────────────────────────

    // Striker line — bright cyan, thick
    private final Paint aimPaint;
    // Coin deflection line — orange
    private final Paint coinPathPaint;
    // Snap dashed line — lime green
    private final Paint snapLinePaint, snapCoinRingPaint;
    // Pocket glow/fill
    private final Paint pocketGlowPaint, pocketFillPaint;
    // Striker circle paints
    private final Paint strikerFillPaint, strikerRingPaint, dragHintPaint;
    // Coin fill paints
    private final Paint blackFillPaint, whiteFillPaint, redFillPaint, strikerCoinPaint;
    private final Paint coinOutlinePaint;
    // UI paints
    private final Paint targetPaint, textPaint, boardOutlinePaint;
    private final Paint boardHandlePaint, boardHandleFillPaint, boardRectPaint;
    // Arrow head paint
    private final Paint arrowPaint;

    public AimOverlayView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;

        // Cyan aim line — 4dp thick, rounded caps
        aimPaint       = stroke(Color.parseColor("#00E5FF"), 4.2f);

        // Orange coin deflection line
        coinPathPaint  = stroke(Color.parseColor("#FF8A00"), 3.5f);

        // Arrow head (same color as respective line, drawn separately)
        arrowPaint     = stroke(Color.parseColor("#00E5FF"), 3.0f);

        // Green dashed snap line
        snapLinePaint  = stroke(Color.parseColor("#22FF6E"), 2.8f);
        snapLinePaint.setPathEffect(new DashPathEffect(new float[]{10*dp, 6*dp}, 0));
        snapCoinRingPaint = stroke(Color.parseColor("#22FF6E"), 2.5f);

        // Pocket
        pocketGlowPaint = fill(Color.parseColor("#7722C55E"));
        pocketFillPaint = fill(Color.parseColor("#4422C55E"));

        // Striker
        strikerFillPaint = fill(Color.parseColor("#55FFFFFF"));
        strikerRingPaint = stroke(Color.parseColor("#FFD700"), 3.0f);
        dragHintPaint    = stroke(Color.parseColor("#88FFD700"), 1.2f);
        dragHintPaint.setPathEffect(new DashPathEffect(new float[]{5*dp, 4*dp}, 0));

        // Coins
        blackFillPaint   = fill(Color.parseColor("#CC111111"));
        whiteFillPaint   = fill(Color.parseColor("#88E8E8E8"));
        redFillPaint     = fill(Color.parseColor("#CCFF2244"));
        strikerCoinPaint = fill(Color.parseColor("#883399FF"));
        coinOutlinePaint = stroke(Color.parseColor("#AAFFFFFF"), 1.5f);

        // UI
        targetPaint = stroke(Color.WHITE, 1.8f); targetPaint.setAlpha(210);
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE); textPaint.setTextSize(13*dp);
        textPaint.setShadowLayer(3*dp, 0, 0, Color.BLACK);

        boardOutlinePaint = stroke(Color.parseColor("#88FFD700"), 1.5f);
        boardOutlinePaint.setPathEffect(new DashPathEffect(new float[]{8*dp, 6*dp}, 0));
        boardHandlePaint     = stroke(Color.parseColor("#00E5FF"), 2.5f);
        boardHandleFillPaint = fill(Color.parseColor("#CC00E5FF"));
        boardRectPaint       = stroke(Color.parseColor("#8800E5FF"), 2.0f);
        boardRectPaint.setPathEffect(new DashPathEffect(new float[]{10*dp, 6*dp}, 0));

        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void setShotMode(String m)            { shotMode = m; postInvalidate(); }
    public void setMarginOffset(float dx,float dy){ marginOffsetX=dx; marginOffsetY=dy; postInvalidate(); }
    public void setSensitivity(float v)          { sensitivity=Math.max(0.3f,Math.min(v,3f)); postInvalidate(); }
    public void setStrikerMoveable(boolean m)    { strikerMoveable=m; postInvalidate(); }
    public void setSnapMode(boolean on)          { snapMode=on; clearSnap(); postInvalidate(); }
    public void setAutoSwipeEnabled(boolean on)  { autoSwipeEnabled=on; }

    public void setManualBoardMode(boolean on) {
        manualBoardMode = on;
        if (!on && !boardSet) clearManualBoard();
        postInvalidate();
    }

    public void clearManualBoard() {
        manualBoard=null; boardSet=false; drawingBoard=false; dragCornerIdx=-1;
        if (boardListener!=null) boardListener.onBoardCleared();
        postInvalidate();
    }

    public void setDetectedState(GameState s) {
        detected = s;
        if (touchMode != TOUCH_DRAG_STRIKER) manualStriker = null;
        // Auto-aim: if snap mode, pick best shot automatically
        if (snapMode && s != null && s.striker != null && !s.coins.isEmpty()) {
            autoAimBestShot(s);
            // Auto-swipe: perform the swipe from detected striker position
            if (autoSwipeEnabled && targetPos != null && s.striker != null) {
                triggerAutoSwipe(s.striker.pos, targetPos);
            }
        }
        postInvalidate();
    }

    private void clearSnap() { snapCoin=null; snapPocket=null; }

    // ── Auto-aim ───────────────────────────────────────────────────────────────

    private void autoAimBestShot(GameState s) {
        PointF sp = effectiveStriker(s);
        if (sp==null || s.pockets.isEmpty()) return;
        Coin bc=null; PointF bp=null;
        int bb=Integer.MAX_VALUE; float bd=Float.MAX_VALUE;
        for (Coin coin : s.coins) {
            if (coin.color==Coin.COLOR_STRIKER) continue;
            for (PointF pocket : s.pockets) {
                int blocked = countBlockers(coin, pocket, s);
                float d = dist(coin.pos.x,coin.pos.y,pocket.x,pocket.y);
                if (blocked<bb||(blocked==bb&&d<bd)) { bb=blocked; bd=d; bc=coin; bp=pocket; }
            }
        }
        if (bc!=null && bp!=null) applySnap(bc, s.pockets, sp, s.striker.radius);
    }

    // ── Auto-swipe ─────────────────────────────────────────────────────────────

    private long lastSwipeMs = 0;
    private static final long SWIPE_COOLDOWN_MS = 1500;

    private void triggerAutoSwipe(PointF strikerPos, PointF target) {
        long now = System.currentTimeMillis();
        if (now - lastSwipeMs < SWIPE_COOLDOWN_MS) return;
        lastSwipeMs = now;
        // Direction from striker toward target
        float dx = target.x - strikerPos.x;
        float dy = target.y - strikerPos.y;
        float len = (float) Math.sqrt(dx*dx+dy*dy);
        if (len < 10) return;
        // Swipe starts at striker center, ends 120dp in aim direction
        float swipeLen = 120 * dp;
        float toX = strikerPos.x + (dx/len) * swipeLen;
        float toY = strikerPos.y + (dy/len) * swipeLen;
        if (swipeListener != null) {
            swipeListener.onSwipe(strikerPos.x, strikerPos.y, toX, toY);
        }
    }

    // ── Touch ──────────────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        float ex=ev.getX(), ey=ev.getY();
        GameState s = currentState();
        PointF strikerPos = effectiveStriker(s);

        if (manualBoardMode) return handleBoardTouch(ev, ex, ey, ev.getPointerCount());

        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN: {
                // Hit test striker with 2.5× radius
                if (strikerMoveable && strikerPos != null) {
                    float r = (s != null && s.striker != null) ? s.striker.radius : 22*dp;
                    if (dist(ex, ey, strikerPos.x, strikerPos.y) < r * 2.5f) {
                        touchMode = TOUCH_DRAG_STRIKER;
                        manualStriker = new PointF(ex, ey);
                        clearSnap(); postInvalidate(); return true;
                    }
                }
                touchMode = TOUCH_AIM;
                if (snapMode && s != null && strikerPos != null) {
                    Coin nearby = findNearestCoin(s.coins, ex, ey);
                    if (nearby != null) {
                        applySnap(nearby, s.pockets, strikerPos,
                                s.striker!=null ? s.striker.radius : 18*dp);
                        postInvalidate(); return true;
                    }
                }
                clearSnap();
                targetPos = new PointF(ex+marginOffsetX, ey+marginOffsetY);
                postInvalidate(); return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (touchMode==TOUCH_DRAG_STRIKER) {
                    manualStriker = new PointF(ex, ey);
                } else if (!snapMode) {
                    targetPos = new PointF(ex+marginOffsetX, ey+marginOffsetY);
                    clearSnap();
                }
                postInvalidate(); return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                touchMode = TOUCH_AIM;
                postInvalidate(); return true;
            }
        }
        return super.onTouchEvent(ev);
    }

    private boolean handleBoardTouch(MotionEvent ev, float ex, float ey, int pc) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (boardSet && manualBoard != null) {
                    dragCornerIdx = nearestCorner(ex, ey);
                    if (dragCornerIdx >= 0) { postInvalidate(); return true; }
                }
                drawingBoard=true; boardDragStart=new PointF(ex,ey);
                manualBoard=new RectF(ex,ey,ex,ey); boardSet=false; postInvalidate(); return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (drawingBoard && pc>=2)
                    manualBoard=makeRect(ev.getX(0),ev.getY(0),ev.getX(1),ev.getY(1));
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (dragCornerIdx>=0 && boardSet && manualBoard!=null) moveCorner(dragCornerIdx,ex,ey);
                else if (drawingBoard) {
                    manualBoard = pc>=2
                            ? makeRect(ev.getX(0),ev.getY(0),ev.getX(1),ev.getY(1))
                            : makeRect(boardDragStart.x,boardDragStart.y,ex,ey);
                }
                postInvalidate(); return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                if (drawingBoard && manualBoard!=null && manualBoard.width()>60*dp) {
                    boardSet=true; drawingBoard=false; dragCornerIdx=-1;
                    if (boardListener!=null) boardListener.onBoardSet(new RectF(manualBoard));
                }
                dragCornerIdx=-1; postInvalidate(); return true;
            }
        }
        return true;
    }

    // ── Draw ───────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        GameState s = currentState();
        if (s == null) s = synthFallback();

        if (manualBoardMode) drawManualBoardUI(canvas);

        RectF boardRect = (manualBoard!=null&&boardSet) ? manualBoard
                        : (s!=null ? s.board : null);
        if (boardRect != null) canvas.drawRect(boardRect, boardOutlinePaint);

        if (s == null || s.striker == null) {
            drawHint(canvas, manualBoardMode
                    ? "Drag to draw board rect  •  Drag corners to adjust"
                    : "Waiting for board detection…");
            return;
        }

        // Pockets
        for (PointF p : s.pockets) canvas.drawCircle(p.x, p.y, 14*dp, pocketFillPaint);

        // Coins
        for (Coin c : s.coins) {
            Paint fill = c.color==Coin.COLOR_BLACK  ? blackFillPaint
                       : c.color==Coin.COLOR_RED    ? redFillPaint
                       : c.color==Coin.COLOR_STRIKER ? strikerCoinPaint
                                                     : whiteFillPaint;
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, fill);
            canvas.drawCircle(c.pos.x, c.pos.y, c.radius, coinOutlinePaint);
            if (snapMode && snapCoin!=null
                    && c.pos.x==snapCoin.pos.x && c.pos.y==snapCoin.pos.y) {
                canvas.drawCircle(c.pos.x, c.pos.y, c.radius+5*dp, snapCoinRingPaint);
            }
        }

        // Snap guide: coin → pocket (green dashed)
        if (snapMode && snapCoin!=null && snapPocket!=null) {
            canvas.drawLine(snapCoin.pos.x, snapCoin.pos.y,
                    snapPocket.x, snapPocket.y, snapLinePaint);
            canvas.drawCircle(snapPocket.x, snapPocket.y, 20*dp, pocketGlowPaint);
        }

        // Striker
        PointF sp = effectiveStriker(s);
        float  sr = s.striker.radius;
        canvas.drawCircle(sp.x, sp.y, sr, strikerFillPaint);
        canvas.drawCircle(sp.x, sp.y, sr, strikerRingPaint);
        if (strikerMoveable) canvas.drawCircle(sp.x, sp.y, sr*2.3f, dragHintPaint);

        if (targetPos == null) {
            drawHint(canvas, snapMode ? "Snap ON — tap a coin or wait for auto-aim"
                                      : "Tap board to aim  •  Drag striker to move");
            return;
        }

        // Target crosshair
        float cr=9*dp, cl=14*dp;
        canvas.drawCircle(targetPos.x, targetPos.y, cr, targetPaint);
        canvas.drawLine(targetPos.x-cl,targetPos.y,targetPos.x+cl,targetPos.y,targetPaint);
        canvas.drawLine(targetPos.x,targetPos.y-cl,targetPos.x,targetPos.y+cl,targetPaint);

        // ── Simulate & draw ALL paths ──────────────────────────────────────────
        RectF simBoard = boardRect!=null ? boardRect : new RectF(0,0,getWidth(),getHeight());
        Coin tempStriker = new Coin(sp.x, sp.y, sr, Coin.COLOR_STRIKER, true);

        List<TrajectorySimulator.PathSegment> paths = simulator.simulate(
                tempStriker, targetPos, s.coins, s.pockets, simBoard, sensitivity);

        boolean drewAnything = false;
        for (TrajectorySimulator.PathSegment seg : paths) {
            if (!shouldDraw(seg)) continue;
            Paint paint = (seg.kind == 0) ? aimPaint : coinPathPaint;
            drawPolylineWithArrow(canvas, seg.points, paint);
            drewAnything = true;
            if (seg.enteredPocket && !seg.points.isEmpty()) {
                PointF end = seg.points.get(seg.points.size()-1);
                canvas.drawCircle(end.x, end.y, 22*dp, pocketGlowPaint);
            }
        }

        // Always draw at least the basic direction line so something always shows
        if (!drewAnything) {
            canvas.drawLine(sp.x, sp.y, targetPos.x, targetPos.y, aimPaint);
            drawArrowHead(canvas, sp.x, sp.y, targetPos.x, targetPos.y, aimPaint);
        }

        drawAngleLabel(canvas, sp, targetPos);

        if (snapMode) {
            Paint lp = new Paint(textPaint);
            lp.setColor(Color.parseColor("#22FF6E")); lp.setTextSize(11*dp);
            canvas.drawText(autoSwipeEnabled ? "AUTO-PLAY ON" : "SNAP ON — tap coin", 24*dp, 72*dp, lp);
        }
        if (manualBoardMode) {
            Paint mp = new Paint(textPaint);
            mp.setColor(Color.parseColor("#00E5FF")); mp.setTextSize(11*dp);
            canvas.drawText(boardSet ? "Board locked — drag corners to adjust"
                    : "Drag to draw board rect", 24*dp, 90*dp, mp);
        }
    }

    /** Draw polyline AND an arrowhead at the last segment end */
    private void drawPolylineWithArrow(Canvas canvas, List<PointF> pts, Paint paint) {
        if (pts == null || pts.size() < 2) return;
        for (int i = 1; i < pts.size(); i++) {
            PointF a = pts.get(i-1), b = pts.get(i);
            canvas.drawLine(a.x, a.y, b.x, b.y, paint);
        }
        // Arrow at last point
        PointF last   = pts.get(pts.size()-1);
        PointF before = pts.get(pts.size()-2);
        drawArrowHead(canvas, before.x, before.y, last.x, last.y, paint);
    }

    /** Draw a small arrowhead at (tx,ty) pointing from (fx,fy) */
    private void drawArrowHead(Canvas canvas, float fx, float fy, float tx, float ty, Paint paint) {
        float dx = tx - fx, dy = ty - fy;
        float len = (float) Math.sqrt(dx*dx+dy*dy);
        if (len < 1) return;
        float ux = dx/len, uy = dy/len;
        float ahl = 14*dp, ahw = 7*dp;
        // Two wing points
        float lx = tx - ux*ahl - uy*ahw;
        float ly = ty - uy*ahl + ux*ahw;
        float rx = tx - ux*ahl + uy*ahw;
        float ry = ty - uy*ahl - ux*ahw;
        canvas.drawLine(tx, ty, lx, ly, paint);
        canvas.drawLine(tx, ty, rx, ry, paint);
    }

    private void drawManualBoardUI(Canvas canvas) {
        if (manualBoard == null) {
            Paint hp = new Paint(textPaint); hp.setColor(Color.parseColor("#00E5FF"));
            canvas.drawText("Drag to outline the carrom board", 24*dp, 56*dp, hp);
            return;
        }
        canvas.drawRect(manualBoard, boardRectPaint);
        float[][] corners = {
            {manualBoard.left, manualBoard.top},
            {manualBoard.right,manualBoard.top},
            {manualBoard.left, manualBoard.bottom},
            {manualBoard.right,manualBoard.bottom}
        };
        float hr = 14*dp;
        for (float[] c : corners) {
            canvas.drawCircle(c[0], c[1], hr, boardHandleFillPaint);
            canvas.drawCircle(c[0], c[1], hr, boardHandlePaint);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private boolean shouldDraw(TrajectorySimulator.PathSegment seg) {
        if (seg==null||seg.points==null||seg.points.size()<2) return false;
        switch (shotMode) {
            case MODE_DIRECT: return seg.wallBounces==0;
            case MODE_AI:     return seg.kind==0 || seg.coinHits>0;
            case MODE_GOLDEN: return seg.wallBounces<=1;
            case MODE_LUCKY:  return seg.wallBounces<=2;
            default:          return true;
        }
    }

    private void drawAngleLabel(Canvas canvas, PointF from, PointF to) {
        double angle = Math.toDegrees(Math.atan2(to.y-from.y, to.x-from.x));
        if (angle < 0) angle += 360;
        canvas.drawText(String.format("%.1f°", angle),
                (from.x+to.x)/2f+10*dp, (from.y+to.y)/2f-10*dp, textPaint);
    }

    private void drawHint(Canvas canvas, String msg) { canvas.drawText(msg, 24*dp, 60*dp, textPaint); }

    private GameState currentState() { return detected; }

    private PointF effectiveStriker(GameState s) {
        if (manualStriker != null) return manualStriker;
        if (s != null && s.striker != null) return s.striker.pos;
        return null;
    }

    private Coin findNearestCoin(List<Coin> coins, float tx, float ty) {
        if (coins==null) return null;
        Coin best=null; float bestD=Float.MAX_VALUE;
        for (Coin c : coins) {
            float d=dist(tx,ty,c.pos.x,c.pos.y);
            if (d < c.radius*3f && d < bestD) { bestD=d; best=c; }
        }
        return best;
    }

    private void applySnap(Coin coin, List<PointF> pockets, PointF strikerPos, float strikerR) {
        if (pockets==null||pockets.isEmpty()) return;
        PointF bp=null; int bb=Integer.MAX_VALUE; float bd=Float.MAX_VALUE;
        for (PointF p : pockets) {
            int blocked=countBlockers(coin,p,detected);
            float d=dist(coin.pos.x,coin.pos.y,p.x,p.y);
            if (blocked<bb||(blocked==bb&&d<bd)) { bb=blocked; bd=d; bp=p; }
        }
        if (bp==null) return;
        float cpDx=bp.x-coin.pos.x, cpDy=bp.y-coin.pos.y;
        float cpLen=(float)Math.sqrt(cpDx*cpDx+cpDy*cpDy);
        if (cpLen<1) return;
        float nx=cpDx/cpLen, ny=cpDy/cpLen;
        float cx=coin.pos.x-nx*(coin.radius+strikerR);
        float cy=coin.pos.y-ny*(coin.radius+strikerR);
        float dirX=cx-strikerPos.x, dirY=cy-strikerPos.y;
        float dLen=(float)Math.sqrt(dirX*dirX+dirY*dirY);
        if (dLen<1) return;
        float FAR=3500f;
        targetPos=new PointF(strikerPos.x+(dirX/dLen)*FAR+marginOffsetX,
                             strikerPos.y+(dirY/dLen)*FAR+marginOffsetY);
        snapCoin=coin; snapPocket=bp;
    }

    private int countBlockers(Coin coin, PointF pocket, GameState s) {
        if (s==null) return 0;
        float ax=coin.pos.x,ay=coin.pos.y,bx=pocket.x,by=pocket.y;
        float abx=bx-ax,aby=by-ay,abL2=abx*abx+aby*aby;
        int count=0;
        for (Coin o : s.coins) {
            if (o.pos.x==coin.pos.x&&o.pos.y==coin.pos.y) continue;
            float apx=o.pos.x-ax,apy=o.pos.y-ay;
            float t=abL2>0?(apx*abx+apy*aby)/abL2:0f;
            t=Math.max(0f,Math.min(1f,t));
            float cx2=ax+t*abx,cy2=ay+t*aby;
            if (dist(o.pos.x,o.pos.y,cx2,cy2)<o.radius) count++;
        }
        return count;
    }

    private RectF makeRect(float x1,float y1,float x2,float y2) {
        return new RectF(Math.min(x1,x2),Math.min(y1,y2),Math.max(x1,x2),Math.max(y1,y2));
    }

    private int nearestCorner(float tx, float ty) {
        if (manualBoard==null) return -1;
        float[][] corners={{manualBoard.left,manualBoard.top},{manualBoard.right,manualBoard.top},
                           {manualBoard.left,manualBoard.bottom},{manualBoard.right,manualBoard.bottom}};
        float thresh=28*dp; int best=-1; float bestD=Float.MAX_VALUE;
        for (int i=0;i<4;i++) {
            float d=dist(tx,ty,corners[i][0],corners[i][1]);
            if (d<thresh&&d<bestD) { bestD=d; best=i; }
        }
        return best;
    }

    private void moveCorner(int idx, float x, float y) {
        if (manualBoard==null) return;
        switch(idx) {
            case 0: manualBoard.left=x; manualBoard.top=y; break;
            case 1: manualBoard.right=x; manualBoard.top=y; break;
            case 2: manualBoard.left=x; manualBoard.bottom=y; break;
            case 3: manualBoard.right=x; manualBoard.bottom=y; break;
        }
        if (boardListener!=null) boardListener.onBoardSet(new RectF(manualBoard));
    }

    private GameState synthFallback() {
        if (getWidth()==0||getHeight()==0) return null;
        GameState s=new GameState();
        int w=getWidth(),h=getHeight();
        float side=Math.min(w,h)*0.90f,cx=w/2f,cy=h/2f;
        s.board=new RectF(cx-side/2f,cy-side/2f,cx+side/2f,cy+side/2f);
        float in=side*0.04f;
        s.pockets.add(new PointF(s.board.left+in, s.board.top+in));
        s.pockets.add(new PointF(s.board.right-in,s.board.top+in));
        s.pockets.add(new PointF(s.board.left+in, s.board.bottom-in));
        s.pockets.add(new PointF(s.board.right-in,s.board.bottom-in));
        s.coins=new ArrayList<>();
        float r=14*dp;
        int[][] grid={{-2,-1},{-1,-1},{0,-1},{1,-1},{2,-1},
                      {-1,0},{0,0},{1,0},{-1,1},{0,1},{1,1}};
        for (int[] g : grid) {
            int color=(Math.abs(g[0]+g[1])%2==0)?Coin.COLOR_BLACK:Coin.COLOR_WHITE;
            s.coins.add(new Coin(cx+g[0]*38*dp,cy+g[1]*38*dp,r,color,false));
        }
        s.coins.add(new Coin(cx,cy,r,Coin.COLOR_RED,false));
        s.striker=new Coin(cx,s.board.bottom-75*dp,19*dp,Coin.COLOR_STRIKER,true);
        return s;
    }

    private static float dist(float x1,float y1,float x2,float y2) {
        float dx=x2-x1,dy=y2-y1; return (float)Math.sqrt(dx*dx+dy*dy);
    }
    private Paint stroke(int color,float w) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color);
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(w*dp);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND); return p;
    }
    private Paint fill(int color) {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(color);
        p.setStyle(Paint.Style.FILL); return p;
    }
}
