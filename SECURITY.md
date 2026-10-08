# Seguridad y datos locales

La aplicación funciona sin permiso de Internet y no transmite BIOS, juego ni guardados. Los archivos importados permanecen en el almacenamiento privado de Android; la tarjeta solo se exporta al destino elegido por el usuario. La copia de seguridad automática está desactivada.

El selector de archivos limita el tamaño importado y no confía en nombres del disco como rutas de escritura. Los lectores comprueban límites de memoria y archivos. La APK local/release desactiva la depuración y sus comandos de prueba; la APK debug es exclusivamente para desarrollo.

No publicar BIOS, imágenes de disco, estados, tarjetas, volcados de memoria, capturas de pruebas, claves de firma ni contraseñas. .gitignore y tools/check_publish.py ayudan a comprobar los archivos preparados. Ninguna revisión estática garantiza que un núcleo nativo no tenga vulnerabilidades; utilizar archivos propios y versiones verificadas.

Para un informe de error, compartir pasos de reproducción y versión de la app sin adjuntar BIOS, juego, claves ni datos privados. Los fallos funcionales pueden comunicarse en las Issues del repositorio; no publicar allí detalles de una vulnerabilidad explotable.
