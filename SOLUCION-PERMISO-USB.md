# Permiso USB de la impresora que vuelve a salir tras reiniciar el quiosco

> **Estado (23/11/2026):** la versión 1.1 de la app de prueba ya tiene la solución, pero **todavía no se ha probado en el quiosco**. La causa descrita abajo es la más probable según cómo funciona Android y queda confirmada o descartada con el registro de la prueba (sección 5).

## 1. El problema

En un quiosco con Android 15 y una impresora térmica USB, al imprimir sale el cuadro de permiso con la casilla **«Always open…»**. Si lo aceptas, imprime bien todas las veces. Pero al apagar y prender el quiosco, el cuadro vuelve a salir.

**Equipo detectado en el quiosco:**

| Dispositivo | VID / PID (hex) | VID / PID (decimal) |
|---|---|---|
| IP1000 Printer USB001 | 0519 / 2013 | 1305 / 8211 |
| Adaptador de red USB (Realtek) | 0BDA / 8153 | 3034 / 33107 |
| Cámara Full HD | 1BCF / 2281 | 7119 / 8833 |
| Pantalla táctil ILITEK | 222A / 0001 | 8746 / 1 |

La impresora usa la interfaz 0, de clase 7 (impresora), con salida *bulk OUT*.

## 2. Por qué pasa

Hay tres hechos de Android que, juntos, explican el problema:

1. **El permiso USB no se guarda en disco.** Lo que entrega `UsbManager.requestPermission()` vive en memoria y se pierde al apagar.
2. **«Always open…» no guarda el permiso.** Guarda tu app como *la app por defecto* para ese modelo de impresora. Después, cada vez que el sistema detecta la impresora, abre esa app y **ahí** le da el permiso. Para eso la app debe declarar en su manifest que maneja esa impresora (`USB_DEVICE_ATTACHED` + `device_filter.xml`).
3. **Al prender, la impresora se detecta antes del desbloqueo del usuario.** En ese momento Android solo ve las apps marcadas como `directBootAware` (de «arranque directo»). Si la app no lo es, el sistema no la encuentra, no aplica «Always open» y no da el permiso. Cuando luego abres la app, el permiso falta y el cuadro vuelve a salir.

El punto 3 es la causa más probable de que fallara incluso con el manifest bien hecho (versión 1.0).

Además, la versión 1.0 de la app de prueba tenía un error propio. Su filtro aceptaba cualquier dispositivo de clase 255 («propio del fabricante»), y el **adaptador de red USB también es de esa clase**. Por eso es posible que el cuadro que salió al prender fuera por la red y no por la impresora.

## 3. Qué se hizo (versión 1.1)

### 3.1 Filtro solo para la impresora exacta

`app/src/main/res/xml/device_filter.xml` (valores en **decimal**):

```xml
<resources>
    <usb-device vendor-id="1305" product-id="8211" />
</resources>
```

Se quitaron los filtros por clase (`class="7"` y `class="255"`). Con el de clase 255, Android ofrecía la app también para el adaptador de red.

### 3.2 La actividad maneja la conexión y es de arranque directo

`app/src/main/AndroidManifest.xml`:

```xml
<activity
    android:name=".MainActivity"
    android:directBootAware="true"
    android:exported="true"
    android:launchMode="singleTask">

    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>

    <intent-filter>
        <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
    </intent-filter>
    <meta-data
        android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
        android:resource="@xml/device_filter" />
</activity>
```

- `USB_DEVICE_ATTACHED` + `device_filter`: con esto, «Always open» funciona y el sistema le da el permiso a la app cada vez que detecta la impresora.
- `directBootAware="true"`: así el sistema ve la app al arrancar, antes del desbloqueo.
- `singleTask`: si la app ya está abierta, el sistema reusa esa ventana en vez de abrir otra. El aviso llega por `onNewIntent`.

### 3.3 Datos en almacenamiento disponible antes del desbloqueo

Una app de arranque directo puede correr antes del desbloqueo. En ese momento el almacenamiento normal todavía no está disponible, y abrirlo hace fallar la app. Por eso el contador y el registro se guardan en el almacenamiento protegido por dispositivo (`Store.java`):

