package es.digimap.thor;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.CancellationSignal;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class StateTransfer implements AutoCloseable {
  private final ContentResolver resolver;
  private final Consumer<String> notice;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final CancellationSignal cancellation = new CancellationSignal();
  private final AtomicReference<AssetFileDescriptor> active = new AtomicReference<>();
  private final Set<File> exports = ConcurrentHashMap.newKeySet();
  private volatile boolean closed;

  StateTransfer(ContentResolver resolver, Consumer<String> notice) {
    this.resolver = resolver;
    this.notice = notice;
  }

  void importState(Uri source, File folder, String engine, long expectedBytes) {
    if (!validSize(expectedBytes) || !engine.matches("swan-gl|swan-sw|pcsx")) {
      notice.accept(AppLanguage.text("text_start_the_game_before_importing_a_state"));
      return;
    }
    submit(
        () -> {
          File temporary = null;
          try {
            validateUri(source);
            if (!folder.isDirectory()) throw new IOException();
            temporary = File.createTempFile(engine + "-imported-", ".pending", folder);
            try (AssetFileDescriptor descriptor =
                resolver.openAssetFileDescriptor(source, "r", cancellation)) {
              if (descriptor == null) throw new IOException();
              active.set(descriptor);
              if (closed) return;
              try (InputStream input = descriptor.createInputStream();
                  FileOutputStream output = new FileOutputStream(temporary)) {
                copy(input, output, expectedBytes);
                output.getFD().sync();
              }
            } finally {
              active.set(null);
            }
            if (closed) return;
            File destination = new File(folder, temporary.getName().replace(".pending", ".state"));
            if (destination.exists() || !temporary.renameTo(destination)) throw new IOException();
            temporary = null;
            notice.accept(
                AppLanguage.text("text_state_imported_use_continue_latest_session_to_load_it"));
          } catch (IOException | RuntimeException exception) {
            if (!closed)
              notice.accept(
                  AppLanguage.text(
                      "text_could_not_import_use_a_state_from_the_same_game_and_core"));
          } finally {
            if (temporary != null) temporary.delete();
          }
        });
  }

  void exportState(File snapshot, Uri destination) {
    exports.add(snapshot);
    if (!submit(
        () -> {
          try {
            validateUri(destination);
            long bytes = snapshot.length();
            if (!validSize(bytes)) throw new IOException();
            try (AssetFileDescriptor descriptor =
                resolver.openAssetFileDescriptor(destination, "wt", cancellation)) {
              if (descriptor == null) throw new IOException();
              active.set(descriptor);
              if (closed) return;
              try (InputStream input = new FileInputStream(snapshot);
                  OutputStream output = descriptor.createOutputStream()) {
                copy(input, output, bytes);
              }
            } finally {
              active.set(null);
            }
            if (!closed) notice.accept(AppLanguage.text("text_state_exported"));
          } catch (IOException | RuntimeException exception) {
            if (!closed) notice.accept(AppLanguage.text("text_could_not_export_the_state"));
          } finally {
            removeExport(snapshot);
          }
        })) removeExport(snapshot);
  }

  private void removeExport(File snapshot) {
    exports.remove(snapshot);
    snapshot.delete();
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

  private static boolean validSize(long bytes) {
    return bytes > 0 && bytes <= 64L * 1024 * 1024;
  }

  private static void validateUri(Uri uri) throws IOException {
    if (uri == null || !"content".equals(uri.getScheme())) throw new IOException();
  }

  private void copy(InputStream input, OutputStream output, long expectedBytes) throws IOException {
    byte[] buffer = new byte[8192];
    long total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      cancellation.throwIfCanceled();
      total += count;
      if (closed || total > expectedBytes) throw new IOException();
      output.write(buffer, 0, count);
    }
    if (total != expectedBytes) throw new IOException();
    output.flush();
  }

  @Override
  public void close() {
    closed = true;
    cancellation.cancel();
    worker.shutdownNow();
    for (File snapshot : exports) removeExport(snapshot);
    AssetFileDescriptor descriptor = active.getAndSet(null);
    if (descriptor != null)
      try {
        descriptor.close();
      } catch (IOException ignored) {
      }
  }
}
