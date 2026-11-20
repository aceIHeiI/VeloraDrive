## Velora Drive v1.0.3

### Nuevo
- **Velora Drive ya está en inglés.** Sigue al idioma del sistema, y en **Ajustes › Pantalla › Idioma** puedes elegir **Automático**, **Español** o **English** (se aplica al instante).
- **Código del celular de 4 a 6 dígitos:** los códigos nuevos de OfflineTune Car son de 6 dígitos; los de 4 que ya tenías siguen funcionando. Si el celular rechaza el código, Velora lo avisa y solo reintenta cada 70 segundos, para no bloquearse sola (el celular bloquea tras 5 intentos fallidos).
- **La burbuja de música se oculta mientras hay un video** en pantalla completa o en la ventana mini, y vuelve al cerrarlo. Antes quedaba encima del video.
- **La ventana mini del video trae anterior, pausa y siguiente.**
- **El volumen del video se iguala con el de la música** (ya no suena mucho más fuerte).
- Los botones de la burbuja no hacen nada mientras el enlace está en «Reconectando».

### Corregido
- Al arrancar un video que viene del celular, Velora ya no le manda «pausa» (podía pausar el video recién mandado).
- Un `video_play` enviado justo después de otro ya no se pierde.
- El escaneo de toda la red del estéreo se repite como máximo una vez por minuto.
- El código del celular ya no viaja en las copias de seguridad de Android.

### Instalación
Descarga `VeloraDrive-1.0.3.apk`, instálalo en el estéreo y actualiza también **OfflineTune Car a 2.4.5**.

🧪 Probado en emuladores (celular y estéreo Android). Si lo pruebas en tu coche y algo falla, cuéntame en un [Issue](https://github.com/aceIHeiI/VeloraDrive/issues).
