package es.digimap.thor;

import android.view.Surface;

public final class NativeCore {
  static {
    System.loadLibrary("digimap");
  }

  public static native String start(String library, String system, String saves, String game);

  public static native void surface(Surface surface);

  public static native void runFrame(int buttons);

  public static native short[] audio();

  public static native byte[] memory();

  public static native boolean writeRam(int address, int value, int bytes);

  public static native double fps();

  public static native double sampleRate();

  public static native void option(String key, String value);

  public static native boolean saveState(String path);

  public static native boolean loadState(String path);

  public static native boolean saveCard(String path);

  public static native void stop();
}
