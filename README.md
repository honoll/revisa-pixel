# Revisa Pixel

App Android para revisar un **Google Pixel 6a usado antes de comprarlo**. Solo lee: no cambia
ajustes ni envía datos a ningún lado.

**Descarga (abrir en el Pixel):**
https://github.com/honoll/revisa-pixel/releases/latest/download/RevisaPixel.apk

## Qué revisa

- **Sola:** modelo y código de dispositivo (`bluejay`), Android y parche, tipo de compilación,
  indicios de root, administradores de dispositivo (MDM), batería (salud, temperatura, ciclos,
  capacidad estimada, carga), RAM, almacenamiento, pantalla, cámaras, sensores, lector de
  huellas, red. Pruebas automáticas de micrófono, huella, botones de volumen, proximidad y
  acelerómetro.
- **Guiada:** colores a pantalla completa, cuadrícula táctil, bocina, vibración.
- **A mano:** interruptor de Desbloqueo OEM (Android no deja leerlo), IMEI (validado con Luhn y
  comparado con el de la caja), reset sin cuenta anterior, batería sin inflarse, cámaras,
  botón de encendido.

Al final genera un reporte en texto y QR.

## Compilar

```bash
./gradlew assembleDebug
```

Requiere JDK 17 (el de Android Studio sirve). minSdk 31, targetSdk 35.
