package com.prueba.imprimir;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * App de prueba: imprime números secuenciales en la impresora térmica USB (ESC/POS)
 * y deja un registro, que sobrevive a los reinicios, de cómo se obtuvo el permiso USB.
 */
public class MainActivity extends Activity {

    private static final String ACTION_USB_PERMISSION = "com.prueba.imprimir.USB_PERMISSION";

    private UsbManager usbManager;
    private final ExecutorService printer = Executors.newSingleThreadExecutor();

    private TextView txtNumero;
    private TextView txtPermiso;
    private TextView txtLog;
    private ScrollView scrollLog;
    private Button btnImprimir;

    /** Si el usuario tocó «Imprimir» y estamos esperando la respuesta del cuadro de permiso. */
    private boolean printPending = false;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            UsbDevice device = getDeviceExtra(intent);
            if (ACTION_USB_PERMISSION.equals(action)) {
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                log(granted ? "Permiso concedido desde el cuadro." : "Permiso NEGADO desde el cuadro.");
                refreshStatus();
                boolean shouldPrint = granted && printPending && device != null;
                printPending = false;
                if (shouldPrint) print(device);
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                log("Se desconectó: " + Store.describe(device));
                refreshStatus();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        txtNumero = findViewById(R.id.txtNumero);
        txtPermiso = findViewById(R.id.txtPermiso);
        txtLog = findViewById(R.id.txtLog);
        scrollLog = findViewById(R.id.scrollLog);
        btnImprimir = findViewById(R.id.btnImprimir);

        btnImprimir.setOnClickListener(v -> onPrintClicked());
        findViewById(R.id.btnReiniciar).setOnClickListener(v -> {
            Store.setNext(this, 1);
            showNextNumber();
            log("Contador reiniciado a 1.");
        });
        findViewById(R.id.btnDispositivos).setOnClickListener(v -> listDevices());
        findViewById(R.id.btnBorrarLog).setOnClickListener(v -> {
            Store.clearLog(this);
            txtLog.setText("");
        });

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }

        txtLog.setText(Store.readLog(this));
        showNextNumber();
        handleLaunchIntent(getIntent(), "Al abrir");
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleLaunchIntent(intent, "Con la app abierta");
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(usbReceiver);
        printer.shutdown();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- permiso

