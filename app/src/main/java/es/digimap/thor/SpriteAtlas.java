package es.digimap.thor;

import android.graphics.Bitmap;
import java.io.File;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Decodes original menu sprites from the user's disc; assets stay outside the APK. */
final class SpriteAtlas {
  private File disc;
  private volatile boolean closed;
  private final ConcurrentHashMap<Integer, Bitmap> enemyImages = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Integer, Boolean> requestedEnemies = new ConcurrentHashMap<>();
  private final ExecutorService portraits =
      Executors.newSingleThreadExecutor(
          action -> {
            Thread thread = new Thread(action, "DigiBuddy-portraits");
            thread.setDaemon(true);
            return thread;
          });
  private final int[] vram = new int[1024 * 512];
  private final HashMap<Integer, Bitmap> cache = new HashMap<>();

  private static int u16(byte[] b, int at) {
    return (b[at] & 255) | ((b[at + 1] & 255) << 8);
  }

  private static int i32(byte[] b, int at) {
    return u16(b, at) | (u16(b, at + 2) << 16);
  }

  static SpriteAtlas load(File game) throws Exception {
    if (game.getName().endsWith(".cue")) game = new File(game.getParentFile(), "disc.bin");
    byte[] data = DiscIdentity.file(game, "ETCDAT/ETCTIM.BIN");
    if (data == null) return null;
    SpriteAtlas atlas = new SpriteAtlas();
    atlas.disc = game;
    int pos = 0;
    while (pos + 20 < data.length) {
      if (i32(data, pos) != 16) {
        pos += 4;
        continue;
      }
      int flags = i32(data, pos + 4);
      if (flags < 0 || flags > 9) {
        pos += 4;
        continue;
      }
      int block = pos + 8;
      for (int j = 0; j < ((flags & 8) != 0 ? 2 : 1); j++) {
        if (block < 0 || block > data.length - 12) throw new Exception("TIM incompleto");
        int size = i32(data, block),
            x = u16(data, block + 4),
            y = u16(data, block + 6),
            w = u16(data, block + 8),
            h = u16(data, block + 10);
        if (size < 12
            || size > data.length - block
            || w == 0
            || h == 0
            || x + w > 1024
            || y + h > 512
            || 12L + (long) w * h * 2 > size) throw new Exception("TIM no válido");
        for (int row = 0; row < h; row++)
          for (int col = 0; col < w; col++)
            atlas.vram[(y + row) * 1024 + x + col] = u16(data, block + 12 + (row * w + col) * 2);
        block += size;
      }
      pos = block;
    }
    return atlas;
  }

  Bitmap enemy(int type, byte[] ram, GameData.Profile profile) {
    if (closed || type < 1 || type >= 180) return null;
    if (type <= 61) return mon(type, ram, profile);
    Bitmap image = enemyImages.get(type);
    if (image != null || requestedEnemies.putIfAbsent(type, true) != null) return image;
    try {
      ModelIconRenderer.Metadata metadata = ModelIconRenderer.metadata(type, ram, profile);
      portraits.execute(
          () -> {
            try {
              int[] pixels = ModelIconRenderer.render(disc, metadata);
              Bitmap portrait =
                  Bitmap.createBitmap(
                      pixels,
                      ModelIconRenderer.SIZE,
                      ModelIconRenderer.SIZE,
                      Bitmap.Config.ARGB_8888);
              synchronized (this) {
                if (!closed) enemyImages.put(type, portrait);
              }
            } catch (java.io.IOException ignored) {
              /* Keep the marker for unsupported models. */
            }
          });
    } catch (java.io.IOException | java.util.concurrent.RejectedExecutionException ignored) {
      // Missing/corrupt game files cannot prevent the map from being displayed.
    }
    return null;
  }

  synchronized void close() {
    closed = true;
    portraits.shutdownNow();
    enemyImages.clear();
    requestedEnemies.clear();
    cache.clear();
  }

  private Bitmap sprite(int u, int v, int w, int h, int clut, int pageX, int pageY) {
    int palette = ((clut >> 6) & 511) * 1024 + (clut & 63) * 16;
    if (w <= 0
        || h <= 0
        || u < 0
        || v < 0
        || pageX < 0
        || pageY < 0
        || pageX + (u + w - 1) / 4 >= 1024
        || pageY + v + h > 512
        || palette + 16 > vram.length) return null;
    int[] pixels = new int[w * h];
    for (int y = 0; y < h; y++)
      for (int x = 0; x < w; x++) {
        int word = vram[(pageY + v + y) * 1024 + pageX + (u + x) / 4];
        int value = vram[palette + ((word >> ((u + x) % 4 * 4)) & 15)];
        pixels[y * w + x] =
            value == 0
                ? 0
                : 0xff000000
                    | ((value & 31) * 255 / 31 << 16)
                    | (((value >> 5) & 31) * 255 / 31 << 8)
                    | ((value >> 10) & 31) * 255 / 31;
      }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888);
  }

  Bitmap mon(int type, byte[] ram, GameData.Profile profile) {
    if (type < 1
        || type > 61
        || ram == null
        || profile == null
        || !profile.addresses.containsKey("EVO_ICON_DATA")) return null;
    if (cache.containsKey(type)) return cache.get(type);
    int at = profile.at("EVO_ICON_DATA") + (type - 1) * 8;
    if (at < 0 || at > ram.length - 8) return null;
    int clutIndex = ram[at + 6] & 255;
    if (clutIndex > 4) return null;
    int clutAt = profile.at("EVO_ICON_CLUT") + clutIndex * 2;
    if (clutAt < 0 || clutAt > ram.length - 2) return null;
    int clut = u16(ram, clutAt);
    Bitmap image = sprite(ram[at + 4] & 255, ram[at + 5] & 255, 16, 16, clut, 512, 256);
    cache.put(type, image);
    return image;
  }

  Bitmap menu(int tab) {
    int id = 100 + tab;
    if (cache.containsKey(id)) return cache.get(id);
    int u = tab == 1 ? 0 : tab == 2 ? 40 : tab == 3 ? 0 : tab == 5 ? 40 : 0,
        v = tab == 3 ? 212 : tab == 2 ? 232 : 192;
    Bitmap image = sprite(u, v, 20, 20, ((tab == 1 || tab == 3 ? 508 : 509) << 6) | 16, 896, 256);
    cache.put(id, image);
    return image;
  }

  Bitmap item(int type, byte[] ram, GameData.Profile profile) {
    if (type < 0
        || type >= 128
        || ram == null
        || profile == null
        || !profile.addresses.containsKey("ITEM_ICON_PALETTE")) return null;
    int id = 300 + type;
    if (cache.containsKey(id)) return cache.get(id);
    int at = profile.at("ITEM_ICON_PALETTE") + type;
    if (at < 0 || at >= ram.length) return null;
    int palette = ram[at] & 255;
    if (palette >= 24) return null;
    Bitmap image =
        sprite((type % 16) * 16, (type / 16) * 16, 16, 16, ((488 + palette) << 6) | 14, 320, 0);
    cache.put(id, image);
    return image;
  }
}
