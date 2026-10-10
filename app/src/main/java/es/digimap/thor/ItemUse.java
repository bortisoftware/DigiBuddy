package es.digimap.thor;

/**
 * Drives the game's existing menus with controller input. Never writes RAM or reproduces item
 * effects. The confirmation button follows the fingerprinted game's region.
 */
final class ItemUse {
  private static final int TRIANGLE = 1 << 9;
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
  private final int confirmButton;
  private int phase, frames, held, released, pulse;
  private boolean accepted, finished, consumed, failed;
  private String message;

  static String unavailable(byte[] ram, GameData.Profile profile, int slot, int item) {
    if (profile == null
        || (!"jp".equals(profile.id) && !"us".equals(profile.id))
        || profile.signatureHashes.isEmpty())
      return AppLanguage.text("text_direct_item_use_has_not_been_validated_for_this_version_yet");
    for (String key : KEYS)
      if (!profile.addresses.containsKey(key))
        return AppLanguage.text("text_data_for_direct_item_use_is_missing");
    if (!GameData.decode(ram, profile).valid)
      return AppLanguage.text("text_enter_a_recognized_game_session_first");
    int inv = profile.at("INVENTORY");
    int capacity = u8(ram, inv + 90);
    if (slot < 0
        || slot >= capacity
        || item < 0
        || item >= 128
        || u8(ram, inv + slot) != item
        || u8(ram, inv + 30 + slot) == 0)
      return AppLanguage.text("text_the_bag_has_changed_select_the_item_again");
    int context = u8(ram, profile.at("GAME_STATE"));
    int color = u8(ram, profile.at("ITEM_PARA") + item * 32 + 28);
    if (context != 0 && context != 1) return AppLanguage.text("text_wait_for_this_scene_to_finish");
    if (color != 0 && color != 1 && color != 2)
      return AppLanguage.text("text_this_item_cannot_be_used_from_the_bag");
    if (context == 0 && color == 1)
      return AppLanguage.text("text_this_item_can_only_be_used_in_battle");
    if (context == 1 && color == 0)
      return AppLanguage.text("text_this_item_can_only_be_used_outside_battle");
    if (i32(ram, profile.at("TAMER_ITEM") + 8) != 255)
      return AppLanguage.text("text_wait_for_the_previous_item_to_finish");
    int open = i32(ram, profile.at("INVENTORY_OPEN"));
    if (open == 1) {
      int state = i32(ram, profile.at("INVENTORY_STATE"));
      if (state > 3 || box(ram, profile, 2) != 0 || box(ram, profile, 3) != 0)
        return AppLanguage.text("text_close_the_in_game_bag_options_first");
      return null;
    }
    if (context == 0
        && worldObject(ram, profile, 0xfa4)
        && i32(ram, profile.at("IS_IN_MENU")) == 1
        && i32(ram, profile.at("TRIANGLE_MENU_STATE")) == -1
        && box(ram, profile, 0) == 1
        && box(ram, profile, 1) == 0) return null;
    for (int i = 0; i < 6; i++)
      if (box(ram, profile, i) != 0)
        return AppLanguage.text("text_close_the_in_game_dialogue_or_menu_first");
    if (context == 0) {
      if (u8(ram, profile.at("TAMER_STATE")) != 0
          || i32(ram, profile.at("IS_IN_MENU")) != 0
          || i32(ram, profile.at("IS_SCRIPT_PAUSED")) != 1)
        return AppLanguage.text("text_wait_until_you_regain_control_of_the_character");
    } else {
      int raw = i32(ram, profile.at("COMBAT_DATA_PTR"));
      int address = raw & 0x1fffff;
      if ((raw & 0xffe00000) != 0x80000000
          || address < 0x1000
          || address > ram.length - 0x650
          || u8(ram, address + 0x64e) == 1)
        return AppLanguage.text("text_wait_until_the_battle_is_active");
    }
    return null;
  }

  ItemUse(byte[] ram, GameData.Profile profile, int slot, int item) {
    String reason = unavailable(ram, profile, slot, item);
    if (reason != null) throw new IllegalArgumentException(reason);
    this.profile = profile;
    confirmButton = "us".equals(profile.id) ? 1 : 1 << 8;
    this.item = item;
    this.slot = slot;
    initialCount = count(ram);
    context = at8(ram, "GAME_STATE");
    if (at32(ram, "INVENTORY_OPEN") == 1) phase = 2;
    else if (context == 0 && worldObject(ram, profile, 0xfa4)) phase = 1;
  }

  int advance(byte[] ram) {
    if (finished) return 0;
    if (!GameData.signatureMatches(ram, profile))
      return fail(AppLanguage.text("text_the_game_session_has_changed"));
    if (++frames > 1200)
      return fail(
          accepted
              ? AppLanguage.text("text_the_game_is_still_processing_the_item")
              : AppLanguage.text("text_the_game_could_not_open_the_item"));
    if (phase == 4) {
      int remaining = count(ram);
      if (remaining == initialCount - 1) {
        consumed = true;
        return finish(
            AppLanguage.text("text_used") + AppLanguage.catalogText(DataNames.ITEMS[item]));
      }
      if (remaining != initialCount)
        return fail(AppLanguage.text("text_the_item_s_quantity_has_changed"));
      if (i32(ram, profile.at("TAMER_ITEM") + 8) == item) accepted = true;
      if (accepted
          && i32(ram, profile.at("TAMER_ITEM") + 8) == 255
          && at32(ram, "INVENTORY_OPEN") == 0)
        return finish(AppLanguage.text("text_the_game_has_not_consumed_the_item"));
    } else {
      if (at8(ram, "GAME_STATE") != context)
        return fail(AppLanguage.text("text_the_game_situation_has_changed"));
      int inv = profile.at("INVENTORY");
      if (u8(ram, inv + slot) != item || u8(ram, inv + 30 + slot) == 0)
        return fail(AppLanguage.text("text_the_bag_has_changed"));
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
        if (selection < 1 || selection > 7)
          return fail(AppLanguage.text("text_the_in_game_menu_does_not_match"));
        if (selection != 1) return press(LEFT);
        phase = 2;
        return press(confirmButton);
      case 2:
        if (at32(ram, "INVENTORY_OPEN") != 1
            || box(ram, profile, 0) != 1
            || box(ram, profile, 1) != 1) return 0;
        int state = at32(ram, "INVENTORY_STATE");
        if (state > 3 || box(ram, profile, 2) != 0 || box(ram, profile, 3) != 0)
          return fail(AppLanguage.text("text_the_in_game_bag_is_busy"));
        int pointer = at8(ram, "INVENTORY_POINTER");
        if (pointer >= atCapacity(ram))
          return fail(AppLanguage.text("text_invalid_in_game_selection"));
        if (pointer / 2 < slot / 2) return press(DOWN);
        if (pointer / 2 > slot / 2) return press(UP);
        if ((pointer & 1) != (slot & 1)) return press((slot & 1) == 1 ? RIGHT : LEFT);
        phase = 3;
        return press(confirmButton);
      case 3:
        if (box(ram, profile, 2) != 1) return 0;
        if (box(ram, profile, 3) != 0
            || at8(ram, "ACTION_CURSOR") != 0
            || at8(ram, "INVENTORY_POINTER") != slot)
          return fail(AppLanguage.text("text_the_use_option_is_not_selected"));
        phase = 4;
        return press(confirmButton);
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
