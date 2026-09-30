package lab.revisa.pixel;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Revisa Pixel: revisión de un Pixel 6a usado antes de comprarlo. Lee lo que el sistema
 * deja leer sin permisos especiales, guía las pruebas de hardware y arma un reporte con QR.
 * Solo lee: no cambia ajustes ni envía nada a ningún lado.
 */
public class MainActivity extends Activity implements SensorEventListener {

    private static final int REQ_MIC = 1;
    private static final int C_OK = 0xFF1D7F48, C_BAD = 0xFFB3261E, C_WARN = 0xFF8A5A00, C_MUTED = 0xFF5B6873;

    /** Resultados en orden de reporte: clave → valor. */
    private final Map<String, String> auto = new LinkedHashMap<>();
    private final Map<String, String> tests = new LinkedHashMap<>();
    private final Map<String, String> manual = new LinkedHashMap<>();
    private final Map<String, TextView> badges = new LinkedHashMap<>();
    private final List<String> alerts = new ArrayList<>();

    private FrameLayout root;
    /** Prueba a pantalla completa abierta (colores o cuadrícula); atrás la cierra. */
    private View overlay;
    private LinearLayout list;
    private TextView verdict, reportText;
    private ImageView qr;
    private EditText imei1, imei2;
    private SensorManager sensors;
    private boolean volUp, volDown, proxFar, proxNear;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver batteryRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { readBattery(i); refresh(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        for (String k : new String[]{"colores", "tactil", "bocina", "vibra", "mic", "huella", "volumen", "proximidad", "acelerometro", "carga"})
            tests.put(k, null);
        for (String k : new String[]{"oem", "imei", "frp", "org", "bat_fisica", "camaras", "botones_power"})
            manual.put(k, null);
        buildUi();
        readSystem();
        registerReceiver(batteryRx, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        Sensor acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor prox = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (acc != null) sensors.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL);
        if (prox != null) sensors.registerListener(this, prox, SensorManager.SENSOR_DELAY_NORMAL);
    }

    @Override protected void onPause() { super.onPause(); sensors.unregisterListener(this); }

    @Override protected void onDestroy() { super.onDestroy(); try { unregisterReceiver(batteryRx); } catch (Exception ignored) { } }

    // ------------------------------------------------------------------ lectura del sistema
    private void readSystem() {
        auto.put("fabricante", Build.MANUFACTURER);
        auto.put("modelo", Build.MODEL);
        auto.put("dispositivo", Build.DEVICE);
        auto.put("android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        auto.put("parche", Build.VERSION.SECURITY_PATCH);
        auto.put("build", Build.TYPE + "/" + Build.TAGS);
        if (!"bluejay".equals(Build.DEVICE)) alerts.add("no es un Pixel 6a (dispositivo " + Build.DEVICE + ")");

        // Arranque y desbloqueo. Varias propiedades pueden no ser legibles para una app normal.
        String locked = prop("ro.boot.flash.locked"), vb = prop("ro.boot.verifiedbootstate");
        String oemSup = prop("ro.oem_unlock_supported"), oemAllowed = prop("sys.oem_unlock_allowed");
        auto.put("bootloader", locked.isEmpty() ? "no legible" : ("1".equals(locked) ? "bloqueado" : "DESBLOQUEADO"));
        auto.put("arranque", vb.isEmpty() ? "no legible" : vb);
        auto.put("oem_soportado", oemSup.isEmpty() ? "no legible" : oemSup);
        auto.put("oem_permitido", oemAllowed.isEmpty() ? "no legible" : oemAllowed);
        if ("0".equals(oemSup)) alerts.add("el sistema dice que NO soporta desbloqueo OEM");
        if ("0".equals(locked) || "orange".equals(vb)) alerts.add("el bootloader YA está desbloqueado (pudo ser modificado)");

        // Root / modificaciones previas.
        List<String> root = new ArrayList<>();
        for (String p : new String[]{"/system/bin/su", "/system/xbin/su", "/sbin/su", "/data/adb/magisk", "/debug_ramdisk/su"})
            if (new File(p).exists()) root.add(p);
        for (String p : new String[]{"com.topjohnwu.magisk", "me.weishu.kernelsu"})
            if (installed(p)) root.add(p);
        if (Build.TAGS != null && Build.TAGS.contains("test-keys")) root.add("test-keys");
        auto.put("root", root.isEmpty() ? "no" : String.join(",", root));
        if (!root.isEmpty()) alerts.add("tiene indicios de root/modificación");

        // Administración de empresa (MDM).
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
            List<ComponentName> admins = dpm.getActiveAdmins();
            List<String> names = new ArrayList<>();
            if (admins != null) for (ComponentName c : admins) names.add(c.getPackageName());
            auto.put("admins", names.isEmpty() ? "ninguno" : String.join(",", names));
        } catch (Exception e) { auto.put("admins", "no legible"); }

        // Hardware.
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        auto.put("ram", String.format(Locale.US, "%.1f GB", mi.totalMem / 1e9));
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        auto.put("almacenamiento", String.format(Locale.US, "%.0f GB", fs.getTotalBytes() / 1e9));
        Display d = getDisplay();
        if (d != null) {
            Display.Mode m = d.getMode();
            auto.put("pantalla", m.getPhysicalWidth() + "x" + m.getPhysicalHeight() + " @" + Math.round(m.getRefreshRate()) + "Hz");
        }
        int back = 0, front = 0;
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) back++;
                if (f != null && f == CameraCharacteristics.LENS_FACING_FRONT) front++;
            }
            auto.put("camaras", "traseras " + back + ", frontal " + front);
        } catch (Exception e) { auto.put("camaras", "no legible"); }
        if (back == 0 || front == 0) alerts.add("el sistema no ve alguna cámara");
        List<String> miss = new ArrayList<>();
        int[] need = {Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_PROXIMITY, Sensor.TYPE_LIGHT, Sensor.TYPE_MAGNETIC_FIELD};
        String[] needName = {"acelerómetro", "giroscopio", "proximidad", "luz", "brújula"};
        for (int i = 0; i < need.length; i++) if (sensors.getDefaultSensor(need[i]) == null) miss.add(needName[i]);
        auto.put("sensores", miss.isEmpty() ? "completos" : "faltan: " + String.join(",", miss));
        if (!miss.isEmpty()) alerts.add("faltan sensores: " + String.join(",", miss));
        boolean fp = getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT);
        auto.put("lector_huella", fp ? "presente" : "NO detectado");
        if (!fp) alerts.add("no se detecta lector de huellas");
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            boolean wifi = nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean net = nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            auto.put("red", (wifi ? "wifi" : (nc == null ? "sin red" : "datos")) + (net ? " con internet" : " sin internet"));
        } catch (Exception e) { auto.put("red", "no legible"); }
    }

    private void readBattery(Intent i) {
        if (i == null) return;
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level >= 0 ? Math.round(level * 100f / scale) : -1;
        int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        int health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        float temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f;
        int cycles = i.getIntExtra("android.os.extra.CYCLE_COUNT", -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
        String h;
        switch (health) {
            case BatteryManager.BATTERY_HEALTH_GOOD: h = "buena"; break;
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: h = "SOBRECALENTADA"; break;
            case BatteryManager.BATTERY_HEALTH_DEAD: h = "MUERTA"; break;
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: h = "SOBREVOLTAJE"; break;
            case BatteryManager.BATTERY_HEALTH_COLD: h = "fría"; break;
            default: h = "desconocida";
        }
        auto.put("bateria", pct + "% " + (charging ? "cargando" : "sin cargar") + ", salud " + h
                + String.format(Locale.US, ", %.1f °C", temp) + (cycles >= 0 ? ", " + cycles + " ciclos" : ""));
        // Capacidad estimada: carga actual / nivel. Ruidosa con nivel bajo; referencia nueva ≈ 4400 mAh.
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            int uah = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
            if (uah > 0 && pct >= 20) auto.put("capacidad_est", Math.round(uah / 1000f * 100f / pct) + " mAh (nuevo ≈ 4400)");
            else auto.put("capacidad_est", "sube a 20%+ para estimar");
        } catch (Exception e) { auto.put("capacidad_est", "no legible"); }
        if (charging && tests.get("carga") == null) { tests.put("carga", "ok"); }
    }

    private static String prop(String key) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"getprop", key});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String s = r.readLine();
            p.waitFor();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private boolean installed(String pkg) {
        try { getPackageManager().getPackageInfo(pkg, 0); return true; } catch (PackageManager.NameNotFoundException e) { return false; }
    }

    // ------------------------------------------------------------------ interfaz
    private void buildUi() {
        root = new FrameLayout(this);
        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        list.setPadding(pad, dp(24), pad, dp(48));
        sv.addView(list);
        root.addView(sv);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars());
            list.setPadding(dp(16), s.top + dp(16), dp(16), s.bottom + dp(48));
            return ins;
        });
        setContentView(root);

        title("Revisa Pixel", 26);
        note("Revisión de un Pixel 6a usado. Solo lee: no cambia nada ni manda datos a ningún lado.");
        verdict = new TextView(this);
        verdict.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        verdict.setTypeface(Typeface.DEFAULT_BOLD);
        verdict.setPadding(dp(14), dp(12), dp(14), dp(12));
        list.addView(verdict, lp(dp(12)));

        section("1. Desbloqueo OEM (lo decisivo)");
        note("Ajustes → Acerca del teléfono → toca 7 veces «Número de compilación». Luego Ajustes → Sistema → Opciones de desarrollador → «Desbloqueo OEM». Con internet.");
        choice("oem", manual, new String[]{"Se deja activar", "ok"}, new String[]{"Está en gris", "gris"}, new String[]{"No aparece", "noaparece"});

        section("2. IMEI");
        note("Android no deja que las apps lean el IMEI. Marca *#06# y escríbelo; luego el de la caja o de Ajustes → Acerca del teléfono.");
        imei1 = imeiField("IMEI de *#06#");
        imei2 = imeiField("IMEI de la caja o Ajustes");
        badge("imei");

        section("3. Pruebas");
        testRow("colores", "Colores a pantalla completa", "Toca para cambiar. Busca manchas, líneas o imagen quemada.", "Probar", v -> runColors());
        testRow("tactil", "Pantalla táctil", "Pinta los 66 cuadros con el dedo.", "Probar", v -> runTouch());
        testRow("bocina", "Bocina", "Sube el volumen: suenan tres tonos.", "Probar", v -> {
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                tg.startTone(ToneGenerator.TONE_CDMA_ABBR_ALERT, 1500);
                ui.postDelayed(tg::release, 2000);
            } catch (Exception ignored) { }
            confirm("bocina", "¿Se oyó claro?");
        });
        testRow("vibra", "Vibración", "", "Probar", v -> {
            Vibrator vib = getSystemService(Vibrator.class);
            if (vib != null) vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 400, 150, 400}, -1));
            confirm("vibra", "¿Vibró?");
        });
        testRow("mic", "Micrófono", "Habla o aplaude cerca durante 3 segundos.", "Grabar", v -> runMic());
        testRow("huella", "Sensor de huellas", "Registra una huella en Ajustes → Seguridad y luego pruébala aquí.", "Probar", v -> runFingerprint());
        testRow("volumen", "Botones de volumen", "Presiona subir y bajar volumen.", null, null);
        testRow("proximidad", "Sensor de proximidad", "Tapa la parte de arriba de la pantalla con la mano y destápala.", null, null);
        testRow("acelerometro", "Acelerómetro", "Se revisa solo.", null, null);
        testRow("carga", "Carga por USB-C", "Conecta el cargador.", null, null);

        section("4. A mano");
        manualRow("frp", "Restablecido frente a ti", "Arrancó sin pedir la cuenta de Google del dueño anterior.");
        manualRow("org", "Sin administración de empresa", "No dice «Este dispositivo pertenece a…».");
        manualRow("bat_fisica", "Batería sin inflarse", "La pantalla no se levanta en los bordes y no se calienta en reposo.");
        manualRow("camaras", "Cámaras", "Toma una foto con la trasera y con la frontal en la app Cámara.");
        manualRow("botones_power", "Botón de encendido", "Apaga y prende la pantalla.");

        section("5. Lo que el teléfono dice de sí mismo");
        TextView autoView = new TextView(this);
        autoView.setTag("auto");
        autoView.setTypeface(Typeface.MONOSPACE);
        autoView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        list.addView(autoView, lp(dp(4)));

        section("6. Reporte para Claude");
        note("Escanea el código con la cámara del iPhone, copia el texto y pégalo en el chat. Si no abre, tómale foto a esta sección.");
        qr = new ImageView(this);
        list.addView(qr, new LinearLayout.LayoutParams(dp(260), dp(260)));
        reportText = new TextView(this);
        reportText.setTypeface(Typeface.MONOSPACE);
        reportText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        reportText.setTextIsSelectable(true);
        list.addView(reportText, lp(dp(8)));
        Button copy = new Button(this);
        copy.setText("Copiar reporte");
        copy.setOnClickListener(v -> {
            ClipboardManager cb = getSystemService(ClipboardManager.class);
            cb.setPrimaryClip(ClipData.newPlainText("reporte", reportText.getText()));
            Toast.makeText(this, "Reporte copiado", Toast.LENGTH_SHORT).show();
        });
        list.addView(copy, lp(dp(8)));
    }

    private void title(String t, int sp) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFF18212B);
        list.addView(v);
    }

    private void section(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(0xFF0B6A86);
        list.addView(v, lp(dp(24)));
    }

    private void note(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextColor(C_MUTED);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        list.addView(v, lp(dp(4)));
    }

    private TextView badge(String key) {
        TextView b = new TextView(this);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        badges.put(key, b);
        list.addView(b, lp(dp(6)));
        return b;
    }

    private void choice(String key, Map<String, String> into, String[]... opts) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] o : opts) {
            Button btn = new Button(this);
            btn.setText(o[0]);
            btn.setAllCaps(false);
            btn.setOnClickListener(v -> { into.put(key, o[1]); refresh(); });
            row.addView(btn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        }
        list.addView(row, lp(dp(6)));
        badge(key);
    }

    private EditText imeiField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(15)});
        e.setTypeface(Typeface.MONOSPACE);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) { refresh(); }
        });
        list.addView(e, lp(dp(6)));
        return e;
    }

    private void testRow(String key, String name, String hint, String action, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(t, lp(dp(14)));
        if (!hint.isEmpty()) note(hint);
        if (action != null) {
            Button b = new Button(this);
            b.setText(action);
            b.setAllCaps(false);
            b.setOnClickListener(l);
            list.addView(b, lp(dp(4)));
        }
        badge(key);
    }

    private void manualRow(String key, String name, String hint) {
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        list.addView(t, lp(dp(14)));
        note(hint);
        choice(key, manual, new String[]{"Bien", "ok"}, new String[]{"Falla", "falla"});
    }

    /** Muestra dos botones bajo la prueba para que la persona confirme lo que percibió. */
    private void confirm(String key, String question) {
        TextView b = badges.get(key);
        if (b == null) return;
        ViewGroup parent = (ViewGroup) b.getParent();
        if (parent.findViewWithTag("c_" + key) != null) return;
        LinearLayout row = new LinearLayout(this);
        row.setTag("c_" + key);
        Button yes = new Button(this), no = new Button(this);
        yes.setText("Sí"); no.setText("No");
        yes.setOnClickListener(v -> { tests.put(key, "ok"); refresh(); });
        no.setOnClickListener(v -> { tests.put(key, "falla"); refresh(); });
        TextView q = new TextView(this);
        q.setText(question + "  ");
        row.addView(q);
        row.addView(yes);
        row.addView(no);
        parent.addView(row, parent.indexOfChild(b), lp(0));
    }

    // ------------------------------------------------------------------ pruebas
    private void immersive(boolean on) {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        if (on) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            c.show(WindowInsets.Type.systemBars());
        }
    }

    private void runColors() {
        final int[] colors = {Color.WHITE, Color.BLACK, Color.RED, Color.GREEN, Color.BLUE, Color.GRAY};
        final int[] i = {0};
        View v = new View(this);
        v.setBackgroundColor(colors[0]);
        v.setOnClickListener(x -> {
            i[0]++;
            if (i[0] >= colors.length) {
                closeOverlay();
                confirm("colores", "¿Sin manchas ni líneas?");
            } else v.setBackgroundColor(colors[i[0]]);
        });
        openOverlay(v);
    }

    private void runTouch() {
        TouchGrid g = new TouchGrid(this, 6, 11, () -> {
            tests.put("tactil", "ok");
            refresh();
        });
        g.setOnDone(this::closeOverlay);
        openOverlay(g);
    }

    private void openOverlay(View v) {
        overlay = v;
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        immersive(true);
    }

    private void closeOverlay() {
        if (overlay == null) return;
        root.removeView(overlay);
        overlay = null;
        immersive(false);
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) closeOverlay();
        else super.onBackPressed();
    }

    private void runMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        tests.put("mic", "grabando");
        refresh();
        new Thread(() -> {
            int rate = 16000, peak = 0;
            int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            try {
                AudioRecord rec = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                short[] buf = new short[2048];
                rec.startRecording();
                long end = System.currentTimeMillis() + 3000;
                while (System.currentTimeMillis() < end) {
                    int n = rec.read(buf, 0, buf.length);
                    for (int k = 0; k < n; k++) peak = Math.max(peak, Math.abs(buf[k]));
                }
                rec.stop();
                rec.release();
            } catch (Exception e) {
                peak = -1;
            }
            final int p = peak;
            ui.post(() -> {
                tests.put("mic", p > 3000 ? "ok(pico " + p + ")" : (p < 0 ? "error" : "falla(pico " + p + ")"));
                refresh();
            });
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == REQ_MIC && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) runMic();
        else if (req == REQ_MIC) { tests.put("mic", "sin permiso"); refresh(); }
    }

    private void runFingerprint() {
        BiometricManager bm = getSystemService(BiometricManager.class);
        int can = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (can == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
            Toast.makeText(this, "Primero registra una huella en Ajustes → Seguridad", Toast.LENGTH_LONG).show();
            return;
        }
        if (can != BiometricManager.BIOMETRIC_SUCCESS) { tests.put("huella", "falla(hw " + can + ")"); refresh(); return; }
        BiometricPrompt p = new BiometricPrompt.Builder(this)
                .setTitle("Prueba de huella")
                .setSubtitle("Pon el dedo registrado en el sensor")
                .setNegativeButton("Cancelar", getMainExecutor(), (d, w) -> { })
                .build();
        p.authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
            @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { tests.put("huella", "ok"); refresh(); }
            @Override public void onAuthenticationError(int code, CharSequence msg) {
                if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    tests.put("huella", "falla(" + code + ")"); refresh();
                }
            }
        });
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_VOLUME_UP) volUp = true;
        if (code == KeyEvent.KEYCODE_VOLUME_DOWN) volDown = true;
        if (volUp && volDown && tests.get("volumen") == null) { tests.put("volumen", "ok"); refresh(); }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER && tests.get("acelerometro") == null) {
            double g = Math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]);
            if (g > 8.5 && g < 11.2) { tests.put("acelerometro", "ok"); refresh(); }
        } else if (e.sensor.getType() == Sensor.TYPE_PROXIMITY && tests.get("proximidad") == null) {
            boolean near = e.values[0] < Math.min(e.sensor.getMaximumRange(), 5f);
            if (near) proxNear = true; else proxFar = true;
            if (proxNear && proxFar) { tests.put("proximidad", "ok"); refresh(); }
        }
    }

    @Override public void onAccuracyChanged(Sensor s, int a) { }

    // ------------------------------------------------------------------ veredicto y reporte
    private static boolean luhn(String s) {
        if (!s.matches("\\d{15}")) return false;
        int sum = 0;
        for (int i = 0; i < 15; i++) {
            int d = s.charAt(14 - i) - '0';
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9; }
            sum += d;
        }
        return sum % 10 == 0;
    }

    private void refresh() {
        if (verdict == null) return;
        String a = imei1.getText().toString(), b = imei2.getText().toString();
        if (a.isEmpty() || b.isEmpty()) manual.put("imei", (a.isEmpty() && b.isEmpty()) ? null : "incompleto");
        else if (!luhn(a) || !luhn(b)) manual.put("imei", "invalido");
        else manual.put("imei", a.equals(b) ? "ok" : "nocoinciden");

        List<String> fails = new ArrayList<>(alerts);
        int done = 0, total = 0;
        for (Map<String, String> m : List.of(tests, manual)) {
            for (Map.Entry<String, String> e : m.entrySet()) {
                total++;
                String v = e.getValue();
                TextView bd = badges.get(e.getKey());
                if (v == null || v.equals("grabando") || v.equals("incompleto")) {
                    if (bd != null) { bd.setText(v == null ? "pendiente" : v); bd.setTextColor(C_MUTED); }
                    continue;
                }
                done++;
                boolean ok = v.startsWith("ok");
                if (!ok) fails.add(e.getKey() + "=" + v);
                if (bd != null) { bd.setText(ok ? "✔ " + v : "✘ " + v); bd.setTextColor(ok ? C_OK : C_BAD); }
            }
        }

        String oem = manual.get("oem");
        if (oem != null && !oem.equals("ok")) {
            setVerdict("NO LO COMPRES: el desbloqueo OEM no se puede activar.", C_BAD, 0xFFFBE7E5);
        } else if (!fails.isEmpty()) {
            setVerdict("Hay fallas: " + String.join("; ", fails), C_BAD, 0xFFFBE7E5);
        } else if (done < total) {
            setVerdict("Revisión incompleta: " + done + " de " + total + ".", C_WARN, 0xFFFBF0D9);
        } else {
            setVerdict("Todo en orden. Manda el reporte a Claude antes de pagar.", C_OK, 0xFFE3F3EA);
        }

        StringBuilder sb = new StringBuilder("REVISA PIXEL v1\n");
        for (Map.Entry<String, String> e : auto.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        sb.append("imei_tel=").append(a.isEmpty() ? "?" : a).append(" imei_caja=").append(b.isEmpty() ? "?" : b).append('\n');
        StringBuilder t = new StringBuilder();
        for (Map.Entry<String, String> e : tests.entrySet()) t.append(e.getKey()).append('=').append(e.getValue() == null ? "?" : e.getValue()).append(' ');
        sb.append(t.toString().trim()).append('\n');
        StringBuilder mm = new StringBuilder();
        for (Map.Entry<String, String> e : manual.entrySet()) mm.append(e.getKey()).append('=').append(e.getValue() == null ? "?" : e.getValue()).append(' ');
        sb.append(mm.toString().trim()).append('\n');
        sb.append("fallas=").append(fails.isEmpty() ? "ninguna" : String.join("; ", fails)).append(" avance=").append(done).append('/').append(total);
        String report = sb.toString();

        TextView autoView = list.findViewWithTag("auto");
        if (autoView != null) {
            StringBuilder s = new StringBuilder();
            for (Map.Entry<String, String> e : auto.entrySet()) s.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            autoView.setText(s.toString().trim());
        }
        if (!report.equals(reportText.getText().toString())) {
            reportText.setText(report);
            qr.setImageBitmap(qrBitmap(report, dp(260)));
        }
    }

    private void setVerdict(String text, int fg, int bg) {
        verdict.setText(text);
        verdict.setTextColor(fg);
        verdict.setBackgroundColor(bg);
    }

    private static Bitmap qrBitmap(String text, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int[] px = new int[size * size];
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    px[y * size + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    private LinearLayout.LayoutParams lp(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
