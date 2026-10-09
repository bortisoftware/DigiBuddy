package es.digimap.thor;

final class ItemDescriptions {
  private ItemDescriptions() {}

  private static final String[] EFFECTS = {
    "Recupera PV de tu compañero.",
    "Recupera una cantidad mayor de PV que la recuperación pequeña.",
    "Recupera una gran cantidad de PV.",
    "Restaura todos los PV de tu compañero.",
    "Recupera PM de tu compañero.",
    "Recupera una cantidad mayor de PM que el disco básico.",
    "Recupera una gran cantidad de PM.",
    "Recupera PV y PM a la vez.",
    "Cura estados alterados del combate, como veneno, parálisis y confusión.",
    "Cura estados alterados y recupera PV y PM.",
    "Protege contra cambios de estado durante el combate.",
    "Reanima al compañero derrotado y recupera parte de sus PV.",
    "Reanima al compañero derrotado, elimina estados alterados y restaura sus PV.",
    "Trata heridas y puede ayudar contra la enfermedad.",
    "Trata heridas y enfermedad.",
    "Aumenta el ataque durante el combate actual.",
    "Aumenta la defensa durante el combate actual.",
    "Aumenta la velocidad durante el combate actual.",
    "Aumenta varias estadísticas durante el combate actual.",
    "Aumenta notablemente el ataque durante el combate actual.",
    "Aumenta notablemente la defensa durante el combate actual.",
    "Aumenta notablemente la velocidad durante el combate actual.",
    "Permite regresar rápidamente al pueblo cuando el juego lo permite.",
    "Aumenta permanentemente el ataque.",
    "Aumenta permanentemente la defensa.",
    "Aumenta permanentemente la inteligencia.",
    "Aumenta permanentemente la velocidad.",
    "Aumenta permanentemente los PV máximos.",
    "Aumenta permanentemente los PM máximos.",
    "Aumenta ataque e inteligencia, pero reduce la vida restante de tu compañero.",
    "Aumenta defensa y velocidad, pero reduce la vida restante de tu compañero.",
    "Aumenta los PV y PM máximos, pero reduce la vida restante de tu compañero.",
    "Permite atender la necesidad de baño sin ir a un WC. Se consume al usarlo.",
    "Mejora el entrenamiento mientras lo llevas en la bolsa. No se consume desde este panel.",
    "Mejora la recuperación al dormir mientras lo llevas en la bolsa.",
    "Reduce la incorporación de enemigos al combate mientras lo llevas en la bolsa.",
    "Favorece la incorporación de enemigos al combate mientras lo llevas en la bolsa.",
    "Permite recuperar algo de PV y PM al caminar; no funciona al correr.",
    "Reduce el hambre y aumenta ligeramente el peso.",
    "Reduce más el hambre que la carne normal y aumenta el peso.",
    "Reduce bastante el hambre, mejora el ánimo y alivia el cansancio; aumenta el peso.",
    "Reduce el hambre, baja el peso y mejora temporalmente ciertos entrenamientos.",
    "Reduce el hambre, aumenta el peso y mejora temporalmente ciertos entrenamientos.",
    "Reduce el hambre y alivia mucho el cansancio; aumenta ligeramente el peso.",
    "Reduce el hambre y aumenta el peso en 1.",
    "Reduce el hambre y mejora la disciplina; aumenta el peso.",
    "Reduce bastante el hambre y mejora varias estadísticas; aumenta el peso.",
    "Reduce el hambre y mejora temporalmente el entrenamiento; aumenta el peso.",
    "Reduce el hambre y mejora el ánimo; aumenta el peso.",
    "Reduce el hambre y el cansancio, y mejora ánimo y disciplina; aumenta el peso.",
    "Alimento que aumenta el peso y puede venderse a buen precio.",
    "Sacia mucho el hambre, pero aumenta notablemente el peso.",
    "Reduce el hambre y restaura los PV; aumenta el peso.",
    "Reduce el hambre y restaura los PM; aumenta el peso.",
    "Reduce el hambre y baja el peso.",
    "Reduce el hambre y recupera PV y PM; aumenta el peso.",
    "Reduce el hambre y mejora el ataque; aumenta el peso.",
    "Reduce el hambre y mejora la defensa; aumenta el peso.",
    "Reduce el hambre y mejora la velocidad; aumenta el peso.",
    "Reduce el hambre y mejora la inteligencia; aumenta el peso.",
    "Reduce el hambre y aumenta los PV máximos; aumenta el peso.",
    "Reduce el hambre y aumenta los PM máximos; aumenta el peso.",
    "Alimento de pesca que reduce un poco el hambre y aumenta ligeramente el peso.",
    "Alimento de pesca que reduce el hambre y aumenta ligeramente el peso.",
    "Alimento de pesca que reduce el hambre y aumenta el peso.",
    "Reduce el hambre, mejora varias estadísticas y baja el peso.",
    "Alimento de pesca que sacia bastante y aumenta notablemente el peso.",
    "Recupera PV y PM y prolonga algo la vida; aumenta el peso y puede causar enfermedad.",
    "Reduce el hambre y aumenta el peso, pero causa enfermedad.",
    "Mejora el ánimo y alivia el cansancio, pero puede causar enfermedad.",
    "Reduce el hambre, mejora el ánimo, alivia el cansancio y prolonga la vida. Puede causar"
        + " enfermedad.",
    "Objeto de evolución asociado a Greymon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Meramon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Birdramon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Centarumon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Monochromon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Drimogemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Tyrannomon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Devimon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Ogremon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Leomon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Angemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Bakemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Kaminarmon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Airdramon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Cockatrimon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Unimon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Kabuterimon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Kuwagamon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Vegimon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Igamon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Seadramon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Whamon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Shellmon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Coelamon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Garurumon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Yukidarumon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Mojyamon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Nanimon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a MetalGreymon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a SkullGreymon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Andromon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Megadramon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Mamemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a MetalMamemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Giromon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Piccolomon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Monzaemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Vademon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Etemon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Digitamamon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a Hououmon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a HerculesKabuterimon. Solo puede usarse cuando el juego permite"
        + " esa evolución.",
    "Objeto de evolución asociado a MegaSeadramon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Objeto de evolución asociado a WereGarurumon. Solo puede usarse cuando el juego permite esa"
        + " evolución.",
    "Símbolo de tu amistad con Seadramon; está relacionado con el acceso al Lago del Dragón.",
    "Permite pescar en el lago. Se utiliza desde los lugares de pesca.",
    "Caña mejorada para pescar en el lago. Se utiliza desde los lugares de pesca.",
    "Tablilla vinculada a la historia de Leomon y sus antepasados.",
    "Llave para acceder a la mansión. Se utiliza durante el evento correspondiente.",
    "Objeto especial de historia que se utiliza en su evento; no es un consumible de cuidado.",
    "Recupera PM y tiene una función especial en un evento de la historia.",
    "Alimento que reduce el hambre y recupera PV; puede causar enfermedad.",
    "Llave del frigorífico. Se utiliza durante el evento correspondiente.",
    "Permite interpretar la escritura antigua durante los eventos correspondientes.",
    "Objeto de evolución asociado a Gigadramon. Su uso depende de las condiciones del juego.",
    "Objeto de evolución asociado a Panjyamon. Su uso depende de las condiciones del juego.",
    "Objeto de evolución asociado a MetalEtemon. Su uso depende de las condiciones del juego.",
  };

  static String effect(int item) {
    return item >= 0 && item < EFFECTS.length
        ? EFFECTS[item]
        : "Objeto no identificado. No lo uses hasta que el juego lo reconozca.";
  }
}
