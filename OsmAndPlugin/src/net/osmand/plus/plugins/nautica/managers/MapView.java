package net.osmand.plus.plugins.nautica.managers;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import android.widget.*;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.views.OsmandMapLayer;
import net.osmand.data.GpxFile;
import net.osmand.data.GpxTrack;
import net.osmand.plus.gpx.GpxLayer;
import org.json.*;

import java.io.File;
import java.util.*;

public class NauticaMapView extends OsmandMapLayer {
    private final MapActivity mapActivity;
    private final SignalK signalK;
    private final NauticaWidgetManager widgetManager;
    private final NauticaWeatherManager weatherManager;

    // MOB and drift
    private double[] mobWaypoint = null;
    private List<double[]> driftPath = new ArrayList<>();

    // SART
    private Map<String, double[]> sartWaypoints = new HashMap<>();

    // GPX
    private GpxLayer gpxLayer;

    // Paints
    private Paint driftPaint;
    private Paint sartPaint;
    private Paint aisPaint;
    private Paint labelPaint;

    // UI
    private Button mobToggleButton;
    private Button splitToggleButton;
    private LinearLayout mapContainer;

    // Split screen
    private OsmandMapView primaryMapView;
    private OsmandMapView secondaryMapView;
    private boolean isSplitScreen = false;
    private float lastCOG = 0f;
    private long lastCOGUpdate = 0;

    // AIS
    private Map<String, JSONObject> aisTargets = new HashMap<>();

    public NauticaMapView(MapActivity mapActivity, SignalKClient signalKClient,
                          NauticaWidgetManager widgetManager, NauticaWeatherManager weatherManager) {
        this.mapActivity = mapActivity;
        this.signalKClient = signalKClient;
        this.widgetManager = widgetManager;
        this.weatherManager = weatherManager;
    }
}

@Override
public void initLayer(Context context) {
    primaryMapView = mapActivity.getMapView();

    // Initialize paint styles
    driftPaint = new Paint();
    driftPaint.setColor(Color.RED);
    driftPaint.setStyle(Paint.Style.STROKE);
    driftPaint.setStrokeWidth(2);
    driftPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));

    sartPaint = new Paint();
    sartPaint.setColor(Color.MAGENTA);
    sartPaint.setStyle(Paint.Style.FILL);

    aisPaint = new Paint();
    aisPaint.setColor(Color.GREEN);

    labelPaint = new Paint();
    labelPaint.setTextSize(12);
    labelPaint.setColor(Color.WHITE);

    // MOB Button
    if (mapActivity.getSettings().getCustomPreferenceBoolean("mob_button_enabled", true)) {
        mobToggleButton = new Button(context);
        mobToggleButton.setText("Trigger MOB");
        mobToggleButton.setBackgroundColor(Color.LTGRAY);
        mobToggleButton.setOnClickListener(v -> {
            widgetManager.toggleMOB();
            updateButtonState();
        });
        addToLayout(mobToggleButton, Gravity.TOP | Gravity.RIGHT);
    }

    // Split Screen Button
    if (mapActivity.getSettings().getCustomPreferenceBoolean("split_screen_enabled", true)) {
        splitToggleButton = new Button(context);
        splitToggleButton.setText("Split View");
        splitToggleButton.setBackgroundColor(Color.LTGRAY);
        splitToggleButton.setOnClickListener(v -> toggleSplitScreen());
        addToLayout(splitToggleButton, Gravity.TOP | Gravity.LEFT);
    }

    // GPX Layer
    if (mapActivity.getSettings().getCustomPreferenceBoolean("gpx_enabled", true)) {
        gpxLayer = new GpxLayer(mapActivity);
        gpxLayer.setTrackColor(Color.BLUE);
        gpxLayer.setPointColor(Color.GREEN);
        gpxLayer.setTrackWidth(3);
        mapActivity.addMapLayer(gpxLayer);
        loadGpxFiles();
    }

    // Map container
    mapContainer = new LinearLayout(context);
    mapContainer.setOrientation(getSplitOrientation());
    mapContainer.addView(primaryMapView);
    mapActivity.setContentView(mapContainer);
}

