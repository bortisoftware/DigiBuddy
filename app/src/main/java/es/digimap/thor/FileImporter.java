package es.digimap.thor;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

/** Imports selected documents into private files; provider work never retains an Activity. */
final class FileImporter implements AutoCloseable {
  private static final long BIOS_BYTES = 512 * 1024;
  private static final long MAX_DISC_BYTES = 2L * 1024 * 1024 * 1024;
  private static final int MAX_NAME_CHARS = 256;

  interface Listener {
    void progress(long bytes);

    void completed(Result result);

    void failed();
  }

  static final class Result {
    final File path;
    final boolean bios;
    final String sha256, name;
    final DiscIdentity identity;

    Result(File path, boolean bios, String sha256, String name, DiscIdentity identity) {
      this.path = path;
      this.bios = bios;
      this.sha256 = sha256;
      this.name = name;
      this.identity = identity;
    }
  }

  private final ContentResolver resolver;
  private final File directory;
  private final Listener listener;
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          action -> {
            Thread thread = new Thread(action, "DigiBuddy-import");
            thread.setDaemon(true);
            return thread;
          });
  private final AtomicReference<Job> activeJob = new AtomicReference<>();
  private volatile boolean closed;

  FileImporter(ContentResolver resolver, File directory, Listener listener) {
    this.resolver = resolver;
    this.directory = directory;
    this.listener = listener;
  }

  boolean start(Uri source, boolean bios) {
    if (closed || source == null || !"content".equals(source.getScheme())) return false;
    Job job = new Job(source, bios);
    if (!activeJob.compareAndSet(null, job)) return false;
    try {
      worker.execute(() -> run(job));
      return true;
    } catch (RejectedExecutionException exception) {
      activeJob.compareAndSet(job, null);
      return false;
    }
  }

  private void run(Job job) {
    Result result = null;
    try {
      result = importDocument(job);
    } catch (Exception exception) {
      if (!job.cancelled && !closed)
        Log.w("DigiBuddy", "file_import_failed: " + exception.getClass().getSimpleName());
    } finally {
      job.closeFiles();
      activeJob.compareAndSet(job, null);
    }
    if (!closed && !job.cancelled) {
      if (result == null) listener.failed();
      else listener.completed(result);
    }
  }

  private Result importDocument(Job job) throws Exception {
    job.checkOpen();
    String name = readName(job);
    String lower = name.toLowerCase(Locale.ROOT);
    if (!job.bios
        && !(lower.endsWith(".iso")
            || lower.endsWith(".bin")
            || lower.endsWith(".img")
            || lower.endsWith(".chd"))) throw new IOException("Unsupported disc extension");
    job.checkOpen();
    File temp = File.createTempFile("import-", ".tmp", directory);
    job.temporary = temp;
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    long total = copy(job, temp, digest);
    job.checkOpen();
    String hash = hexadecimal(digest.digest());

    if (job.bios) {
      if (total != BIOS_BYTES) throw new IOException("Invalid BIOS size");
      File system = new File(directory, "system");
      ensureDirectory(system);
      File destination = new File(system, "scph1001.bin");
      job.checkOpen();
      if (!temp.renameTo(destination)) throw new IOException("BIOS commit failed");
      return new Result(destination, true, hash, name, null);
    }

    DiscIdentity identity = DiscIdentity.read(temp);
    if (identity.sectorSize == 0 && !lower.endsWith(".chd"))
      throw new IOException("Unrecognized PSX disc");
    File folder = new File(directory, "games/" + hash);
    ensureDirectory(folder);
    String extension =
        lower.endsWith(".chd") ? ".chd" : identity.sectorSize == 2352 ? ".bin" : ".iso";
    File game = new File(folder, "disc" + extension);
    File cueTemp = null;
    try {
      if (identity.sectorSize == 2352) {
        cueTemp = File.createTempFile("import-cue-", ".tmp", folder);
        job.cueTemporary = cueTemp;
        try (FileOutputStream output = new FileOutputStream(cueTemp)) {
          output.write(
              ("FILE \"disc.bin\" BINARY\n  TRACK 01 "
                      + (identity.payloadOffset == 16 ? "MODE1" : "MODE2")
                      + "/2352\n    INDEX 01 00:00:00\n")
                  .getBytes(StandardCharsets.US_ASCII));
          output.getFD().sync();
        }
      }
      job.checkOpen();
      if (!temp.renameTo(game)) throw new IOException("Disc commit failed");
      if (cueTemp != null) {
        File cue = new File(folder, "disc.cue");
        if (!cueTemp.renameTo(cue)) throw new IOException("Cue commit failed");
        game = cue;
      }
      return new Result(game, false, hash, name, identity);
    } finally {
      if (cueTemp != null) cueTemp.delete();
      job.cueTemporary = null;
    }
  }

  private String readName(Job job) throws IOException {
    try (Cursor cursor =
        resolver.query(
            job.source,
            new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
            null,
            null,
            null,
            job.cancellation)) {
      job.cursor.set(cursor);
      job.checkOpen();
      String name = "";
      if (cursor != null && cursor.moveToFirst()) {
        job.checkOpen();
        int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
          String provided = cursor.getString(nameColumn);
          if (provided != null) name = provided;
        }
        int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
          long size = cursor.getLong(sizeColumn);
          if (size > (job.bios ? BIOS_BYTES : MAX_DISC_BYTES))
            throw new IOException("Document exceeds size limit");
        }
      }
      if (name.length() > MAX_NAME_CHARS || (!job.bios && name.length() == 0))
        throw new IOException("Invalid display name");
      return name;
    } finally {
      job.cursor.set(null);
    }
  }

  private long copy(Job job, File temp, MessageDigest digest) throws IOException {
    try (AssetFileDescriptor descriptor =
        resolver.openAssetFileDescriptor(job.source, "r", job.cancellation)) {
      job.descriptor.set(descriptor);
      job.checkOpen();
      if (descriptor == null) throw new IOException("Document unavailable");
      long declaredLength = descriptor.getDeclaredLength();
      long limit = job.bios ? BIOS_BYTES : MAX_DISC_BYTES;
      if (declaredLength > limit) throw new IOException("Document exceeds size limit");
      try (InputStream input = descriptor.createInputStream();
          FileOutputStream output = new FileOutputStream(temp)) {
        job.input.set(input);
        job.output.set(output);
        job.checkOpen();
        byte[] buffer = new byte[1024 * 1024];
        long total = 0, last = 0;
        int count;
        while (true) {
          job.checkOpen();
          count = input.read(buffer);
          if (count == -1) break;
          job.checkOpen();
          total += count;
          if (total > limit) throw new IOException("Document exceeds size limit");
          output.write(buffer, 0, count);
          digest.update(buffer, 0, count);
          if (total - last > 8 * 1024 * 1024) {
            last = total;
            if (!closed) listener.progress(total);
          }
        }
        job.checkOpen();
        output.getFD().sync();
        return total;
      } finally {
        job.input.set(null);
        job.output.set(null);
      }
    } finally {
      job.descriptor.set(null);
    }
  }

  private static String hexadecimal(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte part : bytes) value.append(String.format(Locale.ROOT, "%02x", part & 255));
    return value.toString();
  }

  private static void ensureDirectory(File directory) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs())
      throw new IOException("Private directory unavailable");
  }

  @Override
  public void close() {
    closed = true;
    Job job = activeJob.getAndSet(null);
    worker.shutdownNow();
    if (job == null) return;
    job.cancelled = true;
    // A provider's cancellation/close Binder calls can themselves block; keep them off the UI.
    Thread cleanup = new Thread(job::cancelIo, "DigiBuddy-import-cancel");
    cleanup.setDaemon(true);
    cleanup.start();
  }

  private static void closeQuietly(Closeable resource) {
    if (resource == null) return;
    try {
      resource.close();
    } catch (IOException | RuntimeException exception) {
      Log.w("DigiBuddy", "file_import_close_failed");
    }
  }

  private static final class Job {
    final Uri source;
    final boolean bios;
    final CancellationSignal cancellation = new CancellationSignal();
    final AtomicReference<Cursor> cursor = new AtomicReference<>();
    final AtomicReference<AssetFileDescriptor> descriptor = new AtomicReference<>();
    final AtomicReference<InputStream> input = new AtomicReference<>();
    final AtomicReference<FileOutputStream> output = new AtomicReference<>();
    volatile boolean cancelled;
    volatile File temporary, cueTemporary;

    Job(Uri source, boolean bios) {
      this.source = source;
      this.bios = bios;
    }

    void checkOpen() throws IOException {
      if (cancelled || Thread.currentThread().isInterrupted())
        throw new java.io.InterruptedIOException("Import cancelled");
      cancellation.throwIfCanceled();
    }

    void closeFiles() {
      closeQuietly(descriptor.getAndSet(null));
      closeQuietly(input.getAndSet(null));
      closeQuietly(output.getAndSet(null));
      if (temporary != null) temporary.delete();
      if (cueTemporary != null) cueTemporary.delete();
    }

    void cancelIo() {
      closeFiles();
      try {
        cancellation.cancel();
      } catch (RuntimeException exception) {
        Log.w("DigiBuddy", "file_import_cancel_failed");
      } finally {
        closeQuietly(cursor.getAndSet(null));
      }
    }
  }
}
