package es.digimap.thor;

/**
 * Drives the game's existing menus with controller input. Never writes RAM or reproduces item
 * effects. Only the fingerprinted reference profile has validated menu behavior.
 */
final class ItemUse {
  private static final int TRIANGLE = 1 << 9, CIRCLE = 1 << 8;
  private static final int UP = 1 << 4, DOWN = 1 << 5, LEFT = 1 << 6, RIGHT = 1 << 7;
  private static final String[] KEYS = {
    "INVENTORY_STATE",
    "INVENTORY_OPEN",
    "INVENTORY_POINTER",
    "GAME_MENU_SPRITES",
    "TRIANGLE_MENU_STATE",
    "GAME_STATE",
    "UI_BOX_DATA",
    "TAMER_ITEM",
    "COMBAT_DATA_PTR",
    "ACTION_CURSOR",
    "TAMER_STATE",
    "IS_IN_MENU",
    "IS_SCRIPT_PAUSED",
    "WORLD_OBJECTS"
  };
  private final GameData.Profile profile;
  private final int item, slot, initialCount, context;
  private int phase, frames, held, released, pulse;
  private boolean accepted, finished, consumed, failed;
  private String message;

  static String unavailable(byte[] ram, GameData.Profile profile, int slot, int item) {
    if (profile == null || !"jp".equals(profile.id) || profile.signatureHashes.isEmpty())
      return "El uso directo aún no está validado para esta versión.";
    for (String key : KEYS)
      if (!profile.addresses.containsKey(key)) return "Faltan datos para el uso directo.";
    if (!GameData.decode(ram, profile).valid) return "Entra en una partida reconocida primero.";
    int inv = profile.at("INVENTORY");
    int capacity = u8(ram, inv + 90);
    if (slot < 0
        || slot >= capacity
        || item < 0
        || item >= 128
        || u8(ram, inv + slot) != item
        || u8(ram, inv + 30 + slot) == 0)
      return "La bolsa ha cambiado. Selecciona el objeto de nuevo.";
    int context = u8(ram, profile.at("GAME_STATE"));
    int color = u8(ram, profile.at("ITEM_PARA") + item * 32 + 28);
    if (context != 0 && context != 1) return "Espera a terminar esta escena.";
    if (color != 0 && color != 1 && color != 2) return "Este objeto no se usa desde la bolsa.";
    if (context == 0 && color == 1) return "Este objeto solo se usa en combate.";
    if (context == 1 && color == 0) return "Este objeto solo se usa fuera del combate.";
    if (i32(ram, profile.at("TAMER_ITEM") + 8) != 255)
      return "Espera a que termine el objeto anterior.";
    int open = i32(ram, profile.at("INVENTORY_OPEN"));
    if (open == 1) {
      int state = i32(ram, profile.at("INVENTORY_STATE"));
      if (state > 3 || box(ram, profile, 2) != 0 || box(ram, profile, 3) != 0)
        return "Cierra las opciones de la bolsa del juego primero.";
      return null;
    }
    if (context == 0
        && worldObject(ram, profile, 0xfa4)
        && i32(ram, profile.at("IS_IN_MENU")) == 1
        && i32(ram, profile.at("TRIANGLE_MENU_STATE")) == -1
        && box(ram, profile, 0) == 1
        && box(ram, profile, 1) == 0) return null;
    for (int i = 0; i < 6; i++)
      if (box(ram, profile, i) != 0) return "Cierra el diálogo o menú del juego primero.";
    if (context == 0) {
      if (u8(ram, profile.at("TAMER_STATE")) != 0
          || i32(ram, profile.at("IS_IN_MENU")) != 0
          || i32(ram, profile.at("IS_SCRIPT_PAUSED")) != 1)
        return "Espera a recuperar el control del personaje.";
    } else {
      int raw = i32(ram, profile.at("COMBAT_DATA_PTR"));
      int address = raw & 0x1fffff;
      if ((raw & 0xffe00000) != 0x80000000
          || address < 0x1000
          || address > ram.length - 0x650
          || u8(ram, address + 0x64e) == 1) return "Espera a que el combate esté activo.";
    }
    return null;
  }

  ItemUse(byte[] ram, GameData.Profile profile, int slot, int item) {
    String reason = unavailable(ram, profile, slot, item);
    if (reason != null) throw new IllegalArgumentException(reason);
    this.profile = profile;
    this.item = item;
    this.slot = slot;
    initialCount = count(ram);
    context = at8(ram, "GAME_STATE");
    if (at32(ram, "INVENTORY_OPEN") == 1) phase = 2;
    else if (context == 0 && worldObject(ram, profile, 0xfa4)) phase = 1;
  }

