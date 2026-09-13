package local.nova.diagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;

/** Lightweight, static glass, water and meadow shapes; no background animation or assets. */
final class AeroTheme {
    static final int INK = 0xff123c56;
    static final int MUTED = 0xff3c687c;
    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }

    static Drawable glass(Context c) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xeaffffff, 0xc8eafaff});
        d.setCornerRadius(dp(c, 22));
        d.setStroke(dp(c, 1), 0xf0ffffff);
        return d;
    }

    private static Drawable button(Context c, boolean green, boolean muted, boolean focus) {
        int[] colors = muted ? new int[]{0xf7ffffff, 0xe8d9edf5}
                : green ? new int[]{0xffa5eb89, 0xff238757, 0xff08704c}
                : new int[]{0xff85dbfa, 0xff127cab, 0xff07598f};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors);
        d.setCornerRadius(dp(c, 13));
        d.setStroke(dp(c, focus ? 2 : 1), focus ? 0xff17537b : 0xdfffffff);
        return d;
    }

    static Drawable buttonStates(Context c, boolean green, boolean muted) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{-android.R.attr.state_enabled}, button(c, false, true, false));
        d.addState(new int[]{android.R.attr.state_pressed}, button(c, green, muted, true));
        d.addState(new int[]{android.R.attr.state_focused}, button(c, green, muted, true));
        d.addState(new int[]{}, button(c, green, muted, false));
        return d;
    }

    static final class Sky extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            p.setAlpha(255);
            p.setShader(new LinearGradient(0, 0, w*.8f, h,
                    new int[]{0xff0784c8, 0xff66cfea, 0xffd9f7f8}, null, Shader.TileMode.CLAMP));
            c.drawRect(getBounds(), p);
            p.setShader(new RadialGradient(w*.84f, h*.04f, w*.58f,
                    new int[]{0xdfffffff, 0x00ffffff}, null, Shader.TileMode.CLAMP));
            c.drawRect(getBounds(), p);
            p.setShader(null);
            p.setColor(0x42ffffff);
            c.drawOval(new RectF(w*.47f, -h*.12f, w*1.13f, h*.27f), p);
            p.setColor(0x24ffffff);
            c.drawOval(new RectF(-w*.15f, h*.15f, w*.61f, h*.38f), p);
            Path land = new Path();
            land.moveTo(0, h*.82f);
            land.cubicTo(w*.3f, h*.63f, w*.53f, h*1.1f, w, h*.82f);
            land.lineTo(w, h); land.lineTo(0, h); land.close();
            p.setAlpha(255);
            p.setShader(new LinearGradient(0, h*.74f, 0, h,
                    new int[]{0xff94d763, 0xff34a880}, null, Shader.TileMode.CLAMP));
            c.drawPath(land, p);
            p.setShader(null);
            p.setColor(0x33ffffff);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(1, w/650));
            for (int i=0; i<7; i++) {
                float x=w*(.06f+.145f*i), y=h*(.1f+.07f*(i%3));
                c.drawCircle(x, y, w*(.015f+.006f*(i%3)), p);
            }
            p.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.OPAQUE; }
    }

    static final class Orb extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        @Override public void draw(Canvas canvas) {
            RectF b = new RectF(getBounds());
            float r = Math.min(b.width(), b.height())*.46f, x=b.centerX(), y=b.centerY();
            p.setShader(null); p.setColor(0x25215770);
            canvas.drawOval(new RectF(x-r*.7f,y+r*.83f,x+r*.7f,y+r*1.06f),p);
            p.setAlpha(255);
            p.setShader(new RadialGradient(x-r*.3f,y-r*.5f,r*1.6f,
                    new int[]{0xffd4ffff,0xff44c5ef,0xff086cae},new float[]{0,.45f,1},Shader.TileMode.CLAMP));
            canvas.drawCircle(x,y,r,p);
            canvas.save();
            Path clip = new Path(); clip.addCircle(x,y,r,Path.Direction.CW); canvas.clipPath(clip);
            Path hill = new Path(); hill.moveTo(x-r,y+r*.24f);
            hill.cubicTo(x-r*.12f,y-r*.3f,x+r*.22f,y+r*.7f,x+r,y+r*.08f);
            hill.lineTo(x+r,y+r);hill.lineTo(x-r,y+r);hill.close();
            p.setShader(new LinearGradient(x,y,x,y+r,new int[]{0xffb0eb6b,0xff168e65},null,Shader.TileMode.CLAMP));
            canvas.drawPath(hill,p);
            p.setShader(new LinearGradient(x,y-r,x,y,new int[]{0xeaffffff,0x0affffff},null,Shader.TileMode.CLAMP));
            canvas.drawOval(new RectF(x-r*.84f,y-r*.93f,x+r*.84f,y+r*.03f),p);
            canvas.restore();
            p.setShader(null);p.setColor(0xcfffffff);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(r*.015f);
            canvas.drawCircle(x,y,r,p);p.setStyle(Paint.Style.FILL);
        }
        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter filter) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
