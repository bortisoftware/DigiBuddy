package es.digimap.thor;

import java.util.ArrayList;
import java.util.List;

/** Species/model replacement stays inside the original game's evolution sequence. */
final class EvolutionAction {
  private static final int RAM_SIZE = 2097152;
  private static final String[] REQUIRED_KEYS = {
    "EVOLUTION_TARGET",
    "PARTNER_STATE",
    "PARTNER_SUB_STATE",
    "TAMER_SUBSTATE",
    "TAMER_STATE",
    "GAME_STATE",
    "IS_IN_MENU",
    "IS_SCRIPT_PAUSED",
    "INVENTORY_OPEN",
    "UI_BOX_DATA",
    "TAMER_ITEM",
    "YEAR",
    "DAY",
    "HAS_USED_EVOITEM",
    "IMMORTAL_HOUR",
    "HAS_IMMORTAL_HOUR",
    "EVO_GAINS_DATA"
  };
  private final GameData.Profile profile;
  final int target;
  final boolean reversing;
  private final int[][] preservedProgress;
  private int frames;
  private boolean finished, failed;

  static String unavailable(byte[] ram, GameData.Profile profile, int source, int target) {
    String reason = contextUnavailable(ram, profile);
    if (reason != null) return reason;
    GameData.Snapshot snapshot = GameData.decode(ram, profile);
    if (snapshot.type != source)
      return "Tu compañero ha cambiado. Selecciona la evolución de nuevo.";
    for (GameData.Evolution evolution : snapshot.evolutions) {
      if (evolution.type == target
          && evolution.candidate
          && nativeTargetMatches(ram, profile, target)) return null;
    }
    return "Todavía no cumples los requisitos de esta evolución.";
  }

  static String reverseUnavailable(byte[] ram, GameData.Profile profile, EvolutionHistory history) {
    String reason = contextUnavailable(ram, profile);
    if (reason != null) return reason;
    if (history == null) return "Todavía no hay una evolución registrada que deshacer.";
    GameData.Snapshot snapshot = GameData.decode(ram, profile);
    if (!profile.id.equals(history.profileId)
        || snapshot.type != history.target
        || generation(ram, profile) != history.lifeGeneration
        || birthDay(ram, profile) != history.birthDay)
      return "La evolución registrada no corresponde a tu compañero actual.";
    if (!hasNaturalPath(ram, profile, history.source, history.target)
        || !nativeTargetMatches(ram, profile, history.source))
      return "No se ha podido verificar la forma anterior de tu compañero.";
    return null;
  }

  private static String contextUnavailable(byte[] ram, GameData.Profile profile) {
    if (profile == null || !"jp".equals(profile.id) || profile.signatureHashes.isEmpty())
      return "La evolución directa aún no está validada para esta versión.";
    for (String key : REQUIRED_KEYS)
      if (!profile.addresses.containsKey(key)) return "Faltan datos para la evolución directa.";
    GameData.Snapshot snapshot = GameData.decode(ram, profile);
    if (!snapshot.valid) return "Entra en una partida reconocida primero.";
    if (generation(ram, profile) > 99
        || unsignedByte(ram, profile.at("YEAR")) >= 100
        || signedShort(ram, profile.at("DAY")) < 0
        || signedShort(ram, profile.at("DAY")) >= 30)
      return "Los datos del compañero no corresponden a una partida reconocida.";
    if (unsignedByte(ram, profile.at("GAME_STATE")) != 0
        || unsignedByte(ram, profile.at("TAMER_STATE")) != 0
        || unsignedByte(ram, profile.at("PARTNER_STATE")) != 1
        || integer(ram, profile.at("IS_IN_MENU")) != 0
        || integer(ram, profile.at("IS_SCRIPT_PAUSED")) != 1
        || integer(ram, profile.at("INVENTORY_OPEN")) != 0
        || integer(ram, profile.at("TAMER_ITEM") + 8) != 255
        || signedShort(ram, profile.at("EVOLUTION_TARGET")) != -1)
      return "Cierra los menús y espera a recuperar el control del personaje.";
    for (int box = 0; box < 6; box++)
      if (unsignedByte(ram, profile.at("UI_BOX_DATA") + box * 36 + 18) != 0)
        return "Cierra el diálogo del juego antes de evolucionar.";
    if ((snapshot.conditions & ((1 << 0) | (1 << 5) | (1 << 6))) != 0)
      return "Despierta y cura a tu compañero antes de evolucionar.";
    return null;
  }

  static EvolutionHistory history(byte[] ram, GameData.Profile profile, int source, int target) {
    if (ram == null || ram.length != RAM_SIZE || profile == null)
      throw new IllegalArgumentException("No se pudo identificar al compañero.");
    return new EvolutionHistory(
        profile.id, source, target, generation(ram, profile), birthDay(ram, profile));
  }

  private static int generation(byte[] ram, GameData.Profile profile) {
    return unsignedByte(ram, profile.at("TAMER_ENTITY") + 57);
  }

