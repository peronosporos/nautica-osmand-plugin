package net.osmand.plus.plugins.nautica.managers;

import android.content.Context;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.search.core.SearchResult;
import net.osmand.plus.search.core.SearchUICore;
import net.osmand.plus.search.core.SearchProvider;
import net.osmand.plus.plugins.nautica.models.AISTarget;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AIS {

    private final OsmandApplication app;
    private final Context context;
    private final MapActivity mapActivity;
    private final SignalKClient signalKClient;
    private boolean useOnlineAIS = false;

    public AIS(OsmandApplication app, Context context, MapActivity mapActivity, signalKClient signalKClient) {
        this.app = app;
        this.context = context;
        this.mapActivity = mapActivity;
        this.signalKClient = signalKClient;
    }

    public void updateAISData() {
        JSONObject aisData = signalKClient.getCachedData("ais.vessels");
        if (aisData != null) {
            for (String mmsi : aisData.keySet()) {
                JSONObject vessel = aisData.optJSONObject(mmsi);
                if (vessel != null && vessel.optBoolean("sart", false)) {
                    signalKClient.updateData("navigation.mob", new JSONObject().put("active", true));
                }
            }
        }
    }

    public void initializeAISSource() {
        useOnlineAIS = app.getSettings().getCustomPreferenceBoolean("ais_online_enabled", false);
        if (useOnlineAIS) {
            String config = app.getSettings().getCustomPreferenceString("ais_online_config", "");
            startOnlineAISFetch(config);
        } else {
            signalKClient.connectAIS();
        }
    }

    private void startOnlineAISFetch(String config) {
        new Thread(() -> {
            try {
                URL url = new URL("http://aisHub.net/api?key=xxx&bbox=lat1,lon1,lat2,lon2");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                String response = new BufferedReader(new InputStreamReader(conn.getInputStream()))
                        .lines().collect(Collectors.joining());
                JSONObject data = new JSONObject(response);
                updateAISTargets(data);
            } catch (Exception e) {
                Log.e("Nautica", "Online AIS failed: " + e);
            }
        }).start();
    }

    private void updateAISTargets(JSONObject data) {
        // Parse and update targets on the map (to be implemented)
    }

    public void checkProximityAlarms() {
        if (!app.getSettings().getCustomPreferenceBoolean("ais_proximity_enabled", true)) return;
        String zones = app.getSettings().getCustomPreferenceString("ais_proximity_zones", "1nm,0.5nm");
        JSONObject vessels = signalKClient.getVessels();
        for (String mmsi : vessels.keySet()) {
            JSONObject vessel = vessels.optJSONObject(mmsi);
            double distance = calculateDistance(vessel.optJSONObject("position"));
            for (String zone : zones.split(",")) {
                if (distance < parseDistance(zone)) {
                    triggerAlert(mmsi, distance);
                }
            }
        }
    }

    private double calculateDistance(JSONObject position) {
        // Dummy logic for now
        return 0.0;
    }

    private double parseDistance(String zone) {
        try {
            return Double.parseDouble(zone.replace("nm", "").trim());
        } catch (Exception e) {
            return 1.0;
        }
    }

    private void triggerAlert(String mmsi, double distance) {
        Log.w("Nautica", "Proximity alert for " + mmsi + " at " + distance + " nm");
    }

    public void triggerAISSearch() {
        app.getSearchUICore().setActiveCategory("AIS Targets");
        mapActivity.showSearchDialog();
    }

    public void registerSearchProvider() {
        if (!app.getSettings().getCustomPreferenceBoolean("ais_search_enabled", true)) return;
        app.getSearchUICore().registerCategory("AIS Targets", new AISSearchProvider());
    }

    public void toggleAISLabels() {
        if (!app.getSettings().getCustomPreferenceBoolean("ais_labels_enabled", true)) return;
        String fields = app.getSettings().getCustomPreferenceString("ais_label_fields", "mmsi,name");
        drawAISLabels(fields);
    }

    public void showAISTargetPopup(AISTarget target) {
        if (!app.getSettings().getCustomPreferenceBoolean("ais_popup_enabled", true)) return;
        new AlertDialog.Builder(context)
            .setTitle("AIS Target")
            .setMessage(formatTargetInfo(target))
            .setPositiveButton("Close", null)
            .show();
    }

    private void drawAISLabels(String fields) {
        // Drawing logic placeholder
    }

    private String formatTargetInfo(AISTarget target) {
        return "MMSI: " + target.mmsi + "\nName: " + target.name +
                "\nSpeed: " + target.speed + " kt\nCourse: " + target.course +
                "\nCPA: " + target.cpa + " nm\nTCPA: " + target.tcpa + " min";
    }

    private class AISSearchProvider implements SearchProvider {
        @Override
        public List<SearchResult> search(String query) {
            List<SearchResult> results = new ArrayList<>();
            JSONObject vessels = signalKClient.getVessels();
            for (String mmsi : vessels.keySet()) {
                JSONObject vessel = vessels.optJSONObject(mmsi);
                if (vessel != null && (vessel.optString("name", "").contains(query) || mmsi.contains(query))) {
                    JSONObject pos = vessel.optJSONObject("position");
                    results.add(new SearchResult(mmsi, vessel.optString("name", "Unknown"), pos));
                }
            }
            return results;
        }
    }
}
