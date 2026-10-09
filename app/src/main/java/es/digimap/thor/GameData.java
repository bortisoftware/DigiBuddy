package es.digimap.thor;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Read-only decoder. Addresses are trusted only after profile validation. */
public final class GameData {
  public static final class Profile {
    public String id, label;
    public Map<String, Integer> addresses;
    public final List<Integer> signatureOffsets = new ArrayList<>();
    public final List<Integer> signatureLengths = new ArrayList<>();
    public final List<String> signatureHashes = new ArrayList<>();

    int at(String name) {
      return addresses.get(name);
    }
  }

  public static final class Item {
    public final int id, count, slot;
    public final String name;

    Item(int id, int count, int slot, String name) {
      this.id = id;
      this.count = count;
      this.slot = slot;
      this.name = name;
    }
  }

  public static final class Evolution {
    public String name, details;
    public int type, score;
    public boolean candidate;
  }

  public static final class Enemy {
    public int type, x, y, hp, maxHp, mp, maxMp, offense, defense, speed, brains, difficulty;
    public int recruitType;
    public String name;
  }

  public static final class Exit {
    public int trigger, target, minX = 100, minY = 100, maxX = -1, maxY = -1;
    public String name;
  }

  public static final class Marker {
    public int kind, x, y, itemId; // 0: WC, 1: loose item, 2: unopened chest

    Marker(int kind, int x, int y, int itemId) {
      this.kind = kind;
      this.x = x;
      this.y = y;
      this.itemId = itemId;
    }
  }

  public static final class Snapshot {
    public boolean valid;
    public String message = "Esperando una partida activa…", name = "", map = "";
    public int type, hp, mp, maxHp, maxMp, offense, defense, speed, brains;
    public int age, weight, happiness, discipline, tiredness, care, battles, conditions;
    public int money, hour, minute, x, y, mapId, inventorySize;
    public int zoneId = -1;
    public int prosperity, recruitable;
    public boolean prosperityAvailable;
    public final List<Recruit> recruits = new ArrayList<>();
    public final List<Recruit> pendingRecruits = new ArrayList<>();
    public byte[] collision;
    public final List<Enemy> enemies = new ArrayList<>();
    public final List<Enemy> recruitMarkers = new ArrayList<>();
    public final List<Exit> exits = new ArrayList<>();
    public final List<Marker> markers = new ArrayList<>();
    public final List<Item> items = new ArrayList<>();
    public final List<String> moves = new ArrayList<>();
    public final List<Evolution> evolutions = new ArrayList<>();
  }

  public static final class Recruit {
    public final int type, points;
    public final String name;

    Recruit(int type, int points, String name) {
      this.type = type;
      this.points = points;
      this.name = name;
    }
  }

  private final byte[] ram;
  private final Profile profile;

  private GameData(byte[] ram, Profile profile) {
    this.ram = ram;
    this.profile = profile;
  }

  private int u8(int p) {
    return p >= 0 && p < ram.length ? ram[p] & 255 : 0;
  }

  private int s16(int p) {
    return (short) (u8(p) | u8(p + 1) << 8);
  }

  private int i32(int p) {
    return u8(p) | u8(p + 1) << 8 | u8(p + 2) << 16 | u8(p + 3) << 24;
  }

  private String digimon(int type) {
    return type >= 0 && type < DataNames.DIGIMON.length
        ? DataNames.DIGIMON[type]
        : "Digimon " + type;
  }

  private int level(int type) {
    return u8(profile.at("DIGIMON_DATA") + type * 52 + 29);
  }

  public static Snapshot decode(byte[] ram, Profile profile) {
    Snapshot empty = new Snapshot();
    if (ram == null || ram.length != 2097152 || profile == null) {
      empty.message =
          ram == null
              ? "Preparando la partida…"
              : profile == null
                  ? "Esperando una partida compatible para activar el panel…"
                  : "Esperando la memoria del juego…";
      return empty;
    }
    try {
      return new GameData(ram, profile).read();
    } catch (RuntimeException ex) {
      empty.message = "Los datos aún no son coherentes. Esperando una partida activa…";
      return empty;
    }
  }

