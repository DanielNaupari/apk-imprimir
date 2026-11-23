package com.prueba.imprimir;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

/** Diagnóstico: anota en el registro si la impresora ya tenía permiso en cada etapa del arranque. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String stage = Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())
                ? "ARRANQUE (bloqueado)"
                : "ARRANQUE (desbloqueado)";
        UsbManager usb = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbDevice printer = Store.findPrinter(usb);
        if (printer == null) {
            Store.log(context, "──── " + stage + ": impresora NO detectada");
        } else {
            Store.log(context, "──── " + stage + ": " + Store.describe(printer)
                    + " → permiso " + (usb.hasPermission(printer) ? "SÍ" : "NO"));
        }
    }
}
