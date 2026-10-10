package es.digimap.thor;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves UI resources without tying emulator workers to an Activity's lifetime. */
final class AppLanguage {
  static final String PREFERENCE = "appLanguage";
  private static volatile Resources resources;
  private static volatile Locale locale = Locale.ENGLISH;
  private static final ConcurrentHashMap<String, Integer> identifiers = new ConcurrentHashMap<>();

  private AppLanguage() {}

  static String preference(SharedPreferences preferences) {
    String choice = preferences.getString(PREFERENCE, "system");
    return "es".equals(choice) || "en".equals(choice) ? choice : "system";
  }

  static String systemLanguage(Configuration configuration) {
    LocaleList languages = configuration.getLocales();
    return !languages.isEmpty() && "es".equals(languages.get(0).getLanguage()) ? "es" : "en";
  }

  static void apply(Context context, SharedPreferences preferences) {
    String choice = preference(preferences);
    String language =
        "system".equals(choice)
            ? systemLanguage(context.getResources().getConfiguration())
            : choice;
    Locale selected = Locale.forLanguageTag(language);
    Configuration configuration = new Configuration(context.getResources().getConfiguration());
    configuration.setLocales(new LocaleList(selected));
    Context application = context.getApplicationContext();
    Resources localized =
        (application != null ? application : context)
            .createConfigurationContext(configuration)
            .getResources();
    locale = selected;
    resources = localized;
  }

  static Locale locale() {
    return locale;
  }

  static String text(String name) {
    Resources current = resources;
    if (current == null) throw new IllegalStateException("App language is not initialized");
    Integer identifier = identifiers.get(name);
    if (identifier == null) {
      int resolved = current.getIdentifier(name, "string", "es.digimap.thor");
      if (resolved == 0) throw new IllegalArgumentException("Unknown UI resource");
      identifiers.putIfAbsent(name, resolved);
      identifier = resolved;
    }
    return current.getString(identifier);
  }

  static String catalogText(String value) {
    return value.startsWith("@") ? text(value.substring(1)) : value;
  }
}
