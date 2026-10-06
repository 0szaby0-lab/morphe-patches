package app.morphe.extension.shared.license;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Szaby License Manager for YouTube and YouTube Music patches.
 * Handles validation with https://szaby-license-server.onrender.com.
 * When not activated, patches are disabled and apps behave like the original stock apps.
 */
public final class LicenseManager {

    public static final String SERVER_URL = "https://szaby-license-server.onrender.com";
    private static final String PREFS_NAME = "szaby_license_prefs";
    private static final String KEY_LICENSE_KEY = "license_key";
    private static final String KEY_IS_ACTIVATED = "is_activated";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_LAST_CHECKED = "last_checked_ms";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    // In-memory cached activation state for high-frequency bytecode and Litho hooks
    private static volatile Boolean cachedActivated = null;
    private static volatile boolean startupDialogPrompted = false;

    private LicenseManager() {
    }

    private static SharedPreferences getPrefs() {
        Context context = Utils.getContext();
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Fast check called by patch injection points.
     * Returns true only if a valid, unexpired license has been activated.
     */
    public static boolean isActivated() {
        if (cachedActivated != null) {
            return cachedActivated;
        }

        try {
            SharedPreferences prefs = getPrefs();
            boolean activated = prefs.getBoolean(KEY_IS_ACTIVATED, false);
            if (!activated) {
                cachedActivated = false;
                return false;
            }

            String expiresAt = prefs.getString(KEY_EXPIRES_AT, null);
            if (expiresAt != null && !expiresAt.startsWith("9999")) {
                try {
                    if (Instant.parse(expiresAt).isBefore(Instant.now())) {
                        Logger.printInfo(() -> "Szaby license expired: " + expiresAt);
                        prefs.edit().putBoolean(KEY_IS_ACTIVATED, false).apply();
                        cachedActivated = false;
                        return false;
                    }
                } catch (Exception e) {
                    Logger.printException(() -> "Error parsing license expiry date", e);
                }
            }

            cachedActivated = true;
            return true;
        } catch (Exception e) {
            Logger.printException(() -> "Error reading license state", e);
            return false;
        }
    }

    /**
     * Computes the device HWID as SHA-256 hash of the device Android ID.
     */
    public static String getHWID() {
        try {
            Context context = Utils.getContext();
            String rawId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (rawId == null || rawId.trim().isEmpty()) {
                rawId = Build.FINGERPRINT != null ? Build.FINGERPRINT : "UNKNOWN_DEVICE_ID";
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(rawId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Logger.printException(() -> "Failed to generate HWID", e);
            return "UNKNOWN_HWID";
        }
    }

    public static String getStoredKey() {
        return getPrefs().getString(KEY_LICENSE_KEY, "");
    }

    public static String getExpiresAt() {
        return getPrefs().getString(KEY_EXPIRES_AT, "");
    }

    public static String getFormattedStatus() {
        if (isActivated()) {
            String exp = getExpiresAt();
            if (exp.startsWith("9999")) {
                return "Aktív (Örökös licenc)";
            } else if (!exp.isEmpty()) {
                try {
                    String datePart = exp.contains("T") ? exp.split("T")[0] : exp;
                    return "Aktív (Lejárat: " + datePart + ")";
                } catch (Exception ignored) {}
            }
            return "Aktív";
        }
        return "Nem aktív (Eredeti mód)";
    }

    /**
     * Validates license key with the server asynchronously.
     */
    public static void validateKey(String rawKey, Consumer<ValidationResult> callback) {
        final String cleanKey = (rawKey == null ? "" : rawKey.trim().toUpperCase(Locale.ROOT));
        final String hwid = getHWID();

        if (cleanKey.isEmpty()) {
            Utils.runOnMainThread(() -> callback.accept(new ValidationResult(false, "Kérlek adj meg egy licenckulcsot!")));
            return;
        }

        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                String queryUrl = SERVER_URL + "/api/validate?key="
                        + URLEncoder.encode(cleanKey, "UTF-8")
                        + "&hwid=" + URLEncoder.encode(hwid, "UTF-8");

                URL url = new URL(queryUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("User-Agent", "SzabyPatcher/1.0 (Android)");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    Utils.runOnMainThread(() -> callback.accept(new ValidationResult(false, "Szerver hiba (" + responseCode + ")")));
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                String status = json.optString("status", "INVALID");

                if ("VALID".equalsIgnoreCase(status)) {
                    String expiresAt = json.optString("expires_at", "9999-12-31T23:59:59Z");
                    getPrefs().edit()
                            .putString(KEY_LICENSE_KEY, cleanKey)
                            .putBoolean(KEY_IS_ACTIVATED, true)
                            .putString(KEY_EXPIRES_AT, expiresAt)
                            .putLong(KEY_LAST_CHECKED, System.currentTimeMillis())
                            .apply();

                    cachedActivated = true;
                    Logger.printInfo(() -> "License activated successfully for key: " + cleanKey);
                    Utils.runOnMainThread(() -> callback.accept(new ValidationResult(true, "Sikeres aktiválás!")));
                } else if ("BANNED".equalsIgnoreCase(status)) {
                    deactivate();
                    String reason = json.optString("reason", "A kulcs vagy az eszköz bannolva van.");
                    Utils.runOnMainThread(() -> callback.accept(new ValidationResult(false, reason)));
                } else if ("EXPIRED".equalsIgnoreCase(status)) {
                    deactivate();
                    Utils.runOnMainThread(() -> callback.accept(new ValidationResult(false, "A megadott licenckulcs lejárt.")));
                } else {
                    deactivate();
                    String reason = json.optString("reason", "Érvénytelen licenckulcs.");
                    Utils.runOnMainThread(() -> callback.accept(new ValidationResult(false, reason)));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "License validation network failure", ex);
                Utils.runOnMainThread(() -> callback.accept(new ValidationResult(false, "Nem sikerült elérni a licencszervert. Ellenőrizd az internetkapcsolatot!")));
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    public static void deactivate() {
        getPrefs().edit()
                .putBoolean(KEY_IS_ACTIVATED, false)
                .remove(KEY_LICENSE_KEY)
                .remove(KEY_EXPIRES_AT)
                .apply();
        cachedActivated = false;
    }

    /**
     * Re-verifies active license in the background every 24 hours.
     */
    public static void checkInBackground() {
        if (!isActivated()) {
            return;
        }

        long lastChecked = getPrefs().getLong(KEY_LAST_CHECKED, 0);
        long now = System.currentTimeMillis();
        if (now - lastChecked < 24 * 60 * 60 * 1000L) {
            return; // checked recently
        }

        String key = getStoredKey();
        if (key.isEmpty()) {
            deactivate();
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                String hwid = getHWID();
                String queryUrl = SERVER_URL + "/api/validate?key="
                        + URLEncoder.encode(key, "UTF-8")
                        + "&hwid=" + URLEncoder.encode(hwid, "UTF-8");

                URL url = new URL(queryUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String status = json.optString("status");
                    if ("VALID".equalsIgnoreCase(status)) {
                        String expiresAt = json.optString("expires_at", "9999-12-31T23:59:59Z");
                        getPrefs().edit()
                                .putString(KEY_EXPIRES_AT, expiresAt)
                                .putLong(KEY_LAST_CHECKED, System.currentTimeMillis())
                                .apply();
                    } else if ("BANNED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status) || "INVALID".equalsIgnoreCase(status)) {
                        deactivate();
                    }
                }
                conn.disconnect();
            } catch (Exception e) {
                // Keep local activation cache if offline
                Logger.printInfo(() -> "Background license check failed (offline): " + e.getMessage());
            }
        });
    }

    /**
     * Called on startup during initialization.
     * If unactivated, prompts user with activation dialog.
     */
    public static void checkOnStartup() {
        if (isActivated()) {
            checkInBackground();
            return;
        }

        if (startupDialogPrompted) {
            return;
        }
        startupDialogPrompted = true;

        Utils.runOnMainThreadDelayed(() -> {
            Activity activity = Utils.getActivity();
            if (activity == null || activity.isFinishing()) {
                return;
            }
            showActivationDialog(activity, null);
        }, 1200);
    }

    /**
     * Displays the activation or status dialog.
     */
    public static void showActivationDialog(Activity activity, Runnable onUpdated) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (isActivated()) {
            // Already active: show info and deactivation option
            String key = getStoredKey();
            String maskedKey = key.length() > 8
                    ? key.substring(0, 6) + "-****-" + key.substring(key.length() - 4)
                    : key;

            String infoMsg = "Előfizetésed állapota: AKTÍV\n\n"
                    + "Kulcs: " + maskedKey + "\n"
                    + "Lejárat: " + getExpiresAt().replace("T", " ").replace("Z", "") + "\n\n"
                    + "A prémium funkciók (reklámmentesség, háttérzene, stb.) be vannak kapcsolva.";

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    "Szaby Előfizetés",
                    infoMsg,
                    null,
                    "Rendben",
                    () -> {
                        if (onUpdated != null) onUpdated.run();
                    },
                    null,
                    "Kijelentkezés",
                    () -> {
                        deactivate();
                        Utils.showToastShort("Előfizetés törölve. Újraindítás...");
                        Utils.runOnMainThreadDelayed(() -> Utils.restartApp(activity), 500);
                    },
                    true
            );
            dialogPair.first.show();
            return;
        }

        // Inactive: prompt for license key
        EditText inputField = new EditText(activity);
        inputField.setHint("SZABY-XXXX-XXXX-XXXX");
        inputField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        inputField.setSingleLine(true);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                activity,
                "Szaby Előfizetés Aktiválás",
                "A prémium funkciók (reklámblokkolás, háttérzene, letöltés) aktiválásához add meg a vásárolt licenckulcsodat.\n\nKulcs nélkül az alkalmazás az eredeti, ingyenes módban fog futni.",
                inputField,
                "Aktiválás",
                () -> {
                    String enteredKey = inputField.getText().toString().trim();
                    if (enteredKey.isEmpty()) {
                        Utils.showToastShort("Kérlek írd be a licenckulcsodat!");
                        return;
                    }

                    Utils.showToastShort("Kulcs ellenőrzése folyamatban...");
                    validateKey(enteredKey, result -> {
                        if (result.success) {
                            Utils.showToastLong("Sikeres aktiválás! Az alkalmazás újraindul...");
                            Utils.runOnMainThreadDelayed(() -> Utils.restartApp(activity), 800);
                        } else {
                            Utils.showToastLong(result.message);
                        }
                        if (onUpdated != null) onUpdated.run();
                    });
                },
                () -> {
                    // Canceled / Continue original
                    Utils.showToastShort("Eredeti mód: prémium funkciók kikapcsolva.");
                    if (onUpdated != null) onUpdated.run();
                },
                null,
                null,
                true
        );

        dialogPair.first.setCancelable(true);
        dialogPair.first.show();
    }

    public static class ValidationResult {
        public final boolean success;
        public final String message;

        public ValidationResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
