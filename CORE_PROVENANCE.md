# Procedencia de los núcleos

Los núcleos están permitidos en la APK. La BIOS y el juego no lo están. El repositorio utiliza scripts y un manifiesto de hashes para obtener los binarios de desarrollo, sin incluir archivos del usuario.

| Núcleo | Fuente | Commit identificado en el binario probado |
| --- | --- | --- |
| SwanStation | https://github.com/libretro/swanstation | b6c30a7b270a3f68ac41f268eafdfa678d17dea2 |
| PCSX ReARMed | https://github.com/libretro/pcsx_rearmed | c8816799b50388e61cfe237fe2cdbb7d8175f20a |

tools/dependencies.json fija los archivos, tamaños y SHA-256 de herramientas, binarios y archivos de fuentes. Los archivos de fuentes usan URL con el commit completo. Se conservan avisos de licencias del árbol correspondiente en app/src/main/assets/licenses/upstream.

SHA-256 de las bibliotecas ARM64 probadas:

- libswanstation.so: b52f803929909a8256b889bf451c10e4fb1ef3064af19a28121e1e0694f0d9b2
- libpcsx_rearmed.so: 8abed40849eafa1e0e02eceb332354ae2134b2b6dba109e163b2114360070464

Estos binarios proceden del buildbot de libretro. Sus URL nightly/latest pueden cambiar; el descargador rechaza cualquier contenido distinto de los hashes registrados. No se ha demostrado una reconstrucción idéntica a partir de los archivos de fuentes ni documentado todavía todas las opciones del buildbot.

La release v0.3.2 adjunta DigiBuddy-0.3.2-sources.zip: el código de DigiBuddy del commit de la release, los dos árboles upstream fijados con sus dependencias incluidas y avisos originales, las recetas Android de CI y BUILDING.md con comandos y opciones. SHA256SUMS.txt permite comprobar los adjuntos. El archivo no contiene herramientas compiladas, BIOS comerciales, discos, guardados ni claves. SwanStation incorpora OpenBIOS libre como parte del núcleo: se conserva su código y licencia, pero se omite el archivo independiente openbios.bin del paquete de fuentes. No se incorpora la BIOS aportada por el usuario.

La receta de SwanStation fija Release, Android API 24, ARM64 y c++_static; usa CMake y el toolchain del NDK. PCSX ReARMed usa jni/Android.mk con APP_ABI=arm64-v8a y sus opciones por defecto. Las recetas externas se conservan en el paquete con su URL y fecha de obtención. No se afirma igualdad byte a byte: no se conoce la imagen exacta de herramientas utilizada por el buildbot para los binarios descargados. Las fuentes fijadas, sus dependencias y los scripts permiten reconstruir los núcleos, pero la reconstrucción no se ha validado en este PC.

libdigimap.so se compila desde app/src/main/cpp/bridge.cpp con NDK r27c en tools/build_apk.py. No contiene BIOS ni código extraído del juego.
