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
    File latest = null;
    File[] states =
        folder.listFiles(
            file -> {
              String name = file.getName();
              return name.equals(engine + "-auto.state")
                  || name.equals(engine + "-quick.state")
                  || name.matches(engine + "-imported-[0-9]+\\.state");
            });
    if (states == null) return null;
    for (File state : states) {
      if (state.isFile()
          && state.length() > 0
          && state.length() <= 64L * 1024 * 1024
          && (latest == null || state.lastModified() > latest.lastModified())) latest = state;
    }
    return latest;
  }
}
