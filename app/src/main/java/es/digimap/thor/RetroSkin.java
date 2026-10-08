package es.digimap.thor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/** Original pixel frames and pictograms for the companion's game menu. */
final class RetroSkin {
  static final int INK = 0xff27394b, PAPER = 0xfff0f4e4, BLUE = 0xff4267a0, GOLD = 0xffffd36b;

  static final class Frame extends Drawable {
    private final Paint paint = new Paint();
    private final int fill;
    private final float pixel;

    Frame(int fill, float density) {
      this.fill = fill;
      pixel = Math.max(1, Math.round(density));
    }

    @Override
    public void draw(Canvas c) {
      Rect b = getBounds();
      float p = pixel;
      paint.setColor(INK);
      c.drawRect(b.left + p, b.top, b.right - p, b.bottom, paint);
      c.drawRect(b.left, b.top + p, b.right, b.bottom - p, paint);
      paint.setColor(0xff96add0);
      c.drawRect(b.left + 2 * p, b.top + p, b.right - 2 * p, b.bottom - p, paint);
      c.drawRect(b.left + p, b.top + 2 * p, b.right - p, b.bottom - 2 * p, paint);
      paint.setColor(fill);
      c.drawRect(b.left + 3 * p, b.top + 3 * p, b.right - 3 * p, b.bottom - 3 * p, paint);
      paint.setColor(0x18ffffff);
      for (float y = b.top + 4 * p; y < b.bottom - 4 * p; y += 4 * p)
        c.drawRect(b.left + 3 * p, y, b.right - 3 * p, y + p, paint);
    }

    @Override
    public void setAlpha(int alpha) {}

    @Override
    public void setColorFilter(ColorFilter filter) {}

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static final class Icon extends Drawable {
    private final int kind;
    private final Paint p = new Paint();

    Icon(int kind) {
      this.kind = kind;
    }

    @Override
    public void draw(Canvas c) {
      Rect b = getBounds();
      c.save();
      c.translate(b.left, b.top);
      c.scale(b.width() / 16f, b.height() / 16f);
      p.setColor(INK);
      if (kind == 1) {
        rect(c, 3, 6, 13, 15);
        rect(c, 5, 2, 11, 7);
        p.setColor(GOLD);
        rect(c, 4, 7, 12, 14);
        rect(c, 6, 3, 10, 5);
        p.setColor(0xffcc6b4a);
        rect(c, 6, 10, 10, 13);
      } else if (kind == 2) {
        rect(c, 1, 2, 15, 14);
        p.setColor(0xff8dc78b);
        rect(c, 2, 3, 14, 13);
        p.setColor(0xff6ba9cb);
        rect(c, 3, 3, 6, 9);
        rect(c, 6, 7, 11, 10);
        p.setColor(GOLD);
        rect(c, 10, 4, 13, 7);
      } else if (kind == 3) {
        rect(c, 10, 1, 13, 4);
        rect(c, 8, 3, 11, 6);
        rect(c, 6, 5, 9, 8);
        rect(c, 4, 7, 7, 10);
        rect(c, 2, 9, 5, 12);
        p.setColor(0xfff0f4e4);
        rect(c, 9, 2, 12, 4);
        rect(c, 7, 4, 10, 6);
        p.setColor(GOLD);
        rect(c, 2, 7, 4, 10);
        rect(c, 4, 10, 7, 12);
      } else if (kind == 4) {
        rect(c, 5, 1, 11, 3);
        rect(c, 3, 3, 13, 12);
        rect(c, 5, 12, 11, 15);
        p.setColor(0xfff0f4e4);
        rect(c, 5, 3, 11, 13);
        rect(c, 4, 5, 12, 11);
        p.setColor(0xffcc6b4a);
        rect(c, 5, 5, 7, 7);
        rect(c, 9, 9, 11, 11);
      } else if (kind == 5) {
        rect(c, 1, 5, 15, 12);
        rect(c, 3, 3, 13, 14);
        p.setColor(0xff96add0);
        rect(c, 3, 5, 13, 12);
        p.setColor(INK);
        rect(c, 4, 6, 6, 11);
        rect(c, 3, 8, 8, 9);
        p.setColor(0xffcc6b4a);
        rect(c, 10, 7, 12, 9);
      } else {
        rect(c, 3, 1, 13, 15);
        rect(c, 1, 5, 15, 11);
        p.setColor(0xff6ba9cb);
        rect(c, 4, 2, 12, 14);
        p.setColor(0xffd5e5ba);
        rect(c, 4, 4, 12, 10);
        p.setColor(INK);
        rect(c, 5, 5, 7, 7);
        rect(c, 9, 5, 11, 7);
        rect(c, 6, 8, 10, 9);
        p.setColor(GOLD);
        rect(c, 6, 11, 10, 13);
      }
      c.restore();
    }

    private void rect(Canvas c, int l, int t, int r, int b) {
      c.drawRect(l, t, r, b, p);
    }

    @Override
    public void setAlpha(int a) {}

    @Override
    public void setColorFilter(ColorFilter f) {}

    @Override
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  static final class Meter extends View {
    private final Paint p = new Paint();
    private final String label;
    private final int color;
    private int value, max;

    Meter(Context ctx, String label, int color) {
      super(ctx);
      this.label = label;
      this.color = color;
    }

    void update(int value, int max) {
      if (this.value != value || this.max != max) {
        this.value = value;
        this.max = max;
        invalidate();
      }
    }

    @Override
    protected void onDraw(Canvas c) {
      float d = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight();
      p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
      p.setTextSize(12 * d);
      p.setColor(INK);
      c.drawText(label, 4 * d, 13 * d, p);
      p.setTextAlign(Paint.Align.RIGHT);
      c.drawText(max > 0 ? value + " / " + max : "—", w - 4 * d, 13 * d, p);
      p.setTextAlign(Paint.Align.LEFT);
      p.setColor(INK);
      c.drawRect(3 * d, 19 * d, w - 3 * d, h - 2 * d, p);
      p.setColor(0xffd4ddcd);
      c.drawRect(5 * d, 21 * d, w - 5 * d, h - 4 * d, p);
      p.setColor(color);
      c.drawRect(
          5 * d,
          21 * d,
          5 * d + (w - 10 * d) * (max > 0 ? Math.max(0, Math.min(1, value / (float) max)) : 0),
          h - 4 * d,
          p);
    }
  }
}
