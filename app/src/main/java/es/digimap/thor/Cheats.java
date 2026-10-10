package es.digimap.thor;

import java.util.ArrayList;

/** Data-only writes to a fingerprinted game's RAM, on the emulation thread. */
final class Cheats {
  static final String[] LABELS = {
    "@text_restore_hp_and_mp",
    "@text_999_999_bits",
    "@text_maximize_stats",
    "@text_happiness_and_discipline_to_100",
    "@text_clear_fatigue",
    "@text_clear_care_mistakes",
    "@text_current_items_99",
    "@text_learn_all_techniques"
  };
  static final String[] DESCRIPTIONS = {
    "@text_restores_your_partner_s_hp_and_mp",
    "@text_sets_your_money_to_999_999_bits",
    "@text_sets_hp_and_mp_to_9_999_and_the_other_stats_to_999",
    "@text_sets_happiness_and_discipline_to_100",
    "@text_clears_your_partner_s_fatigue",
    "@text_clears_accumulated_care_mistakes",
    "@text_sets_every_item_already_in_your_bag_to_99_units",
    "@text_your_partner_learns_all_56_techniques"
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
