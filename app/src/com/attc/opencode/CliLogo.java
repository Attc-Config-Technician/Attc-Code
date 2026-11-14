package com.attc.opencode;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Vector marks for the three CLIs offered on the start screen. */
final class CliLogo extends View {

    static final int ATTC = 0;
    static final int CLAUDE = 1;
    static final int ANTIGRAVITY = 2;
    static final int CODEX = 3;
    static final int KIMI = 4;
    static final int TERMINAL = 5;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    CliLogo(Context context, int kind) {
        super(context);
        this.kind = kind;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float size = Math.min(w, h);
        float left = (w - size) / 2f;
        float top = (h - size) / 2f;
        paint.setStyle(Paint.Style.FILL);
        if (kind == ATTC) {
            rect.set(left, top, left + size, top + size);
            paint.setColor(0xFF10251C);
            canvas.drawRoundRect(rect, size * 0.26f, size * 0.26f, paint);
            paint.setColor(0xFF00E5A0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.075f);
            canvas.drawRoundRect(rect, size * 0.26f, size * 0.26f, paint);
            paint.setStyle(Paint.Style.FILL);
            Path chevron = new Path();
            chevron.moveTo(left + size * 0.30f, top + size * 0.34f);
            chevron.lineTo(left + size * 0.46f, top + size * 0.50f);
            chevron.lineTo(left + size * 0.30f, top + size * 0.66f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.075f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(chevron, paint);
            paint.setStyle(Paint.Style.FILL);
            rect.set(left + size * 0.54f, top + size * 0.62f,
                    left + size * 0.74f, top + size * 0.70f);
            canvas.drawRoundRect(rect, size * 0.03f, size * 0.03f, paint);
        } else if (kind == CLAUDE) {
            paint.setColor(0xFFD97757);
            float cx = left + size / 2f;
            float cy = top + size / 2f;
            Path star = new Path();
            int rays = 11;
            for (int i = 0; i < rays * 2; i++) {
                double angle = Math.PI * i / rays - Math.PI / 2;
                float radius = (i % 2 == 0) ? size * 0.46f : size * 0.16f;
                float x = cx + (float) Math.cos(angle) * radius;
                float y = cy + (float) Math.sin(angle) * radius;
                if (i == 0) {
                    star.moveTo(x, y);
                } else {
                    star.lineTo(x, y);
                }
            }
            star.close();
            canvas.drawPath(star, paint);
        } else if (kind == CODEX) {
            // Neutral geometric knot: three interlocking ovals rotated around
            // the centre. Deliberately not a copy of any wordmark.
            rect.set(left, top, left + size, top + size);
            paint.setColor(0xFF141414);
            canvas.drawRoundRect(rect, size * 0.26f, size * 0.26f, paint);
            paint.setColor(0xFFEDEDED);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.055f);
            float cx = left + size / 2f;
            float cy = top + size / 2f;
            float hw = size * 0.30f;
            float hh = size * 0.13f;
            for (int i = 0; i < 3; i++) {
                canvas.save();
                canvas.rotate(i * 60f, cx, cy);
                rect.set(cx - hw, cy - hh, cx + hw, cy + hh);
                canvas.drawOval(rect, paint);
                canvas.restore();
            }
            paint.setStyle(Paint.Style.FILL);
        } else if (kind == KIMI) {
            // Moon mark: a crescent cut out of a disc plus a small spark.
            rect.set(left, top, left + size, top + size);
            paint.setColor(0xFF0E1424);
            canvas.drawRoundRect(rect, size * 0.26f, size * 0.26f, paint);
            float cx = left + size * 0.46f;
            float cy = top + size * 0.50f;
            paint.setColor(0xFF8FA6FF);
            canvas.drawCircle(cx, cy, size * 0.28f, paint);
            paint.setColor(0xFF0E1424);
            canvas.drawCircle(cx + size * 0.15f, cy - size * 0.07f,
                    size * 0.25f, paint);
            paint.setColor(0xFFB9C6FF);
            Path spark = new Path();
            spark.moveTo(left + size * 0.74f, top + size * 0.26f);
            spark.lineTo(left + size * 0.79f, top + size * 0.36f);
            spark.lineTo(left + size * 0.89f, top + size * 0.41f);
            spark.lineTo(left + size * 0.79f, top + size * 0.46f);
            spark.lineTo(left + size * 0.74f, top + size * 0.56f);
            spark.lineTo(left + size * 0.69f, top + size * 0.46f);
            spark.lineTo(left + size * 0.59f, top + size * 0.41f);
            spark.lineTo(left + size * 0.69f, top + size * 0.36f);
            spark.close();
            canvas.drawPath(spark, paint);
        } else if (kind == TERMINAL) {
            // Plain shell: a window outline with a chevron and cursor.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.06f);
            paint.setColor(0xFF9FB0BF);
            rect.set(left + size * 0.08f, top + size * 0.12f,
                    left + size * 0.92f, top + size * 0.88f);
            canvas.drawRoundRect(rect, size * 0.14f, size * 0.14f, paint);
            float ly = top + size * 0.34f;
            canvas.drawLine(left + size * 0.08f, ly,
                    left + size * 0.92f, ly, paint);
            paint.setStyle(Paint.Style.FILL);
            Path chevron = new Path();
            chevron.moveTo(left + size * 0.25f, top + size * 0.50f);
            chevron.lineTo(left + size * 0.37f, top + size * 0.62f);
            chevron.lineTo(left + size * 0.25f, top + size * 0.74f);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.06f);
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.drawPath(chevron, paint);
            paint.setStyle(Paint.Style.FILL);
            rect.set(left + size * 0.44f, top + size * 0.69f,
                    left + size * 0.58f, top + size * 0.75f);
            canvas.drawRoundRect(rect, size * 0.02f, size * 0.02f, paint);
        } else {
            paint.setColor(0xFF7AA2FF);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(size * 0.07f);
            float cx = left + size / 2f;
            float cy = top + size / 2f;
            canvas.drawCircle(cx, cy, size * 0.16f, paint);
            rect.set(cx - size * 0.46f, cy - size * 0.22f,
                    cx + size * 0.46f, cy + size * 0.22f);
            canvas.save();
            canvas.rotate(-28f, cx, cy);
            canvas.drawOval(rect, paint);
            canvas.restore();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xFFB9CCFF);
            canvas.drawCircle(cx + size * 0.36f, cy - size * 0.12f, size * 0.055f, paint);
        }
    }

    static int accent(int kind) {
        if (kind == CLAUDE) {
            return 0xFFD97757;
        }
        if (kind == ANTIGRAVITY) {
            return 0xFF7AA2FF;
        }
        if (kind == TERMINAL) {
            return 0xFF9FB0BF;
        }
        if (kind == CODEX) {
            return 0xFFD6D6D6;
        }
        if (kind == KIMI) {
            return 0xFF8FA6FF;
        }
        return 0xFF00E5A0;
    }

    static String name(int kind) {
        if (kind == CLAUDE) {
            return "Claude Code";
        }
        if (kind == ANTIGRAVITY) {
            return "Antigravity";
        }
        if (kind == TERMINAL) {
            return "Terminal";
        }
        if (kind == CODEX) {
            return "Codex";
        }
        if (kind == KIMI) {
            return "Kimi Code";
        }
        return "Attc Code";
    }

    static String detail(int kind) {
        if (kind == CLAUDE) {
            return "Anthropic CLI via the Zen gateway";
        }
        if (kind == ANTIGRAVITY) {
            return "Google Antigravity agent CLI";
        }
        if (kind == TERMINAL) {
            return "Plain Ubuntu shell, no CLI";
        }
        if (kind == CODEX) {
            return "OpenAI Codex coding CLI";
        }
        if (kind == KIMI) {
            return "Moonshot Kimi coding CLI";
        }
        return "opencode with the Zen models";
    }

    static String key(int kind) {
        if (kind == CLAUDE) {
            return "claude";
        }
        if (kind == ANTIGRAVITY) {
            return "agy";
        }
        if (kind == TERMINAL) {
            return "terminal";
        }
        if (kind == CODEX) {
            return "codex";
        }
        if (kind == KIMI) {
            return "kimi";
        }
        return "opencode";
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (48 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(size, size);
    }
}
