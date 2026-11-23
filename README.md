# apk-imprimir

App Android de prueba para un quiosco con Android 15 e impresora térmica USB (ESC/POS).
Imprime turnos con números secuenciales y lleva un registro del permiso USB, para comprobar
que el cuadro de permiso no vuelve a salir después de reiniciar el quiosco.

- Qué se hizo y por qué: [SOLUCION-PERMISO-USB.md](SOLUCION-PERMISO-USB.md)

## Compilar

Requiere el Android SDK (API 35) y JDK 17 o superior (sirve el que trae Android Studio).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```