    private void handleLaunchIntent(Intent intent, String when) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = getDeviceExtra(intent);
            boolean has = device != null && usbManager.hasPermission(device);
            log(when + ": el SISTEMA abrió la app al detectar " + Store.describe(device)
                    + " → permiso " + (has ? "SÍ (sin preguntar)" : "NO"));
        } else {
            UsbDevice p = Store.findPrinter(usbManager);
            log(when + ": app abierta a mano (ícono). Permiso de la impresora: "
                    + (p == null ? "no detectada" : usbManager.hasPermission(p) ? "SÍ" : "NO"));
        }
    }

    private void onPrintClicked() {
        UsbDevice device = Store.findPrinter(usbManager);
        if (device == null) {
            log("No encontré la impresora. Toca «Dispositivos».");
            refreshStatus();
            return;
        }
        if (usbManager.hasPermission(device)) {
            print(device);
            return;
        }
        // Solo se pide permiso cuando de verdad falta.
        printPending = true;
        log("Sin permiso: pidiendo permiso para " + Store.describe(device));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // El sistema agrega extras al intent, por eso debe ser mutable (y explícito).
            flags |= PendingIntent.FLAG_MUTABLE;
        }
        Intent permissionIntent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, permissionIntent, flags);
        usbManager.requestPermission(device, pi);
    }

    // -------------------------------------------------------------- impresión

    private void print(UsbDevice device) {
        final int number = Store.next(this);
        btnImprimir.setEnabled(false);
        printer.execute(() -> {
            String error = sendTicket(device, number);
            runOnUiThread(() -> {
                btnImprimir.setEnabled(true);
                if (error == null) {
                    Store.setNext(this, number + 1);
                    showNextNumber();
                    log("Impreso el turno " + format(number) + ".");
                } else {
                    log("ERROR al imprimir el " + format(number) + ": " + error);
                }
                refreshStatus();
            });
        });
    }

    /** Devuelve null si imprimió bien, o el texto del error. */
    private String sendTicket(UsbDevice device, int number) {
        UsbInterface intf = null;
        UsbEndpoint out = null;
        for (int i = 0; i < device.getInterfaceCount() && out == null; i++) {
            UsbInterface candidate = device.getInterface(i);
            for (int e = 0; e < candidate.getEndpointCount(); e++) {
                UsbEndpoint ep = candidate.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                        && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    intf = candidate;
                    out = ep;
                    break;
                }
            }
        }
        if (out == null) return "el dispositivo no tiene endpoint bulk OUT";

        UsbDeviceConnection conn = usbManager.openDevice(device);
        if (conn == null) return "no se pudo abrir el dispositivo (¿permiso?)";
        try {
            if (!conn.claimInterface(intf, true)) return "no se pudo reclamar la interfaz";
            byte[] data = buildTicket(number);
            int chunk = 4096;
            for (int off = 0; off < data.length; off += chunk) {
                int len = Math.min(chunk, data.length - off);
                int sent = conn.bulkTransfer(out, data, off, len, 5000);
                if (sent < 0) return "bulkTransfer falló (¿impresora apagada o sin papel?)";
            }
            conn.releaseInterface(intf);
            return null;
        } finally {
            conn.close();
        }
    }

    /** Ticket ESC/POS: encabezado, número grande, fecha, avance y corte. */
    private byte[] buildTicket(int number) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        String fecha = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(new Date());

        write(b, 0x1B, 0x40);             // ESC @  inicializar
        write(b, 0x1B, 0x61, 0x01);       // ESC a 1  centrar
        write(b, 0x1B, 0x45, 0x01);       // ESC E 1  negrita
        text(b, "PRUEBA DE IMPRESION\n");
        write(b, 0x1B, 0x45, 0x00);
        text(b, "TURNO\n");
        write(b, 0x1D, 0x21, 0x33);       // GS ! 0x33  4x ancho y alto
        text(b, format(number) + "\n");
        write(b, 0x1D, 0x21, 0x00);       // tamaño normal
        text(b, fecha + "\n");
        write(b, 0x1B, 0x64, 0x04);       // ESC d 4  avanzar 4 líneas
        write(b, 0x1D, 0x56, 0x42, 0x00); // GS V 66 0  corte parcial (se ignora si no hay cuchilla)
        return b.toByteArray();
    }

    // ------------------------------------------------------------ dispositivos

    private void listDevices() {
        if (usbManager.getDeviceList().isEmpty()) {
            log("No hay dispositivos USB conectados.");
            return;
        }
        StringBuilder sb = new StringBuilder("Dispositivos USB:");
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            sb.append("\n• ").append(Store.describe(d))
              .append("\n  vendor-id=\"").append(d.getVendorId())
              .append("\" product-id=\"").append(d.getProductId()).append("\" (decimal)")
              .append("\n  clase del equipo=").append(d.getDeviceClass())
              .append(", permiso=").append(usbManager.hasPermission(d) ? "sí" : "no");
            for (int i = 0; i < d.getInterfaceCount(); i++) {
                UsbInterface intf = d.getInterface(i);
                sb.append("\n  interfaz ").append(i).append(": clase=").append(intf.getInterfaceClass())
                  .append(Store.hasBulkOut(intf) ? " (bulk OUT)" : "");
            }
        }
        log(sb.toString());
    }

    // ----------------------------------------------------------------- UI/util

    private void refreshStatus() {
        UsbDevice d = Store.findPrinter(usbManager);
        if (d == null) {
            txtPermiso.setText("Impresora: no detectada");
            txtPermiso.setBackgroundColor(0xFFFDECEA);
        } else if (usbManager.hasPermission(d)) {
            txtPermiso.setText("Impresora: " + Store.describe(d) + "\nPermiso USB: CONCEDIDO");
            txtPermiso.setBackgroundColor(0xFFE6F4EA);
        } else {
            txtPermiso.setText("Impresora: " + Store.describe(d) + "\nPermiso USB: falta (se pedirá al imprimir)");
            txtPermiso.setBackgroundColor(0xFFFFF4E5);
        }
    }

    private void showNextNumber() {
        txtNumero.setText(format(Store.next(this)));
    }

    private void log(String msg) {
        txtLog.append(Store.log(this, msg));
        scrollLog.post(() -> scrollLog.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static String format(int n) {
        return String.format(Locale.US, "%03d", n);
    }

    @SuppressWarnings("deprecation")
    private static UsbDevice getDeviceExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }

    private static void write(ByteArrayOutputStream b, int... bytes) {
        for (int x : bytes) b.write(x);
    }

    private static void text(ByteArrayOutputStream b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        b.write(bytes, 0, bytes.length);
    }
}
