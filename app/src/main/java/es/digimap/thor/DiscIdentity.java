package es.digimap.thor;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded ISO-9660 metadata reader. It never extracts user-controlled file paths. */
public final class DiscIdentity {
  private static final int MAX_ENTRY_BYTES = 1024 * 1024;
  private static final Pattern SERIAL =
      Pattern.compile("(SLPS|SLUS|SLES|SCPS|SCUS|SCES)_[0-9]{3}\\.[0-9]{2}");
  public int sectorSize, payloadOffset;
  public String serial = "Desconocida";

  public static DiscIdentity read(File file) throws IOException {
    DiscIdentity id = new DiscIdentity();
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      int[][] formats = {{2048, 0}, {2352, 24}, {2352, 16}, {2336, 8}};
      byte[] magic = new byte[7];
      for (int[] format : formats) {
        if (in.length() < format[0] * 17L) continue;
        in.seek(format[0] * 16L + format[1]);
        in.readFully(magic);
        if (magic[0] == 1
            && magic[1] == 'C'
            && magic[2] == 'D'
            && magic[3] == '0'
            && magic[4] == '0'
            && magic[5] == '1'
            && magic[6] == 1) {
          id.sectorSize = format[0];
          id.payloadOffset = format[1];
          break;
        }
      }
      if (id.sectorSize == 0) return id; // CHD is handled by the core.
      byte[] pvd = read(in, id, 16, 2048);
      byte[] dir = read(in, id, little(pvd, 158), little(pvd, 166));
      Matcher found = SERIAL.matcher(new String(dir, StandardCharsets.ISO_8859_1));
      if (found.find()) id.serial = found.group().replace('_', '-').replace(".", "");
    }
    return id;
  }

  private static int little(byte[] data, int p) {
    return (data[p] & 255)
        | (data[p + 1] & 255) << 8
        | (data[p + 2] & 255) << 16
        | (data[p + 3] & 255) << 24;
  }

  public static byte[] file(File disc, String path) throws IOException {
    return fileRange(disc, path, 0, -1);
  }

  public static byte[] fileRange(File disc, String path, int fileOffset, int count)
      throws IOException {
    if (fileOffset < 0 || count < -1 || count > MAX_ENTRY_BYTES)
      throw new IOException("Rango no válido");
    if (path == null || path.startsWith("/") || path.contains("..") || path.contains("\\\\"))
      throw new IOException("Ruta interna no válida");
    DiscIdentity id = read(disc);
    if (id.sectorSize == 0) return null;
    String[] components = path.split("/");
    try (RandomAccessFile in = new RandomAccessFile(disc, "r")) {
      byte[] pvd = read(in, id, 16, 2048);
      int lba = little(pvd, 158), size = little(pvd, 166);
      for (int i = 0; i < components.length; i++) {
        String component = components[i];
        if (component.isEmpty()) throw new IOException("Ruta interna vacía");
        byte[] directory = read(in, id, lba, size);
        boolean found = false;
        for (int offset = 0; offset < directory.length; ) {
          int length = directory[offset] & 255;
          if (length == 0) {
            offset = (offset / 2048 + 1) * 2048;
            continue;
          }
          if (length < 34 || length > directory.length - offset)
            throw new IOException("Registro ISO incompleto");
          int nameLength = directory[offset + 32] & 255;
          if (nameLength == 0 || nameLength > length - 33)
            throw new IOException("Nombre ISO no válido");
          String name =
              new String(directory, offset + 33, nameLength, StandardCharsets.US_ASCII)
                  .split(";")[0];
          if (name.equalsIgnoreCase(component)) {
            boolean isDirectory = (directory[offset + 25] & 2) != 0;
            if (isDirectory != (i < components.length - 1))
              throw new IOException("Tipo de entrada ISO no válido");
            lba = little(directory, offset + 2);
            size = little(directory, offset + 10);
            found = true;
            break;
          }
          offset += length;
        }
        if (!found) return null;
      }
      int length = count == -1 ? size : count;
      if (size < 0 || length < 0 || length > MAX_ENTRY_BYTES || fileOffset > size - length)
        throw new IOException("Rango fuera de la entrada");
      int first = fileOffset / 2048, skip = fileOffset % 2048;
      // At most one extra sector is needed when a slice starts inside a sector.
      if (skip + length > MAX_ENTRY_BYTES) throw new IOException("Rango demasiado grande");
      byte[] sliced = read(in, id, lba + first, skip + length);
      return java.util.Arrays.copyOfRange(sliced, skip, skip + length);
    }
  }

  private static byte[] read(RandomAccessFile in, DiscIdentity id, int lba, int bytes)
      throws IOException {
    if (lba < 0 || bytes < 0 || bytes > MAX_ENTRY_BYTES)
      throw new IOException("Tamaño o sector ISO no válido");
    long start = (long) lba * id.sectorSize + id.payloadOffset;
    long last =
        start
            + (bytes == 0
                ? 0
                : (long) ((bytes - 1) / 2048) * id.sectorSize + (bytes - 1) % 2048 + 1);
    if (start > in.length() || last > in.length())
      throw new IOException("La entrada ISO sale del disco");
    byte[] data = new byte[bytes];
    int done = 0;
    while (done < bytes) {
      in.seek((long) lba * id.sectorSize + id.payloadOffset);
      int count = Math.min(2048, bytes - done);
      in.readFully(data, done, count);
      lba++;
      done += count;
    }
    return data;
  }
}
