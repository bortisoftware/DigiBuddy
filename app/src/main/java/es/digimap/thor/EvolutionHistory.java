package es.digimap.thor;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Last requested evolution, scoped by the caller's disc folder and emulator engine. */
final class EvolutionHistory {
  private static final int MAGIC = 0x44424548;
  private static final int VERSION = 1;
  private static final int MAX_PROFILE_LENGTH = 32;
  private static final int MIN_SIZE = 12;
  private static final int MAX_SIZE = 11 + MAX_PROFILE_LENGTH;

  final String profileId;
  final int source, target, lifeGeneration, birthDay;

  EvolutionHistory(String profileId, int source, int target, int lifeGeneration, int birthDay) {
    if (profileId == null || !profileId.matches("[a-z][a-z0-9_-]{0,31}"))
      throw new IllegalArgumentException("Invalid evolution profile");
    if (source < 1 || source > 62 || target < 1 || target > 62 || source == target)
      throw new IllegalArgumentException("Invalid evolution species");
    if (lifeGeneration < 0 || lifeGeneration > 99 || birthDay < 0 || birthDay >= 3000)
      throw new IllegalArgumentException("Invalid partner generation");
    this.profileId = profileId;
    this.source = source;
    this.target = target;
    this.lifeGeneration = lifeGeneration;
    this.birthDay = birthDay;
  }

  /** Missing, malformed or future-version records never authorize a reversal. */
  static EvolutionHistory read(File folder, String engine) {
    try {
      Path path = file(folder, engine);
      long size = Files.size(path);
      if (size < MIN_SIZE || size > MAX_SIZE || !Files.isRegularFile(path)) return null;
      try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
        if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null;
        int length = input.readUnsignedByte();
        if (length < 1 || length > MAX_PROFILE_LENGTH) return null;
        byte[] profile = new byte[length];
        input.readFully(profile);
        EvolutionHistory history =
            new EvolutionHistory(
                new String(profile, StandardCharsets.US_ASCII),
                input.readUnsignedByte(),
                input.readUnsignedByte(),
                input.readUnsignedByte(),
                input.readUnsignedShort());
        return input.read() == -1 ? history : null;
      }
    } catch (IOException | IllegalArgumentException e) {
      return null;
    }
  }

  /** Complete the small record before replacing the previous history. */
  void save(File folder, String engine) throws IOException {
    Path destination = file(folder, engine);
    Path directory = destination.getParent();
    Files.createDirectories(directory);
    Path temporary = Files.createTempFile(directory, engine + "-evolution-history-", ".tmp");
    try {
      try (FileOutputStream output = new FileOutputStream(temporary.toFile());
          DataOutputStream data = new DataOutputStream(output)) {
        byte[] profile = profileId.getBytes(StandardCharsets.US_ASCII);
        data.writeInt(MAGIC);
        data.writeByte(VERSION);
        data.writeByte(profile.length);
        data.write(profile);
        data.writeByte(source);
        data.writeByte(target);
        data.writeByte(lifeGeneration);
        data.writeShort(birthDay);
        data.flush();
        output.getFD().sync();
      }
      try {
        Files.move(
            temporary,
            destination,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  static void clear(File folder, String engine) throws IOException {
    Files.deleteIfExists(file(folder, engine));
  }

  private static Path file(File folder, String engine) {
    if (!"swan-gl".equals(engine) && !"swan-sw".equals(engine) && !"pcsx".equals(engine))
      throw new IllegalArgumentException("Invalid emulator engine");
    if (folder == null) throw new IllegalArgumentException("Missing disc save folder");
    return new File(folder, engine + "-evolution-history.dat").toPath();
  }
}
