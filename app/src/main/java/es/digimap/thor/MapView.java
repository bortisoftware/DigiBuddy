package es.digimap.thor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

/** World coordinates are independent of game resolution and display aspect. */
public final class MapView extends View {
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  private GameData.Snapshot s;
  private int minX, minY, maxX, maxY;
  private float left, top, cell;
  private SpriteAtlas atlas;
  private byte[] ram;
  private GameData.Profile profile;

  interface EnemyListener {
    void selected(GameData.Enemy enemy);
  }

  private EnemyListener listener;
  private float touchX, touchY;
  private boolean touchingEnemy;

  void onEnemy(EnemyListener listener) {
    this.listener = listener;
    setFocusable(false);
  }

  private GameData.Enemy enemyAt(float x, float y) {
    if (s == null || !s.valid || cell <= 0) return null;
    float radius = 24 * getResources().getDisplayMetrics().density;
    float best = radius * radius;
    GameData.Enemy selected = null;
    for (GameData.Enemy enemy : mapDigimon()) {
      float dx = x - (left + (enemy.x - minX + .5f) * cell);
      float dy = y - (top + (enemy.y - minY + .5f) * cell);
      float distance = dx * dx + dy * dy;
      if (distance < best) {
        best = distance;
        selected = enemy;
      }
    }
    return selected;
  }

  private java.util.List<GameData.Enemy> mapDigimon() {
    java.util.List<GameData.Enemy> markers = new java.util.ArrayList<>(s.enemies);
    markers.addAll(s.recruitMarkers);
    return markers;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        touchX = event.getX();
        touchY = event.getY();
        touchingEnemy = enemyAt(touchX, touchY) != null;
        return touchingEnemy;
      case MotionEvent.ACTION_UP:
        if (!touchingEnemy) return false;
        touchingEnemy = false;
        float slop = 12 * getResources().getDisplayMetrics().density;
        if (Math.abs(event.getX() - touchX) <= slop && Math.abs(event.getY() - touchY) <= slop) {
          GameData.Enemy enemy = enemyAt(event.getX(), event.getY());
          if (enemy != null && listener != null) listener.selected(enemy);
          performClick();
        }
        return true;
      case MotionEvent.ACTION_CANCEL:
        touchingEnemy = false;
        return true;
      default:
        return touchingEnemy;
    }
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  public MapView(Context ctx) {
    super(ctx);
  }

  public void update(GameData.Snapshot next) {
    s = next;
    invalidate();
  }

