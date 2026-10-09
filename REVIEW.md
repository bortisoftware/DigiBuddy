# Validación y límites

## Comprobado

- AYN Thor: doble pantalla, navegación, controles y confirmaciones. En 0.3.2 se verificaron cambios 4:3/pantalla completa con el juego activo y pausado: conserva imagen, pausa y sesión sin reconstruir las pantallas.
- Anbernic RG DS: distribución de pantallas; guardar y cargar estados verificados en dispositivo.
- Acciones sobre copias de partidas: uso de objetos y evolución nativa. La reversión Greymon → Agumon conservó inventario, dinero, reloj y progreso del compañero.
- Control del contenido del repositorio y de la APK: sin BIOS, imágenes de disco, partidas ni claves privadas. Los commits usan una dirección noreply.

## Límites conocidos

- El panel está validado para la edición de referencia; USA, PAL y otros parches no tienen validación completa.
- No se han probado todos los objetos, evoluciones ni eventos. El filtro de enemigos puede omitir encuentros cuyo script no interpreta.
- La dificultad estimada no modela todas las técnicas, resistencias ni decisiones del jugador.
- Deshacer una evolución conserva los atributos actuales y puede retener bonus anteriores; es una ayuda, no una mecánica oficial.
- El contador de generaciones satura en 99; dos renacimientos el mismo día después de esa saturación pueden compartir identidad.
- Las pruebas de memoria y cierre son cortas. Quedan sesiones prolongadas, combinaciones gráficas y proveedores de archivos reales que ignoren la cancelación.
- Los núcleos precompilados no se han sometido aquí a una auditoría independiente ni fuzzing.

## Antes de distribuir la APK

Cerrar las fuentes correspondientes y opciones de construcción de los núcleos según [CORE_PROVENANCE.md](CORE_PROVENANCE.md), y disponer de una copia de seguridad independiente y recuperable de la firma definitiva. La firma de publicación es distinta de la utilizada en las APK de desarrollo.

La revisión reduce riesgos concretos; no certifica ausencia absoluta de errores o vulnerabilidades.

Para la 0.3.3 se comprobaron la selección de pistas con capturas de memoria locales, los filtros de reclutados, regiones y requisitos, las comparaciones de versión y la selección de estados importados. Las pistas se abrieron correctamente en la Thor, confirmado por el usuario. La comprobación manual de actualizaciones recibió la release pública de GitHub sin errores.

Para la 0.3.4 se compilaron los controles y se instaló la APK local sobre la 0.3.1 en la RG DS sin desinstalar. Se verificó el diálogo en 640×480 y, mediante una comprobación aislada en Android, la persistencia, el restablecimiento, la separación por mando y las pulsaciones simultáneas de teclas y ejes. El usuario confirmó la captura de botones físicos y la confirmación completa en un popup centrado. Las capturas de RAM existentes confirmaron nuevas salidas en Ciudad File y la casa de Jijimon y un marcador pendiente en Manglares. Se compararon cuatro orientaciones de varios modelos para elegir la vista frontal. Estas comprobaciones no cubren todos los mandos ni todos los eventos del juego.

En 0.3.5 se reprodujo en la Thor el fallo de actualizaciones: HTTP 403 con cero consultas anónimas restantes. La consulta del manifiesto público funcionó en Android; se comprobaron versiones iguales y anteriores y el rechazo de enlaces de otros repositorios. La APK firmada se instaló sin desinstalar y el usuario confirmó que la comprobación manual dejó de mostrar el error. Se añadió el vídeo de presentación al README, se agruparon los ajustes gráficos y de sonido en un desplegable y se conservó la cabecera de controles en el mapa.

En 0.3.7 se añade descarga directa en la caché privada, con cancelación, límites de HTTPS y redirecciones y verificación de tamaño, hash, paquete, versión y firma. Un auxiliar privado ejecutado en la Thor aceptó la APK firmada y rechazó otra clave, una versión incorrecta, metadatos sin hash y un enlace externo. También se descargó y verificó la APK publicada desde GitHub en la Thor, y se rechazó una descarga con hash incorrecto. La instalación conserva la confirmación de Android y requiere permiso explícito del usuario.

El usuario confirmó en la Thor el flujo de descarga y permiso/instalador sin navegador. Después de la prueba se verificó por ADB la versión oficial 0.3.7, código 12, instalada sobre la variante de prueba sin desinstalar.

En 0.3.9 el usuario confirmó en la Thor los seis desplegables, las cuatro estadísticas juntas y la fecha y confirmación al cargar un estado. También confirmó la apertura de los detalles de objetos y evoluciones; durante esa prueba se detectó y completó el catálogo de efectos, incluido Digiseta. Un auxiliar privado comprobó los 128 IDs, los límites del catálogo, los efectos adversos y la selección de sprites solapados sin activación al arrastrar. Los cambios de interfaz no modifican los núcleos ni el formato de guardado.
