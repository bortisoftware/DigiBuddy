package es.digimap.thor;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Exports a stable private card copy without allowing document providers to block emulation. */
final class CardExporter implements AutoCloseable {
  private static final long MAX_CARD_BYTES = 1024 * 1024;
  private final ContentResolver resolver;
  private final Consumer<String> notice;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(action -> new Thread(action, "DigiBuddy-export"));
  private final Set<File> snapshots = ConcurrentHashMap.newKeySet();
  private final AtomicReference<AssetFileDescriptor> activeDestination = new AtomicReference<>();
  private final CancellationSignal cancellation = new CancellationSignal();
  private volatile boolean closed;

  CardExporter(ContentResolver resolver, Consumer<String> notice) {
    this.resolver = resolver;
    this.notice = notice;
  }

  static File snapshot(File card, File directory) throws IOException {
    File snapshot = null;
    try (FileInputStream source = new FileInputStream(card)) {
      long size = source.getChannel().size();
      if (size < 1 || size > MAX_CARD_BYTES) throw new IOException("Invalid card size");
      snapshot = File.createTempFile("card-export-", ".tmp", directory);
      try (FileOutputStream output = new FileOutputStream(snapshot)) {
        copyBounded(source, output, null);
      }
      return snapshot;
    } catch (IOException | RuntimeException exception) {
      if (snapshot != null) snapshot.delete();
      throw exception;
    }
  }

  void prepareAndExport(File card, File directory, Uri destination) {
    submit(
        () -> {
          try {
            File snapshot = snapshot(card, directory);
            snapshots.add(snapshot);
            exportSnapshot(snapshot, destination);
          } catch (IOException | RuntimeException exception) {
            failure(exception);
          }
        });
  }

  void export(File snapshot, Uri destination) {
    snapshots.add(snapshot);
    if (!submit(() -> exportSnapshot(snapshot, destination))) removeSnapshot(snapshot);
  }

  private boolean submit(Runnable action) {
    if (closed) return false;
    try {
      worker.execute(action);
      return true;
    } catch (RejectedExecutionException exception) {
      return false;
    }
  }

  private void exportSnapshot(File snapshot, Uri destination) {
    try {
      if (closed) return;
      if (destination == null || !"content".equals(destination.getScheme()))
        throw new IOException("Invalid document URI");
      try (AssetFileDescriptor descriptor =
          resolver.openAssetFileDescriptor(destination, "wt", cancellation)) {
        if (descriptor == null) throw new IOException("Destination unavailable");
        activeDestination.set(descriptor);
        if (closed) return;
        try (FileInputStream source = new FileInputStream(snapshot);
            OutputStream output = descriptor.createOutputStream()) {
          copyBounded(source, output, cancellation);
          output.flush();
        }
      }
      if (!closed) notice.accept("Tarjeta exportada");
    } catch (IOException | RuntimeException exception) {
      failure(exception);
    } finally {
      activeDestination.set(null);
      removeSnapshot(snapshot);
    }
  }

  private static void copyBounded(
      FileInputStream source, OutputStream output, CancellationSignal cancellation)
      throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    int count;
    while ((count = source.read(buffer)) != -1) {
      if (cancellation != null) cancellation.throwIfCanceled();
      total += count;
      if (total > MAX_CARD_BYTES) throw new IOException("Card exceeds size limit");
      output.write(buffer, 0, count);
    }
  }

  private void failure(Exception exception) {
    Log.w("DigiBuddy", "card_export_failed: " + exception.getClass().getSimpleName());
    if (!closed) notice.accept("No se pudo exportar la tarjeta.");
  }

  private void removeSnapshot(File snapshot) {
    snapshots.remove(snapshot);
    snapshot.delete();
  }

  @Override
  public void close() {
    closed = true;
    cancellation.cancel();
    worker.shutdownNow();
    AssetFileDescriptor destination = activeDestination.getAndSet(null);
    if (destination != null) {
      try {
        destination.close();
      } catch (IOException exception) {
        Log.w("DigiBuddy", "card_export_close_failed");
      }
    }
    for (File snapshot : snapshots) removeSnapshot(snapshot);
  }
}
