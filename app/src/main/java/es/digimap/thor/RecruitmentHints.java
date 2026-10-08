package es.digimap.thor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class RecruitmentHints {
  static final class Hint {
    final GameData.Recruit recruit;
    final String location, clue, requirements, proximity;
    private final int distance;

    Hint(GameData.Recruit recruit, Entry entry, int distance, String requirements) {
      this.recruit = recruit;
      location = DataNames.ZONES[entry.zone];
      clue = entry.clue;
      this.requirements = requirements;
      this.distance = distance;
      proximity =
          distance == 0 ? "En tu zona" : distance == 1 ? "Zona de una salida" : "En esta región";
    }
  }

  private static final class Entry {
    final int type, zone, prosperity, prerequisite;
    final String clue;

    Entry(int type, int zone, int prosperity, int prerequisite, String clue) {
      this.type = type;
      this.zone = zone;
      this.prosperity = prosperity;
      this.prerequisite = prerequisite;
      this.clue = clue;
    }
  }

  private static Entry clue(int type, int zone, String clue) {
    return new Entry(type, zone, 0, -1, clue);
  }

  private static final Entry[] ENTRIES = {
    clue(3, 0, "El primer encuentro del bosque termina en combate."),
    clue(46, 0, "Busca al sur del WC. Insiste en hablar."),
    clue(32, 68, "Lleva comida para quien espera en el árbol."),
    clue(49, 1, "Visita la costa al atardecer; regresa tras cruzar."),
    new Entry(5, 8, 15, -1, "Habla con Jijimon y sal de su casa."),
    new Entry(7, 8, 50, -1, "Jijimon anuncia la apertura del monte; sal preparado."),
    new Entry(42, 0, 50, -1, "Revisa el árbol con una puerta."),
    clue(58, 4, "Busca al ninja cuando exista la tienda secreta."),
    clue(4, 6, "Habla con el Digimon perdido entre los manglares."),
    new Entry(25, 5, 0, 46, "Una planta marchita necesita la planta de lluvia."),
    clue(36, 69, "El recorrido correcto termina en la señal circular."),
    clue(55, 5, "El visitante de la playa aparece solo ocasionalmente."),
    clue(9, 17, "Ayuda a despejar el túnel para alcanzar la lava."),
    new Entry(38, 12, 0, 9, "Regresa al excavador después de unos días."),
    clue(34, 12, "La persecución del bandido acaba en este túnel."),
    new Entry(33, 11, 0, 36, "Habla antes en la clínica; lleva recuperación."),
    clue(13, 9, "Un visitante poco frecuente puede retarte aquí."),
    new Entry(28, 11, 45, 35, "Los círculos y las noticias llevan a un visitante."),
    clue(18, 30, "Ten paciencia con sus descargas y vuelve a hablar."),
    clue(31, 30, "Este rival necesita varias victorias consecutivas."),
    clue(45, 30, "Tu compañero puede ayudarte a cortar su huida."),
    new Entry(48, 30, 45, 9, "Busca su legado en la cueva ancestral."),
    clue(39, 56, "Habla con el súbdito que está cerca del rey."),
    clue(37, 19, "Las respuestas importan más que combatir."),
    new Entry(26, 29, 40, 35, "Investiga la desaparición de Myotismon; necesitas un Virus."),
    clue(47, 26, "Demuestra tu habilidad gestionando su tienda."),
    clue(21, 21, "En lo alto del cañón hay un nido."),
    clue(35, 39, "El ascensor del bandido deja a alguien atrapado."),
    new Entry(8, 32, 0, 36, "Investiga el problema de la región del tiempo."),
    clue(53, 33, "Este viajero se deja ver en cinco lugares."),
    clue(53, 25, "Este viajero se deja ver en cinco lugares."),
    clue(53, 37, "Este viajero se deja ver en cinco lugares."),
    clue(53, 50, "Este viajero se deja ver en cinco lugares."),
    clue(53, 57, "Este viajero se deja ver en cinco lugares."),
    clue(52, 34, "Completa los intercambios de los tres comerciantes."),
    clue(57, 34, "Una victoria en curling puede convencerlo."),
    clue(24, 34, "Ayuda a recuperar la cueva ocupada por bandidos."),
    clue(23, 34, "Tras su rescate, vuelve con alguien resistente al frío."),
    clue(22, 34, "La revancha tiene una hora y reglas propias."),
    clue(20, 35, "Un compañero Vacuna puede abrir el santuario."),
    clue(50, 38, "Busca al ave antes de que termine la mañana."),
    clue(17, 38, "Un tímido habitante se esconde detrás de un árbol."),
    clue(14, 62, "Numemon puede encontrar uso a un disfraz vacío."),
    clue(19, 58, "Habla de entrenamiento con los habitantes de la isla."),
    clue(51, 58, "Pregunta por el entrenamiento dentro de su gimnasio."),
    clue(11, 57, "Antes hay que resolver la crisis de la fábrica."),
    clue(40, 52, "Investiga la fábrica y vuelve tras resolver su problema."),
    clue(41, 52, "Detén al saboteador y regresa más tarde."),
    clue(27, 52, "Este visitante de la fábrica aparece ocasionalmente."),
    clue(6, 59, "En el monte abierto te espera un combate."),
    clue(54, 59, "Busca otro desafío dentro del monte."),
    clue(12, 59, "Continúa por el monte hasta encontrarlo."),
    clue(56, 59, "Regresa al monte después de completar la historia.")
  };

  static List<Hint> nearby(GameData.Snapshot snapshot) {
    List<Hint> hints = new ArrayList<>();
    if (!snapshot.valid || !snapshot.prosperityAvailable || snapshot.zoneId < 0) return hints;
    for (Entry entry : ENTRIES) {
      GameData.Recruit recruit = pending(snapshot, entry.type);
      if (recruit == null) continue;
      int distance = distance(snapshot, entry.zone);
      if (distance < 0) continue;
      hints.add(new Hint(recruit, entry, distance, requirements(snapshot, entry)));
    }
    hints.sort(
        Comparator.comparingInt((Hint hint) -> hint.distance)
            .thenComparingInt(hint -> hint.requirements.isEmpty() ? 0 : 1)
            .thenComparingInt(hint -> hint.recruit.type));
    List<Hint> selected = new ArrayList<>();
    for (Hint hint : hints) {
      boolean duplicate = false;
      for (Hint previous : selected)
        if (previous.recruit.type == hint.recruit.type) duplicate = true;
      if (!duplicate) selected.add(hint);
      if (selected.size() == 4) break;
    }
    return selected;
  }

  private static GameData.Recruit pending(GameData.Snapshot snapshot, int type) {
    for (GameData.Recruit recruit : snapshot.pendingRecruits)
      if (recruit.type == type) return recruit;
    return null;
  }

  private static String requirements(GameData.Snapshot snapshot, Entry entry) {
    List<String> missing = new ArrayList<>();
    if (snapshot.prosperity < entry.prosperity) missing.add("Prosperidad " + entry.prosperity);
    if (entry.prerequisite >= 0 && pending(snapshot, entry.prerequisite) != null)
      missing.add("Reclutar a " + DataNames.DIGIMON[entry.prerequisite]);
    return String.join(" · ", missing);
  }

  private static int distance(GameData.Snapshot snapshot, int zone) {
    if (snapshot.zoneId == zone) return 0;
    for (GameData.Exit exit : snapshot.exits) if (DataNames.ZONES[zone].equals(exit.name)) return 1;
    int region = region(snapshot.zoneId);
    return region >= 0 && region == region(zone) ? 2 : -1;
  }

  private static int region(int zone) {
    switch (zone) {
      case 0:
      case 1:
      case 2:
      case 4:
      case 68:
        return 0;
      case 5:
      case 6:
      case 69:
        return 1;
      case 3:
      case 12:
      case 13:
      case 14:
      case 15:
      case 16:
      case 17:
      case 37:
        return 2;
      case 7:
      case 9:
      case 10:
      case 11:
        return 3;
      case 8:
      case 36:
      case 43:
      case 44:
      case 45:
      case 46:
      case 47:
      case 48:
      case 49:
      case 53:
      case 54:
      case 55:
      case 60:
        return 4;
      case 18:
      case 19:
      case 27:
      case 28:
      case 29:
      case 64:
      case 65:
      case 66:
        return 5;
      case 20:
      case 21:
      case 22:
      case 23:
      case 24:
      case 25:
      case 26:
      case 39:
        return 6;
      case 30:
      case 56:
        return 7;
      case 31:
      case 32:
      case 33:
        return 8;
      case 34:
      case 35:
      case 40:
        return 9;
      case 38:
        return 10;
      case 50:
      case 61:
      case 62:
      case 63:
        return 11;
      case 51:
        return 12;
      case 52:
      case 57:
        return 13;
      case 58:
        return 14;
      case 59:
        return 15;
      default:
        return -1;
    }
  }
}
