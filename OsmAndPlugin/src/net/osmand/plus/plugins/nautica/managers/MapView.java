package net.osmand.plus.plugins.nautica.managers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import net.osmand.data.GpxFile;
import net.osmand.data.GpxTrack;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.gpx.GpxLayer;
import net.osmand.plus.render.OsmandRenderer;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.views.OsmandMapLayer;
import net.osmand.plus.views.OsmandMapView;
import net.osmand.plus.views.layers.ContextMenuLayer;
import net.osmand.plus.views.layers.MapControlsLayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class NauticaMapView extends OsmandMapLayer {
    private static final Logger LOGGER = Logger.getLogger(NauticaMapView.class.getName());

    // Orientation modes
    private static final int MODE_OSMAND_NORTH_UP = 0; // OsmAnd North-up
    private static final int MODE_OSMAND_DIRECTION = 1; // OsmAnd Direction of movement
    private static final int MODE_NAUTICA_COURSE_UP = 2; // Nautica Course-up
    private static final int MODE_NAUTICA_HEAD_UP = 3; // Nautica Head-up

    private final OsmandApplication app;
    private final OsmandSettings settings;
    private final SignalKClient signalKClient;
    private final NauticaWidgetManager widgetManager;
    private final NauticaWeatherManager weatherManager;
    private final Context context;

    // Data structures
    private double[] mobWaypoint;
    private final List<double[]> driftPath = new ArrayList<>();
    private final Map<String, double[]> sartWaypoints = new HashMap<>();
    private final Map<String, JSONObject> aisTargets = new HashMap<>();
    private GpxLayer gpxLayer;

    // Paints
    private final Paint driftPaint = createDriftPaint();
    private final Paint sartPaint = createSartPaint();
    private final Paint aisPaint = createAisPaint();
    private final Paint labelPaint = createLabelPaint();

    // Split screen
    private OsmandMapView primaryMapView;
    private OsmandMapView secondaryMapView;
    private LinearLayout mapContainer;
    private boolean isSplitScreen;

    // Orientation
    private int orientationMode; // 0: OsmAnd North-up, 1: OsmAnd Direction, 2: Course-up, 3: Head-up

    public NauticaMapView(OsmandApplication app, SignalKClient signalKClient,
                          NauticaWidgetManager widgetManager, NauticaWeatherManager weatherManager) {
        this.app = app;
        this.settings = app.getSettings();
        this.signalKClient = signalKClient;
        this.widgetManager = widgetManager;
        this.weatherManager = weatherManager;
        this.context = app.getApplicationContext();
    }

    // Paint initialization
    private Paint createDriftPaint() {
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        return paint;
    }

    private Paint createSartPaint() {
        Paint paint = new Paint();
        paint.setColor(Color.MAGENTA);
        paint.setStyle(Paint.Style.FILL);
        return paint;
    }

    private Paint createAisPaint() {
        Paint paint = new Paint();
        paint.setColor(Color.GREEN);
        return paint;
    }

    private Paint createLabelPaint() {
        Paint paint = new Paint();
        paint.setTextSize(12);
        paint.setColor(Color.WHITE);
        return paint;
    }

    @Override
    public void initLayer(OsmandMapView mapView) {
        super.initLayer(mapView);
        this.primaryMapView = mapView;
        initGpxLayer(mapView);
        renderChart();
        updateOrientation();
        setupUIControls();
    }

    private void setupUIControls() {
        MapControlsLayer controlsLayer = app.getMapView().getLayerByClass(MapControlsLayer.class);
        if (controlsLayer != null) {
            // Compass menu items
            ContextMenuLayer contextMenuLayer = app.getMapView().getLayerByClass(ContextMenuLayer.class);
            if (contextMenuLayer != null) {
                contextMenuLayer.registerContextMenuActions(adapter -> {
                    adapter.addItem(new ContextMenuLayer.MenuItem(
                            app.getString(R.string.nautica_course_up),
                            R.drawable.ic_action_course_up, // Assumes icon
                            () -> setOrientationMode(MODE_NAUTICA_COURSE_UP)
                    ));
                    adapter.addItem(new ContextMenuLayer.MenuItem(
                            app.getString(R.string.nautica_head_up),
                            R.drawable.ic_action_head_up, // Assumes icon
                            () -> setOrientationMode(MODE_NAUTICA_HEAD_UP)
                    ));
                });
            } else {
                LOGGER.warning("ContextMenuLayer not available for compass menu");
            }

            // Custom split screen button
            controlsLayer.registerCustomButton(
                    "nautica_split_screen",
                    app.getString(R.string.nautica_split_screen), // In strings.xml
                    R.drawable.ic_split, // Assumes ic_split.png
                    v -> {
                        toggleSplitScreen();
                        LOGGER.info("Split screen toggled, isSplitScreen: " + isSplitScreen);
                    }
            );
        } else {
            LOGGER.warning("MapControlsLayer not available");
        }
    }

    private void initGpxLayer(OsmandMapView mapView) {
        if (!settings.getCustomPreferenceBoolean("gpx_enabled", true)) return;
        gpxLayer = new GpxLayer(app);
        gpxLayer.setTrackColor(Color.BLUE);
        gpxLayer.setPointColor(Color.GREEN);
        gpxLayer.setTrackWidth(3);
        mapView.addLayer(gpxLayer);
        loadGpxFiles();
    }

    // Chart Handling (Q1, Q22(a))
    private void renderChart() {
        OsmandRenderer renderer = app.getRenderer();
        String nauticalPath = settings.getCustomPreferenceString(
                "chart_directory",
                app.getAppPath("nautical").getAbsolutePath()
        );
        renderer.addVectorLayer("nautical_s57", getS57Data(nauticalPath + "/s57"));
        renderer.addRasterLayer("nautical_bsb", getBSBData(nauticalPath + "/bsb"));
        renderer.addRasterLayer("nautical_mbtiles", nauticalPath + "/mbtiles/nautical.mbtiles");
        app.getResourceManager().setStorageLimit("nautical_mbtiles", 500 * 1024 * 1024);
        // S63 charts stub (Q22(a))
    }

    private Object getS57Data(String path) { return null; } // Stub
    private Object getBSBData(String path) { return null; } // Stub
    public double getDepthAtCurrentPosition() { return 0.0; } // Stub (P12)

    // MOB Handling (Q12/Q22b)
    public void addMOBWaypoint(double[] position) {
        mobWaypoint = position;
        driftPath.clear();
        driftPath.add(position);
        refreshMap();
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
        refreshMap();
    }

    public void clearMOBWaypoint() {
        mobWaypoint = null;
        driftPath.clear();
        refreshMap();
    }

    // Waypoints and SART
    public void addWaypoint(double[] position, String name) {
        try {
            if (name.startsWith("SART_")) {
                sartWaypoints.put(name, position);
            } else {
                JSONObject wp = new JSONObject()
                        .put("latitude", position[0])
                        .put("longitude", position[1])
                        .put("name", name);
                signalKClient.updateData("waypoints." + name, wp);
            }
            refreshMap();
        } catch (Exception e) {
            LOGGER.warning("Error adding waypoint: " + e.getMessage());
        }
    }

    // GPX Handling
    private void loadGpxFiles() {
        File dir = new File(app.getAppPath("nautical/gpx").getAbsolutePath());
        if (!dir.exists() && !dir.mkdirs()) {
            LOGGER.warning("Failed to create GPX directory");
            return;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".gpx"));
        if (files == null) return;
        for (File file : files) {
            GpxFile gpx = GpxFile.loadFromFile(file);
            if (gpx != null) gpxLayer.addTrack(gpx);
        }
    }

    public void importGpx(File file) {
        if (!settings.getCustomPreferenceBoolean("gpx_enabled", true) || gpxLayer == null) return;
        GpxFile gpx = GpxFile.loadFromFile(file);
        if (gpx != null) {
            gpxLayer.addTrack(gpx);
            File dest = new File(app.getAppPath("nautical/gpx/" + file.getName()).getAbsolutePath());
            if (!file.renameTo(dest)) {
                LOGGER.warning("Failed to move GPX file to " + dest.getAbsolutePath());
            }
            refreshMap();
        }
    }

    public void exportGpx(String name, List<double[]> waypoints, List<double[]> route) {
        if (!settings.getCustomPreferenceBoolean("gpx_enabled", true) || gpxLayer == null) return;
        GpxFile gpx = new GpxFile();
        for (double[] pt : waypoints) {
            gpx.addWaypoint(pt[0], pt[1], "WPT_" + System.currentTimeMillis());
        }
        if (!route.isEmpty()) {
            GpxTrack track = new GpxTrack();
            route.forEach(pt -> track.addPoint(pt[0], pt[1]));
            gpx.addTrack(track);
        }
        File file = new File(app.getAppPath("nautical/gpx/" + name + ".gpx").getAbsolutePath());
        gpx.saveToFile(file);
        gpxLayer.addTrack(gpx);
        refreshMap();
    }

    // Split Screen
    public boolean isSplitScreen() {
        return isSplitScreen;
    }

    public void toggleSplitScreen() {
        isSplitScreen = !isSplitScreen;
        ViewGroup parent = (ViewGroup) primaryMapView.getParent();
        if (isSplitScreen) {
            if (mapContainer == null) {
                mapContainer = new LinearLayout(context);
                mapContainer.setOrientation(LinearLayout.VERTICAL);
                mapContainer.setLayoutParams(new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
            }

            // Remove primaryMapView from parent
            parent.removeView(primaryMapView);

            // Add mapContainer to parent
            parent.addView(mapContainer);

            // Add primaryMapView to mapContainer
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f // Equal weight
            );
            mapContainer.addView(primaryMapView, params);

            // Create and add secondaryMapView
            secondaryMapView = new OsmandMapView(app);
            secondaryMapView.setMapLayer(this);
            mapContainer.addView(secondaryMapView, params);

            if (settings.getCustomPreferenceBoolean("split_screen_sync", false)) {
                syncMapViews();
            }
        } else {
            // Remove mapContainer and restore primaryMapView
            if (mapContainer != null) {
                mapContainer.removeView(primaryMapView);
                mapContainer.removeView(secondaryMapView);
                parent.removeView(mapContainer);
                parent.addView(primaryMapView);
                secondaryMapView = null;
                mapContainer = null;
            }
        }
        primaryMapView.refreshMap();
        if (secondaryMapView != null) {
            secondaryMapView.refreshMap();
        }
    }

    private void syncMapViews() {
        if (secondaryMapView == null) return;
        secondaryMapView.setZoom(primaryMapView.getZoom());
        secondaryMapView.setMapPosition(primaryMapView.getLatitude(), primaryMapView.getLongitude());
        primaryMapView.addMapChangeListener((lat, lon, zoom) -> {
            if (settings.getCustomPreferenceBoolean("split_screen_sync", false)) {
                secondaryMapView.setZoom(zoom);
                secondaryMapView.setMapPosition(lat, lon);
            }
        });
    }

    // Orientation
    private void setOrientationMode(int mode) {
        orientationMode = mode;
        updateOrientation();
        LOGGER.info("Orientation mode set to: " + mode);
    }

    public void updateOrientation() {
        OsmandMapView mapView = primaryMapView;
        switch (orientationMode) {
            case MODE_OSMAND_NORTH_UP: // OsmAnd North-up
                mapView.setMapOrientation(0f);
                settings.setCustomPreferenceBoolean("head_up_enabled", false);
                settings.setCustomPreferenceBoolean("course_up_enabled", false);
                break;
            case MODE_OSMAND_DIRECTION: // OsmAnd Direction of movement
                mapView.setMapOrientationToMovement();
                settings.setCustomPreferenceBoolean("head_up_enabled", false);
                settings.setCustomPreferenceBoolean("course_up_enabled", false);
                break;
            case MODE_NAUTICA_COURSE_UP: // Nautica Course-up
                JSONObject course = signalKClient.getCachedData("navigation.courseOverGroundTrue");
                if (course != null) {
                    mapView.setMapOrientation((float) course.optDouble("value", 0.0));
                }
                settings.setCustomPreferenceBoolean("head_up_enabled", false);
                settings.setCustomPreferenceBoolean("course_up_enabled", true);
                break;
            case MODE_NAUTICA_HEAD_UP: // Nautica Head-up
                JSONObject heading = signalKClient.getCachedData("navigation.headingTrue");
                if (heading != null) {
                    mapView.setMapOrientation((float) heading.optDouble("value", 0.0));
                }
                settings.setCustomPreferenceBoolean("head_up_enabled", true);
                settings.setCustomPreferenceBoolean("course_up_enabled", false);
                break;
        }
        if (isSplitScreen && secondaryMapView != null) {
            secondaryMapView.setMapOrientation(mapView.getMapOrientation());
            secondaryMapView.refreshMap();
        }
        mapView.refreshMap();
    }

    // Route Creation
    public void createRoute(List<double[]> waypoints, String routeType, double maxLat) {
        if (!settings.getCustomPreferenceBoolean("route_enabled", true) || waypoints.size() < 2) return;
        List<double[]> routePoints = new ArrayList<>();
        for (int i = 0; i < waypoints.size() - 1; i++) {
            double[] start = waypoints.get(i);
            double[] end = waypoints.get(i + 1);
            List<double[]> segmentPoints = switch (routeType) {
                case "great_circle" -> calculateGreatCirclePoints(start, end);
                case "limited_circle" -> calculateLimitedCirclePoints(start, end, maxLat);
                default -> calculateRhumbPoints(start, end);
            };
            routePoints.addAll(adjustForWaveHeight(segmentPoints));
        }
        routePoints.add(waypoints.get(waypoints.size() - 1));
        saveRoute(routePoints, "route_" + System.currentTimeMillis());
    }

    private void saveRoute(List<double[]> routePoints, String routeName) {
        try {
            JSONObject route = new JSONObject();
            JSONArray points = new JSONArray();
            for (double[] pt : routePoints) {
                points.put(new JSONObject().put("latitude", pt[0]).put("longitude", pt[1]));
            }
            route.put("points", points);
            signalKClient.updateData("navigation.route", route);
            exportGpx(routeName, new ArrayList<>(), routePoints);
        } catch (Exception e) {
            LOGGER.warning("Error saving route: " + e.getMessage());
        }
    }

    private List<double[]> calculateGreatCirclePoints(double[] start, double[] end) {
        List<double[]> points = new ArrayList<>();
        double lat1 = Math.toRadians(start[0]);
        double lon1 = Math.toRadians(start[1]);
        double lat2 = Math.toRadians(end[0]);
        double lon2 = Math.toRadians(end[1]);
        double d = calculateGreatCircleDistance(start, end);
        int segments = (int) (d / 0.01745); // ~10nm/segment
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
        return List.of(start, end);
    }

    private List<double[]> adjustForWaveHeight(List<double[]> points) {
        List<double[]> adjusted = new ArrayList<>();
        for (double[] pt : points) {
            double wave = weatherManager.getWaveHeight(pt[0], pt[1]);
            adjusted.add(wave > 3.0 ? new double[]{pt[0] + 0.1, pt[1] + 0.1} : pt);
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

    // AIS Handling (P9)
    private void updateAisTargets() {
        JSONObject data = signalKClient.getCachedData("ais.targets");
        if (data != null) {
            data.keys().forEachRemaining(key -> aisTargets.put(key, data.optJSONObject(key)));
        }
    }

    // Rendering
    @Override
    public void onDraw(Canvas canvas, RectF latLonBounds, RectF tilesRect, DrawSettings drawSettings) {
        weatherManager.drawWeatherOverlay(canvas, latLonBounds);
        drawMOB(canvas);
        drawSART(canvas, latLonBounds);
        drawAIS(canvas, latLonBounds);
    }

    private void drawMOB(Canvas canvas) {
        if (mobWaypoint == null || driftPath.isEmpty()) return;
        OsmandMapView mapView = primaryMapView;
        float[] startPx = mapView.getPointFromLatLon(mobWaypoint[0], mobWaypoint[1]);
        canvas.drawCircle(startPx[0], startPx[1], 10, driftPaint);
        float[] prevPx = startPx;
        for (double[] pt : driftPath) {
            float[] px = mapView.getPointFromLatLon(pt[0], pt[1]);
            canvas.drawLine(prevPx[0], prevPx[1], px[0], px[1], driftPaint);
            prevPx = px;
        }
    }

    private void drawSART(Canvas canvas, RectF bounds) {
        OsmandMapView mapView = primaryMapView;
        for (Map.Entry<String, double[]> e : sartWaypoints.entrySet()) {
            double[] pos = e.getValue();
            if (bounds.contains((float) pos[1], (float) pos[0])) {
                float[] px = mapView.getPointFromLatLon(pos[0], pos[1]);
                canvas.drawCircle(px[0], px[1], 8, sartPaint);
            }
        }
    }

    private void drawAIS(Canvas canvas, RectF bounds) {
        if (!settings.getCustomPreferenceBoolean("ais_enabled", true)) return;
        updateAisTargets();
        OsmandMapView mapView = primaryMapView;
        int maxTargets = settings.getCustomPreferenceInt("ais_max_targets", 100);
        int count = 0;
        for (Map.Entry<String, JSONObject> e : aisTargets.entrySet()) {
            if (count++ >= maxTargets) break;
            JSONObject target = e.getValue();
            JSONObject pos = target.optJSONObject("navigation") != null
                    ? target.optJSONObject("navigation").optJSONObject("position")
                    : null;
            if (pos == null) continue;
            double lat = pos.optDouble("latitude", 0);
            double lon = pos.optDouble("longitude", 0);
            float[] px = mapView.getPointFromLatLon(lat, lon);
            canvas.drawCircle(px[0], px[1], 6, aisPaint);
            canvas.drawText(target.optString("name", e.getKey()), px[0] + 8, px[1], labelPaint);
        }
    }

    // AIS Popup (P9)
    public void showAISTargetPopup(double latitude, double longitude) {
        app.showToastMessage(String.format("AIS Target at %.4f, %.4f", latitude, longitude));
    }

    private void refreshMap() {
        primaryMapView.refreshMap();
        if (isSplitScreen && secondaryMapView != null) {
            secondaryMapView.refreshMap();
        }
    }
}
