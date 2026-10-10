package es.digimap.thor;

import android.content.SharedPreferences;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.util.HashMap;
import java.util.Map;

final class ControllerBindings {
  static final String[] LABELS = {
    "✕", "□", "Select", "Start", "↑", "↓", "←", "→", "○", "△", "L1", "R1", "L2", "R2"
  };
  private static final int[] DEFAULT_KEYS = {
    KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X,
    KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_START,
    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y,
    KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
    KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2
  };
  private static final int[] AXES = {
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_LTRIGGER,
    MotionEvent.AXIS_RTRIGGER,
    MotionEvent.AXIS_BRAKE,
    MotionEvent.AXIS_GAS
  };
  private final SharedPreferences preferences;
  private final Map<String, Integer> pressed = new HashMap<>();

  ControllerBindings(SharedPreferences preferences) {
    this.preferences = preferences;
  }

  static String device(InputDevice device) {
    return device == null ? "builtin" : device.getDescriptor();
  }

  static String keySource(int code) {
    return "key:" + code;
  }

  static String axisSource(int axis, boolean positive) {
    return "axis:" + axis + ":" + (positive ? "+" : "-");
  }

  private static String preference(String device, String source) {
    return "controls." + device + "." + source;
  }

  int binding(String device, String source) {
    String key = preference(device, source);
    if (preferences.contains(key)) {
      int value = preferences.getInt(key, -1);
      return value >= 0 && value < LABELS.length ? value : -1;
    }
    for (int index = 0; index < DEFAULT_KEYS.length; index++)
      if (source.equals(keySource(DEFAULT_KEYS[index]))) return index;
    if (source.equals(axisSource(MotionEvent.AXIS_HAT_X, false))
        || source.equals(axisSource(MotionEvent.AXIS_X, false))) return 6;
    if (source.equals(axisSource(MotionEvent.AXIS_HAT_X, true))
        || source.equals(axisSource(MotionEvent.AXIS_X, true))) return 7;
    if (source.equals(axisSource(MotionEvent.AXIS_HAT_Y, false))
        || source.equals(axisSource(MotionEvent.AXIS_Y, false))) return 4;
    if (source.equals(axisSource(MotionEvent.AXIS_HAT_Y, true))
        || source.equals(axisSource(MotionEvent.AXIS_Y, true))) return 5;
    return -1;
  }

  void assign(String device, String source, int target) {
    if (target < 0 || target >= LABELS.length) return;
    SharedPreferences.Editor editor = preferences.edit();
    for (int code : DEFAULT_KEYS) clearBinding(editor, device, keySource(code), target);
    for (int axis : AXES) {
      clearBinding(editor, device, axisSource(axis, false), target);
      clearBinding(editor, device, axisSource(axis, true), target);
    }
    String prefix = "controls." + device + ".";
    for (String key : preferences.getAll().keySet())
      if (key.startsWith(prefix) && binding(device, key.substring(prefix.length())) == target)
        editor.putInt(key, -1);
    editor.putInt(preference(device, source), target).apply();
    clear();
  }

  private void clearBinding(
      SharedPreferences.Editor editor, String device, String source, int target) {
    if (binding(device, source) == target) editor.putInt(preference(device, source), -1);
  }

  void reset(String device) {
    SharedPreferences.Editor editor = preferences.edit();
    String prefix = "controls." + device + ".";
    for (String key : preferences.getAll().keySet()) if (key.startsWith(prefix)) editor.remove(key);
    editor.apply();
    clear();
  }

  String description(String device, int target) {
    for (String key : preferences.getAll().keySet()) {
      String prefix = "controls." + device + ".";
      if (key.startsWith(prefix)) {
        String source = key.substring(prefix.length());
        if (binding(device, source) == target) return sourceLabel(source);
      }
    }
    for (int code : DEFAULT_KEYS)
      if (binding(device, keySource(code)) == target) return sourceLabel(keySource(code));
    for (int axis : AXES)
      for (boolean positive : new boolean[] {false, true})
        if (binding(device, axisSource(axis, positive)) == target)
          return sourceLabel(axisSource(axis, positive));
    return AppLanguage.text("text_unassigned");
  }

  private static String sourceLabel(String source) {
    String[] parts = source.split(":");
    try {
      if (parts.length == 2 && parts[0].equals("key"))
        return KeyEvent.keyCodeToString(Integer.parseInt(parts[1])).replace("KEYCODE_", "");
      if (parts.length == 3 && parts[0].equals("axis"))
        return MotionEvent.axisToString(Integer.parseInt(parts[1])).replace("AXIS_", "")
            + " "
            + parts[2];
    } catch (NumberFormatException ignored) {
    }
    return AppLanguage.text("text_unassigned");
  }

  int key(KeyEvent event) {
    String device = device(event.getDevice()), source = keySource(event.getKeyCode());
    update(device, source, event.getAction() == KeyEvent.ACTION_DOWN);
    return buttons();
  }

  int motion(MotionEvent event) {
    String device = device(event.getDevice());
    for (int axis : AXES) {
      float value = event.getAxisValue(axis);
      update(device, axisSource(axis, false), value < -.4f);
      update(device, axisSource(axis, true), value > .4f);
    }
    return buttons();
  }

  static String captureAxis(MotionEvent event) {
    for (int axis : AXES) {
      float value = event.getAxisValue(axis);
      if (Math.abs(value) > .65f) return axisSource(axis, value > 0);
    }
    return null;
  }

  private void update(String device, String source, boolean down) {
    String key = preference(device, source);
    int target = binding(device, source);
    if (down && target >= 0) pressed.put(key, 1 << target);
    else pressed.remove(key);
  }

  private int buttons() {
    int mask = 0;
    for (int value : pressed.values()) mask |= value;
    return mask;
  }

  void clear() {
    pressed.clear();
  }
}
