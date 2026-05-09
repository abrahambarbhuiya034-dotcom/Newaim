package com.bitaim.carromaim.cv;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * TrajectorySimulator  v5
 *
 * Key changes:
 *  - Returns ALL paths (striker + every coin it hits), not just "best".
 *    This gives the multi-line look shown in the reference video.
 *  - Striker path always returned even when no coin is hit (wall-bounce lines).
 *  - PATH_EPSILON reduced so short-distance bounces still record points.
 *  - Wall-bounce segments draw correctly with direction changes.
 */
public class TrajectorySimulator {

    private static final float  DT            = 1f / 90f;
    private static final float  MAX_TIME      = 5f;
    private static final float  FRICTION      = 0.70f;
    private static final float  STRIKER_SPEED = 5000f;
    private static final float  STOP_SPEED    = 15f;
    private static final float  RESTITUTION   = 0.96f;
    private static final int    MAX_WALL_HITS = 5;
    private static final float  PATH_EPSILON  = 2f;   // smaller = more points recorded
    private static final float  FRICTION_DECAY = (float) Math.pow(FRICTION, DT);

    public static class PathSegment {
        public List<PointF> points     = new ArrayList<>();
        public int          kind;       // 0=striker, 1=white, 2=black, 3=queen
        public boolean      enteredPocket = false;
        public int          wallBounces   = 0;
        public int          coinHits      = 0;
        public float        score         = 0f;
    }

    private static class Body {
        PointF pos, vel;
        float  radius, mass;
        int    kind;
        boolean active = true, potted = false;
        PathSegment path = new PathSegment();
        int wallBounces = 0, coinHits = 0;
    }

    /**
     * Simulate and return ALL paths — striker + every coin that moves.
     * This produces the multi-line display seen in the reference video.
     */
    public List<PathSegment> simulate(
            Coin striker, PointF target,
            List<Coin> coins, List<PointF> pockets,
            RectF board, float sensitivity
    ) {
        if (striker == null || target == null || board == null) return new ArrayList<>();

        float dx  = target.x - striker.pos.x;
        float dy  = target.y - striker.pos.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1) return new ArrayList<>();

        float speed = STRIKER_SPEED * Math.max(0.3f, Math.min(sensitivity, 3.0f));

        List<Body> bodies = new ArrayList<>();
        Body s = makeBody(striker.pos.x, striker.pos.y, striker.radius, 1.2f, 0,
                dx / len * speed, dy / len * speed);
        bodies.add(s);

        if (coins != null) {
            for (Coin c : coins) {
                if (c.color == Coin.COLOR_STRIKER) continue; // skip striker duplicate
                int kind = c.color == Coin.COLOR_BLACK ? 2
                         : c.color == Coin.COLOR_RED   ? 3 : 1;
                bodies.add(makeBody(c.pos.x, c.pos.y, c.radius, 1.0f, kind, 0, 0));
            }
        }

        float pocketR = Math.max(board.width() * 0.045f, 30f);
        float t = 0;

        while (t < MAX_TIME) {
            t += DT;
            boolean anyMoving = false;

            for (Body b : bodies) {
                if (!b.active) continue;
                float sp = speed(b.vel);
                if (sp < STOP_SPEED) { b.vel.set(0, 0); continue; }
                anyMoving = true;

                b.pos.x += b.vel.x * DT;
                b.pos.y += b.vel.y * DT;
                b.vel.x *= FRICTION_DECAY;
                b.vel.y *= FRICTION_DECAY;

                bounceWalls(b, board);

                for (PointF p : pockets) {
                    if (dist(b.pos, p) < pocketR) {
                        b.potted = true;
                        b.active = false;
                        b.path.enteredPocket = true;
                        recordPoint(b, p.x, p.y);
                        break;
                    }
                }

                if (b.active) recordPoint(b, b.pos.x, b.pos.y);
            }

            int n = bodies.size();
            for (int i = 0; i < n; i++) {
                Body a = bodies.get(i);
                if (!a.active) continue;
                for (int j = i + 1; j < n; j++) {
                    Body bb = bodies.get(j);
                    if (!bb.active) continue;
                    float d = dist(a.pos, bb.pos);
                    if (d < (a.radius + bb.radius) && d > 0.001f) {
                        resolveElastic(a, bb, d);
                        a.coinHits++;
                        bb.coinHits++;
                    }
                }
            }

            if (!anyMoving) break;
        }

