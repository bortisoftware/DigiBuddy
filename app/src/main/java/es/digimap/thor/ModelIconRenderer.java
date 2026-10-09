package es.digimap.thor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Produces a small transparent portrait from a creature's original MMD/TIM files. Binary format
 * reference: Operation-Decoded/DW1ModelConverter (MIT, SydMontague). No model, texture or portrait
 * is bundled or saved by this renderer.
 */
final class ModelIconRenderer {
  static final int SIZE = 64;

  static final class Metadata {
    final int type;
    final String file;
    final byte[] skeleton;

    Metadata(int type, String file, byte[] skeleton) {
      this.type = type;
      this.file = file;
      this.skeleton = skeleton;
    }
  }

  private static final class Reader {
    final byte[] data;

    Reader(byte[] data) throws IOException {
      if (data == null) throw new IOException("Archivo ausente");
      this.data = data;
    }

    void range(int at, int size) throws IOException {
      if (at < 0 || size < 0 || at > data.length - size) throw new IOException("Modelo incompleto");
    }

    int u8(int at) throws IOException {
      range(at, 1);
      return data[at] & 255;
    }

    int u16(int at) throws IOException {
      range(at, 2);
      return u8(at) | u8(at + 1) << 8;
    }

    int s16(int at) throws IOException {
      return (short) u16(at);
    }

    int i32(int at) throws IOException {
      range(at, 4);
      return u16(at) | u16(at + 2) << 16;
    }
  }

  static Metadata metadata(int type, byte[] ram, GameData.Profile profile) throws IOException {
    if (type < 1
        || type >= 180
        || profile == null
        || ram == null
        || ram.length != 2097152
        || !profile.addresses.containsKey("DIGIMON_SKELETONS")
        || !profile.addresses.containsKey("PTR_DIGIMON_FILE_NAMES"))
      throw new IOException("Modelo no disponible");
    Reader r = new Reader(ram);
    int bones = r.i32(profile.at("DIGIMON_DATA") + type * 52 + 20);
    if (bones < 1 || bones > 64) throw new IOException("Esqueleto no válido");
    int name = pointer(r.i32(profile.at("PTR_DIGIMON_FILE_NAMES") + type * 4));
    r.range(name, 8);
    int length = 0;
    while (length < 8 && ram[name + length] != 0) length++;
    String file = new String(ram, name, length, StandardCharsets.US_ASCII);
    if (!file.matches("[A-Z0-9_]{1,8}")) throw new IOException("Nombre de modelo no válido");
    int skeleton = pointer(r.i32(profile.at("DIGIMON_SKELETONS") + type * 4));
    r.range(skeleton, bones * 2);
    return new Metadata(type, file, Arrays.copyOfRange(ram, skeleton, skeleton + bones * 2));
  }

  private static int pointer(int raw) throws IOException {
    if ((raw & 0xffe00000) != 0x80000000) throw new IOException("Puntero no válido");
    return raw & 0x1fffff;
  }

  static int[] render(File disc, Metadata metadata) throws IOException {
    Reader m =
        new Reader(
            DiscIdentity.file(
                disc, "CHDAT/MMD" + metadata.type / 30 + "/" + metadata.file + ".MMD"));
    Reader texture =
        new Reader(
            DiscIdentity.fileRange(disc, "CHDAT/ALLTIM.TIM", metadata.type * 0x4800, 0x4800));
    return render(m, texture, metadata.skeleton);
  }

  private static final class Texture {
    final Reader r;
    final int palette, paletteX, paletteY, paletteW, paletteH, pixels, x, y, w, h, mode;

