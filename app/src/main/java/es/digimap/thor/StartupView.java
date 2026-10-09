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

  StartupView(Context context, Actions actions) {
    super(context);
    this.actions = actions;
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
        step
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
    if (step != StartupFlow.HOME) label(card, "Bienvenida  ·  BIOS  ·  Juego  ·  Listo", 12);
    if (step == StartupFlow.WELCOME) {
      label(card, "Tu aventura empieza aquí", 20);
      label(
          card,
          "Añade tu BIOS de PlayStation y tu copia de Digimon World. DigiBuddy no incluye estos"
              + " archivos.",
          15);
      action(card, "Comenzar", actions::next, !busy);
    } else if (step == StartupFlow.BIOS) {
      label(card, "1 · Añade la BIOS", 20);
      label(card, "Selecciona tu archivo de BIOS de PlayStation de 512 KiB.", 15);
      action(card, busy ? "Importando…" : "Seleccionar BIOS", actions::importBios, !busy);
    } else if (step == StartupFlow.GAME) {
      label(card, "BIOS preparada ✓", 14);
      label(card, "2 · Añade Digimon World", 20);
      label(
          card,
          "Selecciona el disco .iso, .bin, .img o .chd. La compatibilidad del panel depende de la"
              + " edición.",
          15);
      action(card, busy ? "Importando…" : "Seleccionar juego", actions::importGame, !busy);
    } else if (step == StartupFlow.READY) {
      label(card, "BIOS y juego preparados ✓", 20);
      label(card, compatibility, 14);
      label(card, "Arriba verás el juego. Abajo tendrás compañero, bolsa, mapa y evoluciones.", 15);
      action(card, "Terminar configuración", actions::finish, !busy);
    } else {
      action(card, "Continuar última sesión", actions::resume, canResume && !busy);
      if (canResume) label(card, "Estado del " + savedDate + " · vuelve a ese punto exacto.", 12);
      action(card, "Cargar partida del juego", actions::loadGameCard, !busy, !canResume);
      label(
          card,
          "Abre el juego desde el inicio y elige Cargar en su menú. Usa tu tarjeta de memoria.",
          12);
      if (!canResume)
        label(
            card,
            "Todavía no hay una sesión guardada. Empieza una partida; podrás cargar tu tarjeta"
                + " desde el menú del juego.",
            13);
      action(card, "Nueva partida", actions::newGame, !busy, false);
      action(card, "Ajustes", actions::settings, !busy, false);
    }
    if (!progress.isEmpty()) label(card, progress, 13);
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
