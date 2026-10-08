package es.digimap.thor;

import java.io.File;

final class StartupFlow {
  static final int WELCOME = 0, BIOS = 1, GAME = 2, READY = 3, HOME = 4;

  static boolean biosReady(String path) {
    File file = new File(path);
    return file.isFile() && file.length() == 524288;
  }

  static boolean gameReady(String path) {
    File file = new File(path);
    if (!file.isFile() || file.length() == 0) return false;
    if (!path.endsWith(".cue")) return true;
    File disc = new File(file.getParentFile(), "disc.bin");
    return disc.isFile() && disc.length() > 0;
  }

  static int step(int requested, boolean completed, boolean bios, boolean game) {
    if (completed && bios && game) return HOME;
    if (requested <= WELCOME && !bios && !game) return WELCOME;
    if (!bios) return BIOS;
    if (!game) return GAME;
    return READY;
  }

  static File latestState(File folder, String engine) {
    if (folder == null) return null;
    if (!"swan-gl".equals(engine) && !"swan-sw".equals(engine) && !"pcsx".equals(engine))
      return null;
    File auto = new File(folder, engine + "-auto.state");
    File quick = new File(folder, engine + "-quick.state");
    boolean hasAuto = auto.isFile() && auto.length() > 0;
    boolean hasQuick = quick.isFile() && quick.length() > 0;
    if (!hasAuto) return hasQuick ? quick : null;
    return !hasQuick || auto.lastModified() > quick.lastModified() ? auto : quick;
  }
}
