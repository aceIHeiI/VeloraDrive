# Velora Drive

**Velora Drive** es un launcher (pantalla de inicio) para estéreos Android de coche, pensado para pantallas de 7", 9" y 10" (1024×600). Junta en una sola pantalla lo que usas manejando: lo que suena, tus apps, la radio, el video, los medidores del coche y el audio.

<p align="center">
  <img src="docs/01-principal.png" width="80%" alt="Pantalla principal de Velora Drive">
</p>

## ✨ Qué hace

- 🏠 **Pantalla principal** con la música que suena, hora, atajos a Apps, Video, Radio FM y OBD2.
- 🎵 **Reproductor** con carátula, onda animada y controles grandes. Funciona con la música que suene en el estéreo.
- 📲 **Conexión con [OfflineTune Car](https://github.com/aceIHeiI/OfflineTune/releases/latest)** por el hotspot del celular: el estéreo no necesita internet propio, el celular le manda la canción, la portada y el video.
- 🎬 **Video** que le manda OfflineTune Car, con biblioteca, calidad automática o manual y reproductor propio.
- 🚗 **OBD2**: medidores en vivo con un escáner ELM327 por Bluetooth, códigos de falla y un modo demo para probar sin escáner.
- 🧭 **Viaje**: kilómetros, tiempo y velocidad promedio del trayecto.
- 🎚️ **Presets de audio** y **Bass Boost** que se enciende y apaga aparte.
- 🧩 **Modo dividido**: Maps o Waze a pantalla completa con un mini reproductor encima.
- 🌙 **Protector de pantalla** con reloj grande.
- 🎨 **Colores y botones a tu gusto**: elige qué app abre cada botón y el emblema de tu coche (formas genéricas con tu marca en texto).

## 📱 Capturas

<p align="center">
  <img src="docs/02-ajustes-celular.png" width="48%" alt="Ajustes: conexión con el celular">
  <img src="docs/03-ajustes-botones.png" width="48%" alt="Ajustes: botones y apps">
</p>

## ⬇️ Instalación

1. Ve a **[Releases](https://github.com/aceIHeiI/VeloraDrive/releases/latest)** y descarga el `.apk`.
2. Instálalo en tu estéreo (puede pedir permitir apps de fuentes desconocidas).
3. Pulsa el botón de inicio y elige **Velora Drive** como launcher.
4. Para usar la música y el video del celular: instala **OfflineTune Car** en el celular, activa su hotspot, abre OfflineTune Car y escribe en Velora, en **Ajustes › Celular**, el código de 4 dígitos que muestra.

Para actualizar, instala la versión nueva encima.

## 🔐 Permisos y para qué sirven

Velora Drive no tiene cuentas ni anuncios y **no envía datos a ningún servidor**. Solo se comunica con tu celular por la red local.

| Permiso | Para qué |
|---|---|
| Mostrar sobre otras apps | El mini reproductor del modo dividido. |
| Acceso a notificaciones | Leer qué canción suena en el estéreo para mostrarla y controlarla. |
| Ubicación (y servicio en primer plano) | Velocidad y kilómetros del viaje. |
| Micrófono / audio | Solo analiza el volumen de la música para animar la onda; no graba. |
| Bluetooth | Conectar el escáner OBD2. |
| Wi-Fi y red local | Hablar con OfflineTune Car en el celular. |

## ⚠️ Importante

- **No mires video ni uses el estéreo manejando.** La función de video es para cuando el coche está detenido. Usa Velora con responsabilidad y respeta las leyes de tu país.
- Los medidores OBD2 son informativos: no sustituyen el tablero de tu coche ni un diagnóstico mecánico.
- Probado en estéreos Android chinos de 1024×600. Si en el tuyo algo falla, ve a **Ajustes › Diagnóstico**, copia el resultado y abre un Issue.

## 💬 ¿Errores o ideas?

Abre un **[Issue](https://github.com/aceIHeiI/VeloraDrive/issues)**. ¡Leo todos!

## ☕ Apoya mis apps

Velora Drive y OfflineTune los hago yo solo en mi tiempo libre. Si te sirven, puedes apoyar en **[Ko-fi](https://ko-fi.com/iheii)**. 💜

## 📄 Licencia

[MIT](LICENSE) · [Política de privacidad](https://aceiheii.github.io/VeloraDrive/privacy-policy.html). Marcas y logotipos de terceros (Google Maps, Waze, etc.) pertenecen a sus dueños y se mencionan solo para indicar con qué apps funciona.
