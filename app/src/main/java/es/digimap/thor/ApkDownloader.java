package es.digimap.thor;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;

final class ApkDownloader implements AutoCloseable {
  interface Callback {
    void progress(int percent);

    void completed(Uri apk);
  }

  private final Context context;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private volatile boolean closed;
  private volatile HttpsURLConnection connection;

  ApkDownloader(Context context) {
    this.context = context.getApplicationContext();
  }

  void download(ReleaseUpdates.Result update, Callback callback) {
    worker.execute(
        () -> {
          Uri result = null;
          File temporary = null;
          try {
            if (!update.sha256.matches("[0-9a-f]{64}")
                || update.size <= 0
                || update.size > 67108864) throw new IllegalArgumentException();
            File directory = new File(context.getCacheDir(), "updates");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException();
            File[] oldFiles = directory.listFiles();
            if (oldFiles != null) for (File file : oldFiles) file.delete();
            temporary = File.createTempFile("download-", ".part", directory);
            HttpsURLConnection request = open(update.downloadUrl);
            if (request.getContentLengthLong() > update.size) throw new java.io.IOException();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long started = System.nanoTime();
            long total = 0;
            int previousPercent = -1;
            try (InputStream input = request.getInputStream();
                FileOutputStream output = new FileOutputStream(temporary)) {
              byte[] buffer = new byte[32768];
              int count;
              while ((count = input.read(buffer)) != -1) {
                total += count;
                if (closed || total > update.size || System.nanoTime() - started > 180000000000L)
                  throw new java.io.IOException();
                output.write(buffer, 0, count);
                digest.update(buffer, 0, count);
                int percent = (int) (total * 100 / update.size);
                if (percent != previousPercent) {
                  callback.progress(percent);
                  previousPercent = percent;
                }
              }
              output.getFD().sync();
            }
            if (closed || total != update.size || !hex(digest.digest()).equals(update.sha256))
              throw new java.io.IOException();
            verifyPackage(temporary, update.version);
            File verified = new File(directory, update.sha256 + ".apk");
            if (closed || !temporary.renameTo(verified)) throw new java.io.IOException();
            result =
                Uri.parse(
                    "content://" + context.getPackageName() + ".updates/apk/" + verified.getName());
          } catch (Exception failure) {
            // A partial or unverified package must never reach the installer.
          } finally {
            if (temporary != null) temporary.delete();
            HttpsURLConnection request = connection;
            if (request != null) request.disconnect();
            connection = null;
          }
          if (!closed) callback.completed(result);
        });
  }

  private HttpsURLConnection open(String address) throws Exception {
    URL url = new URL(address);
    if (!address.matches(
        "https://github\\.com/bortisoftware/DigiBuddy/releases/download/v?[0-9.]+/DigiBuddy-[0-9.]+\\.apk"))
      throw new IllegalArgumentException();
    for (int redirects = 0; redirects <= 5; redirects++) {
      String host = url.getHost();
      if (!"https".equals(url.getProtocol())
          || url.getUserInfo() != null
          || (url.getPort() != -1 && url.getPort() != 443)
          || !(host.equals("github.com")
              || host.equals("release-assets.githubusercontent.com")
              || host.equals("objects.githubusercontent.com"))) throw new java.io.IOException();
      HttpsURLConnection request = (HttpsURLConnection) url.openConnection();
      connection = request;
      if (closed) throw new InterruptedException();
      request.setConnectTimeout(15000);
      request.setReadTimeout(15000);
      request.setInstanceFollowRedirects(false);
      request.setRequestProperty("User-Agent", "DigiBuddy-apk-update");
      int status = request.getResponseCode();
      if (status == 200) return request;
      String location = request.getHeaderField("Location");
      request.disconnect();
      if (!(status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
          || location == null) throw new java.io.IOException();
      url = new URL(url, location);
    }
    throw new java.io.IOException();
  }

  @SuppressWarnings("deprecation")
  private void verifyPackage(File apk, String version) throws Exception {
    PackageManager manager = context.getPackageManager();
    PackageInfo installed =
        manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
    PackageInfo candidate =
        manager.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNATURES);
    if (candidate == null
        || !context.getPackageName().equals(candidate.packageName)
        || !version.equals(candidate.versionName)
        || !ReleaseUpdates.newer(version, installed.versionName)
        || candidate.versionCode < installed.versionCode
        || !signers(installed.signatures).equals(signers(candidate.signatures)))
      throw new SecurityException();
  }

  private static Set<String> signers(Signature[] signatures) throws Exception {
    if (signatures == null || signatures.length == 0) throw new SecurityException();
    Set<String> result = new HashSet<>();
    for (Signature signature : signatures)
      result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
    return result;
  }

  private static String hex(byte[] bytes) {
    StringBuilder text = new StringBuilder();
    for (byte value : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
    return text.toString();
  }

  @Override
  public void close() {
    closed = true;
    HttpsURLConnection active = connection;
    if (active != null) active.disconnect();
    worker.shutdownNow();
  }
}