    Texture(Reader r) throws IOException {
      this.r = r;
      if (r.i32(0) != 16) throw new IOException("TIM no válido");
      int flags = r.i32(4);
      mode = flags & 7;
      if ((flags & 8) == 0 || (mode != 0 && mode != 1))
        throw new IOException("Formato TIM no válido");
      int clutSize = r.i32(8);
      paletteX = r.u16(12);
      paletteY = r.u16(14);
      paletteW = r.u16(16);
      paletteH = r.u16(18);
      palette = 20;
      if (paletteW < 16
          || paletteW > 256
          || paletteH < 1
          || paletteH > 32
          || clutSize < 12 + paletteW * paletteH * 2) throw new IOException("Paleta no válida");
      r.range(8, clutSize);
      int at = 8 + clutSize, size = r.i32(at);
      x = r.u16(at + 4);
      y = r.u16(at + 6);
      w = r.u16(at + 8);
      h = r.u16(at + 10);
      pixels = at + 12;
      if (w < 1 || w > 256 || h < 1 || h > 256 || size < 12 + w * h * 2)
        throw new IOException("Textura no válida");
      r.range(at, size);
    }

    int color(double u, double v, int clut, int page) throws IOException {
      int px =
          (int) Math.floor(u) + (page & 15) * 64 * (mode == 0 ? 4 : 2) - x * (mode == 0 ? 4 : 2);
      int py = (int) Math.floor(v) + ((page >> 4) & 1) * 256 - y;
      int clutX = (clut & 63) * 16 - paletteX, clutY = (clut >> 6 & 511) - paletteY;
      int width = w * (mode == 0 ? 4 : 2);
      if (px < 0 || px >= width || py < 0 || py >= h || clutX < 0 || clutY < 0 || clutY >= paletteH)
        return 0;
      int packed = r.u16(pixels + (py * w + px / (mode == 0 ? 4 : 2)) * 2);
      int index = mode == 0 ? (packed >> ((px % 4) * 4)) & 15 : (packed >> ((px % 2) * 8)) & 255;
      if (clutX + index >= paletteW) return 0;
      int c = r.u16(palette + (clutY * paletteW + clutX + index) * 2);
      return c == 0
          ? 0
          : 0xff000000
              | ((c & 31) * 255 / 31 << 16)
              | ((c >> 5 & 31) * 255 / 31 << 8)
              | (c >> 10 & 31) * 255 / 31;
    }
  }

  private static final class Face {
    double[][] vertex = new double[3][];
    int[] u = new int[3], v = new int[3];
    int clut, page, color;
    boolean textured;
  }

