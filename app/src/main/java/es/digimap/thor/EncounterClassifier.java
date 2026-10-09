package es.digimap.thor;

/**
 * Bounded, read-only inspection of current interaction scripts. Unknown branches are never guessed
 * to be combat or map transitions.
 */
final class EncounterClassifier {
  private final byte[] ram;
  private final int base, size;
  private final byte[] triggers, pstats;

  private EncounterClassifier(byte[] ram, int base, int size, int state) {
    this.ram = ram;
    this.base = base;
    this.size = size;
    triggers = java.util.Arrays.copyOfRange(ram, state + 245, state + 345);
    pstats = java.util.Arrays.copyOfRange(ram, state + 345, state + 601);
  }

  static boolean hostile(byte[] ram, GameData.Profile profile, int npc) {
    // Automatic contact begins a script; it does not by itself mean aggression.
    if (u8(ram, npc + 102) != 1) return false;
    EncounterClassifier script = currentScript(ram, profile);
    return script != null && script.section(u8(ram, npc + 101)) == -2;
  }

  static int exitTarget(byte[] ram, GameData.Profile profile, int trigger) {
    EncounterClassifier script = currentScript(ram, profile);
    return script == null ? -1 : script.section(trigger);
  }

  private static EncounterClassifier currentScript(byte[] ram, GameData.Profile profile) {
    String[] keys = {
      "SCRIPT_DATA_PTR",
      "SCRIPT_HEADER_PTR",
      "ACTIVE_MAP_SCRIPT",
      "CURRENT_SCRIPT_ID",
      "SCRIPT_STATE_PTR"
    };
    for (String key : keys) if (!profile.addresses.containsKey(key)) return null;
    int base = pointer(ram, profile.at("SCRIPT_DATA_PTR"), 8192);
    int header = pointer(ram, profile.at("SCRIPT_HEADER_PTR"), 8192);
    int state = pointer(ram, profile.at("SCRIPT_STATE_PTR"), 668);
    int script = u16(ram, profile.at("CURRENT_SCRIPT_ID"));
    if (base < 0
        || header < 0
        || state < 0
        || script < 1
        || script > 2046
        || u16(ram, profile.at("ACTIVE_MAP_SCRIPT")) != script) return null;
    long start = i32(ram, header + script * 4) & 0xffffffffL;
    long end = i32(ram, header + (script + 1) * 4) & 0xffffffffL;
    long length = end - start;
    if (length < 6 || length > 8192) return null;
    return new EncounterClassifier(ram, base, (int) length, state);
  }

  private int section(int id) {
    int header = word(0), pc = -1;
    if (header < 6 || header > size || (header & 1) != 0) return -1;
    for (int at = 2; at + 3 < header; at += 4) {
      int key = word(at);
      if (key == 65535) break;
      if (key == id) {
        pc = word(at + 2);
        break;
      }
    }
    if (pc < header || pc >= size) return -1;
    int[] returns = new int[16];
    int depth = 0;
    // A bound handles loops without allocating a graph or scanning text as opcodes.
    for (int instructions = 0; instructions < 512 && pc >= header && pc < size; instructions++) {
      int op = byteAt(pc);
      if (op == 0x1c || op == 0x1d) {
        if (pc + 4 > size) return -1;
        int trigger = word(pc + 2);
        if (trigger < 0 || trigger >= 800) return -1;
        if (op == 0x1c) triggers[trigger / 8] |= 1 << (trigger % 8);
        else triggers[trigger / 8] &= ~(1 << (trigger % 8));
        pc += 4;
        continue;
      }
      if (op >= 0x1e && op <= 0x20) {
        if (pc + 4 > size) return -1;
        int index = byteAt(pc + 2), value = byteAt(pc + 3);
        pstats[index] =
            (byte)
                (op == 0x1e
                    ? value
                    : op == 0x1f ? (pstats[index] & 255) + value : (pstats[index] & 255) - value);
        pc += 4;
        continue;
      }
      if (op == 0x66) return pc + 1 < size ? -2 : -1;
      if (op == 0x4b) {
        if (pc + 4 > size) return -1;
        int target = byteAt(pc + 1);
        return target < 255 ? target : -1;
      }
      if (op == 0x58) {
        if (pc + 2 > size || byteAt(pc + 1) >= 255) return -1;
        int target = pstats[byteAt(pc + 1)] & 255;
        return target < 255 ? target : -1;
      }
      if (op == 0xfe
          || op == 0xff
          || op == 0x10
          || op == 0x14
          || op == 0x17
          || op == 0x49
          || op == 0x64
          || op == 0xfb) return -1;
      if (op == 0x13 || op == 0x16) {
        if (pc + 4 > size) return -1;
        if (op == 0x13) {
          if (depth == returns.length) return -1;
          returns[depth++] = pc + 4;
        }
        pc = word(pc + 2);
        continue;
      }
      if (op == 0x15) {
        if (depth == 0) return -1;
        pc = returns[--depth];
        continue;
      }
      if (op == 0x19) {
        pc = condition(pc + 2);
        continue;
      }
      if (op == 0x1a) {
        pc += 2;
        boolean ended = false;
        while (pc + 1 < size) {
          int glyph = word(pc);
          pc += 2;
          if (glyph == 0) {
            ended = true;
            break;
          }
        }
        if (!ended) return -1;
        continue;
      }
      int length = fixedLength(op);
      if (length == 0 || pc > size - length) return -1;
      pc += length;
    }
    return -1;
  }

