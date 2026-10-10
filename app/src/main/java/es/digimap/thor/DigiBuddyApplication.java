package es.digimap.thor;

import android.app.Application;
import android.content.res.Configuration;

public final class DigiBuddyApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    updateLanguage();
  }

  @Override
  public void onConfigurationChanged(Configuration configuration) {
    super.onConfigurationChanged(configuration);
    updateLanguage();
  }

  private void updateLanguage() {
    AppLanguage.apply(this, getSharedPreferences("digimap", MODE_PRIVATE));
  }
}
