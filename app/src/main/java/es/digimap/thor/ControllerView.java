package es.digimap.thor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class ControllerView extends View {
  interface Selection {
    void button(int target);
  }

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Selection selection;
  private int selected = -1;
  // Coordinates share the same controller space for drawing and hit testing.
  private static final float[][] POSITIONS = {
    {330, 145}, {290, 105}, {165, 120}, {225, 120}, {80, 72}, {80, 148}, {42, 110}, {118, 110},
    {370, 105}, {330, 65}, {65, 20}, {335, 20}, {130, 20}, {270, 20}
  };

  ControllerView(Context context, Selection selection) {
    super(context);
    this.selection = selection;
    setContentDescription("Mando PlayStation: toca el botón que quieres remapear");
  }

  void selected(int target) {
    selected = target;
    invalidate();
  }

  @Override
  protected void onMeasure(int width, int height) {
    int size = MeasureSpec.getSize(width);
    setMeasuredDimension(size, Math.round(size * .52f));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float scale = getWidth() / 410f;
    canvas.save();
    canvas.scale(scale, scale);
    paint.setColor(0xffb7bdc8);
    canvas.drawRoundRect(new RectF(12, 42, 398, 198), 45, 45, paint);
    paint.setColor(0xff626978);
    canvas.drawRoundRect(new RectF(33, 59, 127, 160), 18, 18, paint);
    canvas.drawCircle(330, 105, 68, paint);
    for (int index = 0; index < POSITIONS.length; index++) {
      float x = POSITIONS[index][0], y = POSITIONS[index][1];
      paint.setColor(index == selected ? RetroSkin.GOLD : 0xff343b4c);
      canvas.drawRoundRect(new RectF(x - 23, y - 19, x + 23, y + 19), 9, 9, paint);
      paint.setColor(index == selected ? RetroSkin.INK : 0xffffffff);
      paint.setTextAlign(Paint.Align.CENTER);
      paint.setTextSize(index == 2 || index == 3 ? 11 : 18);
      canvas.drawText(ControllerBindings.LABELS[index], x, y + 6, paint);
    }
    canvas.restore();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      float x = event.getX() * 410 / getWidth(), y = event.getY() * 410 / getWidth();
      for (int index = 0; index < POSITIONS.length; index++)
        if (Math.abs(x - POSITIONS[index][0]) <= 25 && Math.abs(y - POSITIONS[index][1]) <= 22) {
          selection.button(index);
          performClick();
          break;
        }
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
