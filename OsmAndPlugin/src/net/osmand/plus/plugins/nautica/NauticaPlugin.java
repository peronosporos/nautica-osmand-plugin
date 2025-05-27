package net.osmand.plus.plugins.nautica;

import android.content.Context;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.activities.actions.AppActionAdapter;
import net.osmand.plus.activities.actions.ApplicationModeActions;
import net.osmand.plus.activities.actions.FavoritesAction;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.OsmandPlugin.OnOsmandReadyListener;

public class NauticaPlugin extends OsmandPlugin implements OnOsmandReadyListener {

    private static NauticaPlugin instance;

    public static NauticaPlugin getInstance() {
        return instance;
    }

    public NauticaPlugin() {
        instance = this;
    }

    @Override
    public void onOsmandReady(OsmandApplication app) {
        // Called when OsmAnd is ready
    }

    @Override
    public void onMapActivityCreated(MapActivity mapActivity) {
        // You can use this to inject UI components later
    }

    @Override
    public String getId() {
        return "nautica";
    }

    @Override
    public String getName() {
        return "Nautica Plugin";
    }

    @Override
    public String getDescription() {
        return "Marine navigation plugin for OsmAnd";
    }

    @Override
    public boolean isEnabledByDefault() {
        return true;
    }
}