  void sprites(SpriteAtlas source, byte[] memory, GameData.Profile layout) {
    atlas = source;
    ram = memory;
    profile = layout;
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(0xffd4dfc5);
    if (s == null || !s.valid || s.collision == null) return;
    minX = 99;
    minY = 99;
    maxX = 0;
    maxY = 0;
    for (int y = 0; y < 100; y++)
      for (int x = 0; x < 100; x++)
        if ((s.collision[x + y * 100] & 128) == 0) {
          minX = Math.min(minX, x);
          minY = Math.min(minY, y);
          maxX = Math.max(maxX, x);
          maxY = Math.max(maxY, y);
        }
    // Include every marker before fitting the map; NPCs can stand outside walkable cells.
    for (GameData.Enemy foe : mapDigimon()) {
      minX = Math.min(minX, foe.x);
      maxX = Math.max(maxX, foe.x);
      minY = Math.min(minY, foe.y);
      maxY = Math.max(maxY, foe.y);
    }
    for (GameData.Exit exit : s.exits) {
      minX = Math.min(minX, exit.minX);
      maxX = Math.max(maxX, exit.maxX);
      minY = Math.min(minY, exit.minY);
      maxY = Math.max(maxY, exit.maxY);
    }
    for (GameData.Marker marker : s.markers) {
      minX = Math.min(minX, marker.x);
      maxX = Math.max(maxX, marker.x);
      minY = Math.min(minY, marker.y);
      maxY = Math.max(maxY, marker.y);
    }
    minX = Math.max(0, Math.min(minX, s.x) - 2);
    maxX = Math.min(99, Math.max(maxX, s.x) + 2);
    minY = Math.max(0, Math.min(minY, s.y) - 2);
    maxY = Math.min(99, Math.max(maxY, s.y) + 2);
    float d = getResources().getDisplayMetrics().density, pad = 10 * d;
    cell =
        Math.min(
            (getWidth() - 2 * pad) / (maxX - minX + 1),
            (getHeight() - 2 * pad) / (maxY - minY + 1));
    if (cell <= 0) return;
    left = (getWidth() - (maxX - minX + 1) * cell) / 2;
    top = (getHeight() - (maxY - minY + 1) * cell) / 2;
    for (int y = minY; y <= maxY; y++)
      for (int x = minX; x <= maxX; x++) {
        int value = s.collision[x + y * 100] & 255;
        p.setColor((value & 128) != 0 ? 0xff738875 : 0xffb9ce9a);
        c.drawRect(
            left + (x - minX) * cell,
            top + (y - minY) * cell,
            left + (x - minX + 1) * cell + .5f,
            top + (y - minY + 1) * cell + .5f,
            p);
      }
    for (GameData.Exit exit : s.exits) {
      p.setColor(0xff258446);
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(Math.max(3 * d, cell * .5f));
      c.drawRect(
          left + (exit.minX - minX) * cell,
          top + (exit.minY - minY) * cell,
          left + (exit.maxX - minX + 1) * cell,
          top + (exit.maxY - minY + 1) * cell,
          p);
      p.setStyle(Paint.Style.FILL);
    }
    for (GameData.Marker marker : s.markers) {
      float x = left + (marker.x - minX + .5f) * cell,
          y = top + (marker.y - minY + .5f) * cell,
          r = 12 * d;
      if (marker.kind == 0) {
        p.setColor(0xfff4f8f1);
        c.drawRect(x - 14 * d, y - 12 * d, x + 14 * d, y + 12 * d, p);
        p.setColor(0xff276fa4);
        c.drawRect(x - 12 * d, y - 10 * d, x + 12 * d, y + 10 * d, p);
        p.setColor(0xffffffff);
        p.setTextSize(12 * d);
        p.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        p.setTextAlign(Paint.Align.CENTER);
        c.drawText("WC", x, y + 4 * d, p);
        p.setTextAlign(Paint.Align.LEFT);
      } else if (marker.kind == 2) {
        p.setColor(0xff4d3428);
        c.drawRect(x - 11 * d, y - 9 * d, x + 11 * d, y + 9 * d, p);
        p.setColor(0xffc08b41);
        c.drawRect(x - 9 * d, y - 7 * d, x + 9 * d, y + 7 * d, p);
        p.setColor(0xff4d3428);
        c.drawRect(x - 10 * d, y - 1 * d, x + 10 * d, y + 1 * d, p);
        p.setColor(0xffffdd73);
        c.drawRect(x - 3 * d, y - 3 * d, x + 3 * d, y + 4 * d, p);
      } else {
        p.setColor(0xfffff1be);
        c.drawCircle(x, y, r, p);
        Bitmap icon = atlas == null ? null : atlas.item(marker.itemId, ram, profile);
        if (icon != null) {
          p.setFilterBitmap(false);
          c.drawBitmap(
              icon, null, new RectF(x - r + 2 * d, y - r + 2 * d, x + r - 2 * d, y + r - 2 * d), p);
        } else {
          p.setColor(0xffd6a83c);
          c.drawCircle(x, y, 6 * d, p);
        }
      }
    }
    for (GameData.Enemy foe : mapDigimon()) {
      float x = left + (foe.x - minX + .5f) * cell, y = top + (foe.y - minY + .5f) * cell;
      Bitmap image =
          atlas == null
              ? null
              : foe.recruitType > 0
                  ? atlas.mon(foe.recruitType, ram, profile)
                  : atlas.enemy(foe.type, ram, profile);
      float radius = image == null ? 7 * d : 16 * d;
      p.setColor(
          foe.recruitType > 0
              ? 0xff447bb7
              : foe.difficulty == 0 ? 0xff42c569 : foe.difficulty == 1 ? 0xffffc34a : 0xffb43b58);
      c.drawCircle(x, y, radius, p);
      if (image != null) {
        p.setColor(0xfff0f4e4);
        c.drawCircle(x, y, radius - 2 * d, p);
        p.setFilterBitmap(false);
        c.drawBitmap(image, null, new RectF(x - 14 * d, y - 14 * d, x + 14 * d, y + 14 * d), p);
      } else {
        p.setColor(0xff913d40);
        c.drawCircle(x, y, 4 * d, p);
      }
      if (foe.recruitType > 0) {
        p.setColor(0xffffd25f);
        c.drawCircle(x + 12 * d, y - 12 * d, 7 * d, p);
        p.setColor(0xff273951);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(12 * d);
        c.drawText("!", x + 12 * d, y - 8 * d, p);
        p.setTextAlign(Paint.Align.LEFT);
      }
    }
    float x = left + (s.x - minX + .5f) * cell, y = top + (s.y - minY + .5f) * cell;
    p.setColor(0xfff6f8eb);
    c.drawCircle(x, y, 7 * d, p);
    p.setColor(0xff277dc3);
    c.drawCircle(x, y, 5 * d, p);
  }
}