  public static boolean signatureMatches(byte[] ram, Profile profile) {
    if (ram == null || ram.length != 2097152 || profile == null) return false;
    if (profile.signatureHashes.size() != profile.signatureOffsets.size()
        || profile.signatureLengths.size() != profile.signatureOffsets.size()) return false;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < profile.signatureHashes.size(); i++) {
        int offset = profile.signatureOffsets.get(i), length = profile.signatureLengths.get(i);
        if (offset < 0 || length < 1 || length > 4096 || offset > ram.length - length) return false;
        digest.update(ram, offset, length);
        StringBuilder actual = new StringBuilder(64);
        for (byte value : digest.digest())
          actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!actual.toString().equals(profile.signatureHashes.get(i))) return false;
      }
      return true;
    } catch (java.security.NoSuchAlgorithmException ex) {
      return false;
    }
  }

  private Snapshot read() {
    Snapshot s = new Snapshot();
    if (!signatureMatches(ram, profile)) {
      s.message = "Esperando el código del juego o perfil incompatible con este parche.";
      return s;
    }
    int entity = profile.at("PARTNER_ENTITY"),
        para = profile.at("PARTNER_PARA"),
        inv = profile.at("INVENTORY");
    s.type = i32(entity);
    s.maxHp = s16(entity + 72);
    s.maxMp = s16(entity + 74);
    s.inventorySize = u8(inv + 90);
    if (s.type < 1
        || s.type > 65
        || s.maxHp < 1
        || s.maxHp > 9999
        || s.maxMp < 0
        || s.maxMp > 9999
        || (s.inventorySize != 10 && s.inventorySize != 20 && s.inventorySize != 30)) return s;
    // Stable table structure checks also protect the experimental USA profile.
    if (level(1) != 1 || level(3) != 3 || i32(profile.at("DIGIMON_DATA") + 20) != 17) {
      s.message = "La estructura de datos no coincide con el perfil. Panel desactivado.";
      return s;
    }
    s.name = digimon(s.type);
    s.hp = s16(entity + 76);
    s.mp = s16(entity + 78);
    if (s.hp < 0 || s.hp > s.maxHp || s.mp < 0 || s.mp > s.maxMp) return s;
    s.offense = s16(entity + 56);
    s.defense = s16(entity + 58);
    s.speed = s16(entity + 60);
    s.brains = s16(entity + 62);
    s.conditions = i32(para);
    s.tiredness = s16(para + 34);
    s.discipline = s16(para + 40);
    s.happiness = s16(para + 42);
    s.weight = s16(para + 66);
    s.age = s16(para + 74);
    s.care = s16(para + 82);
    s.battles = s16(para + 84);
    if (s.weight < 0
        || s.weight > 999
        || s.age < 0
        || s.age > 999
        || s.discipline < 0
        || s.discipline > 100
        || s.happiness < 0
        || s.happiness > 100) return s;
    s.money = i32(profile.at("MONEY"));
    s.hour = s16(profile.at("HOUR"));
    s.minute = s16(profile.at("MINUTE"));
    if (s.hour < 0
        || s.hour > 23
        || s.minute < 0
        || s.minute > 59
        || s.money < 0
        || s.money > 9999999) return s;
    for (int slot = 0; slot < s.inventorySize; slot++) {
      int type = u8(inv + slot), count = u8(inv + 30 + slot);
      if (type == 255 || count == 0) continue;
      if (type > 127 || count > 99) return new Snapshot();
      String name = DataNames.ITEMS[type];
      s.items.add(new Item(type, count, slot, name.isEmpty() ? "Objeto " + type : name));
    }
    for (int move = 0; move < 58; move++) {
      if (move == 48 || move == 57) continue;
      if ((u8(entity + 88 + move / 8) & (1 << (move % 8))) != 0) {
        String name = DataNames.TECHNIQUES[move];
        s.moves.add(name.isEmpty() ? "Técnica " + move : name);
      }
    }
    s.mapId =
        profile.addresses.containsKey("CURRENT_SCREEN")
            ? u8(profile.at("CURRENT_SCREEN"))
            : s16(profile.at("CURRENT_MAP_ID"));
    if (s.mapId >= 0 && s.mapId < 255) {
      int entry = profile.at("MAP_ENTRIES") + s.mapId * 16;
      int nameId = u8(entry + 15);
      s.zoneId = nameId < DataNames.ZONES.length ? nameId : -1;
      s.map = nameId < DataNames.ZONES.length ? DataNames.ZONES[nameId] : "";
      if (s.map.isEmpty()) s.map = "Zona " + s.mapId;
    } else {
      s.map = "Ubicación no disponible";
    }
    int[] player = tile(profile.at("TAMER_ENTITY"));
    s.x = player != null ? player[0] : (byte) u8(profile.at("TAMER_PREVIOUS_TILE_X"));
    s.y = player != null ? player[1] : (byte) u8(profile.at("TAMER_PREVIOUS_TILE_Y"));
    s.collision = new byte[10000];
    System.arraycopy(ram, profile.at("MAP_COLLISION_DATA"), s.collision, 0, 10000);
    prosperity(s);
    mapEntities(s);
    mapObjects(s);
    evolution(s);
    s.valid = true;
    s.message = "Estás en: " + s.map;
    return s;
  }

  private void prosperity(Snapshot s) {
    if (!profile.addresses.containsKey("SCRIPT_STATE_PTR")) return;
    int raw = i32(profile.at("SCRIPT_STATE_PTR"));
    int state = raw & 0x1fffff;
    if ((raw & 0xffe00000) != 0x80000000 || state < 0x1000 || state + 668 > ram.length) return;
    // ScriptState: 212 script bytes, 33 card bytes, then 100 trigger bytes.
    int triggers = state + 245;
    for (int type = 3; type < 59; type++) {
      int stage = level(type);
      if (stage < 3 || stage > 5) continue;
      s.recruitable++;
      int trigger = 200 + type;
      int points = type == 11 || type == 39 || type == 53 ? 1 : stage - 2;
      if ((u8(triggers + trigger / 8) & (1 << (trigger % 8))) == 0) {
        s.pendingRecruits.add(new Recruit(type, points, digimon(type)));
        continue;
      }
      s.recruits.add(new Recruit(type, points, digimon(type)));
      s.prosperity += points;
    }
    s.prosperityAvailable = s.prosperity <= 100;
  }

  private int[] tile(int entity) {
    int pointer = i32(entity + 4) & 0x1fffff;
    if (pointer < 0x1000 || pointer + 136 >= ram.length) return null;
    int x = i32(pointer + 120), z = i32(pointer + 128);
    if (Math.abs((long) x) > 10000 || Math.abs((long) z) > 10000) return null;
    return worldTile(x, z);
  }

  private static int[] worldTile(int x, int z) {
    int tx = x / 100 + 50 - (x < 0 ? 1 : 0), ty = 50 - z / 100 - (z > 0 ? 1 : 0);
    if (tx < 0 || tx > 99 || ty < 0 || ty > 99) return null;
    return new int[] {tx, ty};
  }

  private void mapObjects(Snapshot s) {
    if (s.mapId >= 0 && s.mapId < 255 && profile.addresses.containsKey("TOILET_DATA")) {
      int id = u8(profile.at("MAP_ENTRIES") + s.mapId * 16 + 14);
      if (id >= 1 && id <= 10) {
        int at = profile.at("TOILET_DATA") + (id - 1) * 8;
        int[] tile = worldTile(s16(at + 4), s16(at + 6));
        if (tile != null) s.markers.add(new Marker(0, tile[0], tile[1], -1));
      }
    }
    if (profile.addresses.containsKey("DROPPED_ITEMS")
        && profile.addresses.containsKey("WORLD_OBJECTS")) {
      boolean[] active = new boolean[11];
      for (int i = 0; i < 128; i++) {
        int at = profile.at("WORLD_OBJECTS") + i * 12, id = s16(at + 2);
        if (s16(at) == 0x195 && id >= 0 && id < 11 && i32(at + 8) != 0) active[id] = true;
      }
      for (int i = 0; i < 11; i++) {
        int at = profile.at("DROPPED_ITEMS") + i * 16,
            type = i32(at + 8),
            x = s16(at + 12),
            y = s16(at + 14);
        if (active[i] && type >= 0 && type < 128 && x >= 0 && x < 100 && y >= 0 && y < 100)
          s.markers.add(new Marker(1, x, y, type));
      }
    }
    if (profile.addresses.containsKey("CHEST_ARRAY"))
      for (int i = 0; i < 8; i++) {
        int at = profile.at("CHEST_ARRAY") + i * 56, type = u8(at + 50);
        if (type >= 128 || u8(at + 51) != 0) continue;
        int[] tile = worldTile(i32(at), i32(at + 8));
        if (tile != null) s.markers.add(new Marker(2, tile[0], tile[1], type));
      }
  }

  private String zone(int map) {
    if (map < 0 || map >= 255) return "Zona " + map;
    int name = u8(profile.at("MAP_ENTRIES") + map * 16 + 15);
    return name < DataNames.ZONES.length ? DataNames.ZONES[name] : "Zona " + map;
  }

  private void mapEntities(Snapshot s) {
    if (profile.addresses.containsKey("MAP_WARPS"))
      for (int id = 0; id < 10; id++) {
        int target = s16(profile.at("MAP_WARPS") + 80 + id * 2);
        if (target < 0 || target >= 255) continue;
        Exit exit = new Exit();
        exit.trigger = 110 + id;
        exit.target = target;
        exit.name = zone(target);
        for (int y = 0; y < 100; y++)
          for (int x = 0; x < 100; x++)
            if ((s.collision[y * 100 + x] & 255) == exit.trigger) {
              exit.minX = Math.min(exit.minX, x);
              exit.maxX = Math.max(exit.maxX, x);
              exit.minY = Math.min(exit.minY, y);
              exit.maxY = Math.max(exit.maxY, y);
            }
        if (exit.maxX >= 0) s.exits.add(exit);
      }
    addScriptExits(s);
    if (!profile.addresses.containsKey("ENTITY_TABLE")) return;
    for (int id = 2; id < 10; id++) {
      int raw = i32(profile.at("ENTITY_TABLE") + id * 4);
      if (raw == 0) continue;
      int entity = raw & 0x1fffff;
      if (entity < 0x1000 || entity + 104 >= ram.length || u8(entity + 52) == 0) continue;
      // NPC types extend beyond the 65 raisable partners (e.g. Goburimon is 80).
      // The same species can be either a civilian or an enemy in different scripts.
      int type = i32(entity), max = s16(entity + 72), hp = s16(entity + 76);
      if (type < 1 || type >= DataNames.DIGIMON.length) continue;
      int[] pos = tile(entity);
      if (pos == null) continue;
      int recruitType = 0;
      for (Recruit recruit : s.pendingRecruits)
        if ((type >= 128 || type == 3)
            && recruit.name.equals(digimon(type))
            && RecruitmentHints.atLocation(s, recruit.type) != null) {
          recruitType = recruit.type;
          break;
        }
      if (recruitType > 0) {
        Enemy marker = new Enemy();
        marker.type = type;
        marker.recruitType = recruitType;
        marker.name = digimon(type);
        marker.x = pos[0];
        marker.y = pos[1];
        s.recruitMarkers.add(marker);
        continue;
      }
      if (type < 1
          || type >= 180
          || level(type) < 3
          || level(type) > 5
          || max <= 0
          || max > 9999
          || hp <= 0
          || hp > max) continue;
      if (!EncounterClassifier.hostile(ram, profile, entity)) continue;
      Enemy enemy = new Enemy();
      enemy.type = type;
      enemy.name = digimon(type);
      enemy.x = pos[0];
      enemy.y = pos[1];
      enemy.hp = hp;
      enemy.maxHp = max;
      enemy.mp = s16(entity + 78);
      enemy.maxMp = s16(entity + 74);
      enemy.brains = s16(entity + 62);
      enemy.offense = s16(entity + 56);
      enemy.defense = s16(entity + 58);
      enemy.speed = s16(entity + 60);
      if (enemy.offense < 0 || enemy.defense < 0 || enemy.speed < 0) continue;
      double own =
          Math.sqrt(s.hp + 1.0)
              * (s.offense + 30)
              * Math.sqrt(s.defense + 30.0)
              * (1 + s.speed / 2000.0);
      double rival =
          Math.sqrt(hp + 1.0)
              * (enemy.offense + 30)
              * Math.sqrt(enemy.defense + 30.0)
              * (1 + enemy.speed / 2000.0);
      double ratio = rival / Math.max(1, own);
      enemy.difficulty = ratio < 0.70 ? 0 : ratio > 1.25 ? 2 : 1;
      s.enemies.add(enemy);
    }
  }

  private void addScriptExits(Snapshot snapshot) {
    for (int trigger = 51; trigger < 110; trigger++) {
      boolean present = false;
      for (byte value : snapshot.collision)
        if ((value & 255) == trigger) {
          present = true;
          break;
        }
      if (!present) continue;
      int target = EncounterClassifier.exitTarget(ram, profile, trigger);
      if (target < 0 || target == snapshot.mapId) continue;
      Exit exit = new Exit();
      exit.trigger = trigger;
      exit.target = target;
      exit.name = zone(target);
      for (int index = 0; index < snapshot.collision.length; index++)
        if ((snapshot.collision[index] & 255) == trigger) {
          exit.minX = Math.min(exit.minX, index % 100);
          exit.maxX = Math.max(exit.maxX, index % 100);
          exit.minY = Math.min(exit.minY, index / 100);
          exit.maxY = Math.max(exit.maxY, index / 100);
        }
      snapshot.exits.add(exit);
    }
  }

  private void evolution(Snapshot s) {
    if (s.type > 62) return;
    int path = profile.at("EVO_PATHS_DATA") + (s.type - 1) * 11 + 5;
    for (int i = 0; i < 6; i++) {
      int target = u8(path + i);
      if (target == 255 || target < 1 || target > 62) continue;
      Evolution e = new Evolution();
      e.type = target;
      e.name = digimon(target);
      int req = profile.at("EVO_REQ_DATA") + target * 28;
      int flags = u8(req + 26), care = s16(req + 14), weight = s16(req + 16);
      boolean careOK = (flags & 16) != 0 ? s.care <= care : s.care >= care;
      boolean weightOK = Math.abs(s.weight - weight) <= 5;
      int[] values = {s.maxHp / 10, s.maxMp / 10, s.offense, s.defense, s.speed, s.brains};
      String[] labels = {"PV", "PM", "Ataque", "Defensa", "Velocidad", "Inteligencia"};
      boolean statsOK = true;
      StringBuilder detail = new StringBuilder();
      if (level(target) == 3) {
        int highest = 0;
        for (int j = 1; j < 6; j++) if (values[j] >= values[highest]) highest = j;
        statsOK = s16(req + 2 + highest * 2) == 1;
        detail
            .append(mark(statsOK))
            .append(" Estadística dominante: ")
            .append(labels[highest])
            .append('\n');
      } else {
        for (int j = 0; j < 6; j++) {
          int threshold = s16(req + 2 + j * 2);
          if (threshold == -1) continue;
          boolean ok = values[j] >= threshold;
          statsOK &= ok;
          detail
              .append(mark(ok))
              .append(' ')
              .append(labels[j])
              .append(" ≥ ")
              .append(j < 2 ? threshold * 10 : threshold)
              .append('\n');
        }
      }
      boolean bonus = false;
      if (s16(req) != -1 && s.type == s16(req)) bonus = true;
      int[] bonusValues = {s.discipline, s.happiness, s.battles, s.moves.size()};
      String[] bonusLabels = {"Disciplina", "Felicidad", "Combates", "Técnicas"};
      StringBuilder bonusDetail = new StringBuilder();
      for (int j = 0; j < 4; j++) {
        int threshold = s16(req + 18 + j * 2);
        if (threshold == -1) continue;
        boolean max = j == 2 && (flags & 1) != 0;
        boolean ok = max ? bonusValues[j] <= threshold : bonusValues[j] >= threshold;
        bonus |= ok;
        bonusDetail
            .append(bonusLabels[j])
            .append(max ? " ≤ " : " ≥ ")
            .append(threshold)
            .append("; ");
      }
      detail
          .append(mark(careOK))
          .append(" Errores de cuidado ")
          .append((flags & 16) != 0 ? "≤ " : "≥ ")
          .append(care)
          .append(" (actual: ")
          .append(s.care)
          .append(")\n");
      detail
          .append(mark(weightOK))
          .append(" Peso ")
          .append(weight - 5)
          .append("–")
          .append(weight + 5)
          .append(" (actual: ")
          .append(s.weight)
          .append(")\n");
      detail
          .append(mark(bonus))
          .append(" Bonus: ")
          .append(bonusDetail.length() == 0 ? "afinidad de especie" : bonusDetail)
          .append('\n');
      e.score = (careOK ? 1 : 0) + (weightOK ? 1 : 0) + (statsOK ? 1 : 0) + (bonus ? 1 : 0);
      e.candidate = e.score >= 3;
      if (level(s.type) == 1) {
        e.candidate = level(target) == 2;
        detail = new StringBuilder("Evolución de bebé condicionada por el reloj del juego.\n");
      }
      e.details = detail.toString();
      s.evolutions.add(e);
    }
  }

  private static String mark(boolean yes) {
    return yes ? "✓" : "○";
  }
}