        // Return ALL bodies that moved (have >= 2 points), not just "best"
        List<PathSegment> out = new ArrayList<>();
        for (Body b : bodies) {
            b.path.wallBounces = b.wallBounces;
            b.path.kind        = b.kind;
            b.path.coinHits    = b.coinHits;
            float score = 0;
            if (b.potted)          score += 1000;
            if (b.coinHits > 0)    score += 200 * b.coinHits;
            if (b.wallBounces > 0) score +=  50 * b.wallBounces;
            b.path.score = score;
            if (b.path.points.size() >= 2) {
                out.add(b.path);
            }
        }

        // Always include striker path first, even if it has no coin hits
        // (so wall-bounce lines always show)
        return out;
    }

    private Body makeBody(float x,float y,float r,float mass,int kind,float vx,float vy) {
        Body b = new Body();
        b.pos = new PointF(x, y); b.vel = new PointF(vx, vy);
        b.radius = r; b.mass = mass; b.kind = kind; b.path.kind = kind;
        b.path.points.add(new PointF(x, y));
        return b;
    }

    private void bounceWalls(Body b, RectF board) {
        boolean hit = false;
        if (b.pos.x - b.radius < board.left) {
            b.pos.x = board.left + b.radius;
            b.vel.x = Math.abs(b.vel.x) * 0.90f; hit = true;
        } else if (b.pos.x + b.radius > board.right) {
            b.pos.x = board.right - b.radius;
            b.vel.x = -Math.abs(b.vel.x) * 0.90f; hit = true;
        }
        if (b.pos.y - b.radius < board.top) {
            b.pos.y = board.top + b.radius;
            b.vel.y = Math.abs(b.vel.y) * 0.90f; hit = true;
        } else if (b.pos.y + b.radius > board.bottom) {
            b.pos.y = board.bottom - b.radius;
            b.vel.y = -Math.abs(b.vel.y) * 0.90f; hit = true;
        }
        if (hit) { b.wallBounces++; if (b.wallBounces >= MAX_WALL_HITS) b.active = false; }
    }

    private void recordPoint(Body b, float x, float y) {
        List<PointF> pts = b.path.points;
        if (pts.isEmpty()) { pts.add(new PointF(x, y)); return; }
        PointF last = pts.get(pts.size() - 1);
        if (dist(last.x, last.y, x, y) >= PATH_EPSILON) pts.add(new PointF(x, y));
    }

    private static void resolveElastic(Body a, Body b, float d) {
        float nx = (b.pos.x - a.pos.x) / d, ny = (b.pos.y - a.pos.y) / d;
        float overlap = (a.radius + b.radius) - d;
        float totalM  = a.mass + b.mass;
        a.pos.x -= nx * overlap * (b.mass / totalM);
        a.pos.y -= ny * overlap * (b.mass / totalM);
        b.pos.x += nx * overlap * (a.mass / totalM);
        b.pos.y += ny * overlap * (a.mass / totalM);
        float rvx = b.vel.x - a.vel.x, rvy = b.vel.y - a.vel.y;
        float vn  = rvx * nx + rvy * ny;
        if (vn > 0) return;
        float j = -(1 + RESTITUTION) * vn / (1f / a.mass + 1f / b.mass);
        float ix = j * nx, iy = j * ny;
        a.vel.x -= ix / a.mass; a.vel.y -= iy / a.mass;
        b.vel.x += ix / b.mass; b.vel.y += iy / b.mass;
    }

    private static float speed(PointF v) { return (float) Math.sqrt(v.x*v.x + v.y*v.y); }
    private static float dist(PointF a, PointF b) { return dist(a.x,a.y,b.x,b.y); }
    private static float dist(float x1,float y1,float x2,float y2) {
        float dx=x2-x1,dy=y2-y1; return (float)Math.sqrt(dx*dx+dy*dy);
    }
}