private void addToLayout(Button button, int gravity) {
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
    params.setMargins(16, 16, 16, 16);
    params.gravity = gravity;
    mapActivity.addContentView(button, params);
}

// --- MOB Handling ---
public void addMOBWaypoint(double[] position) {
    mobWaypoint = position;
    driftPath.clear();
    driftPath.add(position);
    updateButtonState();
    mapActivity.refreshMap();
}

public void updateMOBDriftPath(double[] start, double speed, double direction) {
    if (mobWaypoint == null) return;
    double time = 10.0;
    double dx = speed * time * Math.cos(direction);
    double dy = speed * time * Math.sin(direction);
    double[] newPoint = new double[]{
        start[0] + dx / 111111.0,
        start[1] + dy / (111111.0 * Math.cos(Math.toRadians(start[0])))
    };
    driftPath.add(newPoint);
    mapActivity.refreshMap();
}

public void clearMOBWaypoint() {
    mobWaypoint = null;
    driftPath.clear();
    updateButtonState();
    mapActivity.refreshMap();
}

public void updateButtonState() {
    if (mobToggleButton == null) return;
    JSONObject data = signalKClient.getCachedData("navigation.mob");
    boolean active = data != null && data.optBoolean("active", false);
    mobToggleButton.setText(active ? "Clear MOB" : "Trigger MOB");
    mobToggleButton.setBackgroundColor(active ? Color.RED : Color.LTGRAY);
}

// --- Waypoints and SART ---
public void addWaypoint(double[] position, String name) {
    if (name.startsWith("SART_")) {
        sartWaypoints.put(name, position);
    } else {
        JSONObject wp = new JSONObject();
        wp.put("latitude", position[0]);
        wp.put("longitude", position[1]);
        wp.put("name", name);
        signalKClient.updateData("waypoints." + name, wp);
    }
    mapActivity.refreshMap();
}

// --- GPX Import/Export ---
private void loadGpxFiles() {
    File dir = new File("/sdcard/Nautica/GPX");
    if (!dir.exists()) dir.mkdirs();
    File[] files = dir.listFiles();
    if (files == null) return;
    for (File file : files) {
        if (file.getName().endsWith(".gpx")) {
            GpxFile gpx = GpxFile.loadFromFile(file);
            if (gpx != null) gpxLayer.addTrack(gpx);
        }
    }
}

public void importGpx(File file) {
    if (!mapActivity.getSettings().getCustomPreferenceBoolean("gpx_enabled", true)) return;
    GpxFile gpx = GpxFile.loadFromFile(file);
    if (gpx != null) {
        gpxLayer.addTrack(gpx);
        File dest = new File("/sdcard/Nautica/GPX/" + file.getName());
        file.renameTo(dest);
        mapActivity.refreshMap();
    }
}

public void exportGpx(String name, List<double[]> waypoints, List<double[]> route) {
    if (!mapActivity.getSettings().getCustomPreferenceBoolean("gpx_enabled", true)) return;
    GpxFile gpx = new GpxFile();
    for (double[] pt : waypoints) {
        gpx.addWaypoint(pt[0], pt[1], "WPT_" + System.currentTimeMillis());
    }
    if (!route.isEmpty()) {
        GpxTrack track = new GpxTrack();
        for (double[] pt : route) track.addPoint(pt[0], pt[1]);
        gpx.addTrack(track);
    }
    File file = new File("/sdcard/Nautica/GPX/" + name + ".gpx");
    gpx.saveToFile(file);
    gpxLayer.addTrack(gpx);
    mapActivity.refreshMap();
}

// --- Split Screen ---
private int getSplitOrientation() {
    String orientation = mapActivity.getSettings().getCustomPreference("split_screen_orientation", "horizontal");
    return orientation.equals("horizontal") ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
}

