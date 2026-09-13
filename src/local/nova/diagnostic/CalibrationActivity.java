package local.nova.diagnostic;

import android.app.Activity;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Installs native QDCM profiles, loaded by the display system at boot. */
public final class CalibrationActivity extends Activity {
    private static final java.util.concurrent.ExecutorService EXECUTOR = java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean BUSY = new java.util.concurrent.atomic.AtomicBoolean();
    private TextView status;
    private TextView note;
    private Button srgbButton;
    private Button gammaButton;
    private Button restoreButton;
    private Button rebootButton;
    private IBinder service;
    private boolean srgbAllowed;
    private boolean gammaAllowed;
    private volatile boolean firmwareCompatible;
    private volatile boolean statusVerified;
    private volatile boolean rebootNeeded;
    private volatile boolean actionRunning;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        buildUi();
        configureFromManifest();
        setButtonsEnabled(false);
        EXECUTOR.execute(this::refreshNativeStatus);
    }

    private int dp(float n) { return AeroTheme.dp(this, n); }

    private TextView text(String value, float size, int color) {
        TextView v = new TextView(this);
        v.setText(value); v.setTextSize(size); v.setTextColor(color);
        v.setFontFeatureSettings("kern");
        return v;
    }

    private LinearLayout glassColumn() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(18), dp(16), dp(18), dp(16));
        v.setBackground(AeroTheme.glass(this));
        v.setElevation(dp(5));
        return v;
    }

    private android.widget.ScrollView scrollPanel(LinearLayout column) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.addView(column, new android.widget.ScrollView.LayoutParams(-1, -2));
        return scroll;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(15), dp(20), dp(12));
        root.setBackground(new AeroTheme.Sky());

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(getString(R.string.app_name), 22, 0xffffffff);
        title.setMaxLines(1);
        title.setAutoSizeTextTypeUniformWithConfiguration(14, 22, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        title.setShadowLayer(dp(2), 0, dp(1), 0x55304f79);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView badge = text(getString(R.string.system_profile), 10, AeroTheme.INK);
        badge.setLetterSpacing(.10f);
        badge.setPadding(dp(10), dp(7), dp(10), dp(7));
        badge.setBackground(AeroTheme.glass(this));
        header.addView(badge);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, dp(42));
        hp.bottomMargin = dp(14);
        root.addView(header, hp);

        LinearLayout panels = new LinearLayout(this);
        panels.setGravity(Gravity.TOP);
        LinearLayout left = glassColumn();
        left.setGravity(Gravity.CENTER_HORIZONTAL);
        android.widget.ImageView orb = new android.widget.ImageView(this);
        orb.setImageDrawable(new AeroTheme.Orb());
        orb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        left.addView(orb, new LinearLayout.LayoutParams(dp(115), dp(115)));
        TextView promise = text(getString(R.string.calibration_tools), 22, AeroTheme.INK);
        promise.setGravity(Gravity.CENTER);
        promise.setTypeface(android.graphics.Typeface.create("sans-serif-light", 0));
        left.addView(promise, new LinearLayout.LayoutParams(-1, -2));
        TextView specs = text("sRGB    D65    ~225 nit", 14, 0xff107b82);
        specs.setGravity(Gravity.CENTER);
        specs.setPadding(0, dp(16), 0, dp(12));
        specs.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        left.addView(specs, new LinearLayout.LayoutParams(-1, -2));
        note = text(getString(R.string.reference_note), 12, AeroTheme.MUTED);
        note.setGravity(Gravity.CENTER);
        note.setLineSpacing(dp(2), 1);
        left.addView(note, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, .40f);
        lp.rightMargin = dp(14);
        panels.addView(scrollPanel(left), lp);

        LinearLayout right = glassColumn();
        TextView choices = text(getString(R.string.choose_curve), 20, AeroTheme.INK);
        choices.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        right.addView(choices);
        TextView hint = text(getString(R.string.profile_hint), 12, AeroTheme.MUTED);
        hint.setPadding(0, dp(3), 0, dp(13));
        right.addView(hint);
        gammaButton = addButton(right, "Gamma 2.2", () -> apply("gamma22"));
        gammaButton.setBackground(AeroTheme.buttonStates(this, true, false));
        srgbButton = addButton(right, "sRGB", () -> apply("srgb"));
        restoreButton = addButton(right, getString(R.string.restore_original), this::restore);
        restoreButton.setBackground(AeroTheme.buttonStates(this, false, true));
        restoreButton.setTextColor(neutralButtonColors());
        rebootButton = addButton(right, getString(R.string.reboot_device), this::rebootDevice);
        rebootButton.setBackground(AeroTheme.buttonStates(this, false, true));
        rebootButton.setTextColor(neutralButtonColors());
        rebootButton.setEnabled(false);
        status = text(getString(R.string.reading_profile), 12, AeroTheme.INK);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(10), dp(7), dp(10), dp(7));
        status.setBackground(AeroTheme.glass(this));
        status.setMinHeight(dp(54));
        status.setOnLongClickListener(view -> { showOperationDetails(); return true; });
        status.setContentDescription(getString(R.string.details_hint));
        right.addView(status, new LinearLayout.LayoutParams(-1, -2));
        panels.addView(scrollPanel(right), new LinearLayout.LayoutParams(0, -1, .60f));
        root.addView(panels, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footer = text(getString(R.string.measurement_footer), 10, 0xff164a50);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(10), 0, 0);
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    private void configureFromManifest() {
        try {
            JSONObject manifest = new JSONObject(readAssetText("profile-manifest.json"));
            JSONObject profiles = manifest.getJSONObject("profiles");
            boolean srgbReady = profiles.getJSONObject("srgb").optBoolean("allow_apply", false);
            boolean gammaReady = profiles.getJSONObject("gamma22").optBoolean("allow_apply", false);
            srgbAllowed = srgbReady;
            gammaAllowed = gammaReady;
            String recommended = manifest.optString("recommended_profile", "");
            srgbButton.setText(getString("srgb".equals(recommended)
                    ? R.string.curve_recommended : R.string.curve_available, "sRGB"));
            gammaButton.setText(getString("gamma22".equals(recommended)
                    ? R.string.curve_recommended : R.string.curve_available, "Gamma 2.2"));
        } catch (Exception error) {
            srgbAllowed = false;
            gammaAllowed = false;
            srgbButton.setEnabled(false);
            gammaButton.setEnabled(false);
            setStatus(getString(R.string.invalid_manifest));
        }
    }

    private Button addButton(LinearLayout parent, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setMaxLines(2);
        button.setAutoSizeTextTypeUniformWithConfiguration(12, 16, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        button.setFilterTouchesWhenObscured(true);
        button.setOnTouchListener((v, event) ->
                (event.getFlags() & android.view.MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0);
        button.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{AeroTheme.MUTED, 0xffffffff}));
        button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        button.setBackground(AeroTheme.buttonStates(this, false, false));
        button.setElevation(dp(2));
        button.setStateListAnimator(null);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(48));
        params.bottomMargin = dp(6);
        parent.addView(button, params);
        button.setOnClickListener(view -> runAction(button, action));
        return button;
    }

    private android.content.res.ColorStateList neutralButtonColors() {
        return new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0x88678896, AeroTheme.INK});
    }

    private void runAction(Button selected, Runnable action) {
        if (!statusVerified || !BUSY.compareAndSet(false, true)) return;
        actionRunning = true;
        setButtonsEnabled(false);
        setStatus(getString(R.string.operation_running));
        EXECUTOR.execute(() -> {
            try {
                action.run();
            } catch (Exception error) {
                android.util.Log.e("NovaCalibration", "Profile operation failed", error);
                saveOperation("operation_error", String.valueOf(error));
                String detail = String.valueOf(error) + " " + String.valueOf(error.getCause());
                setStatus(detail.contains("odin_saturation_not_100")
                        ? getString(R.string.error_saturation)
                        : detail.contains("firmware_mismatch")
                            ? getString(R.string.error_firmware)
                        : detail.contains("foreign_override")
                            ? getString(R.string.error_foreign)
                            : getString(R.string.error_save));
            } finally {
                BUSY.set(false);
                runOnUiThread(() -> { actionRunning = false; setButtonsEnabled(true); });
            }
        });
    }

    private void apply(String profileId) {
        try {
            if (!profileId.equals("gamma22") && !profileId.equals("srgb"))
                throw new IllegalArgumentException("Invalid native profile");
            String hash = profileId.equals("gamma22")
                    ? "c6e20079ef58220960e9511d5641bc02f1bcfb0f90c5bb0a842e5b2ed00c9afd"
                    : "8be527253edcc712006e73a26cbadd4f3aa1ade3bd8d2d00ae38587b3f393b25";
            byte[] factory;
            byte[] patch;
            try (InputStream in = new java.io.FileInputStream(ProfilePayload.FACTORY_PATH)) {
                factory = ProfilePayload.readFactory(in);
            }
            try (InputStream in = getAssets().open("profiles/" + profileId + ".npatch")) {
                patch = ProfilePayload.readPatch(in);
            }
            byte[] data = ProfilePayload.reconstruct(factory, patch, hash);
            ensurePServer();
            File helper = stageInstaller();
            writePrivateStage("nova-profile-system.json", data);
            String result = runInstaller(helper, "apply " + profileId);
            saveOperation("native_apply " + profileId, result);
            if (!result.contains("NOVA_NATIVE_CONFIG_SAVED=1"))
                throw new IllegalStateException("Native install failed: " + result);
            rebootNeeded = result.contains("NOVA_NATIVE_REBOOT_REQUIRED=1");
            String name = profileId.equals("gamma22") ? "Gamma 2.2" : "sRGB";
            setStatus(getString(rebootNeeded ? R.string.profile_saved : R.string.profile_unchanged, name));
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private void restore() {
        try {
            ensurePServer();
            File helper = stageInstaller();
            String result = runInstaller(helper, "restore");
            saveOperation("native_restore", result);
            if (!result.contains("NOVA_NATIVE_CONFIG_SAVED=1"))
                throw new IllegalStateException("Native restore failed: " + result);
            rebootNeeded = result.contains("NOVA_NATIVE_REBOOT_REQUIRED=1");
            setStatus(result.contains("NOVA_NATIVE_REBOOT_REQUIRED=1")
                    ? getString(R.string.restore_saved)
                    : getString(R.string.original_unchanged));
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private void rebootDevice() {
        try {
            ensurePServer();
            setStatus(getString(R.string.rebooting));
            root("reboot");
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    private void refreshNativeStatus() {
        try {
            ensurePServer();
            File helper = stageInstaller();
            String result = runInstaller(helper, "status");
            saveOperation("native_status", result);
            if (!result.contains("NOVA_NATIVE_STATUS_OK=1"))
                throw new IllegalStateException(result);
            rebootNeeded = result.contains("NOVA_NATIVE_REBOOT_REQUIRED=1");
            firmwareCompatible = result.contains("NOVA_NATIVE_FIRMWARE_COMPATIBLE=1");
            statusVerified = true;
            runOnUiThread(() -> { if (!actionRunning) setButtonsEnabled(true); });
            String name = result.contains("NOVA_NATIVE_PROFILE=gamma22") ? "Gamma 2.2"
                    : result.contains("NOVA_NATIVE_PROFILE=srgb") ? "sRGB" : getString(R.string.original);
            if (result.contains("NOVA_NATIVE_FIRMWARE_COMPATIBLE=0")) {
                firmwareCompatible = false;
                runOnUiThread(() -> { if (!actionRunning) setButtonsEnabled(true); });
                setStatus(getString(R.string.firmware_changed));
                return;
            }
            setStatus(getString(rebootNeeded ? R.string.profile_saved : R.string.profile_active, name));
        } catch (Exception error) {
            statusVerified = false;
            saveOperation("status_error", String.valueOf(error));
            runOnUiThread(() -> setButtonsEnabled(false));
            android.util.Log.e("NovaCalibration", "Native profile status failed", error);
            setStatus(getString(R.string.status_unavailable));
        }
    }

    private void ensurePServer() throws Exception {
        service = fetchPServer();
        String identity = root("id");
        if (!identity.contains("uid=0(")) throw new IllegalStateException("Privileged identity not verified");
    }

    private IBinder fetchPServer() throws Exception {
        Class<?> manager = Class.forName("android.os.ServiceManager");
        IBinder current = (IBinder) manager.getDeclaredMethod("getService", String.class).invoke(null, "PServerBinder");
        if (current == null) throw new IllegalStateException("OEM service unavailable");
        return current;
    }

    private String root(String command) throws Exception {
        if (command.length() > 180)
            throw new IllegalArgumentException("PServer command too long: " + command.length() + " > 180");
        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        try {
            service = fetchPServer();
            String transport = "(" + command + ") 2>&1 | base64 | tr -d '\\n'";
            request.writeStringArray(new String[] {transport, "1"});
            if (!service.transact(0, request, response, 0)) throw new IllegalStateException("OEM transaction rejected");
            byte[] bytes = response.createByteArray();
            if (bytes == null) return "<null>";
            String encoded = new String(bytes, StandardCharsets.UTF_8).trim();
            if (encoded.equals("null")) return "<null>";
            return new String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.UTF_8).trim();
        } finally {
            request.recycle();
            response.recycle();
        }
    }

    private File stageInstaller() throws Exception {
        return writePrivateStage("nova-system-profile.sh", readAsset("nova-system-profile.sh"));
    }

    private String runInstaller(File helper, String action) throws Exception {
        if (!action.matches("status|restore|apply (gamma22|srgb)"))
            throw new IllegalArgumentException("Unsupported installer action");
        return root("sh '" + helper.getAbsolutePath() + "' " + action);
    }

    private File writePrivateStage(String name, byte[] data) throws Exception {
        if (!name.equals("nova-system-profile.sh") && !name.equals("nova-profile-system.json"))
            throw new IllegalArgumentException("Unexpected private asset");
        File stageDir = new File(getFilesDir(), "profile-stage");
        if (!stageDir.getAbsolutePath().matches("/data/user/[0-9]+/local\\.nova\\.diagnostic/files/profile-stage"))
            throw new IllegalStateException("Unexpected private storage path");
        if (!stageDir.exists() && !stageDir.mkdir()) throw new IllegalStateException("Could not create private stage directory");
        File canonicalStage = stageDir.getCanonicalFile();
        File canonicalFiles = getFilesDir().getCanonicalFile();
        if (canonicalStage.getParentFile() == null || !canonicalStage.getParentFile().equals(canonicalFiles)
                || !"profile-stage".equals(canonicalStage.getName()) || !stageDir.isDirectory()
                || Files.isSymbolicLink(stageDir.toPath()))
            throw new IllegalStateException("Private stage directory is not a regular app-owned directory");
        stageDir.setReadable(false, false);
        stageDir.setWritable(false, false);
        stageDir.setExecutable(false, false);
        if (!stageDir.setReadable(true, true) || !stageDir.setWritable(true, true)
                || !stageDir.setExecutable(true, true))
            throw new IllegalStateException("Could not keep stage directory private");
        File output = new File(stageDir, name);
        File part = new File(stageDir, name + ".tmp");
        if (Files.isSymbolicLink(output.toPath()) || Files.isSymbolicLink(part.toPath()))
            throw new IllegalStateException("Private stage file is a symlink");
        try (FileOutputStream stream = new FileOutputStream(part, false)) {
            stream.write(data);
            stream.getFD().sync();
        }
        part.setReadable(false, false);
        part.setWritable(false, false);
        if (!part.setReadable(true, true) || !part.setWritable(true, true))
            throw new IllegalStateException("Could not keep stage file private");
        Files.move(part.toPath(), output.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        if (!sha256Hex(Files.readAllBytes(output.toPath())).equals(sha256Hex(data)))
            throw new IllegalStateException("Private asset verification failed");
        return output;
    }

    private byte[] readAsset(String name) throws Exception {
        if (!name.matches("[A-Za-z0-9._/-]+") || name.contains("..")) throw new IllegalStateException("Invalid asset name");
        try (InputStream in = getAssets().open("profiles/" + name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private String readAssetText(String name) throws Exception {
        return new String(readAsset(name), StandardCharsets.UTF_8);
    }

    private String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder actual = new StringBuilder();
        for (byte value : digest) actual.append(String.format(Locale.US, "%02x", value & 255));
        return actual.toString();
    }

    private void setStatus(String value) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            status.setText(value);
            status.setContentDescription(value + " " + getString(R.string.details_hint));
        });
    }

    private void showOperationDetails() {
        String detail;
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(new File(getFilesDir(), "profile-operation.txt"), "r")) {
            int length = (int)Math.min(file.length(), 6000);
            byte[] tail = new byte[length];
            file.seek(file.length() - length);
            file.readFully(tail);
            detail = new String(tail, StandardCharsets.UTF_8);
        } catch (Exception error) { detail = getString(R.string.no_log); }
        TextView log = text(detail, 11, AeroTheme.INK);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        log.setTextIsSelectable(true);
        log.setPadding(dp(16), dp(12), dp(16), dp(12));
        log.setBackgroundColor(0xffeef9fc);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(log);
        new android.app.AlertDialog.Builder(this).setTitle(getString(R.string.operation_details))
                .setView(scroll).setPositiveButton(getString(R.string.close), null).show();
    }

    private synchronized void saveOperation(String action, String output) {
        String entry = "timestamp_ms=" + System.currentTimeMillis() + " action=" + action + "\n"
                + output + "\nEND_PROFILE_OPERATION\n";
        // Bound the local log; never export it or write it to shared storage.
        File logFile = new File(getFilesDir(), "profile-operation.txt");
        int mode = logFile.length() > 128 * 1024 ? MODE_PRIVATE : MODE_APPEND;
        try (FileOutputStream stream = openFileOutput("profile-operation.txt", mode)) {
            stream.write(entry.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            android.util.Log.e("NovaCalibration", "Could not save profile operation report", error);
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        enabled = enabled && statusVerified && !BUSY.get();
        if (srgbButton != null) srgbButton.setEnabled(enabled && srgbAllowed && firmwareCompatible);
        if (gammaButton != null) gammaButton.setEnabled(enabled && gammaAllowed && firmwareCompatible);
        if (restoreButton != null) restoreButton.setEnabled(enabled);
        if (rebootButton != null) rebootButton.setEnabled(enabled && rebootNeeded);
    }

    @Override protected void onDestroy() { super.onDestroy(); }
}