  private static int birthDay(byte[] ram, GameData.Profile profile) {
    int today = unsignedByte(ram, profile.at("YEAR")) * 30 + signedShort(ram, profile.at("DAY"));
    return Math.floorMod(today - signedShort(ram, profile.at("PARTNER_PARA") + 74), 3000);
  }

  private static boolean hasNaturalPath(
      byte[] ram, GameData.Profile profile, int source, int target) {
    if (source < 1 || source > 62 || target < 1 || target > 62) return false;
    int path = profile.at("EVO_PATHS_DATA") + (source - 1) * 11 + 5;
    for (int entry = 0; entry < 6; entry++)
      if (unsignedByte(ram, path + entry) == target) return true;
    return false;
  }

  private static boolean nativeTargetMatches(byte[] ram, GameData.Profile profile, int target) {
    return target >= 1
        && target <= 62
        && signedShort(ram, profile.at("EVO_GAINS_DATA") + target * 14 + 12) == target;
  }

  EvolutionAction(byte[] ram, GameData.Profile profile, int source, int target) {
    this(ram, profile, target, false, unavailable(ram, profile, source, target));
  }

  static EvolutionAction reverse(byte[] ram, GameData.Profile profile, EvolutionHistory history) {
    String reason = reverseUnavailable(ram, profile, history);
    if (reason != null) throw new IllegalArgumentException(reason);
    return new EvolutionAction(ram, profile, history.source, true, null);
  }

  private EvolutionAction(
      byte[] ram, GameData.Profile profile, int target, boolean reversing, String reason) {
    if (reason != null) throw new IllegalArgumentException(reason);
    this.profile = profile;
    this.target = target;
    this.reversing = reversing;
    preservedProgress = reversing ? captureProgress(ram, profile) : new int[0][];
  }

  private static int[][] captureProgress(byte[] ram, GameData.Profile profile) {
    List<int[]> writes = new ArrayList<>();
    int entity = profile.at("PARTNER_ENTITY"), parameters = profile.at("PARTNER_PARA");
    captureRange(writes, ram, entity + 56, 8);
    captureRange(writes, ram, entity + 72, 8);
    captureRange(writes, ram, entity + 88, 8);
    // The stage timer remains native: restoring it could immediately trigger another evolution.
    captureRange(writes, ram, parameters, 86);
    captureRange(writes, ram, parameters + 88, 44);
    captureRange(writes, ram, profile.at("HAS_USED_EVOITEM"), 1);
    captureRange(writes, ram, profile.at("IMMORTAL_HOUR"), 1);
    captureRange(writes, ram, profile.at("HAS_IMMORTAL_HOUR"), 4);
    return writes.toArray(new int[0][]);
  }

  private static void captureRange(List<int[]> writes, byte[] ram, int address, int length) {
    for (int offset = 0; offset < length; ) {
      int width = Math.min(4, length - offset), value = 0;
      for (int index = 0; index < width; index++)
        value |= unsignedByte(ram, address + offset + index) << (index * 8);
      writes.add(new int[] {address + offset, value, width});
      offset += width;
    }
  }

  int[][] writes() {
    List<int[]> writes = new ArrayList<>();
    if (reversing) writes.add(new int[] {profile.at("HAS_USED_EVOITEM"), 1, 1});
    writes.add(new int[] {profile.at("EVOLUTION_TARGET"), target, 2});
    writes.add(new int[] {profile.at("TAMER_STATE"), 6, 1});
    writes.add(new int[] {profile.at("TAMER_SUBSTATE"), 0, 1});
    writes.add(new int[] {profile.at("PARTNER_STATE"), 13, 1});
    writes.add(new int[] {profile.at("PARTNER_SUB_STATE"), 0, 1});
    return writes.toArray(new int[0][]);
  }

  int[][] completionWrites() {
    return finished && !failed ? preservedProgress : new int[0][];
  }

  void advance(byte[] ram) {
    if (finished) return;
    frames++;
    if (ram == null || ram.length != RAM_SIZE) {
      failed = finished = true;
      return;
    }
    if (integer(ram, profile.at("PARTNER_ENTITY")) == target
        && unsignedByte(ram, profile.at("PARTNER_STATE")) == 1
        && unsignedByte(ram, profile.at("TAMER_STATE")) == 0
        && signedShort(ram, profile.at("EVOLUTION_TARGET")) == -1) finished = true;
    else if (frames >= 2400) failed = finished = true;
  }

  boolean finished() {
    return finished;
  }

  boolean failed() {
    return failed;
  }

  private static int unsignedByte(byte[] ram, int address) {
    return ram[address] & 255;
  }

  private static int signedShort(byte[] ram, int address) {
    return (short) (unsignedByte(ram, address) | unsignedByte(ram, address + 1) << 8);
  }

  private static int integer(byte[] ram, int address) {
    return unsignedByte(ram, address)
        | unsignedByte(ram, address + 1) << 8
        | unsignedByte(ram, address + 2) << 16
        | unsignedByte(ram, address + 3) << 24;
  }
}
