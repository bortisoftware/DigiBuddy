# Diseño de DigiBuddy

## Estado actual

Aplicación Android ARM64 funcional, probada con Digimon World de referencia en AYN Thor y Anbernic RG DS. El juego y el panel se asignan mediante DisplayManager y Presentation. La imagen del juego ocupa su pantalla sin cabeceras de la aplicación.

## Responsabilidades

- MainActivity: selección de archivos, pantallas, ajustes e interfaz. Los cuadros de confirmación bloquean los controles físicos y pausan la partida; restauran la pausa previa al cerrar.
- EmulatorSession: un único hilo posee el núcleo, EGL, sonido y comandos. Una reserva atómica impide iniciar otro núcleo antes de completar su cierre. El cierre no bloquea el hilo de interfaz.
- bridge.cpp: interfaz libretro, superficie y audio, memoria PSX y guardados. Comprueba tamaños y límites; escribe guardados mediante archivo temporal y cambio de nombre.
- ProfileLoader y GameData: metadatos acotados, huellas SHA-256 y validación de estructuras antes de leer estadísticas, inventario, requisitos o mapas.
- EncounterClassifier: interpreta de forma limitada el script de interacción vigente. Solo muestra contactos que conducen a combate reconocido; oculta los casos inciertos.
- ItemUse: automatiza los menús originales con pulsaciones acotadas y verifica el consumo de una unidad. No reproduce efectos ni resta objetos escribiendo RAM.
- EvolutionAction: valida el contexto y la ruta sobre memoria fresca. La evolución y su reversión usan la secuencia original; una reversión conserva el progreso actual del compañero y deja al juego elegir técnicas compatibles. EvolutionHistory guarda solo especies, perfil, generación y fecha de nacimiento por disco y motor.
- FileImporter y CardExporter: importación y exportación acotadas en trabajadores de archivos. Usan el resolver de Application y callbacks débiles; los proveedores de documentos no trabajan en el hilo del emulador.
- SpriteAtlas y ModelIconRenderer: cargan gráficos del disco del usuario; trabajo fuera del hilo de interfaz, cachés acotadas y cierre del ejecutor.
- Cheats: planes de escritura de datos, ejecutados por el propietario del núcleo sobre memoria fresca, con confirmación y respaldo previo.
- StartupFlow: decide los pasos desde archivos realmente presentes y configuración persistida; selecciona el estado más reciente por disco y motor. StartupView muestra el asistente y el inicio sobre la pantalla del juego, y desaparece al arrancar el núcleo.

## Guardados y archivos privados

BIOS y disco se importan al almacenamiento privado de Android mediante el selector del sistema. La app no solicita acceso general al almacenamiento. El permiso de Internet se usa para consultar releases públicas por HTTPS; la emulación y los datos del panel son locales. Copias, estados y tarjeta se separan por hash del disco; los estados rápidos distinguen el núcleo.

Las acciones directas se limitan al perfil de referencia reconocido. Durante objetos o evolución se reserva una acción, se bloquean los comandos que puedan interferir y se neutraliza el mando. Antes de modificar la partida se guarda un estado. Deshacer una evolución vuelve a la especie anterior con la animación del juego, sin cargar el estado antiguo. Conserva estadísticas, edad, vida restante, cuidados y técnicas aprendidas del momento de deshacer; reinicia el tiempo en etapa para evitar otra evolución inmediata. Inventario, reloj, dinero y progreso del pueblo siguen actuales. Solo un fallo de la secuencia restaura el respaldo creado justo antes de esa acción. El historial no autoriza una reversión si cambian especie, generación o fecha de nacimiento.

## Mapa

Las coordenadas se obtienen de las entidades del juego y se convierten a casillas. El ajuste del mapa depende de su contenido y del tamaño del panel, sin usar la resolución del renderizador ni el estiramiento del juego. Los marcadores de enemigos usan el script activo, no solo la especie; los personajes amistosos no se clasifican por compartir un modelo con enemigos.

La estimación de dificultad utiliza estadísticas conocidas, pero no modela técnicas, IA, resistencias ni habilidad del jugador. Los encuentros que dependen de opciones o rutinas no verificadas pueden faltar.

## Límites

El controlador de interfaz sigue concentrado en MainActivity. La separación del núcleo y de los decodificadores evita accesos simultáneos a libretro, pero una refactorización futura puede dividir las vistas sin cambiar su comportamiento. No existe una garantía de compatibilidad universal ni una auditoría que demuestre ausencia absoluta de errores o fugas.

## Desarrollo y compilación

### Compilar en Windows

Python 3.11 o posterior y conexión para obtener las herramientas. Las descargas quedan en .tools, fuera del repositorio:

    python tools/fetch_dependencies.py --development-cores --sources
    python tools/extract_tools.py --development-cores --sources
    python tools/build_apk.py --mode local

Las dependencias se verifican por tamaño y SHA-256. Las URL de los núcleos de desarrollo apuntan al buildbot: si su contenido cambia, se rechaza la descarga hasta revisar una nueva versión. Los commits de sus fuentes y la procedencia están en [CORE_PROVENANCE.md](CORE_PROVENANCE.md).