  private static double[] identity() {
    return new double[] {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
  }

  private static double[] multiply(double[] a, double[] b) {
    double[] result = new double[16];
    for (int row = 0; row < 4; row++)
      for (int col = 0; col < 4; col++)
        for (int k = 0; k < 4; k++) result[row * 4 + col] += a[row * 4 + k] * b[k * 4 + col];
    return result;
  }

  private static double[] transform(double[] m, int x, int y, int z) {
    return new double[] {
      m[0] * x + m[1] * y + m[2] * z + m[3],
      m[4] * x + m[5] * y + m[6] * z + m[7],
      m[8] * x + m[9] * y + m[10] * z + m[11]
    };
  }

  private static double[] local(Reader m, int at, boolean scale) throws IOException {
    double sx = 1, sy = 1, sz = 1;
    if (scale) {
      sx = m.s16(at) / 4096.0;
      sy = m.s16(at + 2) / 4096.0;
      sz = m.s16(at + 4) / 4096.0;
      at += 6;
    }
    double ax = m.s16(at) * Math.PI / 2048,
        ay = m.s16(at + 2) * Math.PI / 2048,
        az = m.s16(at + 4) * Math.PI / 2048;
    double cx = Math.cos(ax),
        cy = Math.cos(ay),
        cz = Math.cos(az),
        nx = Math.sin(ax),
        ny = Math.sin(ay),
        nz = Math.sin(az);
    return new double[] {
      cz * cy * sx,
      (cz * ny * nx - nz * cx) * sy,
      (cz * ny * cx + nz * nx) * sz,
      m.s16(at + 6),
      nz * cy * sx,
      (nz * ny * nx + cz * cx) * sy,
      (nz * ny * cx - cz * nx) * sz,
      m.s16(at + 8),
      -ny * sx,
      cy * nx * sy,
      cy * cx * sz,
      m.s16(at + 10),
      0,
      0,
      0,
      1
    };
  }

  private static double[] world(
      int bone, byte[] skeleton, double[][] local, double[][] world, boolean[] visiting)
      throws IOException {
    if (world[bone] != null) return world[bone];
    if (visiting[bone]) throw new IOException("Esqueleto cíclico");
    visiting[bone] = true;
    int parent = skeleton[bone * 2 + 1];
    if (parent >= local.length) throw new IOException("Padre fuera del esqueleto");
    world[bone] =
        parent < 0
            ? local[bone]
            : multiply(world(parent, skeleton, local, world, visiting), local[bone]);
    visiting[bone] = false;
    return world[bone];
  }

  static int[] render(byte[] model, byte[] texture, byte[] skeleton) throws IOException {
    return render(new Reader(model), new Reader(texture), skeleton);
  }

  private static int[] render(Reader m, Reader tim, byte[] skeleton) throws IOException {
    Texture texture = new Texture(tim);
    int tmd = m.i32(0), anim = m.i32(4);
    m.range(tmd, 12);
    if (m.i32(tmd) != 0x41 || m.i32(tmd + 4) != 0) throw new IOException("TMD no válido");
    int objects = m.i32(tmd + 8), base = tmd + 12, bones = skeleton.length / 2;
    if (objects < 1 || objects > 64 || bones < 1 || bones > 64)
      throw new IOException("Modelo demasiado grande");
    m.range(base, objects * 28);
    int first = m.i32(anim);
    if (first < 4 || first > 1024) throw new IOException("Animación no válida");
    int pose = anim + first;
    boolean scaled = (m.u16(pose) & 0x8000) != 0;
    pose += 2;
    double[][] local = new double[bones][], world = new double[bones][];
    local[0] = identity();
    for (int i = 1; i < bones; i++) {
      local[i] = local(m, pose, scaled);
      pose += scaled ? 18 : 12;
    }
    List<Face> faces = new ArrayList<>();
    for (int bone = 0; bone < bones; bone++) {
      int object = skeleton[bone * 2];
      if (object < 0) continue;
      if (object >= objects) throw new IOException("Objeto fuera del modelo");
      double[] matrix = world(bone, skeleton, local, world, new boolean[bones]);
      int entry = base + object * 28,
          verts = base + m.i32(entry),
          vertCount = m.i32(entry + 4),
          primitives = base + m.i32(entry + 16),
          count = m.i32(entry + 20);
      if (vertCount < 1 || vertCount > 4096 || count < 0 || count > 2048)
        throw new IOException("Geometría demasiado grande");
      m.range(verts, vertCount * 8);
      for (int i = 0; i < count; i++) {
        int size = 4 + m.u8(primitives + 1) * 4;
        m.range(primitives, size);
        int flag = m.u8(primitives + 2),
            mode = m.u8(primitives + 3),
            n = (mode & 8) != 0 ? 4 : 3,
            at = primitives + 4;
        if ((mode >> 5) != 1) throw new IOException("Primitiva no soportada");
        boolean textured = (mode & 4) != 0, unlit = (flag & 1) != 0 || (mode & 1) != 0;
        int normals = unlit ? 0 : (mode & 16) != 0 ? n : 1;
        int colors =
            (flag & 4) != 0 ? n : (flag & 1) != 0 ? ((mode & 16) != 0 ? n : 1) : !textured ? 1 : 0;
        int[] u = new int[n], v = new int[n], indices = new int[n];
        int clut = 0, page = 0, color = 0xffdddddd;
        if (textured) {
          for (int j = 0; j < n; j++) {
            u[j] = m.u8(at);
            v[j] = m.u8(at + 1);
            if (j == 0) clut = m.u16(at + 2);
            if (j == 1) page = m.u16(at + 2);
            at += 4;
          }
        }
        if (colors > 0) {
          color = 0xff000000 | m.u8(at) << 16 | m.u8(at + 1) << 8 | m.u8(at + 2);
          at += colors * 4;
        }
        for (int j = 0; j < n; j++) {
          if (j < normals) at += 2;
          indices[j] = m.u16(at);
          at += 2;
          if (indices[j] >= vertCount) throw new IOException("Vértice fuera del modelo");
        }
        if (at > primitives + size) throw new IOException("Primitiva incompleta");
        for (int[] triangle :
            n == 4 ? new int[][] {{0, 1, 2}, {1, 3, 2}} : new int[][] {{0, 1, 2}}) {
          Face face = new Face();
          face.textured = textured;
          face.clut = clut;
          face.page = page;
          face.color = color;
          for (int j = 0; j < 3; j++) {
            int index = triangle[j], a = verts + indices[index] * 8;
            face.vertex[j] = transform(matrix, m.s16(a), m.s16(a + 2), m.s16(a + 4));
            face.u[j] = u[index];
            face.v[j] = v[index];
          }
          faces.add(face);
          if (faces.size() > 4096) throw new IOException("Demasiadas caras");
        }
        primitives += size;
      }
    }
    return raster(faces, texture);
  }

  private static double edge(double[] a, double[] b, double x, double y) {
    return (x - a[0]) * (b[1] - a[1]) - (y - a[1]) * (b[0] - a[0]);
  }

  private static int[] raster(List<Face> faces, Texture texture) throws IOException {
    if (faces.isEmpty()) throw new IOException("Modelo vacío");
    double minX = Double.POSITIVE_INFINITY,
        minY = minX,
        maxX = Double.NEGATIVE_INFINITY,
        maxY = maxX;
    for (Face f : faces)
      for (double[] v : f.vertex) {
        // The models face negative Z; an eye-level front view keeps their faces visible.
        double x = -v[0], z = -v[2], y = v[1];
        v[0] = x;
        v[1] = y;
        v[2] = z;
        minX = Math.min(minX, x);
        maxX = Math.max(maxX, x);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
      }
    double range = Math.max(maxX - minX, maxY - minY);
    if (!Double.isFinite(range) || range < 1 || range > 100000)
      throw new IOException("Modelo fuera de escala");
    double scale = (SIZE - 4) / range, centerX = (minX + maxX) / 2, centerY = (minY + maxY) / 2;
    for (Face f : faces)
      for (double[] v : f.vertex) {
        v[0] = (v[0] - centerX) * scale + SIZE / 2.0;
        v[1] = (v[1] - centerY) * scale + SIZE / 2.0;
      }
    int[] pixels = new int[SIZE * SIZE];
    double[] depth = new double[pixels.length];
    Arrays.fill(depth, Double.NEGATIVE_INFINITY);
    for (Face f : faces) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("Retrato cancelado");
      double[] a = f.vertex[0], b = f.vertex[1], c = f.vertex[2];
      double area = edge(a, b, c[0], c[1]);
      if (Math.abs(area) < .0001) continue;
      int left = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])))),
          right = Math.min(SIZE - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
      int top = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])))),
          bottom = Math.min(SIZE - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
      for (int y = top; y <= bottom; y++)
        for (int x = left; x <= right; x++) {
          double w0 = edge(b, c, x + .5, y + .5) / area,
              w1 = edge(c, a, x + .5, y + .5) / area,
              w2 = 1 - w0 - w1;
          if (w0 < 0 || w1 < 0 || w2 < 0) continue;
          double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
          int index = y * SIZE + x;
          if (z <= depth[index]) continue;
          int color =
              f.textured
                  ? texture.color(
                      w0 * f.u[0] + w1 * f.u[1] + w2 * f.u[2],
                      w0 * f.v[0] + w1 * f.v[1] + w2 * f.v[2],
                      f.clut,
                      f.page)
                  : f.color;
          if (color == 0) continue;
          pixels[index] = color;
          depth[index] = z;
        }
    }
    return pixels;
  }
}