public void toggleSplitScreen() {
    isSplitScreen = !isSplitScreen;
    mapContainer.removeAllViews();
    mapContainer.setOrientation(getSplitOrientation());
    if (isSplitScreen) {
        secondaryMapView = new OsmandMapView(mapActivity);
        secondaryMapView.setMapLayer(this);
        mapContainer.addView(primaryMapView, new LinearLayout.LayoutParams(0, 0, 1f));
        mapContainer.addView(secondaryMapView, new LinearLayout.LayoutParams(0, 0, 1f));
        if (mapActivity.getSettings().getCustomPreferenceBoolean("split_screen_sync", false)) {
            syncMapViews();
        }
        splitToggleButton.setText("Single View");
        splitToggleButton.setBackgroundColor(Color.GRAY);
    } else {
        mapContainer.addView(primaryMapView);
        secondaryMapView = null;
        splitToggleButton.setText("Split View");
        splitToggleButton.setBackgroundColor(Color.LTGRAY);
    }
    mapActivity.refreshMap();
}

private void syncMapViews() {
    if (secondaryMapView == null) return;
    secondaryMapView.setZoom(primaryMapView.getZoom());
    secondaryMapView.setMapPosition(primaryMapView.getLatitude(), primaryMapView.getLongitude());
    primaryMapView.addMapChangeListener((lat, lon, zoom) -> {
        if (mapActivity.getSettings().getCustomPreferenceBoolean("split_screen_sync", false)) {
            secondaryMapView.setZoom(zoom);
            secondaryMapView.setMapPosition(lat, lon);
        }
    });
}

// --- Route Creation ---
public void createRoute(List<double[]> waypoints, String routeType, double maxLat) {
    if (!mapActivity.getSettings().getCustomPreferenceBoolean("route_enabled", true)) return;

    List<double[]> routePoints = new ArrayList<>();
    for (int i = 0; i < waypoints.size() - 1; i++) {
        double[] start = waypoints.get(i);
        double[] end = waypoints.get(i + 1);
        List<double[]> segmentPoints;
        if ("great_circle".equals(routeType)) {
            segmentPoints = calculateGreatCirclePoints(start, end);
        } else if ("limited_circle".equals(routeType)) {
            segmentPoints = calculateLimitedCirclePoints(start, end, maxLat);
        } else {
            segmentPoints = calculateRhumbPoints(start, end);
        }
        segmentPoints = adjustForWaveHeight(segmentPoints);
        routePoints.addAll(segmentPoints);
    }

    if (!routePoints.isEmpty()) {
        routePoints.add(waypoints.get(waypoints.size() - 1));
    }

    // Upload to SignalK and export GPX
    JSONObject route = new JSONObject();
    JSONArray points = new JSONArray();
    for (double[] pt : routePoints) {
        JSONObject point = new JSONObject();
        point.put("latitude", pt[0]);
        point.put("longitude", pt[1]);
        points.put(point);
    }
    route.put("points", points);
    signalKClient.updateData("navigation.route", route);

    GpxFile gpx = new GpxFile();
    GpxTrack track = new GpxTrack();
    for (double[] pt : routePoints) {
        track.addPoint(pt[0], pt[1]);
    }
    gpx.addTrack(track);
    File file = new File("/sdcard/Nautica/GPX/route_" + System.currentTimeMillis() + ".gpx");
    gpx.saveToFile(file);
    gpxLayer.addTrack(gpx);
    mapActivity.refreshMap();
}

private List<double[]> calculateGreatCirclePoints(double[] start, double[] end) {
    List<double[]> points = new ArrayList<>();
    double lat1 = Math.toRadians(start[0]);
    double lon1 = Math.toRadians(start[1]);
    double lat2 = Math.toRadians(end[0]);
    double lon2 = Math.toRadians(end[1]);
    double d = calculateGreatCircleDistance(start, end);
    int segments = (int) (d / 0.01745); // 1° = ~60 nm, ~10nm/segment
    for (int i = 0; i <= segments; i++) {
        double f = (double) i / segments;
        double A = Math.sin((1 - f) * d) / Math.sin(d);
        double B = Math.sin(f * d) / Math.sin(d);
        double x = A * Math.cos(lat1) * Math.cos(lon1) + B * Math.cos(lat2) * Math.cos(lon2);
        double y = A * Math.cos(lat1) * Math.sin(lon1) + B * Math.cos(lat2) * Math.sin(lon2);
        double z = A * Math.sin(lat1) + B * Math.sin(lat2);
        double lat = Math.atan2(z, Math.sqrt(x * x + y * y));
        double lon = Math.atan2(y, x);
        points.add(new double[]{Math.toDegrees(lat), Math.toDegrees(lon)});
    }
    return points;
}

