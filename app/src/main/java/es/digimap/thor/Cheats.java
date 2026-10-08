package es.digimap.thor;

import java.util.ArrayList;

/** Data-only writes to a fingerprinted game's RAM, on the emulation thread. */
final class Cheats {
  static final String[] LABELS = {
    "Curar PV y PM",
    "999.999 bits",
    "Estadísticas al máximo",
    "Felicidad y disciplina al 100",
    "Cansancio a cero",
    "Errores de cuidado a cero",
    "Objetos actuales ×99",
    "Aprender todas las técnicas"
  };
  static final String[] DESCRIPTIONS = {
    "Restaura los PV y PM de tu compañero.",
    "Fija tu dinero en 999.999 bits.",
    "Fija PV y PM en 9.999 y las otras estadísticas en 999.",
    "Fija felicidad y disciplina en 100.",
    "Elimina el cansancio de tu compañero.",
    "Pone a cero los errores de cuidado acumulados.",
    "Pone 99 unidades de cada objeto que ya llevas en la bolsa.",
    "Tu compañero aprende las 56 técnicas."
  };

  static int[][] plan(int id, GameData.Profile p, GameData.Snapshot s) {
    ArrayList<int[]> writes = new ArrayList<>();
    if (p == null || !s.valid) return new int[0][];
    int entity = p.at("PARTNER_ENTITY"), para = p.at("PARTNER_PARA"), inv = p.at("INVENTORY");
    switch (id) {
      case 0:
        writes.add(new int[] {entity + 76, s.maxHp, 2});
        writes.add(new int[] {entity + 78, s.maxMp, 2});
        break;
      case 1:
        writes.add(new int[] {p.at("MONEY"), 999999, 4});
        break;
      case 2:
        for (int at = 56; at <= 62; at += 2) writes.add(new int[] {entity + at, 999, 2});
        for (int at = 72; at <= 78; at += 2) writes.add(new int[] {entity + at, 9999, 2});
        break;
      case 3:
        writes.add(new int[] {para + 40, 100, 2});
        writes.add(new int[] {para + 42, 100, 2});
        break;
      case 4:
        writes.add(new int[] {para + 34, 0, 2});
        break;
      case 5:
        writes.add(new int[] {para + 82, 0, 2});
        break;
      case 6:
        break; // occupied inventory slots are selected from live RAM in the caller
      case 7:
        writes.add(new int[] {entity + 88, -1, 4});
        writes.add(new int[] {entity + 92, 0x03ffffff, 4});
        break;
    }
    return writes.toArray(new int[0][]);
  }
}
