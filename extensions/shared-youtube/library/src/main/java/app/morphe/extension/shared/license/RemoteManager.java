package app.morphe.extension.shared.license;

import android.app.Activity;
import android.app.Dialog;
import android.util.Pair;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Szaby Remote Manager for YouTube & YouTube Music.
 * Handles live telemetry reporting (what the user is watching) and
 * receiving remote admin push messages / notifications.
 * Active only when a valid license is activated.
 */
public final class RemoteManager {

    private static final String HEARTBEAT_ENDPOINT = LicenseManager.SERVER_URL + "/api/heartbeat";
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor();
    private static final AtomicBoolean isStarted = new AtomicBoolean(false);

    private static volatile String currentAppType = "YouTube";
    private static volatile String currentVideoId = "";
    private static volatile String currentTitle = "";
    private static volatile String currentChannel = "";

    // Prevents displaying the exact same message ID multiple times in a session
    private static final Set<Long> seenMessageIds = Collections.synchronizedSet(new HashSet<>());

    private RemoteManager() {
    }

    /**
     * Initializes the background telemetry loop if licensed.
     */
    public static void init(String appType) {
        if (appType != null && !appType.isEmpty()) {
            currentAppType = appType;
        }

        if (isStarted.compareAndSet(false, true)) {
            // Ping every 20 seconds
            SCHEDULER.scheduleWithFixedDelay(RemoteManager::sendHeartbeat, 5, 20, TimeUnit.SECONDS);
            Logger.printInfo(() -> "Szaby RemoteManager started for " + currentAppType);
        }
    }

    /**
     * Called whenever playback media changes (track / video / channel / title).
     */
    public static void onMediaChanged(String appType, String videoId, String title, String channel) {
        if (!LicenseManager.isActivated()) {
            return;
        }

        init(appType);

        boolean changed = false;
        if (videoId != null && !videoId.trim().isEmpty() && !videoId.equals(currentVideoId)) {
            currentVideoId = videoId.trim();
            changed = true;
        }
        if (title != null && !title.trim().isEmpty() && !title.equals(currentTitle)) {
            currentTitle = title.trim();
            changed = true;
        }
        if (channel != null && !channel.trim().isEmpty() && !channel.equals(currentChannel)) {
            currentChannel = channel.trim();
            changed = true;
        }

        if (changed) {
            SCHEDULER.execute(RemoteManager::sendHeartbeat);
        }
    }

    /**
     * Sends heartbeat with current watching telemetry and retrieves push messages.
     */
    public static void sendHeartbeat() {
        if (!LicenseManager.isActivated()) {
            return;
        }

        final String key = LicenseManager.getStoredKey();
        final String hwid = LicenseManager.getHWID();
        if (key == null || key.isEmpty() || hwid == null || hwid.isEmpty()) {
            return;
        }

        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("key", key);
            body.put("hwid", hwid);
            body.put("app_type", currentAppType);
            body.put("video_id", currentVideoId);
            body.put("video_title", currentTitle);
            body.put("channel_name", currentChannel);

            URL url = new URL(HEARTBEAT_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", "SzabyPatcher/1.0 (Android)");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);

            byte[] jsonBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBytes);
                os.flush();
            }

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    sb.append(line);
                }
                br.close();

                JSONObject res = new JSONObject(sb.toString());
                String status = res.optString("status");

                if ("BANNED".equalsIgnoreCase(status) || "EXPIRED".equalsIgnoreCase(status) || "INVALID".equalsIgnoreCase(status)) {
                    LicenseManager.deactivate();
                    return;
                }

                // Process pending messages from admin
                JSONArray msgs = res.optJSONArray("messages");
                if (msgs != null && msgs.length() > 0) {
                    for (int i = 0; i < msgs.length(); i++) {
                        JSONObject m = msgs.getJSONObject(i);
                        long msgId = m.optLong("id", -1);
                        if (msgId != -1 && seenMessageIds.contains(msgId)) {
                            continue;
                        }
                        if (msgId != -1) {
                            seenMessageIds.add(msgId);
                        }

                        String title = m.optString("title", "Admin Értesítés");
                        String message = m.optString("message", "");
                        String type = m.optString("type", "toast");

                        if (!message.isEmpty()) {
                            dispatchRemoteMessage(title, message, type);
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Szaby Remote heartbeat failed: " + ex.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void dispatchRemoteMessage(String title, String message, String type) {
        Utils.runOnMainThread(() -> {
            try {
                if ("dialog".equalsIgnoreCase(type)) {
                    Activity act = Utils.getActivity();
                    if (act != null && !act.isFinishing()) {
                        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                                act,
                                title,
                                message,
                                null,
                                "Rendben",
                                null,
                                null,
                                null,
                                null,
                                true
                        );
                        dialogPair.first.show();
                        return;
                    }
                }

                // Default / Toast fallback
                String display = title.isEmpty() ? message : (title + ": " + message);
                Utils.showToastLong(display);
            } catch (Exception e) {
                Logger.printException(() -> "Error displaying remote message", e);
            }
        });
    }
}