  int advance(byte[] ram) {
    if (finished) return 0;
    if (!GameData.signatureMatches(ram, profile)) return fail("La partida ha cambiado.");
    if (++frames > 1200)
      return fail(
          accepted ? "El juego sigue procesando el objeto." : "El juego no pudo abrir el objeto.");
    if (phase == 4) {
      int remaining = count(ram);
      if (remaining == initialCount - 1) {
        consumed = true;
        return finish("Usado: " + DataNames.ITEMS[item]);
      }
      if (remaining != initialCount) return fail("La cantidad del objeto ha cambiado.");
      if (i32(ram, profile.at("TAMER_ITEM") + 8) == item) accepted = true;
      if (accepted
          && i32(ram, profile.at("TAMER_ITEM") + 8) == 255
          && at32(ram, "INVENTORY_OPEN") == 0) return finish("El juego no ha consumido el objeto.");
    } else {
      if (at8(ram, "GAME_STATE") != context) return fail("Ha cambiado la situación de la partida.");
      int inv = profile.at("INVENTORY");
      if (u8(ram, inv + slot) != item || u8(ram, inv + 30 + slot) == 0)
        return fail("La bolsa ha cambiado.");
    }
    if (held > 0) {
      held--;
      return pulse;
    }
    if (released > 0) {
      released--;
      return 0;
    }
    switch (phase) {
      case 0:
        phase = context == 0 ? 1 : 2;
        return press(TRIANGLE);
      case 1:
        if (!worldObject(ram, profile, 0xfa4)
            || at32(ram, "IS_IN_MENU") != 1
            || at32(ram, "TRIANGLE_MENU_STATE") != -1
            || box(ram, profile, 0) != 1) return 0;
        int selection = s16(ram, profile.at("GAME_MENU_SPRITES") + 4);
        if (selection < 1 || selection > 7) return fail("El menú del juego no coincide.");
        if (selection != 1) return press(LEFT);
        phase = 2;
        return press(CIRCLE);
      case 2:
        if (at32(ram, "INVENTORY_OPEN") != 1
            || box(ram, profile, 0) != 1
            || box(ram, profile, 1) != 1) return 0;
        int state = at32(ram, "INVENTORY_STATE");
        if (state > 3 || box(ram, profile, 2) != 0 || box(ram, profile, 3) != 0)
          return fail("La bolsa del juego está ocupada.");
        int pointer = at8(ram, "INVENTORY_POINTER");
        if (pointer >= atCapacity(ram)) return fail("Selección del juego no válida.");
        if (pointer / 2 < slot / 2) return press(DOWN);
        if (pointer / 2 > slot / 2) return press(UP);
        if ((pointer & 1) != (slot & 1)) return press((slot & 1) == 1 ? RIGHT : LEFT);
        phase = 3;
        return press(CIRCLE);
      case 3:
        if (box(ram, profile, 2) != 1) return 0;
        if (box(ram, profile, 3) != 0
            || at8(ram, "ACTION_CURSOR") != 0
            || at8(ram, "INVENTORY_POINTER") != slot)
          return fail("La opción Usar no está seleccionada.");
        phase = 4;
        return press(CIRCLE);
      default:
        return 0;
    }
  }

  boolean finished() {
    return finished;
  }

  boolean consumed() {
    return consumed;
  }

  boolean accepted() {
    return accepted;
  }

  boolean failed() {
    return failed;
  }

  String message() {
    return message;
  }

  private int press(int input) {
    pulse = input;
    held = 7;
    released = 8;
    return input;
  }

  private int fail(String reason) {
    failed = true;
    return finish(reason);
  }

  private int finish(String reason) {
    finished = true;
    message = reason;
    return 0;
  }

  private int at8(byte[] ram, String key) {
    return u8(ram, profile.at(key));
  }

  private int at32(byte[] ram, String key) {
    return i32(ram, profile.at(key));
  }

  private int atCapacity(byte[] ram) {
    return u8(ram, profile.at("INVENTORY") + 90);
  }

  private int count(byte[] ram) {
    int inv = profile.at("INVENTORY"), total = 0;
    for (int i = 0; i < atCapacity(ram); i++)
      if (u8(ram, inv + i) == item) total += u8(ram, inv + 30 + i);
    return total;
  }

  private static int box(byte[] ram, GameData.Profile profile, int id) {
    return s16(ram, profile.at("UI_BOX_DATA") + id * 36 + 18);
  }

  private static boolean worldObject(byte[] ram, GameData.Profile profile, int id) {
    int objects = profile.at("WORLD_OBJECTS");
    for (int i = 0; i < 128; i++)
      if (s16(ram, objects + i * 12) == id && i32(ram, objects + i * 12 + 4) != 0) return true;
    return false;
  }

  private static int u8(byte[] ram, int at) {
    return at >= 0 && at < ram.length ? ram[at] & 255 : 0;
  }

  private static int s16(byte[] ram, int at) {
    return (short) (u8(ram, at) | u8(ram, at + 1) << 8);
  }

  private static int i32(byte[] ram, int at) {
    return u8(ram, at) | u8(ram, at + 1) << 8 | u8(ram, at + 2) << 16 | u8(ram, at + 3) << 24;
  }
}
