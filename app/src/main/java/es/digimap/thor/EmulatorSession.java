package es.digimap.thor;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.view.Surface;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** One worker owns every core/EGL operation, including shutdown and save commands. */
public final class EmulatorSession {
  private static final AtomicBoolean CORE_OWNED = new AtomicBoolean();
  public volatile boolean running, paused, suspended;
  public volatile int physicalButtons, touchButtons;
  public volatile byte[] ram;
  public volatile String error, saveWarning;
  public volatile File saveFolder;
  public volatile boolean actionBusy;
  public final AtomicReference<String> actionNotice = new AtomicReference<>();
  private ItemUse itemUse;
  private EvolutionAction evolution;
  private File evolutionCheckpoint;
  private boolean pauseAfterEvolution;
  private String evolutionEngine;
  private EvolutionHistory previousEvolution;
  private File itemCheckpoint;
  private boolean pauseAfterItem;
  private volatile boolean stopRequested;
  private Thread worker;
  private Surface requestedSurface;
  private long surfaceGeneration, appliedSurfaceGeneration = -1;
  private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

  public synchronized boolean start(String core, String system, String saves, String game) {
    if (running || !CORE_OWNED.compareAndSet(false, true)) return false;
    saveFolder = new File(saves);
    if (!saveFolder.isDirectory() && !saveFolder.mkdirs()) {
      CORE_OWNED.set(false);
      error = "No se pudo crear la carpeta de guardados";
      return false;
    }
    error = null;
    saveWarning = null;
    ram = null;
    paused = false;
    stopRequested = false;
    running = true;
    appliedSurfaceGeneration = -1;
    worker = new Thread(() -> run(core, system, saves, game), "DigiBuddy-emulator");
    worker.start();
    return true;
  }