El modo local desactiva la depuración y usa una clave de desarrollo local. El modo debug permite las pruebas por ADB. Ninguno es una firma definitiva para distribuir actualizaciones.

El modo release requiere DIGIMAP_KEYSTORE, DIGIMAP_KEY_ALIAS, DIGIMAP_STORE_PASSWORD y DIGIMAP_KEY_PASSWORD en el entorno, y se construye con:

    python tools/build_apk.py --mode release

No guardar la clave ni las contraseñas en Git. Las actualizaciones de una APK necesitan la misma clave de firma. La versión instalada conserva el identificador es.digimap.thor para mantener los datos existentes.

Antes de publicar, revisa todos los archivos preparados en Git y el contenido de la APK:

    python tools/check_publish.py --all --apk dist/DigiBuddy-0.3.2-local.apk

El comprobador aplica una lista de archivos permitidos y busca formatos privados, rutas personales y patrones de secretos. No sustituye la revisión de código.

## Pistas y actualizaciones

El panel agrupa Ajustes en seis desplegables. Las ventanas de objetos y evoluciones conservan botones visibles y desplazan únicamente los detalles. El mapa ofrece un selector si varios Digimon coinciden en el área de toque; un arrastre no activa la consulta. Los estados manuales muestran fecha y requieren confirmar la carga antes de sustituir una sesión. Sus archivos permanecen asociados al juego y al núcleo.

ItemDescriptions contiene descripciones propias en español de los 128 IDs de objetos de referencia. Se contrastaron con las tablas de descripciones y las funciones de objetos y alimentos de la descompilación del juego; no se incorpora código ni archivos del disco. Los efectos corresponden al juego de referencia y pueden variar con parches que cambien sus mecánicas.

ApkDownloader descarga la APK en la caché privada con conexiones y tamaño acotados. Verifica el hash publicado y la identidad y firma del paquete antes de ofrecerlo al instalador. UpdateApkProvider permite compartir únicamente la APK verificada mediante un permiso temporal de lectura. El panel muestra progreso y cancelación; al conceder el permiso de instalación, el flujo continúa al volver a la app. Android solicita la confirmación final. Si se cierra la Activity durante la descarga, esta se cancela y puede reintentarse.

RecruitmentHints filtra los reclutamientos pendientes leídos del perfil validado. Prioriza la zona actual, los nombres de las salidas presentes y, después, la misma región. Devuelve hasta cuatro pistas sin duplicar especies. Los requisitos detectables se muestran separados; las pistas no garantizan que un NPC esté presente ni interpretan todas las etapas de las misiones. El catálogo usa nombres de zona estables, sin depender del idioma del disco.

Referencias de hechos de juego: [guía de reclutamiento de HeroesAndCons](https://gamefaqs.gamespot.com/ps/913684-digimon-world/faqs/73895), [guía de Neve](https://gamefaqs.gamespot.com/ps/913684-digimon-world/faqs/71504) y [ubicaciones de Wikimon](https://wikimon.net/Digimon_World_Guide). Las pistas están redactadas para la app; no se incorpora una copia de esas guías.

ReleaseUpdates consulta tools/latest_release.json mediante raw.githubusercontent.com para evitar el límite de consultas anónimas de la API de GitHub. El archivo se actualiza después de publicar y verificar los adjuntos de una release; si no existe, se consulta la API como respaldo. Usa un trabajador cerrado con la Activity, conexiones HTTPS con tiempos y respuesta acotados y validación del repositorio, versión y adjunto APK. Solo las releases estables con una APK nombrada para esa versión generan un aviso. Las consultas automáticas se realizan al abrir o volver a la app; el botón manual permite reintentar. El aviso usa el diálogo del panel y conserva la pausa anterior del juego. Los errores HTTP se diferencian de los fallos generales de comprobación, sin atribuir todos los errores a la conexión del usuario.

StateTransfer importa estados del tamaño exacto solicitado al núcleo activo en un trabajador de documentos. Primero escribe un temporal y después lo publica por cambio de nombre; no carga ni sobrescribe estados automáticamente. La exportación captura el estado en el propietario del núcleo y entrega una copia temporal al trabajador. Cancelación, descriptores y temporales se cierran con la Activity.

ControllerBindings conserva las asignaciones en preferencias privadas por descriptor del dispositivo, independiente de su ID temporal. Mantiene las pulsaciones de teclas y ejes por separado para que soltar una dirección no cancele un botón sostenido. El diálogo de remapeo pausa la sesión, consume los controles, pide confirmación si el origen ya tiene otra función y limpia las pulsaciones al cerrarse. Los dispositivos virtuales de Android no se ofrecen como mandos físicos.

El mapa añade las transiciones de scripts que puede resolver con el estado actual, además de los diez triggers de salida directa. La inspección acota instrucciones, llamadas y punteros y no ejecuta ni modifica scripts del juego. Los retratos de modelos usan una cámara frontal sin inclinación. Los marcadores de reclutamiento combinan NPC presentes, especie pendiente y ubicación conocida; los diálogos o condiciones no interpretados pueden impedir mostrar alguno.