  private int condition(int pc) {
    boolean result = false;
    for (int entries = 0; entries < 64 && pc + 3 < size; entries++) {
      int op = byteAt(pc), group = op & 0x38, operand = word(pc + 2);
      if (op == 0x19) return pc + 2;
      if (group == 0x10 || group == 0x18) {
        if ((group == 0x10) == result) return operand;
        pc += 4;
        continue;
      }
      boolean value;
      if (group == 0) {
        if (operand >= 800) return -1;
        boolean set = ((triggers[operand / 8] & 255) & (1 << (operand % 8))) != 0;
        value = (op & 7) == 0 ? set : !set;
      } else if (group == 8) {
        int index = byteAt(pc + 2);
        if ((op & 7) > 5) return -1;
        value = compare(pstats[index] & 255, byteAt(pc + 3), op & 7);
      } else {
        // Stat/choice-dependent encounters need dedicated validation before exposing them.
        return -1;
      }
      result = (op & 0x80) != 0 ? result && value : (op & 0x40) != 0 ? result || value : value;
      pc += 4;
    }
    return -1;
  }

  private static boolean compare(int left, int right, int op) {
    switch (op) {
      case 0:
        return left == right;
      case 1:
        return left != right;
      case 2:
        return left >= right;
      case 3:
        return left <= right;
      case 4:
        return left > right;
      case 5:
        return left < right;
      default:
        return false;
    }
  }

  private static int fixedLength(int op) {
    switch (op) {
      case 0x1c:
      case 0x1d:
      case 0x1e:
      case 0x1f:
      case 0x20:
      case 0x24:
      case 0x26:
      case 0x28:
      case 0x29:
      case 0x31:
      case 0x32:
      case 0x33:
      case 0x34:
      case 0x35:
      case 0x36:
      case 0x3a:
      case 0x3b:
      case 0x3c:
      case 0x3f:
      case 0x47:
      case 0x4b:
      case 0x4c:
      case 0x4d:
      case 0x50:
      case 0x51:
      case 0x53:
      case 0x56:
      case 0x57:
      case 0x5a:
      case 0x67:
      case 0x68:
      case 0x6a:
      case 0x6d:
      case 0x6f:
      case 0x70:
      case 0x7a:
        return 4;
      case 0x2a:
      case 0x2b:
      case 0x38:
      case 0x39:
      case 0x4f:
      case 0x74:
      case 0x72:
      case 0x73:
        return 6;
      case 0x4e:
      case 0x52:
      case 0x55:
      case 0x6c:
      case 0x6e:
      case 0x7c:
      case 0x7e:
        return 8;
      case 0x2c:
      case 0x71:
      case 0x7d:
        return 10;
      case 0x75:
        return 12;
      case 0x6b:
        return 0; // Variable data has no verified execution semantics here.
      default:
        return (op == 0x1b
                || op == 0x21
                || op == 0x22
                || op == 0x23
                || op == 0x25
                || op == 0x27
                || op >= 0x2d && op <= 0x30
                || op == 0x37
                || op == 0x3d
                || op == 0x3e
                || op == 0x46
                || op == 0x48
                || op == 0x4a
                || op == 0x54
                || op == 0x58
                || op >= 0x5b && op <= 0x5f
                || op == 0x65
                || op == 0x69
                || op >= 0x76 && op <= 0x79
                || op == 0x7b)
            ? 2
            : 0;
    }
  }

  private int byteAt(int at) {
    return at >= 0 && at < size ? u8(ram, base + at) : -1;
  }

  private int word(int at) {
    return at >= 0 && at + 1 < size ? u16(ram, base + at) : -1;
  }

  private static int pointer(byte[] ram, int at, int length) {
    int raw = i32(ram, at), offset = raw & 0x1fffff;
    return (raw & 0xffe00000) == 0x80000000 && offset >= 0x1000 && offset <= ram.length - length
        ? offset
        : -1;
  }

  private static int u8(byte[] ram, int at) {
    return at >= 0 && at < ram.length ? ram[at] & 255 : 0;
  }

  private static int u16(byte[] ram, int at) {
    return u8(ram, at) | u8(ram, at + 1) << 8;
  }

  private static int i32(byte[] ram, int at) {
    return u16(ram, at) | u16(ram, at + 2) << 16;
  }
}
