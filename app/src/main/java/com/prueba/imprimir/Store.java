package com.prueba.imprimir;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Contador, registro persistente y búsqueda de la impresora.
 *
 * Todo vive en almacenamiento protegido por dispositivo: la actividad y el receptor son
 * directBootAware y pueden correr antes de que el usuario se desbloquee al arrancar.
 */
final class Store {

    static final int PRINTER_VID = 1305; // 0x0519
    static final int PRINTER_PID = 8211; // 0x2013

    private static final String PREFS = "turnos";
    private static final String KEY_NEXT = "siguiente";
    private static final String KEY_LOG = "registro";
    private static final int MAX_LOG_CHARS = 12000;

    private Store() {}

    static SharedPreferences prefs(Context ctx) {
        return ctx.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int next(Context ctx) {
        return prefs(ctx).getInt(KEY_NEXT, 1);
    }

    static void setNext(Context ctx, int value) {
        prefs(ctx).edit().putInt(KEY_NEXT, value).apply();
    }

    /** Agrega una línea al registro que sobrevive a los reinicios. Devuelve la línea con fecha. */
    static synchronized String log(Context ctx, String msg) {
        String when = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(new Date());
        String line = when + "  " + msg + "\n";
        SharedPreferences p = prefs(ctx);
        String all = p.getString(KEY_LOG, "") + line;
        if (all.length() > MAX_LOG_CHARS) {
            all = all.substring(all.length() - MAX_LOG_CHARS);
            int nl = all.indexOf('\n');
            if (nl >= 0) all = all.substring(nl + 1);
        }
        p.edit().putString(KEY_LOG, all).commit();
        return line;
    }

    static String readLog(Context ctx) {
        return prefs(ctx).getString(KEY_LOG, "");
    }

    static void clearLog(Context ctx) {
        prefs(ctx).edit().remove(KEY_LOG).commit();
    }

    /** La impresora del quiosco por VID/PID; si no está, cualquier interfaz de clase impresora con bulk OUT. */
    static UsbDevice findPrinter(UsbManager usb) {
        UsbDevice byClass = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (d.getVendorId() == PRINTER_VID && d.getProductId() == PRINTER_PID) return d;
            for (int i = 0; i < d.getInterfaceCount() && byClass == null; i++) {
                UsbInterface intf = d.getInterface(i);
                if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER && hasBulkOut(intf)) {
                    byClass = d;
                }
            }
        }
        return byClass;
    }

    static boolean hasBulkOut(UsbInterface intf) {
        for (int e = 0; e < intf.getEndpointCount(); e++) {
            if (intf.getEndpoint(e).getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && intf.getEndpoint(e).getDirection() == UsbConstants.USB_DIR_OUT) {
                return true;
            }
        }
        return false;
    }

    static String describe(UsbDevice d) {
        if (d == null) return "(desconocido)";
        String name = d.getProductName();
        if (name == null || name.isEmpty()) name = d.getDeviceName();
        return name + String.format(Locale.US, " [VID %04X / PID %04X]", d.getVendorId(), d.getProductId());
    }
}
