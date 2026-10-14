package es.digimap.thor;

/** Companion palette only: game pixels, sprites and semantic meter colors remain untouched. */
final class AppAppearance {
  static final String PREFERENCE = "darkMode";
  private static volatile boolean dark;

  private AppAppearance() {}

  static void setDark(boolean enabled) {
    dark = enabled;
  }

  static boolean isDark() {
    return dark;
  }

  static int text(int color) {
    if (!dark) return color;
    if (color == RetroSkin.INK) return 0xffedf3fa;
    if (color == 0xff315a9b) return 0xff8fc5ff;
    if (color == 0xff62758a) return 0xffbacbde;
    return color;
  }

  static int surface(int color) {
    if (!dark) return color;
    if (color == RetroSkin.PAPER) return 0xff18283c;
    if (color == 0xffe1e8d6) return 0xff243951;
    if (color == RetroSkin.GOLD) return 0xff614b22;
    if (color == 0xff4267a0) return 0xff0d1828;
    return color;
  }
}
