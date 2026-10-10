package es.digimap.thor;

import android.content.Context;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class StartupView extends ScrollView {
  interface Actions {
    void chooseLanguage(String language);

    void next();

    void importBios();

    void importGame();

    void finish();

    void resume();

    void loadGameCard();

    void newGame();

    void settings();
  }

  private final Actions actions;
  private String rendered = "";
  private boolean home;
  private String chosenLanguage;

  StartupView(Context context, Actions actions) {
    super(context);
    this.actions = actions;
    chosenLanguage = AppLanguage.systemLanguage(context.getResources().getConfiguration());
    setFillViewport(true);
    setVerticalScrollBarEnabled(false);
    setHorizontalScrollBarEnabled(false);
    setBackgroundColor(0xff4267a0);
  }

  void update(
      int step,
      boolean busy,
      String progress,
      String compatibility,
      boolean canResume,
      String savedDate) {
    String identity =
        AppLanguage.locale().getLanguage()
            + "|"
            + step
            + "|"
            + busy
            + "|"
            + progress
            + "|"
            + compatibility
            + "|"
            + canResume
            + "|"
            + savedDate;
    if (identity.equals(rendered)) return;
    rendered = identity;
    home = step == StartupFlow.HOME;
    removeAllViews();
    LinearLayout page = new LinearLayout(getContext());
    page.setOrientation(LinearLayout.VERTICAL);
    page.setGravity(Gravity.CENTER);
    page.setPadding(dp(36), dp(home ? 14 : 22), dp(36), dp(home ? 14 : 22));
    addView(page, new ScrollView.LayoutParams(-1, -1));
    LinearLayout card = new LinearLayout(getContext());
    card.setOrientation(LinearLayout.VERTICAL);
    card.setGravity(Gravity.CENTER);
    card.setPadding(dp(28), dp(home ? 12 : 18), dp(28), dp(home ? 12 : 18));
    card.setBackground(
        new RetroSkin.Frame(RetroSkin.PAPER, getResources().getDisplayMetrics().density));
    page.addView(card, new LinearLayout.LayoutParams(-1, -2));
    ImageView mascot = new ImageView(getContext());
    int image =
        getResources()
            .getIdentifier("jijimon_foreground", "drawable", getContext().getPackageName());
    mascot.setImageResource(image);
    mascot.setScaleType(ImageView.ScaleType.FIT_CENTER);
    card.addView(mascot, new LinearLayout.LayoutParams(dp(home ? 40 : 64), dp(home ? 40 : 64)));
    label(card, "DIGIBUDDY", home ? 22 : 26);
    if (step >= StartupFlow.WELCOME && step != StartupFlow.HOME)
      label(card, AppLanguage.text("text_welcome_bios_game_ready"), 12);
    if (step == StartupFlow.LANGUAGE) {
      label(card, AppLanguage.text("language_welcome"), 20);
      android.widget.RadioGroup languages = new android.widget.RadioGroup(getContext());
      languages.setOrientation(LinearLayout.HORIZONTAL);
      languages.setGravity(Gravity.CENTER);
      android.widget.RadioButton spanish = languageOption("Español", 1);
      android.widget.RadioButton english = languageOption("English", 2);
      languages.addView(spanish);
      languages.addView(english);
      languages.check("es".equals(chosenLanguage) ? 1 : 2);
      languages.setOnCheckedChangeListener(
          (group, checked) -> chosenLanguage = checked == 1 ? "es" : "en");
      card.addView(languages);
      label(card, AppLanguage.text("language_game_note"), 13);
      action(
          card,
          AppLanguage.text("language_continue"),
          () -> actions.chooseLanguage(chosenLanguage),
          !busy);
    } else if (step == StartupFlow.WELCOME) {
      label(card, AppLanguage.text("text_your_adventure_starts_here"), 20);
      label(
          card,
          AppLanguage.text(
              "text_add_your_playstation_bios_and_your_copy_of_digimon_world_digibuddy_does_not_include_t"),
          15);
      action(card, AppLanguage.text("text_get_started"), actions::next, !busy);
    } else if (step == StartupFlow.BIOS) {
      label(card, AppLanguage.text("text_1_add_your_bios"), 20);
      label(card, AppLanguage.text("text_select_your_512_kib_playstation_bios_file"), 15);
      action(
          card,
          busy ? AppLanguage.text("text_importing_2") : AppLanguage.text("text_select_bios"),
          actions::importBios,
          !busy);
    } else if (step == StartupFlow.GAME) {
      label(card, AppLanguage.text("text_bios_ready"), 14);
      label(card, AppLanguage.text("text_2_add_digimon_world"), 20);
      label(
          card,
          AppLanguage.text(
              "text_select_the_iso_bin_img_or_chd_disc_companion_panel_compatibility_depends_on_the_editi"),
          15);
      action(
          card,
          busy ? AppLanguage.text("text_importing_2") : AppLanguage.text("text_select_game"),
          actions::importGame,
          !busy);
    } else if (step == StartupFlow.READY) {
      label(card, AppLanguage.text("text_bios_and_game_ready"), 20);
      label(card, compatibility, 14);
      label(
          card,
          AppLanguage.text(
              "text_the_game_appears_above_your_partner_bag_map_and_evolutions_appear_below"),
          15);
      action(card, AppLanguage.text("text_finish_setup"), actions::finish, !busy);
    } else {
      action(
          card,
          AppLanguage.text("text_continue_latest_session"),
          actions::resume,
          canResume && !busy);
      if (canResume)
        label(
            card,
            AppLanguage.text("text_state_from")
                + savedDate
                + AppLanguage.text("text_returns_to_that_exact_point"),
            12);
      action(
          card,
          AppLanguage.text("text_load_in_game_save"),
          actions::loadGameCard,
          !busy,
          !canResume);
      label(
          card,
          AppLanguage.text(
              "text_starts_the_game_from_the_beginning_choose_load_in_its_menu_to_use_your_memory_card"),
          12);
      if (!canResume)
        label(
            card,
            AppLanguage.text(
                "text_there_is_no_saved_session_yet_start_a_game_you_can_load_your_memory_card_from_the_in"),
            13);
      action(card, AppLanguage.text("text_new_game"), actions::newGame, !busy, false);
      action(card, AppLanguage.text("text_settings"), actions::settings, !busy, false);
    }
    if (!progress.isEmpty()) label(card, progress, 13);
  }

  private android.widget.RadioButton languageOption(String name, int identifier) {
    android.widget.RadioButton button = new android.widget.RadioButton(getContext());
    button.setId(identifier);
    button.setText(name);
    button.setTextColor(RetroSkin.INK);
    button.setMinHeight(dp(48));
    button.setPadding(dp(12), dp(4), dp(12), dp(4));
    return button;
  }

  private void label(LinearLayout parent, String value, int size) {
    TextView text = new TextView(getContext());
    text.setText(value);
    text.setTextSize(size);
    text.setTextColor(RetroSkin.INK);
    text.setGravity(Gravity.CENTER);
    text.setPadding(dp(4), dp(home ? 3 : 6), dp(4), dp(home ? 3 : 6));
    parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
  }

  private void action(LinearLayout parent, String title, Runnable action, boolean enabled) {
    action(parent, title, action, enabled, true);
  }

  private void action(
      LinearLayout parent, String title, Runnable action, boolean enabled, boolean primary) {
    Button button = new Button(getContext());
    button.setText(title);
    button.setAllCaps(false);
    button.setTextColor(RetroSkin.INK);
    button.setBackground(
        new RetroSkin.Frame(
            primary ? RetroSkin.GOLD : 0xffe1e8d6, getResources().getDisplayMetrics().density));
    button.setEnabled(enabled);
    button.setAlpha(enabled ? 1f : .45f);
    button.setOnClickListener(view -> action.run());
    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(48));
    size.topMargin = dp(8);
    parent.addView(button, size);
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
