package es.digimap.thor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

final class ReleaseUpdates implements AutoCloseable {
  interface Callback {
    void completed(Result result);
  }

  static final class Result {
    final String version, page;
    final boolean failed;
    final String errorMessage;
    final String downloadUrl, sha256;
    final long size;

    Result(String version, String page, boolean failed) {
      this(
          version,
          page,
          failed,
          "No se pudo comprobar. Prueba más tarde o abre las releases de GitHub.");
    }

    Result(String version, String page, boolean failed, String errorMessage) {
      this(version, page, failed, errorMessage, "", "", 0);
    }

    Result(
        String version,
        String page,
        boolean failed,
        String errorMessage,
        String downloadUrl,
        String sha256,
        long size) {
      this.version = version;
      this.page = page;
      this.failed = failed;
      this.errorMessage = errorMessage;
      this.downloadUrl = downloadUrl;
      this.sha256 = sha256;
      this.size = size;
    }
  }

  private static final String REPOSITORY = "bortisoftware/DigiBuddy";
  private static final String MANIFEST_URL =
      "https://raw.githubusercontent.com/" + REPOSITORY + "/main/tools/latest_release.json";
  private static final String API_URL =
      "https://api.github.com/repos/" + REPOSITORY + "/releases/latest";

  private static final class UpdateFailure extends Exception {
    final int status;

    UpdateFailure(int status) {
      this.status = status;
    }
  }

  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean busy = new AtomicBoolean();
  private volatile boolean closed;
  private volatile HttpsURLConnection connection;

  boolean check(String installedVersion, Callback callback) {
    if (closed || !busy.compareAndSet(false, true)) return false;
    worker.execute(
        () -> {
          Result result;
          try {
            result = fetch(installedVersion);
          } catch (UpdateFailure failure) {
            String message =
                failure.status == 403 || failure.status == 429
                    ? "GitHub ha limitado temporalmente las consultas. Prueba más tarde o abre las"
                        + " releases."
                    : "El servicio de actualizaciones no está disponible ahora. Prueba más tarde.";
            result = new Result("", "", true, message);
          } catch (Exception exception) {
            result = new Result("", "", true);
          } finally {
            busy.set(false);
          }
          if (!closed) callback.completed(result);
        });
    return true;
  }

  private Result fetch(String installedVersion) throws Exception {
    JSONObject release;
    try {
      release = readRelease(MANIFEST_URL);
    } catch (UpdateFailure failure) {
      if (closed || failure.status != 404) throw failure;
      release = readRelease(API_URL);
    }
    return parseRelease(release, installedVersion);
  }

  private JSONObject readRelease(String address) throws Exception {
    HttpsURLConnection request = (HttpsURLConnection) new URL(address).openConnection();
    connection = request;
    try {
      if (closed) throw new InterruptedException();
      request.setConnectTimeout(8000);
      request.setReadTimeout(8000);
      request.setInstanceFollowRedirects(false);
      request.setRequestProperty("Accept", "application/json");
      request.setRequestProperty("User-Agent", "DigiBuddy-update-check");
      int status = request.getResponseCode();
      if (status != 200) throw new UpdateFailure(status);
      if (request.getContentLengthLong() > 262144)
        throw new IllegalArgumentException("Invalid release response");
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (InputStream input = request.getInputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
          if (closed || bytes.size() + count > 262144) throw new IllegalArgumentException();
          bytes.write(buffer, 0, count);
        }
      }
      return new JSONObject(bytes.toString("UTF-8"));
    } finally {
      request.disconnect();
      connection = null;
    }
  }

  static Result parseRelease(JSONObject release, String installedVersion) throws Exception {
    String tag = release.getString("tag_name");
    if (!tag.matches("v?[0-9]{1,6}(\\.[0-9]{1,6}){2,3}"))
      throw new IllegalArgumentException("Invalid release version");
    String version = tag.startsWith("v") ? tag.substring(1) : tag;
    String page = "https://github.com/" + REPOSITORY + "/releases/tag/" + tag;
    if (!page.equals(release.getString("html_url"))) throw new IllegalArgumentException();
    if (release.optBoolean("draft", true)
        || release.optBoolean("prerelease", true)
        || !newer(version, installedVersion)) return new Result("", "", false);
    JSONArray assets = release.getJSONArray("assets");
    String expectedName = "DigiBuddy-" + version + ".apk";
    String expectedUrl =
        "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/" + expectedName;
    for (int i = 0; i < assets.length(); i++) {
      JSONObject asset = assets.getJSONObject(i);
      long size = asset.optLong("size", 0);
      if (expectedName.equals(asset.optString("name"))
          && expectedUrl.equals(asset.optString("browser_download_url"))
          && "uploaded".equals(asset.optString("state"))
          && size > 0
          && size <= 67108864) {
        String digest = asset.optString("digest");
        if (!digest.matches("sha256:[0-9a-f]{64}"))
          throw new IllegalArgumentException("Missing APK digest");
        return new Result(version, page, false, "", expectedUrl, digest.substring(7), size);
      }
    }
    return new Result("", "", false);
  }

  static boolean newer(String candidate, String installed) {
    if (candidate == null
        || installed == null
        || !candidate.matches("[0-9]{1,6}(\\.[0-9]{1,6}){2,3}")
        || !installed.matches("[0-9]{1,6}(\\.[0-9]{1,6}){2,3}")) return false;
    String[] left = candidate.split("\\."), right = installed.split("\\.");
    for (int i = 0; i < Math.max(left.length, right.length); i++) {
      int a = i < left.length ? Integer.parseInt(left[i]) : 0;
      int b = i < right.length ? Integer.parseInt(right[i]) : 0;
      if (a != b) return a > b;
    }
    return false;
  }

  @Override
  public void close() {
    closed = true;
    HttpsURLConnection active = connection;
    if (active != null) active.disconnect();
    worker.shutdownNow();
  }
}