private List<double[]> calculateLimitedCirclePoints(double[] start, double[] end, double maxLat) {
    if (Math.abs(start[0]) > maxLat || Math.abs(end[0]) > maxLat) {
        return calculateRhumbPoints(start, end);
    }
    return calculateGreatCirclePoints(start, end);
}

private List<double[]> calculateRhumbPoints(double[] start, double[] end) {
    return Arrays.asList(start, end);
}

private List<double[]> adjustForWaveHeight(List<double[]> points) {
    List<double[]> adjusted = new ArrayList<>();
    for (double[] pt : points) {
        double wave = weatherManager.getWaveHeight(pt[0], pt[1]);
        if (wave > 3.0) {
            adjusted.add(new double[]{pt[0] + 0.1, pt[1] + 0.1}); // Avoid high waves
        } else {
            adjusted.add(pt);
        }
    }
    return adjusted;
}

private double calculateGreatCircleDistance(double[] start, double[] end) {
    double lat1 = Math.toRadians(start[0]);
    double lon1 = Math.toRadians(start[1]);
    double lat2 = Math.toRadians(end[0]);
    double lon2 = Math.toRadians(end[1]);
    return Math.acos(Math.sin(lat1) * Math.sin(lat2) +
                     Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon2 - lon1));
}

// --- AIS Drawing and Target Updates ---
private void updateAisTargets() {
    JSONObject data = signalKClient.getCachedData("ais.targets");
    if (data != null) {
        for (Iterator<String> it = data.keys(); it.hasNext(); ) {
            String key = it.next();
            aisTargets.put(key, data.optJSONObject(key));
        }
    }
}

// --- Map Rendering ---
@Override
public void onDraw(Canvas canvas, RectF latLonBounds, RectF tilesRect, DrawSettings settings) {
    weatherManager.drawWeatherOverlay(canvas, latLonBounds);
    drawMOB(canvas);
    drawSART(canvas, latLonBounds);
    drawAIS(canvas, latLonBounds);
}

private void drawMOB(Canvas canvas) {
    if (mobWaypoint != null && !driftPath.isEmpty()) {
        float[] startPx = mapActivity.getMapView().getPointFromLatLon(mobWaypoint[0], mobWaypoint[1]);
        canvas.drawCircle(startPx[0], startPx[1], 10, driftPaint);
        float[] prevPx = startPx;
        for (double[] pt : driftPath) {
            float[] px = mapActivity.getMapView().getPointFromLatLon(pt[0], pt[1]);
            canvas.drawLine(prevPx[0], prevPx[1], px[0], px[1], driftPaint);
            prevPx = px;
        }
    }
}

private void drawSART(Canvas canvas, RectF bounds) {
    for (Map.Entry<String, double[]> e : sartWaypoints.entrySet()) {
        double[] pos = e.getValue();
        if (bounds.contains((float) pos[1], (float) pos[0])) {
            float[] px = mapActivity.getMapView().getPointFromLatLon(pos[0], pos[1]);
            canvas.drawCircle(px[0], px[1], 8, sartPaint);
        }
    }
}

private void drawAIS(Canvas canvas, RectF bounds) {
    if (!mapActivity.getSettings().getCustomPreferenceBoolean("ais_enabled", true)) return;
    updateAisTargets();
    int max = mapActivity.getSettings().getCustomPreferenceInt("ais_max_targets", 100);
    int count = 0;
    for (Map.Entry<String, JSONObject> e : aisTargets.entrySet()) {
        if (count++ >= max) break;
        JSONObject target = e.getValue();
        JSONObject pos = target.optJSONObject("navigation").optJSONObject("position");
        if (pos == null) continue;
        double lat = pos.optDouble("latitude", 0);
        double lon = pos.optDouble("longitude", 0);
        float[] px = mapActivity.getMapView().getPointFromLatLon(lat, lon);
        canvas.drawCircle(px[0], px[1], 6, aisPaint);
        canvas.drawText(target.optString("name", e.getKey()), px[0] + 8, px[1], labelPaint);
    }
}