```java
ctx.createDeviceProtectedStorageContext().getSharedPreferences("turnos", MODE_PRIVATE);
```

### 3.4 Pedir permiso solo cuando falta

```java
if (usbManager.hasPermission(device)) {
    print(device);
} else {
    usbManager.requestPermission(device, pendingIntent);
}
```

El `PendingIntent` es explícito (`setPackage`) y mutable (`FLAG_MUTABLE`). Android 12 y posteriores lo exigen, porque el sistema le agrega el resultado del permiso.

### 3.5 Registro de diagnóstico que sobrevive a los reinicios

- `BootReceiver.java` escucha `LOCKED_BOOT_COMPLETED` y `BOOT_COMPLETED`. Al arrancar anota si la impresora ya tenía permiso en cada etapa.
- La app anota si se abrió a mano o si la abrió el sistema al detectar la impresora, y con qué permiso.

## 4. Archivos del proyecto

| Archivo | Qué hace |
|---|---|
| `AndroidManifest.xml` | Filtro USB, arranque directo, receptor de arranque |
| `res/xml/device_filter.xml` | VID/PID de la impresora |
| `MainActivity.java` | Pantalla, permiso e impresión ESC/POS |
| `Store.java` | Contador, registro, búsqueda de la impresora |
| `BootReceiver.java` | Anota el permiso al arrancar |

APK listo para instalar: `imprimir-turnos.apk`, en la raíz de esta carpeta.

## 5. Cómo probarlo

1. **Desinstala la versión anterior** de la app de prueba. Así se borra la preferencia vieja.
2. Instala `imprimir-turnos.apk`.
3. Abre la app, toca **IMPRIMIR**, marca **«Always open…»** y acepta.
4. Apaga y prende el quiosco. **No toques ningún cuadro** y abre la app.
5. Revisa el registro:

| Registro tras reiniciar | Significa |
|---|---|
| `ARRANQUE (bloqueado) … permiso SÍ` y no salió cuadro | Solucionado |
| `el SISTEMA abrió la app … permiso SÍ` | Solucionado |
| `ARRANQUE … permiso NO` | La ROM no aplica «Always open» al arrancar (sección 7) |

**Ojo:** Android guarda **una sola** app por defecto para la impresora, la última en la que marcaste «Always open». Si después lo marcas en otra app que también maneja la impresora, esa le quita el lugar a la anterior.

## 6. Cómo llevarlo a la app real

Si la prueba sale bien, aplica en el APK de producción lo mismo de la sección 3:

1. Agrega `device_filter.xml` con `vendor-id="1305" product-id="8211"`.
2. En la actividad principal agrega el `intent-filter` de `USB_DEVICE_ATTACHED`, el `meta-data`, `directBootAware="true"` y `launchMode="singleTask"`.
3. Si la app guarda datos (preferencias, base de datos, archivos), usa `createDeviceProtectedStorageContext()`. Otra opción: que la actividad no lea nada hasta que el usuario esté desbloqueado (`UserManager.isUserUnlocked()`).
4. Antes de pedir permiso, revisa `hasPermission()`. Si la app usa el SDK de impresión del fabricante, haz esa revisión antes de llamarlo, porque muchos SDK piden permiso siempre.
5. Desinstala, instala, marca «Always open» una vez y reinicia para probar.

## 7. Si aun así sale el cuadro

Si el registro dice `permiso NO` al arrancar, la ROM del quiosco no aplica la app por defecto en el arranque. Una app normal no puede darse el permiso sola. Los caminos que quedan pasan por el fabricante del quiosco:

| Opción | Qué hace | Quién |
|---|---|---|
| `config_disableUsbPermissionDialogs = true` | Quita los cuadros de permiso USB de todo el equipo | Fabricante (ROM) |
| App de sistema con `MANAGE_USB` | La app se da el permiso al iniciar con `UsbManager.grantPermission(device)` | Fabricante (firma de plataforma) o root |

Para diagnosticar por cable con `adb`:

```bash
adb shell dumpsys usb | grep -iE -A3 "1305|0519"
adb logcat -b all | grep -iE "UsbHost|UsbProfileGroup|UsbPermission"
```
