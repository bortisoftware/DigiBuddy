package es.digimap.thor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RecruitmentHints {
  static final class Hint {
    final GameData.Recruit recruit;
    final String location, clue, requirements, proximity;
    private final int distance;

    Hint(GameData.Recruit recruit, Entry entry, int distance, String requirements) {
      this.recruit = recruit;
      location = AppLanguage.catalogText(DataNames.ZONES[entry.zone]);
      clue = AppLanguage.catalogText(entry.clue);
      this.requirements = requirements;
      this.distance = distance;
      proximity =
          distance == 0
              ? AppLanguage.text("text_in_your_area")
              : distance == 1
                  ? AppLanguage.text("text_one_exit_away")
                  : AppLanguage.text("text_in_this_region");
    }
  }

  private static final class Entry {
    final int type, zone, prosperity, prerequisite;
    final String clue;

    Entry(int type, int zone, int prosperity, int prerequisite, String clue) {
      this.type = type;
      this.zone = zone;
      this.prosperity = prosperity;
      this.prerequisite = prerequisite;
      this.clue = clue;
    }
  }

  private static Entry clue(int type, int zone, String clue) {
    return new Entry(type, zone, 0, -1, clue);
  }

  private static final Entry[] ENTRIES = {
    clue(3, 0, "@text_the_first_encounter_in_the_forest_ends_in_battle"),
    clue(46, 0, "@text_look_south_of_the_toilet_keep_talking"),
    clue(32, 68, "@text_bring_food_for_the_one_waiting_in_the_tree"),
    clue(49, 1, "@text_visit_the_coast_at_dusk_return_after_crossing"),
    new Entry(5, 8, 15, -1, "@text_talk_to_jijimon_and_leave_his_house"),
    new Entry(7, 8, 50, -1, "@text_jijimon_announces_the_mountain_s_opening_leave_prepared"),
    new Entry(42, 0, 50, -1, "@text_check_the_tree_with_a_door"),
    clue(58, 4, "@text_look_for_the_ninja_once_the_secret_shop_exists"),
    clue(4, 6, "@text_talk_to_the_digimon_lost_among_the_mangroves"),
    new Entry(25, 5, 0, 46, "@text_a_wilting_plant_needs_the_rain_plant"),
    clue(36, 69, "@text_the_correct_route_ends_at_the_circular_sign"),
    clue(55, 5, "@text_the_beach_visitor_only_appears_occasionally"),
    clue(9, 17, "@text_help_clear_the_tunnel_to_reach_the_lava"),
    new Entry(38, 12, 0, 9, "@text_return_to_the_digger_after_a_few_days"),
    clue(34, 12, "@text_chasing_the_bandit_ends_in_this_tunnel"),
    new Entry(33, 11, 0, 36, "@text_talk_at_the_clinic_first_bring_recovery_items"),
    clue(13, 9, "@text_a_rare_visitor_may_challenge_you_here"),
    new Entry(28, 11, 45, 35, "@text_the_circles_and_the_news_lead_to_a_visitor"),
    clue(18, 30, "@text_be_patient_with_its_electric_shocks_and_talk_again"),
    clue(31, 30, "@text_this_rival_needs_several_consecutive_victories"),
    clue(45, 30, "@text_your_partner_can_help_cut_off_its_escape"),
    new Entry(48, 30, 45, 9, "@text_look_for_his_legacy_in_the_ancestor_cave"),
    clue(39, 56, "@text_talk_to_the_subject_near_the_king"),
    clue(37, 19, "@text_your_answers_matter_more_than_fighting"),
    new Entry(
        26, 29, 40, 35, "@text_investigate_myotismon_s_disappearance_you_need_a_virus_digimon"),
    clue(47, 26, "@text_prove_your_skill_by_managing_his_shop"),
    clue(21, 21, "@text_there_is_a_nest_at_the_top_of_the_canyon"),
    clue(35, 39, "@text_the_bandit_s_elevator_leaves_someone_trapped"),
    new Entry(8, 32, 0, 36, "@text_investigate_the_problem_in_the_time_region"),
    clue(53, 33, "@text_this_traveler_appears_in_five_places"),
    clue(53, 25, "@text_this_traveler_appears_in_five_places"),
    clue(53, 37, "@text_this_traveler_appears_in_five_places"),
    clue(53, 50, "@text_this_traveler_appears_in_five_places"),
    clue(53, 57, "@text_this_traveler_appears_in_five_places"),
    clue(52, 34, "@text_complete_the_trades_with_the_three_merchants"),
    clue(57, 34, "@text_a_curling_victory_may_convince_him"),
    clue(24, 34, "@text_help_reclaim_the_cave_occupied_by_bandits"),
    clue(23, 34, "@text_after_the_rescue_return_with_a_partner_resistant_to_cold"),
    clue(22, 34, "@text_the_rematch_has_its_own_time_and_rules"),
    clue(20, 35, "@text_a_vaccine_partner_can_open_the_sanctuary"),
    clue(50, 38, "@text_look_for_the_bird_before_the_morning_ends"),
    clue(17, 38, "@text_a_shy_resident_hides_behind_a_tree"),
    clue(14, 62, "@text_numemon_can_find_a_use_for_an_empty_costume"),
    clue(19, 58, "@text_talk_about_training_with_the_island_s_residents"),
    clue(51, 58, "@text_ask_about_training_inside_his_gym"),
    clue(11, 57, "@text_first_solve_the_factory_crisis"),
    clue(40, 52, "@text_investigate_the_factory_and_return_after_solving_its_problem"),
    clue(41, 52, "@text_stop_the_saboteur_and_return_later"),
    clue(27, 52, "@text_this_factory_visitor_appears_occasionally"),
    clue(6, 59, "@text_a_battle_awaits_you_in_the_open_mountain"),
    clue(54, 59, "@text_look_for_another_challenge_inside_the_mountain"),
    clue(12, 59, "@text_keep_going_through_the_mountain_until_you_find_him"),
    clue(56, 59, "@text_return_to_the_mountain_after_completing_the_story")
  };

  static List<Hint> nearby(GameData.Snapshot snapshot) {
    List<Hint> hints = new ArrayList<>();
    if (!snapshot.valid || !snapshot.prosperityAvailable || snapshot.zoneId < 0) return hints;
    for (Entry entry : ENTRIES) {
      GameData.Recruit recruit = pending(snapshot, entry.type);
      if (recruit == null) continue;
      int distance = distance(snapshot, entry.zone);
      if (distance < 0) continue;
      hints.add(new Hint(recruit, entry, distance, requirements(snapshot, entry)));
    }
    hints.sort(
        Comparator.comparingInt((Hint hint) -> hint.distance)
            .thenComparingInt(hint -> hint.requirements.isEmpty() ? 0 : 1)
            .thenComparingInt(hint -> hint.recruit.type));
    List<Hint> selected = new ArrayList<>();
    for (Hint hint : hints) {
      boolean duplicate = false;
      for (Hint previous : selected)
        if (previous.recruit.type == hint.recruit.type) duplicate = true;
      if (!duplicate) selected.add(hint);
      if (selected.size() == 4) break;
    }
    return selected;
  }

  static Hint atLocation(GameData.Snapshot snapshot, int type) {
    if (!snapshot.prosperityAvailable) return null;
    GameData.Recruit recruit = pending(snapshot, type);
    if (recruit == null) return null;
    for (Entry entry : ENTRIES)
      if (entry.type == type && entry.zone == snapshot.zoneId)
        return new Hint(recruit, entry, 0, requirements(snapshot, entry));
    return null;
  }

  private static GameData.Recruit pending(GameData.Snapshot snapshot, int type) {
    for (GameData.Recruit recruit : snapshot.pendingRecruits)
      if (recruit.type == type) return recruit;
    return null;
  }

  private static String requirements(GameData.Snapshot snapshot, Entry entry) {
    List<String> missing = new ArrayList<>();
    if (snapshot.prosperity < entry.prosperity)
      missing.add(AppLanguage.text("text_prosperity_3") + entry.prosperity);
    if (entry.prerequisite >= 0 && pending(snapshot, entry.prerequisite) != null)
      missing.add(
          AppLanguage.text("text_recruit")
              + AppLanguage.catalogText(DataNames.DIGIMON[entry.prerequisite]));
    return String.join(" · ", missing);
  }

  private static int distance(GameData.Snapshot snapshot, int zone) {
    if (snapshot.zoneId == zone) return 0;
    for (GameData.Exit exit : snapshot.exits)
      if (AppLanguage.catalogText(DataNames.ZONES[zone]).equals(exit.name)) return 1;
    int region = region(snapshot.zoneId);
    return region >= 0 && region == region(zone) ? 2 : -1;
  }

  private static int region(int zone) {
    switch (zone) {
      case 0:
      case 1:
      case 2:
      case 4:
      case 68:
        return 0;
      case 5:
      case 6:
      case 69:
        return 1;
      case 3:
      case 12:
      case 13:
      case 14:
      case 15:
      case 16:
      case 17:
      case 37:
        return 2;
      case 7:
      case 9:
      case 10:
      case 11:
        return 3;
      case 8:
      case 36:
      case 43:
      case 44:
      case 45:
      case 46:
      case 47:
      case 48:
      case 49:
      case 53:
      case 54:
      case 55:
      case 60:
        return 4;
      case 18:
      case 19:
      case 27:
      case 28:
      case 29:
      case 64:
      case 65:
      case 66:
        return 5;
      case 20:
      case 21:
      case 22:
      case 23:
      case 24:
      case 25:
      case 26:
      case 39:
        return 6;
      case 30:
      case 56:
        return 7;
      case 31:
      case 32:
      case 33:
        return 8;
      case 34:
      case 35:
      case 40:
        return 9;
      case 38:
        return 10;
      case 50:
      case 61:
      case 62:
      case 63:
        return 11;
      case 51:
        return 12;
      case 52:
      case 57:
        return 13;
      case 58:
        return 14;
      case 59:
        return 15;
      default:
        return -1;
    }
  }
}