  private void run(String core, String system, String saves, String game) {
    AudioTrack audio = null;
    boolean coreStarted = false;
    try {
      applySurface();
      String problem = NativeCore.start(core, system, saves, game);
      if (problem != null) throw new Exception(problem);
      coreStarted = true;
      int rate = (int) Math.round(NativeCore.sampleRate());
      double fps = NativeCore.fps();
      if (rate < 8000 || rate > 192000 || !Double.isFinite(fps) || fps < 1 || fps > 240)
        throw new Exception("Frecuencia de audio o vídeo no válida");
      int minimum =
          AudioTrack.getMinBufferSize(
              rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum < 0) throw new Exception("Audio no disponible");
      audio =
          new AudioTrack.Builder()
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_GAME)
                      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                      .build())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(rate)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .build())
              .setBufferSizeInBytes(Math.max(minimum, 16384))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
      audio.play();
      long frameNanos = (long) (1_000_000_000.0 / fps);
      long next = System.nanoTime(), lastSnapshot = 0, lastSave = next;
      boolean audioPaused = false;
      while (!stopRequested) {
        applySurface();
        Runnable action;
        while (!stopRequested && (action = commands.poll()) != null) action.run();
        if (paused || suspended) {
          if (!audioPaused) {
            audio.pause();
            audio.flush();
            audioPaused = true;
            saveCard();
          }
          Thread.sleep(20);
          next = System.nanoTime();
          continue;
        }
        if (audioPaused) {
          audio.play();
          audioPaused = false;
        }
        int input = physicalButtons | touchButtons;
        if (itemUse != null) {
          input = itemUse.advance(NativeCore.memory());
          if (itemUse.finished()) {
            finishItem();
            if (paused) continue;
          }
        }
        if (evolution != null) {
          input = 0;
          evolution.advance(NativeCore.memory());
          if (evolution.finished()) {
            finishEvolution();
            if (paused) continue;
          }
        }
        NativeCore.runFrame(input);
        short[] batch = NativeCore.audio();
        if (batch != null && batch.length > 0)
          audio.write(batch, 0, batch.length, AudioTrack.WRITE_NON_BLOCKING);
        long now = System.nanoTime();
        if (now - lastSnapshot > 250_000_000L) {
          ram = NativeCore.memory();
          lastSnapshot = now;
        }
        if (now - lastSave > 5_000_000_000L) {
          saveCard();
          lastSave = now;
        }
        next += frameNanos;
        long wait = next - System.nanoTime();
        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
        else if (wait < -frameNanos * 4) next = System.nanoTime();
      }
    } catch (InterruptedException ex) {
      if (!stopRequested) error = "La sesión fue interrumpida";
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      android.util.Log.w("DigiBuddy", "Core session failed: " + ex.getClass().getSimpleName());
      error = "No se pudo continuar la sesión del emulador. Reinicia la sesión desde Ajustes.";
    } finally {
      try {
        if (coreStarted) saveCard();
        if (audio != null) {
          try {
            audio.stop();
          } finally {
            audio.release();
          }
        }
      } finally {
        try {
          NativeCore.stop();
        } finally {
          itemUse = null;
          evolution = null;
          actionBusy = false;
          commands.clear();
          ram = null;
          running = false;
          CORE_OWNED.set(false);
        }
      }
    }
  }

  public synchronized void setSurface(Surface surface) {
    requestedSurface = surface;
    surfaceGeneration++;
  }

  public synchronized void clearSurface(Surface surface) {
    if (requestedSurface == surface) {
      requestedSurface = null;
      surfaceGeneration++;
    }
  }

  private void applySurface() {
    Surface surface;
    long generation;
    synchronized (this) {
      generation = surfaceGeneration;
      if (appliedSurfaceGeneration == generation) return;
      surface = requestedSurface;
    }
    NativeCore.surface(surface != null && surface.isValid() ? surface : null);
    appliedSurfaceGeneration = generation;
  }

  private void saveCard() {
    if (saveFolder != null
        && !NativeCore.saveCard(new File(saveFolder, "memory-card.mcr").getAbsolutePath()))
      saveWarning = "No se pudo guardar la tarjeta de memoria";
  }

  public synchronized boolean command(Runnable action) {
    if (!running || stopRequested || actionBusy) return false;
    commands.add(action);
    return true;
  }

  /** Item requests and all menu input run on the single core owner. */
  public synchronized boolean useItem(GameData.Profile profile, int slot, int item) {
    if (!running || stopRequested || actionBusy) return false;
    actionBusy = true;
    physicalButtons = touchButtons = 0;
    commands.add(
        () -> {
          try {
            ItemUse action = new ItemUse(NativeCore.memory(), profile, slot, item);
            File checkpoint = new File(saveFolder, "item-use-pending.state");
            if (!NativeCore.saveState(checkpoint.getAbsolutePath()))
              throw new IllegalArgumentException("No se pudo guardar la copia previa al objeto.");
            itemCheckpoint = checkpoint;
            pauseAfterItem = paused;
            itemUse = action;
            paused = false;
          } catch (IllegalArgumentException ex) {
            actionNotice.set(ex.getMessage());
            actionBusy = false;
          }
        });
    return true;
  }

  public synchronized boolean evolve(
      GameData.Profile profile, int source, int target, String engine) {
    return queueEvolution(profile, source, target, engine, false);
  }

  public synchronized boolean reverseEvolution(
      GameData.Profile profile, EvolutionHistory history, String engine) {
    if (history == null) return false;
    return queueEvolution(profile, history.target, history.source, engine, true);
  }

  private boolean queueEvolution(
      GameData.Profile profile, int source, int target, String engine, boolean reversing) {
    if (!"swan-gl".equals(engine) && !"swan-sw".equals(engine) && !"pcsx".equals(engine))
      return false;
    if (!running || stopRequested || actionBusy) return false;
    actionBusy = true;
    physicalButtons = touchButtons = 0;
    commands.add(() -> beginEvolution(profile, source, target, engine, reversing));
    return true;
  }

  private void beginEvolution(
      GameData.Profile profile, int source, int target, String engine, boolean reversing) {
    File checkpoint = new File(saveFolder, engine + "-evolution-pending.state");
    EvolutionHistory previous = EvolutionHistory.read(saveFolder, engine);
    boolean saved = false, historyChanged = false;
    try {
      byte[] memory = NativeCore.memory();
      EvolutionAction action;
      if (reversing) {
        if (previous == null || previous.target != source || previous.source != target)
          throw new IllegalArgumentException("La evolución registrada ha cambiado.");
        action = EvolutionAction.reverse(memory, profile, previous);
      } else {
        action = new EvolutionAction(memory, profile, source, target);
      }
      if (!NativeCore.saveState(checkpoint.getAbsolutePath()))
        throw new IllegalArgumentException("No se pudo guardar la copia previa. Acción cancelada.");
      saved = true;
      if (!reversing) {
        EvolutionAction.history(memory, profile, source, target).save(saveFolder, engine);
        historyChanged = true;
      }
      if (!writeEvolutionFields(action.writes()))
        throw new IllegalArgumentException("No se pudo iniciar la evolución.");
      evolutionCheckpoint = checkpoint;
      evolutionEngine = engine;
      previousEvolution = previous;
      pauseAfterEvolution = paused;
      evolution = action;
      paused = false;
    } catch (IOException | IllegalArgumentException failure) {
      String message =
          failure instanceof IOException
              ? "No se pudo guardar el historial. Acción cancelada."
              : failure.getMessage();
      if (saved) {
        if (NativeCore.loadState(checkpoint.getAbsolutePath())) checkpoint.delete();
        else message += " No se pudo restaurar la copia previa.";
      }
      if (historyChanged) restoreEvolutionHistory(previous, engine);
      ram = NativeCore.memory();
      actionNotice.set(message);
      actionBusy = false;
    }
  }

  private static boolean writeEvolutionFields(int[][] writes) {
    for (int[] write : writes) if (!NativeCore.writeRam(write[0], write[1], write[2])) return false;
    return true;
  }

  private void restoreEvolutionHistory(EvolutionHistory previous, String engine) {
    try {
      if (previous == null) EvolutionHistory.clear(saveFolder, engine);
      else previous.save(saveFolder, engine);
    } catch (IOException failure) {
      saveWarning = "No se pudo actualizar el historial de evolución.";
    }
  }

  private void finishEvolution() {
    boolean completed = !evolution.failed() && writeEvolutionFields(evolution.completionWrites());
    String message;
    if (completed) {
      if (evolution.reversing) {
        restoreEvolutionHistory(null, evolutionEngine);
        message = "Tu compañero ha vuelto a su forma anterior.";
      } else message = "Evolución completada.";
      evolutionCheckpoint.delete();
    } else {
      boolean restored = NativeCore.loadState(evolutionCheckpoint.getAbsolutePath());
      if (restored) evolutionCheckpoint.delete();
      restoreEvolutionHistory(previousEvolution, evolutionEngine);
      message =
          restored
              ? "La secuencia no terminó. Se ha recuperado el estado de antes de esta acción."
              : "La secuencia no terminó y no se pudo recuperar la copia de esta acción.";
    }
    evolution = null;
    previousEvolution = null;
    ram = NativeCore.memory();
    physicalButtons = touchButtons = 0;
    paused = pauseAfterEvolution;
    actionBusy = false;
    actionNotice.set(message);
  }

  private void finishItem() {
    String notice = itemUse.message();
    if (itemUse.failed() && !itemUse.accepted()) {
      if (NativeCore.loadState(itemCheckpoint.getAbsolutePath())) {
        itemCheckpoint.delete();
        notice += " No se ha usado ningún objeto.";
      } else {
        notice += " No se pudo restaurar la copia previa.";
      }
    } else if (!itemUse.failed()) {
      itemCheckpoint.delete();
    }
    itemUse = null;
    ram = NativeCore.memory();
    physicalButtons = touchButtons = 0;
    paused = pauseAfterItem;
    actionBusy = false;
    actionNotice.set(notice);
  }

  /** No join on the UI thread; ownership remains held until native cleanup finishes. */
  public synchronized void stop() {
    stopRequested = true;
    physicalButtons = 0;
    touchButtons = 0;
    if (worker != null) worker.interrupt();
  }
}
