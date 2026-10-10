package es.digimap.thor;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity implements DisplayManager.DisplayListener {
  private static final int BG = 0xff4267a0, ACCENT = 0xff315a9b, MUTED = 0xff62758a;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final EmulatorSession session = new EmulatorSession();
  private final List<GameData.Profile> profiles = new ArrayList<>();
  private CardExporter cardExporter;
  private FileImporter fileImporter;
  private StateTransfer stateTransfer;
  private ReleaseUpdates releaseUpdates;
  private ReleaseUpdates.Result availableUpdate;
  private boolean updateNoticeShown;
  private ApkDownloader apkDownloader;
  private Uri pendingInstall;
  private boolean awaitingInstallPermission;
  private SharedPreferences prefs;
  private DisplayManager displays;
  private Presentation secondary;
  private Panel panel;
  private final List<StartupView> startupViews = new ArrayList<>();
  private final List<SurfaceView> gameScreens = new ArrayList<>();
  private volatile SpriteAtlas atlas;
  private int atlasGeneration;
  private volatile File cheatBackup;
  private Button playButton;
  private Dialog panelDialog;
  private ControllerBindings controllerBindings;
  private android.hardware.input.InputManager inputManager;
  private final android.hardware.input.InputManager.InputDeviceListener inputDeviceListener =
      new android.hardware.input.InputManager.InputDeviceListener() {
        public void onInputDeviceAdded(int id) {}

        public void onInputDeviceChanged(int id) {
          clearControllerInputs();
        }

        public void onInputDeviceRemoved(int id) {
          clearControllerInputs();
        }
      };
  private ControllerView controllerView;
  private TextView controllerStatus;
  private TextView captureStatus;
  private Dialog captureDialog;
  private int captureTarget = -1;
  private String mappingDevice = "builtin", pendingSource;
  private Button replaceBinding;
  private FrameLayout root;
  private GameData.Profile profile;
  private boolean gameOnSecondary, importing;
  private volatile boolean destroyed;
  private int selectedDisplay = -1;
  private int selectedTab = 0;
  private String importStatus = "", lastError = "", lastSaveWarning = "";
  private byte[] lastRam;
  private GameData.Snapshot snapshot = new GameData.Snapshot();
  private final Runnable refresh =
      new Runnable() {
        @Override
        public void run() {
          if (destroyed) return;
          if (session.ram != lastRam) {
            lastRam = session.ram;
            if (profile == null && lastRam != null) profile = detectProfile(lastRam);
            snapshot = GameData.decode(lastRam, profile);
          }
          showUpdateNotice();
          updateStartupViews();
          if (panel != null) panel.update();
          String itemNotice = session.actionNotice.getAndSet(null);
          if (itemNotice != null) showMessage(itemNotice);
          if (session.error != null && !session.error.equals(lastError)) {
            lastError = session.error;
            showMessage(lastError);
          }
          if (session.saveWarning != null && !session.saveWarning.equals(lastSaveWarning)) {
            lastSaveWarning = session.saveWarning;
            showMessage(lastSaveWarning);
          }
          ui.postDelayed(this, 250);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    if (relocateToPrimaryDisplay()) return;
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    fullscreen(getWindow().getDecorView());
    prefs = getSharedPreferences("digimap", MODE_PRIVATE);
    AppLanguage.apply(this, prefs);
    controllerBindings = new ControllerBindings(prefs);
    inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
    if (inputManager != null) inputManager.registerInputDeviceListener(inputDeviceListener, ui);
    releaseUpdates = new ReleaseUpdates();
    WeakReference<MainActivity> activity = new WeakReference<>(this);
    Handler mainHandler = ui;
    stateTransfer =
        new StateTransfer(
            getApplicationContext().getContentResolver(),
            message ->
                mainHandler.post(
                    () -> {
                      MainActivity current = activity.get();
                      if (current != null && !current.destroyed) {
                        current.showMessage(message);
                        current.updateStartupViews();
                      }
                    }));
    cardExporter =
        new CardExporter(
            getApplicationContext().getContentResolver(),
            message ->
                mainHandler.post(
                    () -> {
                      MainActivity current = activity.get();
                      if (current != null && !current.destroyed) current.showMessage(message);
                    }));
    fileImporter =
        new FileImporter(
            getApplicationContext().getContentResolver(),
            getFilesDir(),
            new ImportCallbacks(activity, mainHandler));
    // RG DS exposes the lower panel as the default Android display.
    gameOnSecondary = prefs.getBoolean("gameOnSecondary", "RG DS".equals(android.os.Build.MODEL));
    selectedDisplay = prefs.getInt("selectedDisplay", -1);
    if (!prefs.contains("setupComplete") && filesReady())
      prefs.edit().putBoolean("setupComplete", true).apply();
    displays.registerDisplayListener(this, ui);
    loadProfiles();
    rebuildScreens();
    ui.post(refresh);
    if (getIntent().getBooleanExtra("devStart", false)
        && (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
      ui.postDelayed(this::startGame, 500);
  }

  private boolean relocateToPrimaryDisplay() {
    Display current = getWindowManager().getDefaultDisplay();
    Display primary = displays.getDisplay(Display.DEFAULT_DISPLAY);
    if (getIntent().getBooleanExtra("primaryDisplayRedirect", false)
        || primary == null
        || current.getDisplayId() == Display.DEFAULT_DISPLAY
        || (current.getFlags() & Display.FLAG_PRESENTATION) == 0
        || (primary.getFlags() & Display.FLAG_PRESENTATION) != 0) return false;
    // Some handheld launchers start apps on the presentation display; Android rejects
    // presenting on their default display, so the activity must own that display instead.
    Intent launch = new Intent(this, MainActivity.class);
    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    launch.putExtra("primaryDisplayRedirect", true);
    ActivityOptions options = ActivityOptions.makeBasic();
    options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
    try {
      startActivity(launch, options.toBundle());
      finish();
      return true;
    } catch (RuntimeException error) {
      android.util.Log.w(
          "DigiBuddy", "primary_display_launch_failed: " + error.getClass().getSimpleName());
      return false;
    }
  }

  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0)
      return;
    if (intent.getBooleanExtra("devStart", false)) ui.postDelayed(this::startGame, 200);
    String action = intent.getStringExtra("devCommand");
    if ("save".equals(action))
      session.command(
          () -> {
            NativeCore.saveState(
                new File(session.saveFolder, prefs.getString("engine", "swan-gl") + "-quick.state")
                    .getAbsolutePath());
          });
    if ("load".equals(action))
      session.command(
          () -> {
            boolean ok =
                NativeCore.loadState(
                    new File(
                            session.saveFolder,
                            prefs.getString("engine", "swan-gl") + "-quick.state")
                        .getAbsolutePath());
            session.ram = NativeCore.memory();
            ui.post(
                () ->
                    showMessage(
                        ok
                            ? AppLanguage.text("text_game_restored")
                            : AppLanguage.text("text_could_not_restore_the_game")));
          });
    if ("ram".equals(action))
      session.command(
          () -> {
            try (FileOutputStream out =
                new FileOutputStream(new File(getFilesDir(), "debug-ram.bin"))) {
              out.write(NativeCore.memory());
            } catch (Exception ignored) {
            }
          });
    if ("tab".equals(action) && panel != null)
      panel.select(Math.max(0, Math.min(7, intent.getIntExtra("devTab", 0))));
    if ("confirm".equals(action) && panel != null) {
      panel.select(7);
      confirmAction(
          AppLanguage.catalogText(Cheats.LABELS[0]),
          AppLanguage.catalogText(Cheats.DESCRIPTIONS[0]),
          () -> applyCheat(0));
    }
    if ("itemConfirm".equals(action) && panel != null) {
      panel.select(1);
      for (GameData.Item item : snapshot.items)
        if (item.id == intent.getIntExtra("devItem", -1)) {
          confirmItem(item);
          break;
        }
    }
    if ("itemUse".equals(action))
      for (GameData.Item item : snapshot.items)
        if (item.id == intent.getIntExtra("devItem", -1)) {
          session.useItem(profile, item.slot, item.id);
          break;
        }
    if ("evolutionConfirm".equals(action) && panel != null) {
      panel.select(4);
      for (GameData.Evolution evo : snapshot.evolutions)
        if (evo.type == intent.getIntExtra("devEvolution", -1)) {
          confirmEvolution(evo);
          break;
        }
    }
    if ("cancelConfirmation".equals(action) && panelDialog != null) panelDialog.dismiss();
    if ("resume".equals(action)) resumeGame();
    if ("stop".equals(action)) session.stop();
    if ("screens".equals(action)) rebuildScreens();
    if ("pause".equals(action)) session.paused = intent.getBooleanExtra("paused", true);
  }

  private void loadProfiles() {
    try {
      profiles.addAll(ProfileLoader.load(getAssets()));
    } catch (Exception ex) {
      android.util.Log.w("DigiBuddy", "profile_load_failed: " + ex.getClass().getSimpleName());
      showMessage(AppLanguage.text("text_could_not_read_the_app_s_profiles"));
    }
  }

  private GameData.Profile detectProfile(byte[] ram) {
    String serial = prefs.getString("serial", "");
    for (GameData.Profile p : profiles) {
      if (p.id.equals("us") && !serial.equals("SLUS-01032")) continue;
      if (!p.signatureHashes.isEmpty() && GameData.signatureMatches(ram, p)) return p;
    }
    return null;
  }

  private void rebuildScreens() {
    if (panelDialog != null) panelDialog.dismiss();
    session.touchButtons = 0;
    panel = null;
    startupViews.clear();
    gameScreens.clear();
    if (secondary != null) {
      secondary.dismiss();
      secondary = null;
    }
    root = new FrameLayout(this);
    root.setBackgroundColor(BG);
    setContentView(root);
    Display target = null;
    Display current = getWindowManager().getDefaultDisplay();
    for (Display display : displays.getDisplays()) {
      if (display.getDisplayId() == current.getDisplayId()) continue;
      if (target == null || display.getDisplayId() == selectedDisplay) target = display;
    }
    if (target != null) {
      try {
        secondary =
            new Presentation(this, target) {
              @Override
              public boolean dispatchKeyEvent(KeyEvent event) {
                return MainActivity.this.dispatchKeyEvent(event);
              }

              @Override
              public boolean onGenericMotionEvent(MotionEvent event) {
                return MainActivity.this.onGenericMotionEvent(event);
              }
            };
        secondary
            .getWindow()
            .addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        secondary.setContentView(
            gameOnSecondary
                ? gameView(secondary.getContext())
                : (panel = new Panel(secondary.getContext())));
        secondary.show();
        fullscreen(secondary.getWindow().getDecorView());
        root.addView(
            gameOnSecondary ? (panel = new Panel(this)) : gameView(this),
            new FrameLayout.LayoutParams(-1, -1));
      } catch (RuntimeException ex) {
        android.util.Log.w(
            "DigiBuddy", "secondary_display_failed: " + ex.getClass().getSimpleName());
        if (secondary != null) secondary.dismiss();
        secondary = null;
        singleScreen();
        showMessage(
            AppLanguage.text(
                "text_could_not_open_the_second_screen_use_screens_to_select_another_one"));
      }
    } else singleScreen();
  }

  private void singleScreen() {
    LinearLayout split = new LinearLayout(this);
    split.setOrientation(LinearLayout.HORIZONTAL);
    split.addView(gameView(this), new LinearLayout.LayoutParams(0, -1, 1));
    panel = new Panel(this);
    split.addView(panel, new LinearLayout.LayoutParams(0, -1, 1));
    root.addView(split);
  }

  private View gameView(Context context) {
    FrameLayout area = new FrameLayout(context);
    area.setBackgroundColor(Color.BLACK);
    SurfaceView screen = new SurfaceView(context);
    gameScreens.add(screen);
    FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(4, 3, Gravity.CENTER);
    area.addView(screen, size);
    area.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> resizeGameScreen(screen, r - l, b - t));
    screen
        .getHolder()
        .addCallback(
            new SurfaceHolder.Callback() {
              @Override
              public void surfaceCreated(SurfaceHolder holder) {
                session.setSurface(holder.getSurface());
              }

              @Override
              public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                session.setSurface(holder.getSurface());
              }

              @Override
              public void surfaceDestroyed(SurfaceHolder holder) {
                session.clearSurface(holder.getSurface());
              }
            });
    StartupView startup =
        new StartupView(
            context,
            new StartupView.Actions() {
              public void chooseLanguage(String language) {
                changeLanguage(language);
              }

              public void next() {
                prefs.edit().putInt("setupStep", 1).apply();
                updateStartupViews();
              }

              public void importBios() {
                pick(1);
              }

              public void importGame() {
                pick(2);
              }

              public void finish() {
                if (filesReady()) prefs.edit().putBoolean("setupComplete", true).apply();
                updateStartupViews();
              }

              public void resume() {
                resumeGame();
              }

              public void loadGameCard() {
                startGame();
              }

              public void newGame() {
                confirmNewGame();
              }

              public void settings() {
                if (panel != null) panel.select(6);
              }
            });
    startupViews.add(startup);
    area.addView(startup, new FrameLayout.LayoutParams(-1, -1));
    updateStartupViews();
    return area;
  }

  private void resizeGameScreen(SurfaceView screen, int width, int height) {
    if (width <= 0 || height <= 0) return;
    boolean stretch = prefs.getBoolean("stretch", true);
    int targetWidth = stretch ? width : Math.min(width, height * 4 / 3);
    int targetHeight = stretch ? height : targetWidth * 3 / 4;
    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) screen.getLayoutParams();
    if (params.width == targetWidth && params.height == targetHeight) return;
    params.width = targetWidth;
    params.height = targetHeight;
    screen.setLayoutParams(params);
  }

  private void updateGameAspectRatio() {
    // Keep the presentation and its surface attached; replacing the screen loses
    // its displayed frame and can interrupt the active session's window lifecycle.
    for (SurfaceView screen : gameScreens) {
      View parent = (View) screen.getParent();
      if (parent != null) resizeGameScreen(screen, parent.getWidth(), parent.getHeight());
    }
  }

  private boolean filesReady() {
    return StartupFlow.biosReady(prefs.getString("bios", ""))
        && StartupFlow.gameReady(prefs.getString("game", ""));
  }

  private File startupSaveFolder() {
    String hash = prefs.getString("discHash", "");
    if (!hash.matches("[0-9a-f]{64}")) return null;
    return new File(getFilesDir(), "saves/" + hash);
  }

  private int startupStep() {
    if (!prefs.contains(AppLanguage.PREFERENCE)) return StartupFlow.LANGUAGE;
    return StartupFlow.step(
        prefs.getInt("setupStep", 0),
        prefs.getBoolean("setupComplete", false),
        StartupFlow.biosReady(prefs.getString("bios", "")),
        StartupFlow.gameReady(prefs.getString("game", "")));
  }

  private void changeLanguage(String choice) {
    if (!"es".equals(choice) && !"en".equals(choice)) return;
    prefs.edit().putString(AppLanguage.PREFERENCE, choice).apply();
    AppLanguage.apply(this, prefs);
    refreshLanguageViews();
  }

  private void refreshLanguageViews() {
    snapshot = GameData.decode(lastRam, profile);
    if (panelDialog != null) panelDialog.dismiss();
    if (captureDialog != null) captureDialog.dismiss();
    if (panel != null && panel.getParent() instanceof android.view.ViewGroup) {
      Panel previous = panel;
      android.view.ViewGroup parent = (android.view.ViewGroup) previous.getParent();
      int index = parent.indexOfChild(previous);
      android.view.ViewGroup.LayoutParams size = previous.getLayoutParams();
      panel = new Panel(previous.ctx);
      if (selectedTab == 6) {
        panel.expandedSettings.add(AppLanguage.text("language_title"));
        panel.select(6);
      }
      parent.removeView(previous);
      parent.addView(panel, index, size);
    }
    updateStartupViews();
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration configuration) {
    super.onConfigurationChanged(configuration);
    if (prefs != null && "system".equals(AppLanguage.preference(prefs))) {
      AppLanguage.apply(this, prefs);
      refreshLanguageViews();
    }
  }

  private String startupCompatibility() {
    if ("b5ff9ed251bced70c20eb911eab8ac3e5b77d7a140983d3b5dc31decac4837af"
        .equals(prefs.getString("discHash", "")))
      return AppLanguage.text("text_reference_edition_recognized_companion_panel_compatible");
    return AppLanguage.text("text_edition")
        + prefs.getString("serial", AppLanguage.text("text_unknown"))
        + AppLanguage.text(
            "text_the_companion_data_will_be_checked_when_you_enter_the_game_other_patches_may_be_incom");
  }

  private void updateStartupViews() {
    File folder = startupSaveFolder();
    File latest = StartupFlow.latestState(folder, prefs.getString("engine", "swan-gl"));
    boolean canResume = latest != null;
    for (StartupView view : startupViews) {
      view.setVisibility(session.running ? View.GONE : View.VISIBLE);
      if (!session.running)
        view.update(
            startupStep(),
            importing,
            importStatus,
            startupCompatibility(),
            canResume,
            canResume ? savedDate(latest) : "");
    }
  }

  private void confirmNewGame() {
    confirmAction(
        AppLanguage.text("text_new_game"),
        AppLanguage.text(
            "text_the_game_will_restart_from_the_beginning_unsaved_session_progress_will_be_lost_saved"),
        AppLanguage.text("text_open_game"),
        this::restartFromBeginning);
  }

  private void restartFromBeginning() {
    if (!session.running) {
      startGame();
      return;
    }
    session.stop();
    ui.postDelayed(
        new Runnable() {
          private int retries;

          @Override
          public void run() {
            if (destroyed) return;
            if (!session.running) {
              startGame();
              return;
            }
            if (++retries >= 50) {
              showMessage(
                  AppLanguage.text("text_the_session_is_taking_too_long_to_stop_try_again"));
              return;
            }
            ui.postDelayed(this, 100);
          }
        },
        100);
  }

  private static void fullscreen(View view) {
    view.setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
  }

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
  }

  private static String savedDate(File state) {
    return new java.text.SimpleDateFormat("dd/MM/yyyy · HH:mm", AppLanguage.locale())
        .format(new java.util.Date(state.lastModified()));
  }

  private TextView text(Context ctx, String content, int sp, int color) {
    TextView v = new TextView(ctx);
    v.setText(content);
    v.setTextSize(sp);
    v.setTextColor(color == Color.WHITE ? RetroSkin.INK : color);
    v.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.NORMAL));
    return v;
  }

  private android.graphics.drawable.Drawable card(int color, int radius) {
    if (color == Color.rgb(18, 35, 43) || color == Color.rgb(18, 29, 38)) color = RetroSkin.PAPER;
    if (color == Color.rgb(25, 40, 50) || color == Color.rgb(27, 43, 54)) color = 0xffe1e8d6;
    return new RetroSkin.Frame(color, getResources().getDisplayMetrics().density);
  }

  private Button button(Context ctx, String label, Runnable action) {
    Button b = new Button(ctx);
    b.setText(label);
    b.setTextSize(13);
    b.setTextColor(RetroSkin.INK);
    b.setTypeface(android.graphics.Typeface.create("monospace", android.graphics.Typeface.BOLD));
    b.setAllCaps(false);
    b.setFocusable(false);
    b.setFocusableInTouchMode(false);
    b.setGravity(Gravity.CENTER);
    b.setMinHeight(dp(48));
    b.setMinimumWidth(0);
    b.setPadding(dp(10), dp(8), dp(10), dp(8));
    b.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x3365e7c9),
            card(Color.rgb(27, 43, 54), 10),
            null));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void queueGraphics() {
    if (session.running) session.command(this::applyGraphics);
    else applyGraphics();
  }

  private void showMessage(String message) {
    if (destroyed) return;
    if (panel != null) {
      panel.feedback.setText(message);
      panel.noticeUntil = System.currentTimeMillis() + 6000;
      panel.feedback.setVisibility(View.VISIBLE);
    } else android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
  }

  private void pick(int request) {
    if (importing) return;
    if (session.running) {
      showMessage(AppLanguage.text("text_stop_the_game_before_changing_the_bios_or_disc"));
      return;
    }
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.setType("*/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(intent, request);
  }

  private void chooseState(boolean export) {
    if (!session.running || session.actionBusy) {
      showMessage(AppLanguage.text("text_start_the_game_and_wait_for_any_action_to_finish"));
      return;
    }
    Intent intent =
        new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT);
    intent.setType(export ? "application/octet-stream" : "*/*");
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    if (export)
      intent.putExtra(
          Intent.EXTRA_TITLE, "DigiBuddy-" + prefs.getString("engine", "swan-gl") + ".state");
    startActivityForResult(intent, export ? 5 : 4);
  }

  private void exportState(Uri destination) {
    if (!session.command(
        () -> {
          File temporary = null;
          try {
            temporary = File.createTempFile("export-state-", ".state", getCacheDir());
            if (!NativeCore.saveState(temporary.getAbsolutePath())) throw new java.io.IOException();
            stateTransfer.exportState(temporary, destination);
            temporary = null;
          } catch (java.io.IOException exception) {
            ui.post(
                () -> showMessage(AppLanguage.text("text_could_not_prepare_the_current_state")));
          } finally {
            if (temporary != null) temporary.delete();
          }
        })) showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
  }

  private void exportCard(Uri destination) {
    if (session.saveFolder == null || cardExporter == null) return;
    File card = new File(session.saveFolder, "memory-card.mcr");
    File cache = getCacheDir();
    if (!session.running) {
      cardExporter.prepareAndExport(card, cache, destination);
      return;
    }
    boolean queued =
        session.command(
            () -> {
              if (destroyed) return;
              if (!NativeCore.saveCard(card.getAbsolutePath())) {
                ui.post(
                    () ->
                        showMessage(
                            AppLanguage.text(
                                "text_could_not_save_the_current_memory_card_export_cancelled")));
                return;
              }
              try {
                File snapshot = CardExporter.snapshot(card, cache);
                cardExporter.export(snapshot, destination);
              } catch (java.io.IOException | RuntimeException exception) {
                android.util.Log.w(
                    "DigiBuddy", "card_snapshot_failed: " + exception.getClass().getSimpleName());
                ui.post(
                    () ->
                        showMessage(
                            AppLanguage.text("text_could_not_prepare_the_memory_card_for_export")));
              }
            });
    if (!queued)
      showMessage(
          AppLanguage.text(
              "text_wait_for_the_current_action_to_finish_before_exporting_the_memory_card"));
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (result != RESULT_OK || data == null || data.getData() == null) return;
    if (request == 4) {
      String engine = prefs.getString("engine", "swan-gl");
      Uri source = data.getData();
      if (!session.command(
          () ->
              stateTransfer.importState(
                  source, session.saveFolder, engine, NativeCore.stateBytes())))
        showMessage(AppLanguage.text("text_start_the_game_to_import_a_state"));
      return;
    }
    if (request == 5) {
      exportState(data.getData());
      return;
    }
    if (request != 1 && request != 2 && request != 3) return;
    if (request == 3) {
      exportCard(data.getData());
      return;
    }
    if (importing || session.running || fileImporter == null) return;
    importing = true;
    importStatus = AppLanguage.text("text_importing_2");
    if (!fileImporter.start(data.getData(), request == 1)) importFailed();
  }

  private void importCompleted(FileImporter.Result result) {
    SharedPreferences.Editor settings = prefs.edit();
    if (result.bios) {
      settings
          .putString("bios", result.path.getAbsolutePath())
          .putString("biosHash", result.sha256);
    } else {
      settings
          .putString("game", result.path.getAbsolutePath())
          .putString("discHash", result.sha256)
          .putString("discName", result.name)
          .putString("serial", result.identity.serial);
    }
    if (!prefs.getBoolean("setupComplete", false)) settings.putInt("setupStep", 1);
    settings.apply();
    updateStartupViews();
    atlasGeneration++;
    if (atlas != null) atlas.close();
    atlas = null;
    profile = null;
    lastRam = null;
    snapshot = new GameData.Snapshot();
    importStatus = AppLanguage.text("text_import_complete");
    importing = false;
  }

  private void importFailed() {
    importStatus = AppLanguage.text("text_import_failed");
    importing = false;
    showMessage(AppLanguage.text("text_could_not_import_check_the_file_s_format_and_size"));
  }

  /** Callbacks keep only a weak Activity reference, including while a provider is blocked. */
  private static final class ImportCallbacks implements FileImporter.Listener {
    private final WeakReference<MainActivity> activity;
    private final Handler handler;

    ImportCallbacks(WeakReference<MainActivity> activity, Handler handler) {
      this.activity = activity;
      this.handler = handler;
    }

    @Override
    public void progress(long bytes) {
      handler.post(
          () -> {
            MainActivity current = activity.get();
            if (current != null && !current.destroyed)
              current.importStatus =
                  AppLanguage.text("text_importing") + bytes / (1024 * 1024) + " MB";
          });
    }

    @Override
    public void completed(FileImporter.Result result) {
      handler.post(
          () -> {
            MainActivity current = activity.get();
            if (current != null && !current.destroyed) current.importCompleted(result);
          });
    }

    @Override
    public void failed() {
      handler.post(
          () -> {
            MainActivity current = activity.get();
            if (current != null && !current.destroyed) current.importFailed();
          });
    }
  }

  private void startGame() {
    if (importing || session.running) return;
    if (startupStep() != StartupFlow.HOME) {
      updateStartupViews();
      showMessage(AppLanguage.text("text_finish_setup_on_the_game_screen"));
      return;
    }
    String bios = prefs.getString("bios", ""), game = prefs.getString("game", "");
    if (!new File(bios).isFile() || !new File(game).isFile()) {
      showMessage(AppLanguage.text("text_import_your_bios_and_game_first_from_settings"));
      return;
    }
    profile = null;
    lastRam = null;
    lastError = "";
    snapshot = new GameData.Snapshot();
    applyGraphics();
    if (atlas != null) atlas.close();
    atlas = null;
    int generation = ++atlasGeneration;
    new Thread(
            () -> {
              try {
                SpriteAtlas loaded = SpriteAtlas.load(new File(game));
                ui.post(
                    () -> {
                      if (!destroyed && generation == atlasGeneration) atlas = loaded;
                      else if (loaded != null) loaded.close();
                    });
              } catch (Exception ex) {
                ui.post(
                    () -> {
                      if (!destroyed)
                        showMessage(AppLanguage.text("text_could_not_read_the_disc_s_sprites"));
                    });
              }
            },
            "DigiBuddy-sprites")
        .start();
    File save = new File(getFilesDir(), "saves/" + prefs.getString("discHash", "unknown"));
    String engine = prefs.getString("engine", "swan-gl");
    if (!session.start(
        new File(
                getApplicationInfo().nativeLibraryDir,
                engine.equals("pcsx") ? "libpcsx_rearmed.so" : "libswanstation.so")
            .getAbsolutePath(),
        new File(bios).getParent(),
        save.getAbsolutePath(),
        game))
      showMessage(AppLanguage.text("text_wait_for_the_previous_session_to_finish_closing"));
    cheatBackup = null;
  }

  private void resumeGame() {
    if (session.actionBusy) {
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
      return;
    }
    File folder = startupSaveFolder();
    File latest =
        folder == null
            ? null
            : StartupFlow.latestState(folder, prefs.getString("engine", "swan-gl"));
    if (latest == null) {
      showMessage(
          AppLanguage.text("text_there_is_no_saved_session_for_this_game_and_emulator_yet"));
      return;
    }
    if (session.running) {
      confirmAction(
          AppLanguage.text("text_continue_state_from") + savedDate(latest),
          AppLanguage.text(
              "text_the_current_session_will_be_replaced_unsaved_progress_will_be_lost"),
          AppLanguage.text("text_continue"),
          () -> restoreSession(latest));
      return;
    }
    startGame();
    restoreSession(latest);
  }

  private void restoreSession(File latest) {
    if (!session.running || session.saveFolder == null) return;
    session.command(
        () -> {
          boolean ok = NativeCore.loadState(latest.getAbsolutePath());
          session.ram = NativeCore.memory();
          ui.post(
              () ->
                  showMessage(
                      ok
                          ? AppLanguage.text("text_session_resumed")
                          : AppLanguage.text("text_could_not_restore_the_latest_session")));
        });
  }

  private void applyGraphics() {
    NativeCore.option(
        "pcsx_rearmed_neon_enhancement_enable",
        prefs.getBoolean("resolution2x", true) ? "enabled" : "disabled");
    NativeCore.option(
        "pcsx_rearmed_neon_enhancement_tex_adj_v2",
        prefs.getBoolean("textureFix", true) ? "enabled" : "disabled");
    NativeCore.option(
        "pcsx_rearmed_dithering", prefs.getBoolean("dithering", true) ? "enabled" : "disabled");
    NativeCore.option(
        "pcsx_rearmed_spu_interpolation",
        prefs.getBoolean("audioCubic", true) ? "cubic" : "gaussian");
    NativeCore.option("pcsx_rearmed_neon_enhancement_no_main", "disabled");
    NativeCore.option("pcsx_rearmed_memcard1", "libretro");
    NativeCore.option("swanstation_Display_CropMode", "Borders");
    NativeCore.option(
        "swanstation_GPU_Renderer",
        prefs.getString("engine", "swan-gl").equals("swan-sw") ? "Software" : "OpenGL");
    NativeCore.option("swanstation_GPU_ResolutionScale", prefs.getString("resolution", "4"));
    NativeCore.option(
        "swanstation_GPU_PGXPEnable", prefs.getBoolean("pgxp", false) ? "true" : "false");
    NativeCore.option(
        "swanstation_GPU_PGXPTextureCorrection",
        prefs.getBoolean("pgxpTexture", true) ? "true" : "false");
    NativeCore.option(
        "swanstation_GPU_TrueColor", prefs.getBoolean("trueColor", false) ? "true" : "false");
    NativeCore.option("swanstation_GPU_TextureFilter", prefs.getString("textureFilter", "Nearest"));
    NativeCore.option("swanstation_MemoryCards_Card1Type", "Libretro");
    NativeCore.option("swanstation_MemoryCards_Card2Type", "None");
    NativeCore.option("swanstation_BIOS_PathNTSCJ", "scph1001.bin");
    NativeCore.option("swanstation_BIOS_PathNTSCU", "scph1001.bin");
    NativeCore.option("swanstation_BIOS_PathPAL", "scph1001.bin");
    NativeCore.option(
        "digimap_filter", prefs.getBoolean("smoothScaling", false) ? "linear" : "nearest");
  }

  private void togglePause() {
    if (session.actionBusy)
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
    else session.paused = !session.paused;
  }

  private void confirmItem(GameData.Item item) {
    if (session.actionBusy) {
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
      return;
    }
    String reason = ItemUse.unavailable(session.ram, profile, item.slot, item.id);
    GameData.Profile current = profile;
    showDetailsDialog(
        item.name,
        atlas != null ? atlas.item(item.id, lastRam, profile) : null,
        AppLanguage.text("text_in_your_bag")
            + item.count
            + "\n\n"
            + ItemDescriptions.effect(item.id)
            + "\n\n"
            + (reason == null
                ? AppLanguage.text("text_one_unit_will_be_used_in_the_game")
                : reason),
        AppLanguage.text("text_use_1"),
        reason == null,
        () -> {
          if (session.useItem(current, item.slot, item.id))
            showMessage(AppLanguage.text("text_using") + item.name + "…");
          else showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
        });
  }

  private void showEvolutionDetails(GameData.Evolution evolution) {
    showDetailsDialog(
        evolution.name,
        atlas != null ? atlas.mon(evolution.type, lastRam, profile) : null,
        (evolution.candidate
                ? AppLanguage.text("text_requirements_met")
                : AppLanguage.text("text_requirements_pending"))
            + "\n\n"
            + evolution.details
            + AppLanguage.text(
                "text_natural_evolution_also_depends_on_the_in_game_clock_and_events"),
        "Evolucionar…",
        evolution.candidate,
        () -> confirmEvolution(evolution));
  }

  private void showDetailsDialog(
      String title,
      android.graphics.Bitmap sprite,
      String description,
      String positive,
      boolean enabled,
      Runnable action) {
    if (panel == null || session.actionBusy || (panelDialog != null && panelDialog.isShowing()))
      return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(14), dp(16), dp(14));
    box.setBackground(card(RetroSkin.PAPER, 0));
    TextView heading = text(panel.ctx, title, 18, RetroSkin.INK);
    heading.setTypeface(null, android.graphics.Typeface.BOLD);
    if (sprite != null) {
      android.graphics.drawable.BitmapDrawable icon =
          new android.graphics.drawable.BitmapDrawable(getResources(), sprite);
      icon.setFilterBitmap(false);
      icon.setBounds(0, 0, dp(36), dp(36));
      heading.setCompoundDrawables(icon, null, null, null);
      heading.setCompoundDrawablePadding(dp(10));
    }
    box.addView(heading);
    ScrollView details = new ScrollView(panel.ctx);
    TextView explanation = text(panel.ctx, description, 14, RetroSkin.INK);
    explanation.setPadding(0, dp(12), 0, dp(12));
    details.addView(explanation);
    int available = Math.max(dp(64), panel.getHeight() - dp(190));
    explanation.measure(
        View.MeasureSpec.makeMeasureSpec(
            Math.max(dp(120), Math.min(panel.getWidth() - dp(62), dp(388))),
            View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    box.addView(
        details,
        new LinearLayout.LayoutParams(-1, Math.min(available, explanation.getMeasuredHeight())));
    LinearLayout buttons = new LinearLayout(panel.ctx);
    buttons.addView(
        button(panel.ctx, AppLanguage.text("text_close"), dialog::dismiss),
        new LinearLayout.LayoutParams(0, dp(48), 1));
    Button apply =
        button(
            panel.ctx,
            positive,
            () -> {
              dialog.dismiss();
              action.run();
            });
    apply.setEnabled(enabled);
    apply.setAlpha(enabled ? 1f : .45f);
    apply.setBackground(card(RetroSkin.GOLD, 0));
    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1);
    size.leftMargin = dp(8);
    buttons.addView(apply, size);
    box.addView(buttons);
    showPanelDialog(dialog, box);
  }

  private void confirmEvolution(GameData.Evolution evo) {
    if (session.actionBusy) {
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
      return;
    }
    int source = snapshot.type;
    GameData.Profile current = profile;
    String reason = EvolutionAction.unavailable(session.ram, current, source, evo.type);
    if (reason != null) {
      showMessage(reason);
      return;
    }
    confirmAction(
        AppLanguage.text("text_evolve_into") + evo.name,
        AppLanguage.text(
            "text_you_meet_the_requirements_this_evolution_will_be_triggered_without_waiting_for_the_in"),
        AppLanguage.text("text_evolve"),
        () -> {
          if (session.evolve(current, source, evo.type, prefs.getString("engine", "swan-gl"))) {
            showMessage(AppLanguage.text("text_evolving_into") + evo.name + "…");
          } else showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
        });
  }

  private void undoEvolution() {
    if (session.actionBusy) {
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
      return;
    }
    String engine = prefs.getString("engine", "swan-gl");
    EvolutionHistory history = EvolutionHistory.read(session.saveFolder, engine);
    GameData.Profile currentProfile = profile;
    String reason = EvolutionAction.reverseUnavailable(session.ram, currentProfile, history);
    if (reason != null) {
      showMessage(reason);
      return;
    }
    String previousName = AppLanguage.catalogText(DataNames.DIGIMON[history.source]);
    confirmAction(
        AppLanguage.text("text_return_to") + previousName,
        AppLanguage.text(
            "text_you_will_see_the_in_game_animation_to_return_to_your_previous_form_current_stats_care"),
        AppLanguage.text("text_undo"),
        () -> {
          if (session.reverseEvolution(currentProfile, history, engine))
            showMessage(AppLanguage.text("text_returning_to") + previousName + "…");
          else showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
        });
  }

  private void applyCheat(int id) {
    if (id < 0 || id >= Cheats.LABELS.length) return;
    if (!session.running || profile == null || !snapshot.valid) {
      showMessage(AppLanguage.text("text_enter_a_recognized_game_session_before_applying_cheats"));
      return;
    }
    GameData.Profile current = profile;
    session.command(
        () -> {
          byte[] ram = NativeCore.memory();
          GameData.Snapshot state = GameData.decode(ram, current);
          if (!state.valid) {
            ui.post(
                () -> showMessage(AppLanguage.text("text_the_game_is_changing_scenes_try_again")));
            return;
          }
          File backup =
              new File(session.saveFolder, "before-cheat-" + System.currentTimeMillis() + ".state");
          if (!NativeCore.saveState(backup.getAbsolutePath())) {
            ui.post(
                () ->
                    showMessage(
                        AppLanguage.text(
                            "text_could_not_create_the_backup_the_cheat_was_not_applied")));
            return;
          }
          cheatBackup = backup;
          prefs
              .edit()
              .putString("cheatBackup", backup.getAbsolutePath())
              .putString("cheatEngine", prefs.getString("engine", "swan-gl"))
              .apply();
          boolean ok = true;
          for (int[] write : Cheats.plan(id, current, state))
            ok &= NativeCore.writeRam(write[0], write[1], write[2]);
          if (id == 6) {
            int inv = current.at("INVENTORY");
            for (int slot = 0; slot < state.inventorySize; slot++)
              if ((ram[inv + slot] & 255) != 255 && (ram[inv + 30 + slot] & 255) > 0)
                ok &= NativeCore.writeRam(inv + 30 + slot, 99, 1);
          }
          if (!ok) NativeCore.loadState(backup.getAbsolutePath());
          File[] history =
              session.saveFolder.listFiles(
                  (folder, name) -> name.startsWith("before-cheat-") && name.endsWith(".state"));
          if (history != null && history.length > 5) {
            java.util.Arrays.sort(
                history, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int n = 5; n < history.length; n++) history[n].delete();
          }
          session.ram = NativeCore.memory();
          boolean result = ok;
          ui.post(
              () ->
                  showMessage(
                      result
                          ? AppLanguage.text("text_applied")
                              + AppLanguage.catalogText(Cheats.LABELS[id])
                          : AppLanguage.text("text_could_not_apply_the_cheat")));
        });
  }

  private void undoCheat() {
    if (!session.running || session.saveFolder == null) {
      showMessage(AppLanguage.text("text_start_a_game_first"));
      return;
    }
    String engine = prefs.getString("engine", "swan-gl");
    if (cheatBackup == null && prefs.getString("cheatEngine", "").equals(engine)) {
      File last = new File(prefs.getString("cheatBackup", ""));
      if (last.isFile() && session.saveFolder.equals(last.getParentFile())) cheatBackup = last;
    }
    File backup = cheatBackup;
    if (backup == null || !session.saveFolder.equals(backup.getParentFile())) {
      showMessage(AppLanguage.text("text_there_is_no_cheat_to_undo_in_this_game_session"));
      return;
    }
    session.command(
        () -> {
          boolean ok = NativeCore.loadState(backup.getAbsolutePath());
          session.ram = NativeCore.memory();
          ui.post(
              () ->
                  showMessage(
                      ok
                          ? AppLanguage.text("text_cheat_undone")
                          : AppLanguage.text("text_could_not_restore_the_state")));
        });
  }

  private void confirmAction(String title, String description, Runnable apply) {
    confirmAction(title, description, AppLanguage.text("text_apply"), apply);
  }

  private void confirmAction(String title, String description, String positive, Runnable apply) {
    if (session.actionBusy) {
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
      return;
    }
    if (panel == null || (panelDialog != null && panelDialog.isShowing())) return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20), dp(18), dp(20), dp(18));
    box.setBackground(card(RetroSkin.PAPER, 0));
    box.addView(
        text(
            panel.ctx,
            ("es".equals(AppLanguage.locale().getLanguage()) ? "¿" : "") + title + "?",
            18,
            RetroSkin.INK));
    TextView explanation = text(panel.ctx, description, 14, MUTED);
    explanation.setPadding(0, dp(14), 0, dp(18));
    box.addView(explanation);
    LinearLayout choices = new LinearLayout(panel.ctx);
    choices.addView(
        button(panel.ctx, AppLanguage.text("text_cancel"), dialog::dismiss),
        new LinearLayout.LayoutParams(0, dp(48), 1));
    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1);
    size.leftMargin = dp(10);
    Button yes =
        button(
            panel.ctx,
            positive,
            () -> {
              dialog.dismiss();
              apply.run();
            });
    yes.setBackground(card(RetroSkin.GOLD, 0));
    choices.addView(yes, size);
    box.addView(choices);
    showPanelDialog(dialog, box);
  }

  private Dialog createPanelDialog() {
    boolean pausedBefore = session.paused;
    session.paused = true;
    session.physicalButtons = session.touchButtons = 0;
    controllerBindings.clear();
    Dialog dialog =
        new Dialog(panel.ctx) {
          @Override
          public boolean dispatchKeyEvent(KeyEvent event) {
            return MainActivity.this.dispatchKeyEvent(event);
          }

          @Override
          public boolean onGenericMotionEvent(MotionEvent event) {
            return MainActivity.this.onGenericMotionEvent(event);
          }
        };
    panelDialog = dialog;
    configurePanelDialog(dialog);
    dialog.setOnDismissListener(
        d -> {
          cancelControllerCapture();
          controllerView = null;
          controllerStatus = null;
          pendingSource = null;
          controllerBindings.clear();
          session.physicalButtons = 0;
          session.paused = pausedBefore;
          if (panelDialog == dialog) panelDialog = null;
        });
    return dialog;
  }

  private void configurePanelDialog(Dialog dialog) {
    dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
    android.view.Window window = dialog.getWindow();
    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.token = panel.getWindowToken();
    attributes.dimAmount = .55f;
    window.setAttributes(attributes);
    window.addFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
    dialog.setCanceledOnTouchOutside(false);
  }

  private void showPanelDialog(Dialog dialog, View content) {
    dialog.setContentView(content);
    try {
      dialog.show();
      android.view.Window window = dialog.getWindow();
      window.setLayout(Math.min(panel.getWidth() - dp(30), dp(420)), -2);
      fullscreen(window.getDecorView());
    } catch (RuntimeException ex) {
      dialog.dismiss();
      showMessage(AppLanguage.text("text_could_not_open_the_window"));
    }
  }

  private void checkUpdates(boolean manual) {
    if (releaseUpdates == null || prefs == null || destroyed) return;
    if (!manual && !prefs.getBoolean("automaticUpdates", true)) return;
    String installed;
    try {
      installed = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (Exception exception) {
      if (manual) showMessage(AppLanguage.text("text_could_not_check_the_version"));
      return;
    }
    WeakReference<MainActivity> activity = new WeakReference<>(this);
    Handler handler = ui;
    boolean started =
        releaseUpdates.check(
            installed,
            result ->
                handler.post(
                    () -> {
                      MainActivity owner = activity.get();
                      if (owner == null || owner.destroyed) return;
                      if (result.failed) {
                        if (manual) owner.showMessage(result.errorMessage);
                        return;
                      }
                      if (result.version.isEmpty()) {
                        if (manual)
                          owner.showMessage(AppLanguage.text("text_no_updates_available"));
                        return;
                      }
                      boolean alreadyShown =
                          owner.updateNoticeShown
                              && owner.availableUpdate != null
                              && owner.availableUpdate.version.equals(result.version);
                      owner.availableUpdate = result;
                      owner.updateNoticeShown = !manual && alreadyShown;
                      owner.showUpdateNotice();
                    }));
    if (manual)
      showMessage(
          started
              ? AppLanguage.text("text_checking_for_updates")
              : AppLanguage.text("text_already_checking"));
  }

  private void showUpdateNotice() {
    if (availableUpdate == null
        || updateNoticeShown
        || panel == null
        || session.suspended
        || session.actionBusy
        || (panelDialog != null && panelDialog.isShowing())) return;
    ReleaseUpdates.Result update = availableUpdate;
    updateNoticeShown = true;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20), dp(18), dp(20), dp(18));
    box.setBackground(card(RetroSkin.PAPER, 0));
    box.addView(
        text(
            panel.ctx,
            "DigiBuddy " + update.version + AppLanguage.text("text_available"),
            20,
            RetroSkin.INK));
    box.addView(
        text(
            panel.ctx,
            AppLanguage.text(
                "text_the_apk_will_download_here_and_be_verified_before_opening_android_s_installer_save_yo"),
            14,
            MUTED));
    box.addView(
        button(
            panel.ctx,
            AppLanguage.text("text_update"),
            () -> {
              dialog.dismiss();
              downloadUpdate(update);
            }));
    box.addView(
        button(
            panel.ctx,
            AppLanguage.text("text_cancel"),
            () -> {
              dialog.dismiss();
            }));
    showPanelDialog(dialog, box);
  }

  private void downloadUpdate(ReleaseUpdates.Result update) {
    if (apkDownloader != null || panel == null) return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20), dp(18), dp(20), dp(18));
    box.setBackground(card(RetroSkin.PAPER, 0));
    TextView progress =
        text(
            panel.ctx,
            AppLanguage.text("text_downloading_digibuddy") + update.version + "…",
            18,
            RetroSkin.INK);
    box.addView(progress);
    box.addView(
        button(
            panel.ctx,
            AppLanguage.text("text_cancel"),
            () -> {
              if (apkDownloader != null) apkDownloader.close();
              apkDownloader = null;
              dialog.dismiss();
            }));
    showPanelDialog(dialog, box);
    ApkDownloader downloader = new ApkDownloader(this);
    apkDownloader = downloader;
    WeakReference<MainActivity> activity = new WeakReference<>(this);
    Handler handler = ui;
    downloader.download(
        update,
        new ApkDownloader.Callback() {
          @Override
          public void progress(int percent) {
            handler.post(
                () -> {
                  MainActivity owner = activity.get();
                  if (owner != null && !owner.destroyed && owner.apkDownloader == downloader)
                    progress.setText(
                        percent == 100
                            ? AppLanguage.text("text_verifying_apk")
                            : AppLanguage.text("text_downloading") + percent + "%");
                });
          }

          @Override
          public void completed(Uri apk) {
            handler.post(
                () -> {
                  MainActivity owner = activity.get();
                  if (owner == null || owner.destroyed || owner.apkDownloader != downloader) return;
                  downloader.close();
                  owner.apkDownloader = null;
                  dialog.dismiss();
                  if (apk == null) {
                    owner.showMessage(
                        AppLanguage.text("text_could_not_download_or_verify_the_apk_try_again"));
                    return;
                  }
                  owner.pendingInstall = apk;
                  owner.installDownloadedUpdate();
                });
          }
        });
  }

  private void installDownloadedUpdate() {
    if (pendingInstall == null || session.suspended || panel == null) return;
    if (!getPackageManager().canRequestPackageInstalls()) {
      Dialog dialog = createPanelDialog();
      LinearLayout box = new LinearLayout(panel.ctx);
      box.setOrientation(LinearLayout.VERTICAL);
      box.setPadding(dp(20), dp(18), dp(20), dp(18));
      box.setBackground(card(RetroSkin.PAPER, 0));
      box.addView(
          text(
              panel.ctx,
              AppLanguage.text("text_allow_installation_from_digibuddy"),
              19,
              RetroSkin.INK));
      box.addView(
          text(
              panel.ctx,
              AppLanguage.text(
                  "text_the_apk_is_verified_android_needs_your_permission_to_install_updates_from_digibuddy_e"),
              14,
              MUTED));
      box.addView(
          button(
              panel.ctx,
              AppLanguage.text("text_open_permission_settings"),
              () -> {
                dialog.dismiss();
                try {
                  awaitingInstallPermission = true;
                  startActivity(
                      new Intent(
                          android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                          Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException failure) {
                  awaitingInstallPermission = false;
                  showMessage(
                      AppLanguage.text("text_could_not_open_the_installation_permission_settings"));
                }
              }));
      box.addView(
          button(
              panel.ctx,
              AppLanguage.text("text_cancel"),
              () -> {
                pendingInstall = null;
                dialog.dismiss();
              }));
      showPanelDialog(dialog, box);
      return;
    }
    try {
      Intent installer = new Intent(Intent.ACTION_VIEW);
      installer.setDataAndType(pendingInstall, "application/vnd.android.package-archive");
      installer.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      installer.setClipData(android.content.ClipData.newRawUri("DigiBuddy APK", pendingInstall));
      startActivity(installer);
      pendingInstall = null;
    } catch (RuntimeException failure) {
      showMessage(AppLanguage.text("text_could_not_open_android_s_installer"));
    }
  }

  private void showRecruitHint(RecruitmentHints.Hint hint) {
    if (panel == null || session.actionBusy || (panelDialog != null && panelDialog.isShowing()))
      return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20), dp(18), dp(20), dp(18));
    box.setBackground(card(RetroSkin.PAPER, 0));
    box.addView(
        text(panel.ctx, hint.recruit.name + " · +" + hint.recruit.points, 20, RetroSkin.INK));
    box.addView(text(panel.ctx, hint.location + " · " + hint.proximity, 13, MUTED));
    TextView clue = text(panel.ctx, hint.clue, 16, RetroSkin.INK);
    clue.setPadding(0, dp(14), 0, dp(14));
    box.addView(clue);
    if (!hint.requirements.isEmpty())
      box.addView(
          text(panel.ctx, AppLanguage.text("text_first_you_need") + hint.requirements, 14, MUTED));
    box.addView(
        text(
            panel.ctx,
            AppLanguage.text(
                "text_a_hint_for_a_pending_recruitment_some_encounters_depend_on_time_and_events"),
            12,
            MUTED));
    box.addView(button(panel.ctx, AppLanguage.text("text_close"), dialog::dismiss));
    showPanelDialog(dialog, box);
  }

  private void showMapDigimon(List<GameData.Enemy> enemies) {
    if (enemies.size() == 1) {
      showEnemy(enemies.get(0));
      return;
    }
    if (panel == null || session.actionBusy || (panelDialog != null && panelDialog.isShowing()))
      return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(14), dp(16), dp(14));
    box.setBackground(card(RetroSkin.PAPER, 0));
    box.addView(
        text(
            panel.ctx,
            AppLanguage.text("text_which_digimon_would_you_like_to_check"),
            17,
            RetroSkin.INK));
    ScrollView scroll = new ScrollView(panel.ctx);
    LinearLayout choices = new LinearLayout(panel.ctx);
    choices.setOrientation(LinearLayout.VERTICAL);
    for (GameData.Enemy enemy : enemies) {
      String detail =
          enemy.recruitType > 0
              ? AppLanguage.text("text_recruitable")
              : enemy.difficulty == 0
                  ? AppLanguage.text("text_easy")
                  : enemy.difficulty == 1
                      ? AppLanguage.text("text_even_match")
                      : AppLanguage.text("text_hard");
      Button choice =
          button(
              panel.ctx,
              enemy.name + " · " + detail,
              () -> {
                dialog.dismiss();
                showEnemy(enemy);
              });
      android.graphics.Bitmap sprite =
          atlas == null
              ? null
              : enemy.recruitType > 0
                  ? atlas.mon(enemy.recruitType, lastRam, profile)
                  : atlas.enemy(enemy.type, lastRam, profile);
      if (sprite != null) {
        android.graphics.drawable.BitmapDrawable icon =
            new android.graphics.drawable.BitmapDrawable(getResources(), sprite);
        icon.setFilterBitmap(false);
        icon.setBounds(0, 0, dp(32), dp(32));
        choice.setCompoundDrawables(icon, null, null, null);
        choice.setCompoundDrawablePadding(dp(8));
      }
      choices.addView(choice, new LinearLayout.LayoutParams(-1, dp(48)));
    }
    scroll.addView(choices);
    box.addView(
        scroll,
        new LinearLayout.LayoutParams(
            -1, Math.min(dp(48) * enemies.size(), Math.max(dp(48), panel.getHeight() - dp(170)))));
    box.addView(button(panel.ctx, AppLanguage.text("text_close"), dialog::dismiss));
    showPanelDialog(dialog, box);
  }

  private void showEnemy(GameData.Enemy enemy) {
    if (enemy.recruitType > 0) {
      RecruitmentHints.Hint hint = RecruitmentHints.atLocation(snapshot, enemy.recruitType);
      if (hint != null) showRecruitHint(hint);
      return;
    }
    if (panel == null || session.actionBusy || (panelDialog != null && panelDialog.isShowing()))
      return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20), dp(18), dp(20), dp(18));
    box.setBackground(card(RetroSkin.PAPER, 0));
    LinearLayout heading = new LinearLayout(panel.ctx);
    heading.setGravity(Gravity.CENTER_VERTICAL);
    android.graphics.Bitmap sprite =
        atlas != null ? atlas.enemy(enemy.type, lastRam, profile) : null;
    if (sprite != null) {
      android.widget.ImageView image = new android.widget.ImageView(panel.ctx);
      android.graphics.drawable.BitmapDrawable pixels =
          new android.graphics.drawable.BitmapDrawable(getResources(), sprite);
      pixels.setFilterBitmap(false);
      image.setImageDrawable(pixels);
      LinearLayout.LayoutParams imageSize = new LinearLayout.LayoutParams(dp(64), dp(64));
      imageSize.rightMargin = dp(14);
      heading.addView(image, imageSize);
    }
    heading.addView(text(panel.ctx, enemy.name, 20, RetroSkin.INK));
    box.addView(heading);
    String[] labels = {
      AppLanguage.text("text_hp"),
      AppLanguage.text("text_mp"),
      AppLanguage.text("text_offense"),
      AppLanguage.text("text_defense"),
      AppLanguage.text("text_speed"),
      AppLanguage.text("text_brains")
    };
    String[] values = {
      enemy.hp + " / " + enemy.maxHp,
      enemy.mp >= 0 && enemy.maxMp >= enemy.mp ? enemy.mp + " / " + enemy.maxMp : "—",
      Integer.toString(enemy.offense),
      Integer.toString(enemy.defense),
      Integer.toString(enemy.speed),
      Integer.toString(enemy.brains)
    };
    for (int i = 0; i < labels.length; i += 2) {
      LinearLayout pair = new LinearLayout(panel.ctx);
      for (int j = i; j < i + 2; j++) {
        LinearLayout tile = new LinearLayout(panel.ctx);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(8), dp(10), dp(8), dp(10));
        tile.addView(text(panel.ctx, labels[j], 12, MUTED));
        tile.addView(text(panel.ctx, values[j], 18, RetroSkin.INK));
        pair.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));
      }
      box.addView(pair);
    }
    TextView risk =
        text(
            panel.ctx,
            AppLanguage.text("text_estimated_battle")
                + (enemy.difficulty == 0
                    ? AppLanguage.text("text_easy_2")
                    : enemy.difficulty == 1
                        ? AppLanguage.text("text_even_match_2")
                        : AppLanguage.text("text_hard_2")),
            14,
            MUTED);
    risk.setPadding(0, dp(12), 0, dp(16));
    box.addView(risk);
    box.addView(
        button(panel.ctx, AppLanguage.text("text_close"), dialog::dismiss),
        new LinearLayout.LayoutParams(-1, dp(48)));
    showPanelDialog(dialog, box);
  }

  private void chooseDisplay() {
    Display current = getWindowManager().getDefaultDisplay();
    List<Display> choices = new ArrayList<>();
    for (Display d : displays.getDisplays())
      if (d.getDisplayId() != current.getDisplayId()) choices.add(d);
    if (choices.isEmpty()) {
      showMessage(
          AppLanguage.text(
              "text_android_does_not_report_an_available_second_screen_on_thor_enable_the_second_screen_a"));
      return;
    }
    String[] labels = new String[choices.size()];
    for (int i = 0; i < labels.length; i++)
      labels[i] = choices.get(i).getName() + " · ID " + choices.get(i).getDisplayId();
    new AlertDialog.Builder(this)
        .setTitle(AppLanguage.text("text_second_screen"))
        .setItems(
            labels,
            (dialog, index) -> {
              selectedDisplay = choices.get(index).getDisplayId();
              prefs.edit().putInt("selectedDisplay", selectedDisplay).apply();
              rebuildScreens();
            })
        .show();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (controllerView != null && panelDialog != null && panelDialog.isShowing()) {
      if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
        if (event.getAction() == KeyEvent.ACTION_UP) {
          if (captureDialog != null) cancelControllerCapture();
          else panelDialog.dismiss();
        }
        return true;
      }
      if (captureTarget >= 0
          && event.getAction() == KeyEvent.ACTION_DOWN
          && event.getRepeatCount() == 0
          && isControllerKey(event))
        captureControllerInput(event.getDevice(), ControllerBindings.keySource(event.getKeyCode()));
      return true;
    }
    int id =
        controllerBindings.binding(
            ControllerBindings.device(event.getDevice()),
            ControllerBindings.keySource(event.getKeyCode()));
    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
      if (event.getAction() == KeyEvent.ACTION_UP) onBackPressed();
      return true;
    }
    if (id >= 0) {
      if (session.actionBusy || (panelDialog != null && panelDialog.isShowing())) return true;
      session.physicalButtons = controllerBindings.key(event);
      return true;
    }
    if ((event.getSource() & android.view.InputDevice.SOURCE_GAMEPAD)
        == android.view.InputDevice.SOURCE_GAMEPAD) return true;
    if (isControllerKey(event)) return true;
    return super.dispatchKeyEvent(event);
  }

  private static boolean isControllerKey(KeyEvent event) {
    return KeyEvent.isGamepadButton(event.getKeyCode())
        || event.getKeyCode() >= KeyEvent.KEYCODE_DPAD_UP
            && event.getKeyCode() <= KeyEvent.KEYCODE_DPAD_RIGHT;
  }

  private void clearControllerInputs() {
    if (controllerBindings != null) controllerBindings.clear();
    session.physicalButtons = 0;
  }

  private void showControllerMapping() {
    if (panel == null || session.actionBusy || panelDialog != null) return;
    Dialog dialog = createPanelDialog();
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(14), dp(16), dp(14));
    box.setBackground(card(RetroSkin.PAPER, 0));
    box.addView(text(panel.ctx, AppLanguage.text("text_remap_buttons"), 18, RetroSkin.INK));
    List<android.view.InputDevice> devices = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (int id : android.view.InputDevice.getDeviceIds()) {
      android.view.InputDevice device = android.view.InputDevice.getDevice(id);
      if (device != null
          && !device.isVirtual()
          && ((device.getSources() & android.view.InputDevice.SOURCE_GAMEPAD)
                  == android.view.InputDevice.SOURCE_GAMEPAD
              || (device.getSources() & android.view.InputDevice.SOURCE_JOYSTICK)
                  == android.view.InputDevice.SOURCE_JOYSTICK
              || (device.getSources() & android.view.InputDevice.SOURCE_DPAD)
                  == android.view.InputDevice.SOURCE_DPAD)) {
        devices.add(device);
        names.add(device.getName());
      }
    }
    mappingDevice = devices.isEmpty() ? "builtin" : ControllerBindings.device(devices.get(0));
    if (!devices.isEmpty()) {
      Spinner devicePicker = new Spinner(panel.ctx);
      devicePicker.setAdapter(
          new ArrayAdapter<>(panel.ctx, android.R.layout.simple_spinner_dropdown_item, names));
      devicePicker.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
              mappingDevice = ControllerBindings.device(devices.get(position));
              cancelControllerCapture();
            }

            public void onNothingSelected(AdapterView<?> parent) {}
          });
      box.addView(devicePicker);
    }
    controllerStatus =
        text(
            panel.ctx,
            AppLanguage.text(
                "text_tap_a_controller_button_then_press_the_physical_button_you_want_to_use"),
            13,
            MUTED);
    controllerView = new ControllerView(panel.ctx, this::showControllerCapture);
    box.addView(controllerView);
    box.addView(controllerStatus);
    box.addView(
        button(
            panel.ctx,
            AppLanguage.text("text_reset_this_controller_s_bindings"),
            () -> {
              controllerBindings.reset(mappingDevice);
              cancelControllerCapture();
              controllerStatus.setText(AppLanguage.text("text_default_controls_restored"));
            }));
    ScrollView scroll = new ScrollView(panel.ctx);
    scroll.addView(box);
    LinearLayout layout = new LinearLayout(panel.ctx);
    layout.setOrientation(LinearLayout.VERTICAL);
    layout.setBackground(card(RetroSkin.PAPER, 0));
    layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    layout.addView(
        button(panel.ctx, AppLanguage.text("text_close"), dialog::dismiss),
        new LinearLayout.LayoutParams(-1, dp(48)));
    showPanelDialog(dialog, layout);
    if (dialog.isShowing())
      dialog
          .getWindow()
          .setLayout(
              Math.min(panel.getWidth() - dp(30), dp(460)),
              Math.min(panel.getHeight() - dp(30), dp(560)));
  }

  private void cancelControllerCapture() {
    Dialog popup = captureDialog;
    captureDialog = null;
    if (popup != null) popup.dismiss();
    captureStatus = null;
    replaceBinding = null;
    captureTarget = -1;
    pendingSource = null;
    if (controllerView != null) controllerView.selected(-1);
    if (replaceBinding != null) replaceBinding.setVisibility(View.GONE);
    if (controllerStatus != null)
      controllerStatus.setText(
          AppLanguage.text("text_tap_a_controller_button_to_change_its_binding"));
  }

  private void showControllerCapture(int target) {
    cancelControllerCapture();
    captureTarget = target;
    controllerView.selected(target);
    Dialog popup = new Dialog(panel.ctx);
    captureDialog = popup;
    configurePanelDialog(popup);
    popup.setOnDismissListener(
        d -> {
          if (captureDialog == popup) cancelControllerCapture();
        });
    LinearLayout box = new LinearLayout(panel.ctx);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(20), dp(18), dp(20), dp(18));
    box.setBackground(card(RetroSkin.PAPER, 0));
    box.addView(
        text(
            panel.ctx,
            AppLanguage.text("text_assign") + ControllerBindings.LABELS[target] + "»",
            20,
            RetroSkin.INK));
    captureStatus =
        text(
            panel.ctx,
            AppLanguage.text("text_current_2")
                + controllerBindings.description(mappingDevice, target)
                + AppLanguage.text(
                    "text_press_the_physical_button_or_move_the_direction_you_want_to_use"),
            15,
            MUTED);
    captureStatus.setPadding(0, dp(16), 0, dp(18));
    box.addView(captureStatus);
    LinearLayout actions = new LinearLayout(panel.ctx);
    actions.addView(
        button(panel.ctx, AppLanguage.text("text_cancel"), this::cancelControllerCapture),
        new LinearLayout.LayoutParams(0, dp(48), 1));
    replaceBinding =
        button(panel.ctx, AppLanguage.text("text_reassign"), this::applyControllerCapture);
    replaceBinding.setVisibility(View.GONE);
    actions.addView(replaceBinding, new LinearLayout.LayoutParams(0, dp(48), 1));
    box.addView(actions);
    showPanelDialog(popup, box);
  }

  private void captureControllerInput(android.view.InputDevice device, String source) {
    if (captureTarget < 0 || pendingSource != null) return;
    if (!mappingDevice.equals(ControllerBindings.device(device))) {
      captureStatus.setText(
          AppLanguage.text(
              "text_that_button_belongs_to_another_device_use_the_selected_controller"));
      return;
    }
    pendingSource = source;
    int previous = controllerBindings.binding(mappingDevice, source);
    if (previous >= 0 && previous != captureTarget) {
      captureStatus.setText(
          AppLanguage.text("text_this_button_already_controls")
              + ControllerBindings.LABELS[previous]
              + AppLanguage.text("text_reassign_it_to")
              + ControllerBindings.LABELS[captureTarget]
              + "»?");
      replaceBinding.setVisibility(View.VISIBLE);
    } else applyControllerCapture();
  }

  private void applyControllerCapture() {
    if (pendingSource == null || captureTarget < 0) return;
    String label = ControllerBindings.LABELS[captureTarget];
    controllerBindings.assign(mappingDevice, pendingSource, captureTarget);
    cancelControllerCapture();
    controllerStatus.setText("«" + label + AppLanguage.text("text_saved_for_this_controller"));
  }

  @Override
  public boolean onGenericMotionEvent(MotionEvent event) {
    if ((event.getSource() & android.view.InputDevice.SOURCE_JOYSTICK)
        == android.view.InputDevice.SOURCE_JOYSTICK) {
      if (controllerView != null) {
        String source = ControllerBindings.captureAxis(event);
        if (source != null) captureControllerInput(event.getDevice(), source);
        return true;
      }
      if (session.actionBusy || (panelDialog != null && panelDialog.isShowing())) return true;
      session.physicalButtons = controllerBindings.motion(event);
      return true;
    }
    return super.onGenericMotionEvent(event);
  }

  @Override
  public void onBackPressed() {
    if (session.actionBusy) {
      showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
      return;
    }
    if (panelDialog != null && panelDialog.isShowing()) {
      panelDialog.dismiss();
      return;
    }
    if (panel != null && panel.tab != 0) {
      panel.select(0);
      return;
    }
    if (panel == null) return;
    panel.content.removeAllViews();
    panel.section(AppLanguage.text("text_exit_digibuddy"));
    panel.content.addView(
        text(
            panel.ctx,
            AppLanguage.text("text_the_memory_card_will_be_saved_on_exit"),
            15,
            RetroSkin.INK));
    panel.content.addView(
        button(panel.ctx, AppLanguage.text("text_keep_playing"), () -> panel.select(0)));
    panel.content.addView(
        button(
            panel.ctx,
            AppLanguage.text("text_save_and_exit"),
            () -> {
              session.command(
                  () -> {
                    NativeCore.saveState(
                        new File(
                                session.saveFolder,
                                prefs.getString("engine", "swan-gl") + "-auto.state")
                            .getAbsolutePath());
                    ui.post(
                        () -> {
                          session.stop();
                          finish();
                        });
                  });
              if (!session.running) finish();
            }));
    panel.previous = "exit";
    panel.tab = 8;
  }

  @Override
  public void onDisplayAdded(int id) {
    rebuildScreens();
  }

  @Override
  public void onDisplayRemoved(int id) {
    rebuildScreens();
  }

  @Override
  public void onDisplayChanged(int id) {}

  @Override
  protected void onResume() {
    super.onResume();
    session.suspended = false;
    if (awaitingInstallPermission) {
      awaitingInstallPermission = false;
      if (getPackageManager().canRequestPackageInstalls()) installDownloadedUpdate();
      else {
        pendingInstall = null;
        showMessage(AppLanguage.text("text_installation_cancelled_permission_not_granted"));
      }
    }
    checkUpdates(false);
    ui.post(
        () -> {
          if (!destroyed
              && pendingInstall != null
              && !awaitingInstallPermission
              && (panelDialog == null || !panelDialog.isShowing())) installDownloadedUpdate();
        });
    fullscreen(getWindow().getDecorView());
  }

  @Override
  protected void onPause() {
    session.suspended = true;
    session.physicalButtons = 0;
    if (controllerBindings != null) controllerBindings.clear();
    session.touchButtons = 0;
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    if (inputManager != null) inputManager.unregisterInputDeviceListener(inputDeviceListener);
    if (cardExporter != null) cardExporter.close();
    if (fileImporter != null) fileImporter.close();
    if (releaseUpdates != null) releaseUpdates.close();
    if (apkDownloader != null) apkDownloader.close();
    if (stateTransfer != null) stateTransfer.close();
    atlasGeneration++;
    if (atlas != null) atlas.close();
    atlas = null;
    if (panelDialog != null) panelDialog.dismiss();
    ui.removeCallbacksAndMessages(null);
    displays.unregisterDisplayListener(this);
    session.stop();
    if (secondary != null) secondary.dismiss();
    super.onDestroy();
  }

  private final class Panel extends LinearLayout {
    final Context ctx;
    final TextView title, status, body, stats, feedback;
    final android.widget.ImageView portrait;
    final LinearLayout content;
    final MapView map;
    final ScrollView scroll;
    final LinearLayout header, summary;
    final FrameLayout mapArea;
    final int[] navTabs = {0, 1, 2, 3, 4, 7};
    int tab = 0, lastSpriteType = -1;
    private final java.util.Set<String> expandedSettings = new java.util.HashSet<>();
    String previous = "";
    long noticeUntil = 0;
    final ArrayList<Button> navigation = new ArrayList<>();
    final RetroSkin.Meter hpMeter, mpMeter;

    Panel(Context context) {
      super(context);
      ctx = context;
      setOrientation(VERTICAL);
      setBackgroundColor(BG);
      setPadding(dp(14), dp(12), dp(14), dp(12));
      header = new LinearLayout(ctx);
      header.setGravity(Gravity.CENTER_VERTICAL);
      header.setPadding(dp(10), dp(8), dp(10), dp(8));
      header.setBackground(card(RetroSkin.PAPER, 0));
      portrait = new android.widget.ImageView(ctx);
      portrait.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
      LinearLayout.LayoutParams portraitSize = new LinearLayout.LayoutParams(dp(44), dp(44));
      portraitSize.rightMargin = dp(10);
      header.addView(portrait, portraitSize);
      LinearLayout identity = new LinearLayout(ctx);
      identity.setOrientation(VERTICAL);
      title = text(ctx, "DIGIBUDDY", 20, Color.WHITE);
      title.setTypeface(null, android.graphics.Typeface.BOLD);
      identity.addView(title);
      status = text(ctx, AppLanguage.text("text_your_partner_live"), 11, MUTED);
      status.setMaxLines(1);
      status.setEllipsize(android.text.TextUtils.TruncateAt.END);
      identity.addView(status);
      header.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
      Button play =
          button(
              ctx,
              "▶",
              () -> {
                if (session.running) togglePause();
                else resumeGame();
              });
      playButton = play;
      header.addView(play, new LinearLayout.LayoutParams(dp(48), dp(48)));
      Button gear = button(ctx, AppLanguage.text("text_settings"), () -> select(6));
      gear.setTextSize(11);
      LinearLayout.LayoutParams gearSize = new LinearLayout.LayoutParams(dp(80), dp(48));
      gearSize.leftMargin = dp(8);
      header.addView(gear, gearSize);
      addView(header);
      feedback = text(ctx, "", 12, RetroSkin.INK);
      feedback.setBackground(card(RetroSkin.GOLD, 0));
      feedback.setPadding(dp(10), dp(8), dp(10), dp(8));
      feedback.setVisibility(View.GONE);
      addView(feedback);
      summary = new LinearLayout(ctx);
      summary.setOrientation(VERTICAL);
      summary.setPadding(dp(10), dp(6), dp(10), dp(5));
      summary.setBackground(card(RetroSkin.PAPER, 0));
      LinearLayout meters = new LinearLayout(ctx);
      hpMeter = new RetroSkin.Meter(ctx, AppLanguage.text("text_hp"), 0xff55a66b);
      mpMeter = new RetroSkin.Meter(ctx, AppLanguage.text("text_mp"), 0xff609fd2);
      meters.addView(hpMeter, new LinearLayout.LayoutParams(0, dp(34), 1));
      LinearLayout.LayoutParams mpSize = new LinearLayout.LayoutParams(0, dp(34), 1);
      mpSize.leftMargin = dp(12);
      meters.addView(mpMeter, mpSize);
      summary.addView(meters);
      stats = text(ctx, "", 11, MUTED);
      stats.setPadding(dp(4), dp(5), dp(4), 0);
      summary.addView(stats);
      LinearLayout.LayoutParams summarySize = new LinearLayout.LayoutParams(-1, -2);
      summarySize.topMargin = dp(7);
      summarySize.bottomMargin = dp(7);
      addView(summary, summarySize);
      scroll = new ScrollView(ctx);
      scroll.setFillViewport(true);
      scroll.setVerticalScrollBarEnabled(false);
      content = new LinearLayout(ctx);
      content.setOrientation(VERTICAL);
      content.setPadding(dp(14), dp(12), dp(14), dp(12));
      content.setBackground(card(Color.rgb(18, 29, 38), 14));
      scroll.addView(content);
      addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
      body = text(ctx, "", 15, Color.WHITE);
      body.setTextIsSelectable(true);
      body.setLineSpacing(dp(7), 1);
      map = new MapView(ctx);
      map.onEnemy(MainActivity.this::showMapDigimon);
      mapArea = new FrameLayout(ctx);
      mapArea.setPadding(dp(5), dp(5), dp(5), dp(5));
      mapArea.setBackground(card(RetroSkin.PAPER, 0));
      mapArea.addView(map, new FrameLayout.LayoutParams(-1, -1));
      mapArea.setVisibility(View.GONE);
      addView(mapArea, new LinearLayout.LayoutParams(-1, 0, 1));
      String[] names = {
        AppLanguage.text("text_partner"),
        AppLanguage.text("text_bag"),
        AppLanguage.text("text_map"),
        AppLanguage.text("text_prosperity"),
        AppLanguage.text("text_evolution"),
        AppLanguage.text("text_cheats")
      };
      for (int rowIndex = 0; rowIndex < 2; rowIndex++) {
        LinearLayout row = new LinearLayout(ctx);
        LinearLayout.LayoutParams rowSize = new LinearLayout.LayoutParams(-1, dp(48));
        rowSize.topMargin = dp(7);
        addView(row, rowSize);
        for (int col = 0; col < 3; col++) {
          final int index = rowIndex * 3 + col, selected = navTabs[index];
          Button item = button(ctx, names[index], () -> select(selected));
          android.graphics.drawable.Drawable icon =
              index == 2
                  ? ctx.getDrawable(
                      getResources().getIdentifier("map_icon", "drawable", getPackageName()))
                  : new RetroSkin.Icon(index);
          if (icon instanceof android.graphics.drawable.BitmapDrawable)
            ((android.graphics.drawable.BitmapDrawable) icon).setFilterBitmap(false);
          int iconSize = dp(index == 2 ? 24 : 20);
          icon.setBounds(0, 0, iconSize, iconSize);
          item.setCompoundDrawables(icon, null, null, null);
          item.setCompoundDrawablePadding(dp(5));
          item.setTextSize(11);
          navigation.add(item);
          LinearLayout.LayoutParams itemSize = new LinearLayout.LayoutParams(0, -1, 1);
          if (col > 0) itemSize.leftMargin = dp(7);
          row.addView(item, itemSize);
        }
      }
      select(selectedTab);
    }

    void select(int selected) {
      tab = selected;
      selectedTab = selected;
      session.touchButtons = 0;
      previous = "";
      content.removeAllViews();
      content.addView(body);
      body.setGravity(tab < 5 ? Gravity.CENTER : Gravity.LEFT);
      body.setPadding(0, tab < 5 ? dp(25) : 0, 0, dp(10));
      if (tab < 5 && !snapshot.valid) {
        android.graphics.drawable.Drawable device =
            ctx.getDrawable(
                getResources().getIdentifier("jijimon_foreground", "drawable", getPackageName()));
        if (device instanceof android.graphics.drawable.BitmapDrawable)
          ((android.graphics.drawable.BitmapDrawable) device).setFilterBitmap(false);
        device.setBounds(0, 0, dp(68), dp(68));
        body.setCompoundDrawables(null, device, null, null);
        body.setCompoundDrawablePadding(dp(15));
      } else body.setCompoundDrawables(null, null, null, null);
      summary.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
      portrait.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
      status.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
      scroll.setVisibility(tab == 2 ? View.GONE : View.VISIBLE);
      mapArea.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
      if (tab == 2) feedback.setVisibility(View.GONE);
      for (int i = 0; i < navigation.size(); i++) {
        Button item = navigation.get(i);
        item.setTextColor(RetroSkin.INK);
        item.setBackground(card(navTabs[i] == tab ? RetroSkin.GOLD : 0xffe1e8d6, 0));
      }
      if (tab == 6) settings();
      if (tab == 7) cheats();
      update();
      scroll.scrollTo(0, 0);
    }

    void settings() {
      content.removeView(body);
      LinearLayout language = settingsGroup(AppLanguage.text("language_title"));
      String[] languageChoices = {"es", "en"};
      String[] languageLabels = {"Español", "English"};
      android.widget.RadioGroup languageOptions = new android.widget.RadioGroup(ctx);
      for (int index = 0; index < languageChoices.length; index++) {
        android.widget.RadioButton option = new android.widget.RadioButton(ctx);
        option.setId(index + 1);
        option.setText(languageLabels[index]);
        option.setTextColor(RetroSkin.INK);
        option.setMinHeight(dp(48));
        languageOptions.addView(option);
        if (languageChoices[index].equals(AppLanguage.locale().getLanguage()))
          languageOptions.check(index + 1);
      }
      languageOptions.setOnCheckedChangeListener(
          (group, checked) -> {
            if (checked >= 1 && checked <= languageChoices.length)
              ui.post(() -> changeLanguage(languageChoices[checked - 1]));
          });
      language.addView(languageOptions);
      language.addView(text(ctx, AppLanguage.text("language_game_note"), 12, MUTED));
      LinearLayout saves = settingsGroup(AppLanguage.text("text_saves"));
      saves.addView(
          text(ctx, AppLanguage.text("text_emulator_state_exact_point_in_the_session"), 13, MUTED));
      File state = quickState();
      saves.addView(
          text(
              ctx,
              state != null && state.isFile()
                  ? AppLanguage.text("text_last_manual_state") + savedDate(state)
                  : AppLanguage.text("text_no_manual_state_saved"),
              12,
              MUTED));
      saves.addView(button(ctx, AppLanguage.text("text_save_state"), this::saveQuickState));
      saves.addView(button(ctx, AppLanguage.text("text_load_state"), this::confirmLoadQuickState));
      saves.addView(
          button(
              ctx,
              AppLanguage.text("text_continue_latest_session"),
              MainActivity.this::resumeGame));
      saves.addView(
          button(ctx, AppLanguage.text("text_export_current_state"), () -> chooseState(true)));
      saves.addView(button(ctx, AppLanguage.text("text_import_state"), () -> chooseState(false)));
      saves.addView(
          text(
              ctx,
              AppLanguage.text(
                  "text_states_need_the_same_game_and_core_importing_keeps_the_previous_states"),
              12,
              MUTED));
      saves.addView(
          text(ctx, AppLanguage.text("text_memory_card_saves_made_in_the_game"), 13, MUTED));
      saves.addView(
          button(
              ctx,
              AppLanguage.text("text_load_from_the_game_s_menu"),
              () -> {
                if (!session.running) {
                  startGame();
                  return;
                }
                confirmAction(
                    AppLanguage.text("text_open_the_game_s_menu"),
                    AppLanguage.text(
                        "text_the_game_will_restart_to_load_from_your_memory_card_unsaved_session_progress_will_be"),
                    AppLanguage.text("text_open_menu"),
                    MainActivity.this::restartFromBeginning);
              }));
      saves.addView(
          button(ctx, AppLanguage.text("text_export_memory_card"), this::exportMemoryCard));
      saves.addView(
          button(ctx, AppLanguage.text("text_new_game"), MainActivity.this::confirmNewGame));
      saves.addView(
          button(ctx, AppLanguage.text("text_stop_and_save_memory_card"), this::stopGame));
      graphicsSettings(settingsGroup(AppLanguage.text("text_graphics_and_sound")));
      settingsGroup(AppLanguage.text("text_controls"))
          .addView(
              button(
                  ctx,
                  AppLanguage.text("text_remap_buttons"),
                  MainActivity.this::showControllerMapping));
      LinearLayout screens = settingsGroup(AppLanguage.text("text_screens"));
      screens.addView(
          button(
              ctx,
              AppLanguage.text("text_swap_screens"),
              () -> {
                gameOnSecondary = !gameOnSecondary;
                prefs.edit().putBoolean("gameOnSecondary", gameOnSecondary).apply();
                rebuildScreens();
              }));
      screens.addView(
          button(
              ctx,
              AppLanguage.text("text_choose_second_screen"),
              MainActivity.this::chooseDisplay));
      LinearLayout files = settingsGroup(AppLanguage.text("text_files"));
      files.addView(
          text(
              ctx,
              "BIOS: "
                  + (prefs.getString("bios", "").isEmpty()
                      ? AppLanguage.text("text_not_added")
                      : AppLanguage.text("text_imported"))
                  + AppLanguage.text("text_game")
                  + prefs.getString("discName", AppLanguage.text("text_not_added")),
              13,
              MUTED));
      files.addView(button(ctx, AppLanguage.text("text_change_bios"), () -> pick(1)));
      files.addView(button(ctx, AppLanguage.text("text_change_game"), () -> pick(2)));
      LinearLayout updates = settingsGroup(AppLanguage.text("text_updates"));
      try {
        updates.addView(
            text(
                ctx,
                "DigiBuddy " + getPackageManager().getPackageInfo(getPackageName(), 0).versionName,
                14,
                RetroSkin.INK));
      } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
      }
      toggle(
          updates,
          AppLanguage.text("text_check_when_the_app_opens"),
          "automaticUpdates",
          true,
          false);
      updates.addView(
          button(ctx, AppLanguage.text("text_check_for_updates_now"), () -> checkUpdates(true)));
    }

    private LinearLayout settingsGroup(String name) {
      LinearLayout group = new LinearLayout(ctx);
      group.setOrientation(VERTICAL);
      group.setPadding(dp(8), dp(4), dp(8), dp(12));
      group.setVisibility(expandedSettings.contains(name) ? View.VISIBLE : View.GONE);
      Button heading =
          button(ctx, name + (expandedSettings.contains(name) ? " ▾" : " ▸"), () -> {});
      heading.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
      heading.setOnClickListener(
          view -> {
            boolean expand = group.getVisibility() != View.VISIBLE;
            if (expand) expandedSettings.add(name);
            else expandedSettings.remove(name);
            group.setVisibility(expand ? View.VISIBLE : View.GONE);
            heading.setText(name + (expand ? " ▾" : " ▸"));
          });
      LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(48));
      size.bottomMargin = dp(6);
      content.addView(heading, size);
      content.addView(group);
      return group;
    }

    private File quickState() {
      File folder = session.saveFolder != null ? session.saveFolder : startupSaveFolder();
      return folder == null
          ? null
          : new File(folder, prefs.getString("engine", "swan-gl") + "-quick.state");
    }

    private void saveQuickState() {
      File state = quickState();
      if (!session.running || state == null) {
        showMessage(AppLanguage.text("text_start_a_game_first"));
        return;
      }
      session.command(
          () -> {
            boolean ok = NativeCore.saveState(state.getAbsolutePath());
            ui.post(
                () -> {
                  showMessage(
                      ok
                          ? AppLanguage.text("text_state_saved") + savedDate(state)
                          : AppLanguage.text("text_could_not_save"));
                  if (tab == 6) {
                    content.removeAllViews();
                    settings();
                  }
                });
          });
    }

    private void confirmLoadQuickState() {
      File state = quickState();
      if (!session.running) {
        showMessage(AppLanguage.text("text_start_the_game_before_loading_a_manual_state"));
        return;
      }
      if (state == null || !state.isFile() || state.length() == 0) {
        showMessage(AppLanguage.text("text_no_compatible_manual_state_saved"));
        return;
      }
      long timestamp = state.lastModified();
      confirmAction(
          AppLanguage.text("text_load_state_from") + savedDate(state),
          AppLanguage.text(
              "text_you_will_return_to_that_point_unsaved_progress_in_the_current_session_will_be_lost_th"),
          AppLanguage.text("text_load_state_2"),
          () ->
              session.command(
                  () -> {
                    boolean ok =
                        state.isFile()
                            && state.lastModified() == timestamp
                            && NativeCore.loadState(state.getAbsolutePath());
                    session.ram = NativeCore.memory();
                    ui.post(
                        () ->
                            showMessage(
                                ok
                                    ? AppLanguage.text("text_state_loaded")
                                    : AppLanguage.text(
                                        "text_the_state_changed_or_is_incompatible_select_it_again")));
                  }));
    }

    private void stopGame() {
      if (session.actionBusy) {
        showMessage(AppLanguage.text("text_wait_for_the_current_action_to_finish"));
        return;
      }
      confirmAction(
          AppLanguage.text("text_stop_game"),
          AppLanguage.text(
              "text_the_memory_card_will_be_saved_to_keep_the_exact_point_in_this_session_save_a_state_fi"),
          AppLanguage.text("text_stop"),
          () -> {
            session.stop();
            snapshot = new GameData.Snapshot();
            lastRam = null;
            session.ram = null;
          });
    }

    private void exportMemoryCard() {
      if (session.saveFolder == null) {
        showMessage(AppLanguage.text("text_start_a_game_first"));
        return;
      }
      Intent out = new Intent(Intent.ACTION_CREATE_DOCUMENT);
      out.setType("application/octet-stream");
      out.putExtra(Intent.EXTRA_TITLE, "digibuddy-memory-card.mcr");
      startActivityForResult(out, 3);
    }

    private void graphicsSettings(LinearLayout graphics) {
      choice(
          graphics,
          AppLanguage.text("text_graphics_core_stop_the_game_to_change"),
          "engine",
          new String[] {
            "SwanStation · OpenGL ES",
            "SwanStation · software",
            AppLanguage.text("text_pcsx_rearmed_compatibility")
          },
          new String[] {"swan-gl", "swan-sw", "pcsx"},
          "swan-gl",
          true);
      String engine = prefs.getString("engine", "swan-gl");
      if (engine.equals("swan-gl")) {
        choice(
            graphics,
            AppLanguage.text("text_internal_resolution"),
            "resolution",
            new String[] {
              AppLanguage.text("text_1_original_psx"), "2×", "3×", "4×", "5×", "6×", "8×"
            },
            new String[] {"1", "2", "3", "4", "5", "6", "8"},
            "4",
            false);
        choice(
            graphics,
            AppLanguage.text("text_texture_filter"),
            "textureFilter",
            new String[] {
              AppLanguage.text("text_nearest_original"),
              "Bilinear",
              AppLanguage.text("text_bilinear_without_borders")
            },
            new String[] {"Nearest", "Bilinear", "BilinearBinAlpha"},
            "Nearest",
            false);
        toggle(graphics, AppLanguage.text("text_pgxp_stabilize_3d_geometry"), "pgxp", false, false);
        toggle(
            graphics,
            AppLanguage.text("text_pgxp_correct_texture_perspective"),
            "pgxpTexture",
            true,
            false);
        toggle(graphics, AppLanguage.text("text_24_bit_color"), "trueColor", false, false);
        toggle(
            graphics,
            AppLanguage.text("text_smooth_screen_scaling"),
            "smoothScaling",
            false,
            false);
      } else if (engine.equals("pcsx")) {
        toggle(
            graphics,
            AppLanguage.text("text_2_internal_resolution_3d"),
            "resolution2x",
            true,
            false);
        toggle(graphics, AppLanguage.text("text_adjust_textures_for_2"), "textureFix", true, false);
        toggle(graphics, AppLanguage.text("text_original_psx_dithering"), "dithering", true, false);
        toggle(
            graphics,
            AppLanguage.text("text_cubic_sound_interpolation"),
            "audioCubic",
            true,
            false);
      }
      toggle(graphics, AppLanguage.text("text_fill_screen_disable_for_4_3"), "stretch", true, true);
      graphics.addView(
          text(
              ctx,
              engine.equals("swan-sw")
                  ? AppLanguage.text(
                      "text_software_mode_native_resolution_useful_for_checking_compatibility")
                  : AppLanguage.text(
                      "text_higher_resolution_improves_3d_models_2d_backgrounds_and_videos_retain_their_original"),
              11,
              MUTED));
    }

    void toggle(String label, String key, boolean initial, boolean layout) {
      toggle(content, label, key, initial, layout);
    }

    private void toggle(
        LinearLayout destination, String label, String key, boolean initial, boolean layout) {
      Switch control = new Switch(ctx);
      control.setText(label);
      control.setTextColor(RetroSkin.INK);
      control.setFocusable(false);
      control.setTextSize(13);
      control.setMinHeight(dp(48));
      control.setPadding(0, dp(6), 0, dp(6));
      control.setChecked(prefs.getBoolean(key, initial));
      control.setOnCheckedChangeListener(
          (v, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if (layout) updateGameAspectRatio();
            else if (!"automaticUpdates".equals(key)) queueGraphics();
          });
      destination.addView(control);
    }

    private void choice(
        LinearLayout destination,
        String label,
        String key,
        String[] labels,
        String[] values,
        String initial,
        boolean restart) {
      destination.addView(text(ctx, label, 12, MUTED));
      Spinner selector = new Spinner(ctx);
      selector.setAdapter(
          new ArrayAdapter<String>(ctx, android.R.layout.simple_spinner_dropdown_item, labels));
      String current = prefs.getString(key, initial);
      int index = 0;
      for (int i = 0; i < values.length; i++) if (values[i].equals(current)) index = i;
      selector.setSelection(index);
      selector.setOnItemSelectedListener(
          new AdapterView.OnItemSelectedListener() {
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int selected, long id) {
              String value = values[selected];
              if (value.equals(prefs.getString(key, initial))) return;
              if (restart && session.running) {
                showMessage(AppLanguage.text("text_stop_the_game_before_changing_graphics_cores"));
                String old = prefs.getString(key, initial);
                for (int i = 0; i < values.length; i++)
                  if (values[i].equals(old)) selector.setSelection(i);
                return;
              }
              prefs.edit().putString(key, value).apply();
              queueGraphics();
              if (restart) select(6);
            }
          });
      destination.addView(selector);
    }

    void updateSprites(GameData.Snapshot s) {
      if (atlas == null) return;
      int type = s.valid ? s.type : 0;
      if (type == lastSpriteType) return;
      android.graphics.Bitmap image = type > 0 ? atlas.mon(type, lastRam, profile) : atlas.menu(5);
      if (image != null) {
        android.graphics.drawable.BitmapDrawable pic =
            new android.graphics.drawable.BitmapDrawable(getResources(), image);
        pic.setFilterBitmap(false);
        portrait.setImageDrawable(pic);
      }
      for (int i = 0; i < navigation.size(); i++) {
        android.graphics.Bitmap bitmap =
            (i == 0 || i == 4)
                ? (i == 0 && type == 0
                    ? atlas.menu(5)
                    : atlas.mon(i == 0 ? type : 5, lastRam, profile))
                : atlas.menu(i);
        if (bitmap == null) continue;
        android.graphics.drawable.BitmapDrawable sprite =
            new android.graphics.drawable.BitmapDrawable(getResources(), bitmap);
        sprite.setFilterBitmap(false);
        sprite.setBounds(0, 0, dp(24), dp(24));
        navigation.get(i).setCompoundDrawables(sprite, null, null, null);
      }
      if (profile != null) lastSpriteType = type;
    }

    void cheats() {
      for (int i = 0; i < Cheats.LABELS.length; i++) {
        final int id = i;
        Button apply =
            button(
                ctx,
                AppLanguage.catalogText(Cheats.LABELS[i]),
                () ->
                    confirmAction(
                        AppLanguage.catalogText(Cheats.LABELS[id]),
                        AppLanguage.catalogText(Cheats.DESCRIPTIONS[id]),
                        () -> applyCheat(id)));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, dp(44));
        size.bottomMargin = dp(7);
        content.addView(apply, size);
      }
      content.addView(
          button(
              ctx,
              AppLanguage.text("text_undo_latest_cheat"),
              () ->
                  confirmAction(
                      AppLanguage.text("text_undo_latest_cheat"),
                      AppLanguage.text(
                          "text_restores_the_game_to_the_point_before_the_latest_cheat_progress_after_that_backup_wil"),
                      MainActivity.this::undoCheat)));
    }

    void section(String label) {
      TextView heading = text(ctx, label, 13, MUTED);
      heading.setTypeface(null, android.graphics.Typeface.BOLD);
      heading.setPadding(0, dp(4), 0, dp(10));
      content.addView(heading);
    }

    void dataRow(String label, String value) {
      dataRow(label, value, null);
    }

    void spriteRow(int type, String label, String value) {
      dataRow(label, value, atlas != null ? atlas.mon(type, lastRam, profile) : null);
    }

    void dataRow(String label, String value, android.graphics.Bitmap sprite) {
      dataRow(label, value, sprite, null);
    }

    void dataRow(String label, String value, android.graphics.Bitmap sprite, Runnable action) {
      LinearLayout row = new LinearLayout(ctx);
      if (action != null) {
        row.setMinimumHeight(dp(48));
        row.setFocusable(false);
        row.setContentDescription(label + " · " + value);
        row.setOnClickListener(v -> action.run());
      }
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(12), dp(10), dp(12), dp(10));
      row.setBackground(card(Color.rgb(25, 40, 50), 9));
      if (sprite != null) {
        android.widget.ImageView icon = new android.widget.ImageView(ctx);
        android.graphics.drawable.BitmapDrawable drawable =
            new android.graphics.drawable.BitmapDrawable(getResources(), sprite);
        drawable.setFilterBitmap(false);
        icon.setImageDrawable(drawable);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconSize.rightMargin = dp(10);
        row.addView(icon, iconSize);
      }
      TextView name = text(ctx, label, 14, Color.WHITE);
      row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
      TextView amount = text(ctx, value, 16, ACCENT);
      amount.setMaxWidth(dp(160));
      if (value.length() > 12) amount.setTextSize(13);
      amount.setTypeface(null, android.graphics.Typeface.BOLD);
      row.addView(amount);
      LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
      size.bottomMargin = dp(6);
      content.addView(row, size);
    }

    void metricGrid(String[] labels, int[] values) {
      for (int i = 0; i < labels.length; i += 4) {
        LinearLayout row = new LinearLayout(ctx);
        for (int j = i; j < Math.min(i + 4, labels.length); j++) {
          LinearLayout tile = new LinearLayout(ctx);
          tile.setOrientation(VERTICAL);
          tile.setPadding(dp(6), dp(6), dp(6), dp(6));
          tile.setBackground(card(Color.rgb(25, 40, 50), 9));
          tile.addView(text(ctx, labels[j], 11, MUTED));
          TextView number = text(ctx, Integer.toString(values[j]), 20, Color.WHITE);
          number.setTypeface(null, android.graphics.Typeface.BOLD);
          tile.addView(number);
          LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
          if (j > i) size.leftMargin = dp(8);
          row.addView(tile, size);
        }
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(8);
        content.addView(row, size);
      }
    }

    void renderData(GameData.Snapshot s) {
      int y = previous.isEmpty() ? 0 : scroll.getScrollY();
      content.removeAllViews();
      if (tab == 0) {
        String[] labels = {
          AppLanguage.text("text_sleepy"),
          AppLanguage.text("text_fatigue"),
          AppLanguage.text("text_hunger"),
          AppLanguage.text("text_needs_toilet"),
          AppLanguage.text("text_sadness"),
          AppLanguage.text("text_injury"),
          AppLanguage.text("text_sickness")
        };
        StringBuilder needs = new StringBuilder();
        for (int i = 0; i < labels.length; i++)
          if ((s.conditions & (1 << i)) != 0) {
            if (needs.length() > 0) needs.append(" · ");
            needs.append(labels[i]);
          }
        if (needs.length() > 0) {
          TextView attention =
              text(ctx, AppLanguage.text("text_needs_attention") + needs, 14, RetroSkin.INK);
          attention.setPadding(dp(8), dp(8), dp(8), dp(8));
          attention.setBackground(card(RetroSkin.GOLD, 0));
          content.addView(attention);
        } else content.addView(text(ctx, AppLanguage.text("text_all_good"), 13, MUTED));
        section(AppLanguage.text("text_stats"));
        metricGrid(
            new String[] {
              AppLanguage.text("text_offense"),
              AppLanguage.text("text_defense"),
              AppLanguage.text("text_speed"),
              AppLanguage.text("text_brains")
            },
            new int[] {s.offense, s.defense, s.speed, s.brains});
        section(AppLanguage.text("text_care"));
        dataRow(AppLanguage.text("text_happiness"), s.happiness + " / 100");
        dataRow(AppLanguage.text("text_discipline"), s.discipline + " / 100");
        dataRow(AppLanguage.text("text_fatigue"), Integer.toString(s.tiredness));
        dataRow(AppLanguage.text("text_care_mistakes_2"), Integer.toString(s.care));
        dataRow(AppLanguage.text("text_battles"), Integer.toString(s.battles));
        dataRow("Bits", Integer.toString(s.money));
      } else if (tab == 1) {
        section(
            AppLanguage.text("text_bag_2")
                + s.items.size()
                + " / "
                + s.inventorySize
                + AppLanguage.text("text_slots"));
        for (GameData.Item item : s.items) {
          android.graphics.Bitmap icon =
              atlas != null ? atlas.item(item.id, lastRam, profile) : null;
          String reason = ItemUse.unavailable(lastRam, profile, item.slot, item.id);
          dataRow(item.name, "× " + item.count + "  ›", icon, () -> confirmItem(item));
          if (reason != null) {
            TextView hint = text(ctx, reason, 12, MUTED);
            hint.setPadding(dp(10), 0, dp(10), dp(8));
            content.addView(hint);
          }
        }
        if (s.items.isEmpty())
          content.addView(
              text(ctx, AppLanguage.text("text_there_are_no_items_in_the_bag_yet"), 15, MUTED));
      } else if (tab == 2) {
        map.update(s);
      } else if (tab == 3) {
        section(AppLanguage.text("text_city_prosperity"));
        if (!s.prosperityAvailable) {
          content.addView(
              text(ctx, AppLanguage.text("text_prosperity_is_not_available_yet"), 15, MUTED));
        } else {
          dataRow(AppLanguage.text("text_prosperity"), s.prosperity + " / 100");
          section(AppLanguage.text("text_nearby_hints"));
          List<RecruitmentHints.Hint> hints = RecruitmentHints.nearby(s);
          for (RecruitmentHints.Hint hint : hints) {
            dataRow(
                hint.recruit.name + " · +" + hint.recruit.points,
                AppLanguage.text("text_view_hint"),
                atlas != null ? atlas.mon(hint.recruit.type, lastRam, profile) : null,
                () -> showRecruitHint(hint));
            content.addView(text(ctx, hint.location + " · " + hint.proximity, 12, MUTED));
          }
          if (hints.isEmpty())
            content.addView(
                text(
                    ctx,
                    AppLanguage.text("text_no_hints_recorded_nearby_explore_another_region"),
                    14,
                    MUTED));
          section(
              AppLanguage.text("text_recruited_digimon")
                  + s.recruits.size()
                  + " / "
                  + s.recruitable);
          for (GameData.Recruit recruit : s.recruits)
            spriteRow(recruit.type, recruit.name, "+" + recruit.points);
          if (s.recruits.isEmpty())
            content.addView(
                text(
                    ctx,
                    AppLanguage.text("text_bring_digimon_to_the_city_to_increase_prosperity"),
                    15,
                    MUTED));
        }
      } else {
        section(AppLanguage.text("text_next_evolutions"));
        for (GameData.Evolution evo : s.evolutions) {
          dataRow(
              evo.name,
              evo.candidate
                  ? AppLanguage.text("text_available_2")
                  : AppLanguage.text("text_view_requirements"),
              atlas != null ? atlas.mon(evo.type, lastRam, profile) : null,
              () -> showEvolutionDetails(evo));
        }
        content.addView(
            text(
                ctx,
                AppLanguage.text(
                    "text_tap_an_evolution_to_view_its_requirements_direct_evolution_advances_the_clock_and_kee"),
                12,
                MUTED));
      }
      if (tab == 4)
        content.addView(
            button(
                ctx,
                AppLanguage.text("text_undo_latest_evolution"),
                MainActivity.this::undoEvolution));
      scroll.post(() -> scroll.scrollTo(0, y));
    }

    void update() {
      GameData.Snapshot s = snapshot;
      summary.setVisibility(session.running && tab == 0 ? View.VISIBLE : View.GONE);
      portrait.setVisibility(session.running && tab == 0 ? View.VISIBLE : View.GONE);
      if (playButton != null) {
        playButton.setText(
            session.actionBusy ? "…" : session.running && !session.paused ? "Ⅱ" : "▶");
        playButton.setContentDescription(
            session.running
                ? AppLanguage.text("text_pause_or_resume")
                : AppLanguage.text("text_play"));
      }
      updateSprites(s);
      if (feedback.getVisibility() == View.VISIBLE && System.currentTimeMillis() > noticeUntil)
        feedback.setVisibility(View.GONE);
      title.setText(
          tab == 0
              ? (s.valid ? s.name : "DIGIBUDDY")
              : tab == 1
                  ? AppLanguage.text("text_bag_3")
                  : tab == 3
                      ? AppLanguage.text("text_prosperity_2")
                      : tab == 4
                          ? AppLanguage.text("text_evolution_2")
                          : tab == 6
                              ? AppLanguage.text("text_settings_2")
                              : tab == 7
                                  ? AppLanguage.text("text_cheats_2")
                                  : AppLanguage.text("text_map_2"));
      hpMeter.update(s.valid ? s.hp : 0, s.valid ? s.maxHp : 0);
      mpMeter.update(s.valid ? s.mp : 0, s.valid ? s.maxMp : 0);
      status.setText(
          importing
              ? importStatus
              : session.error != null
                  ? AppLanguage.text("text_failed_to_start")
                  : session.running
                      ? (session.paused ? AppLanguage.text("text_game_paused") : s.message)
                      : prefs.getString("game", "").isEmpty()
                          ? AppLanguage.text("text_import_your_game_and_tap_play")
                          : AppLanguage.text("text_continue_your_game_from_the_upper_screen"));
      stats.setText(
          s.valid
              ? s.age
                  + AppLanguage.text("text_days")
                  + s.weight
                  + " g · "
                  + String.format(Locale.ROOT, "%02d:%02d", s.hour, s.minute)
              : AppLanguage.text("text_your_partner_will_appear_when_you_start_the_game"));
      String value;
      if (tab == 8) return;
      if (tab == 2) {
        map.sprites(atlas, lastRam, profile);
        map.update(s);
        mapArea.setVisibility(s.valid ? View.VISIBLE : View.GONE);
        scroll.setVisibility(s.valid ? View.GONE : View.VISIBLE);
        if (!s.valid) body.setText(s.message);
        return;
      }
      if (tab == 7)
        value =
            AppLanguage.text(
                "text_apply_changes_to_this_game_session_each_cheat_creates_a_backup_state_so_you_can_undo");
      else if (tab == 6)
        value =
            AppLanguage.text("text_library_bios")
                + (prefs.getString("bios", "").isEmpty()
                    ? AppLanguage.text("text_not_added")
                    : AppLanguage.text("text_imported"))
                + AppLanguage.text("text_disc")
                + prefs.getString("discName", AppLanguage.text("text_not_added"))
                + AppLanguage.text("text_version")
                + prefs.getString("serial", "—")
                + "\n"
                + importStatus
                + "\n";
      else if (!session.running && tab == 0) {
        String[] steps = {
          AppLanguage.text("text_welcome"),
          AppLanguage.text("text_select_bios"),
          AppLanguage.text("text_select_game"),
          AppLanguage.text("text_ready_to_play"),
          AppLanguage.text("text_continue_game")
        };
        value =
            "DIGIBUDDY\n\n"
                + (startupStep() == StartupFlow.LANGUAGE
                    ? AppLanguage.text("language_welcome")
                    : steps[startupStep()])
                + "\n\n"
                + (importing
                    ? importStatus
                    : AppLanguage.text("text_follow_the_steps_on_the_upper_screen"))
                + AppLanguage.text("text_imported_files_are_kept_when_you_close_the_app");
      } else if (!s.valid) value = s.message;
      else if (tab == 0) {
        String[] conditions = {
          AppLanguage.text("text_sleepy"),
          AppLanguage.text("text_fatigue"),
          AppLanguage.text("text_hunger"),
          AppLanguage.text("text_needs_toilet"),
          AppLanguage.text("text_sadness"),
          AppLanguage.text("text_injury"),
          AppLanguage.text("text_sickness")
        };
        StringBuilder needs = new StringBuilder();
        for (int i = 0; i < conditions.length; i++)
          if ((s.conditions & (1 << i)) != 0) needs.append(conditions[i]).append(" · ");
        value =
            AppLanguage.text("text_offense_2")
                + s.offense
                + AppLanguage.text("text_defense_2")
                + s.defense
                + AppLanguage.text("text_speed_2")
                + s.speed
                + AppLanguage.text("text_brains_2")
                + s.brains
                + AppLanguage.text("text_happiness_2")
                + s.happiness
                + AppLanguage.text("text_100_discipline")
                + s.discipline
                + AppLanguage.text("text_100_fatigue")
                + s.tiredness
                + AppLanguage.text("text_care_mistakes_3")
                + s.care
                + AppLanguage.text("text_battles_2")
                + s.battles
                + "\n\n"
                + (needs.length() == 0
                    ? AppLanguage.text("text_no_care_warnings")
                    : needs.toString())
                + "\n\n"
                + s.money
                + " bits · "
                + String.format(Locale.ROOT, "%02d:%02d", s.hour, s.minute);
      } else if (tab == 1) {
        StringBuilder list =
            new StringBuilder(
                AppLanguage.text("text_bag_4")
                    + s.items.size()
                    + " / "
                    + s.inventorySize
                    + AppLanguage.text("text_slots_2"));
        for (GameData.Item item : s.items)
          list.append(item.name)
              .append("    × ")
              .append(item.count)
              .append(ItemUse.unavailable(lastRam, profile, item.slot, item.id))
              .append('\n');
        if (s.items.isEmpty()) list.append(AppLanguage.text("text_no_items"));
        value = list.toString();
      } else if (tab == 3) {
        StringBuilder list =
            new StringBuilder(
                s.prosperityAvailable
                    ? AppLanguage.text("text_prosperity_3") + s.prosperity
                    : AppLanguage.text("text_prosperity_pending"));
        list.append(" zone=").append(s.zoneId);
        for (GameData.Exit exit : s.exits) list.append(" exit=").append(exit.name);
        for (GameData.Recruit recruit : s.pendingRecruits)
          list.append(" pending=").append(recruit.type);
        for (GameData.Recruit recruit : s.recruits) list.append(' ').append(recruit.type);
        value = list.toString();
      } else {
        StringBuilder list =
            new StringBuilder(
                AppLanguage.text(
                    "text_natural_paths_and_current_requirements_these_are_candidates_not_a_guaranteed_evolutio"));
        for (GameData.Evolution evo : s.evolutions)
          list.append(evo.name)
              .append(" · ")
              .append(evo.score)
              .append(AppLanguage.text("text_4_categories"))
              .append(evo.candidate ? AppLanguage.text("text_requirements_met_2") : "")
              .append('\n')
              .append(evo.details)
              .append('\n');
        if (s.evolutions.isEmpty())
          list.append(AppLanguage.text("text_no_natural_paths_decoded_for_this_species"));
        value = list.toString();
      }
      if (!value.equals(previous)) {
        if (s.valid && tab < 5) renderData(s);
        else body.setText(value);
        previous = value;
      }
    }
  }
}
