package su.geocaching.android.ui.searchmap;

import java.util.List;
import java.util.Locale;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.TextView;
import android.widget.Toast;
import com.google.android.maps.GeoPoint;
import com.google.android.maps.MapActivity;
import com.google.android.maps.MapController;
import com.google.android.maps.MapView;
import com.google.android.maps.Overlay;
import com.google.android.maps.Projection;
import su.geocaching.android.controller.Controller;
import su.geocaching.android.controller.managers.*;
import su.geocaching.android.controller.utils.CoordinateHelper;
import su.geocaching.android.controller.GpsUpdateFrequency;
import su.geocaching.android.controller.apimanager.GeocachingSuApiManager;
import su.geocaching.android.controller.compass.SmoothCompassThread;
import su.geocaching.android.controller.utils.UiHelper;
import su.geocaching.android.model.GeoCache;
import su.geocaching.android.model.GeoCacheStatus;
import su.geocaching.android.model.GeoCacheType;
import su.geocaching.android.model.MapInfo;
import su.geocaching.android.model.SearchMapInfo;
import su.geocaching.android.ui.FavoritesFolderActivity;
import su.geocaching.android.ui.ProgressBarView;
import su.geocaching.android.ui.R;
import su.geocaching.android.ui.geocachemap.GeoCacheOverlayItem;
import su.geocaching.android.ui.preferences.DashboardPreferenceActivity;

/**
 * Search GeoCache with the map
 *
 * @author Android-Geocaching.su student project team
 * @since October 2010
 */
public class SearchMapActivity extends MapActivity implements IConnectionAware, ILocationAware, android.os.Handler.Callback {
    private final static String TAG = SearchMapActivity.class.getCanonicalName();
    private final static float CLOSE_DISTANCE_TO_GC_VALUE = 100; // if we nearly than this distance in meters to geocache - gps will be work maximal often
    private final static String SEARCH_MAP_ACTIVITY_FOLDER = "/SearchMapActivity";

    private static final int DIALOG_ID_TURN_ON_GPS = 1000;

    private CheckpointOverlay checkpointOverlay;
    private SearchGeoCacheOverlay searchGeoCacheOverlay;
    private Drawable cacheMarker;
    private DistanceToGeoCacheOverlay distanceOverlay;
    private DynamicUserLocationOverlay userOverlay;
    private MapView map;
    private MapController mapController;
    private List<Overlay> mapOverlays;

    private TextView gpsStatusTextView;
    private TextView distanceStatusTextView;
    private ProgressBarView progressBarView;
    private Toast providerUnavailableToast;
    private Toast connectionLostToast;

    private SmoothCompassThread animationThread;
    private CheckpointManager checkpointManager;

    // handler associated with this activity
    private Handler handler;

    /*
     * (non-Javadoc)
     *
     * @see com.google.android.maps.MapActivity#onCreate(android.os.Bundle)
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LogManager.d(TAG, "onCreate");
        setContentView(R.layout.search_map_activity);

        providerUnavailableToast = Toast.makeText(this, getString(R.string.search_geocache_best_provider_lost), Toast.LENGTH_LONG);
        connectionLostToast = Toast.makeText(this, getString(R.string.map_internet_lost), Toast.LENGTH_LONG);

        gpsStatusTextView = (TextView) findViewById(R.id.waitingLocationFixText);
        distanceStatusTextView = (TextView) findViewById(R.id.distanceToCacheText);
        progressBarView = (ProgressBarView) findViewById(R.id.progressCircle);
        progressBarView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                NavigationManager.startExternalGpsStatusActivity(v.getContext());
            }
        });

        map = (MapView) findViewById(R.id.searchGeocacheMap);
        mapOverlays = map.getOverlays();
        mapController = map.getController();
        userOverlay = new DynamicUserLocationOverlay(this, map);
        map.setBuiltInZoomControls(true);
        GeoCache geoCache = (GeoCache) getIntent().getParcelableExtra(GeoCache.class.getCanonicalName());

        Controller.getInstance().setSearchingGeoCache(geoCache);
        Controller.getInstance().getPreferencesManager().setLastSearchedGeoCache(geoCache);
        Controller.getInstance().getGoogleAnalyticsManager().trackActivityLaunch(SEARCH_MAP_ACTIVITY_FOLDER);

        checkpointManager = Controller.getInstance().getCheckpointManager(geoCache.getId());
        checkpointOverlay = new CheckpointOverlay(Controller.getInstance().getResourceManager().getCacheMarker(GeoCacheType.CHECKPOINT, GeoCacheStatus.NOT_ACTIVE_CHECKPOINT), this, map);
        for (GeoCache checkpoint : checkpointManager.getCheckpoints()) {
            checkpointOverlay.addOverlayItem(new GeoCacheOverlayItem(checkpoint, "", ""));
            if (checkpoint.getStatus() == GeoCacheStatus.ACTIVE_CHECKPOINT) {
                Controller.getInstance().setSearchingGeoCache(checkpoint);
            }
        }
        mapOverlays.add(checkpointOverlay);
        handler = new Handler(this);
    }

    /*
     * (non-Javadoc)
     *
     * @see com.google.android.maps.MapActivity#onPause()
     */
    @Override
    protected void onPause() {
        super.onPause();
        LogManager.d(TAG, "onPause");
        saveMapInfoToSettings();

        if (Controller.getInstance().getLocationManager().hasLocation()) {
            stopAnimation();
        }

        Controller.getInstance().getConnectionManager().removeSubscriber(this);
        Controller.getInstance().getLocationManager().removeSubscriber(this);
        Controller.getInstance().getCallbackManager().removeSubscriber(handler);
        providerUnavailableToast.cancel();
        connectionLostToast.cancel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        LogManager.d(TAG, "onResume");
        GeoCache geoCache = (GeoCache) getIntent().getParcelableExtra(GeoCache.class.getCanonicalName());

        if (geoCache == null) {
            LogManager.e(TAG, "Geocache is null. Finishing.");
            Toast.makeText(this, getString(R.string.search_geocache_error_no_geocache), Toast.LENGTH_LONG).show();
            this.finish();
            return;
        }

        if (!Controller.getInstance().getDbManager().isCacheStored(geoCache.getId())) {
            LogManager.e(TAG, "Geocache is not in found in database. Finishing.");
            Toast.makeText(this, getString(R.string.search_geocache_error_geocache_not_in_db), Toast.LENGTH_LONG).show();
            this.finish();
            startActivity(new Intent(this, FavoritesFolderActivity.class));
            return;
        }

        if (distanceOverlay != null) {
            distanceOverlay.setCachePoint(Controller.getInstance().getSearchingGeoCache().getLocationGeoPoint());
        }

        checkpointManager = Controller.getInstance().getCheckpointManager(Controller.getInstance().getPreferencesManager().getLastSearchedGeoCache().getId());
        checkpointOverlay.clear();
        for (GeoCache checkpoint : checkpointManager.getCheckpoints()) {
            checkpointOverlay.addOverlayItem(new GeoCacheOverlayItem(checkpoint, "", ""));
        }

        map.setKeepScreenOn(Controller.getInstance().getPreferencesManager().getKeepScreenOnPreference());
        updateMapInfoFromSettings();
        map.setSatellite(Controller.getInstance().getPreferencesManager().useSatelliteMap());

        mapOverlays.remove(searchGeoCacheOverlay);
        cacheMarker = Controller.getInstance().getResourceManager().getCacheMarker(geoCache.getType(), geoCache.getStatus());
        searchGeoCacheOverlay = new SearchGeoCacheOverlay(cacheMarker, this, map);
        GeoCacheOverlayItem cacheOverlayItem = new GeoCacheOverlayItem(geoCache, "", "");
        searchGeoCacheOverlay.addOverlayItem(cacheOverlayItem);
        mapOverlays.add(searchGeoCacheOverlay);

        if (Controller.getInstance().getLocationManager().hasLocation()) {
            LogManager.d(TAG, "Update location with last known location");
            // this update will hide progressBarView
            updateLocation(Controller.getInstance().getLocationManager().getLastKnownLocation());
            startAnimation();
        }

        if (!Controller.getInstance().getLocationManager().isBestProviderEnabled()) {
            showDialog(DIALOG_ID_TURN_ON_GPS);
            progressBarView.hide();
            UiHelper.setGone(gpsStatusTextView);
            LogManager.d(TAG, "resume: best provider %s disabled. Current provider is %s",
                    Controller.getInstance().getLocationManager().getBestProvider(false),
                    Controller.getInstance().getLocationManager().getCurrentProvider());
        } else {
            if (Controller.getInstance().getLocationManager().hasPreciseLocation()) {
                progressBarView.hide();
            } else {
                gpsStatusTextView.setText(R.string.gps_status_initialization);
                progressBarView.show();
            }
        }

        Controller.getInstance().getLocationManager().addSubscriber(this);
        Controller.getInstance().getLocationManager().enableBestProviderUpdates(false);
        Controller.getInstance().getConnectionManager().addSubscriber(this);
        Controller.getInstance().getCallbackManager().addSubscriber(handler);
        map.invalidate();

        if (!Controller.getInstance().getConnectionManager().isActiveNetworkConnected()) {
            onConnectionLost();
            LogManager.w(TAG, "internet not connected");
        }
    }

    /*
     * (non-Javadoc)
     *
     * @see su.geocaching.android.controller.ILocationAware#updateLocation(android.location.Location)
     */
    @Override
    public void updateLocation(Location location) {
        userOverlay.updateLocation(location);
        LogManager.d(TAG, "update location");
        progressBarView.hide();
        if (CoordinateHelper.getDistanceBetween(location, Controller.getInstance().getSearchingGeoCache().getLocationGeoPoint()) < CLOSE_DISTANCE_TO_GC_VALUE) {
            Controller.getInstance().getLocationManager().updateFrequency(GpsUpdateFrequency.MAXIMAL);
        } else {
            Controller.getInstance().getLocationManager().updateFrequencyFromPreferences();
        }
        boolean isPrecise = Controller.getInstance().getLocationManager().hasPreciseLocation();
        distanceStatusTextView.setText(
                CoordinateHelper.distanceToString(
                        CoordinateHelper.getDistanceBetween(Controller.getInstance().getSearchingGeoCache().getLocationGeoPoint(), location),
                        isPrecise));
        userOverlay.setLocationPrecise(isPrecise);
        if (distanceOverlay == null) {
            LogManager.d(TAG, "update location: add distance and user overlays");
            distanceOverlay = new DistanceToGeoCacheOverlay(CoordinateHelper.locationToGeoPoint(location), Controller.getInstance().getSearchingGeoCache().getLocationGeoPoint());
            mapOverlays.add(0, distanceOverlay); // lower overlay
            mapOverlays.add(userOverlay);

            startAnimation();
            return;
        }
        distanceOverlay.setUserPoint(CoordinateHelper.locationToGeoPoint(location));

        map.invalidate();
    }

    /**
     * Set map zoom which can show userPoint and GeoCachePoint
     */
    private void resetZoom() {
        // Calculate min/max latitude & longitude
        int minLat = Integer.MAX_VALUE;
        int maxLat = Integer.MIN_VALUE;
        int minLon = Integer.MAX_VALUE;
        int maxLon = Integer.MIN_VALUE;
        final GeoCache gc = (GeoCache) getIntent().getParcelableExtra(GeoCache.class.getCanonicalName());

        final Location location = Controller.getInstance().getLocationManager().getLastKnownLocation();
        final GeoPoint locationPoint = CoordinateHelper.locationToGeoPoint(location);
        if (location != null) {
            minLat = locationPoint.getLatitudeE6();
            maxLat = locationPoint.getLatitudeE6();
            minLon = locationPoint.getLongitudeE6();
            maxLon = locationPoint.getLongitudeE6();
        }
        minLat = Math.min(gc.getLocationGeoPoint().getLatitudeE6(), minLat);
        maxLat = Math.max(gc.getLocationGeoPoint().getLatitudeE6(), maxLat);
        minLon = Math.min(gc.getLocationGeoPoint().getLongitudeE6(), minLon);
        maxLon = Math.max(gc.getLocationGeoPoint().getLongitudeE6(), maxLon);

        for (GeoCache checkpoint : Controller.getInstance().getCheckpointManager(gc.getId()).getCheckpoints()) {
            minLat = Math.min(checkpoint.getLocationGeoPoint().getLatitudeE6(), minLat);
            maxLat = Math.max(checkpoint.getLocationGeoPoint().getLatitudeE6(), maxLat);
            minLon = Math.min(checkpoint.getLocationGeoPoint().getLongitudeE6(), minLon);
            maxLon = Math.max(checkpoint.getLocationGeoPoint().getLongitudeE6(), maxLon);
        }

        // Calculate span
        int latSpan = maxLat - minLat;
        int lonSpan = maxLon - minLon;

        // Set zoom
        if (latSpan != 0 && lonSpan != 0) {
            mapController.zoomToSpan(latSpan, lonSpan);
        }

        // Calculate new center of map
        GeoPoint center = new GeoPoint((minLat + maxLat) / 2, (minLon + maxLon) / 2);

        // Set new center of map
        mapController.setCenter(center);

        // if markers not in map - zoom out. logic below
        boolean needZoomOut = false;

        if (location != null) {
            // is user marker visible
            GeoPoint currentGeoPoint = CoordinateHelper.locationToGeoPoint(Controller.getInstance().getLocationManager().getLastKnownLocation());
            needZoomOut = !mapContains(currentGeoPoint, userOverlay.getBounds());
        }

        if (!needZoomOut) {
            // still not need zoom out
            // Check contains markers in visible part of map
            needZoomOut = !mapContains(gc.getLocationGeoPoint(), cacheMarker.getBounds());
        }

        // check contains checkpoints markers in visible part of map if still not need zoom out
        if (!needZoomOut) {
            Rect bounds = Controller.getInstance().getResourceManager().getCacheMarker(GeoCacheType.CHECKPOINT, GeoCacheStatus.NOT_ACTIVE_CHECKPOINT).getBounds();

            for (GeoCache checkpoint : Controller.getInstance().getCheckpointManager(gc.getId()).getCheckpoints()) {
                if (needZoomOut = !mapContains(checkpoint.getLocationGeoPoint(), bounds)) {
                    break;
                }
            }
        }

        // if markers are not visible then zoomOut
        if (needZoomOut) {
            LogManager.d(TAG, "markers not in the visible part of map. Zoom out.");
            mapController.zoomOut();
        }
    }

    private boolean mapContains(GeoPoint center, Rect bounds) {
        final Point point = new Point();
        map.getProjection().toPixels(center, point);
        return (point.x + bounds.left > 0) && (point.x + bounds.right < map.getWidth()) &&
                (point.y + bounds.top > 0) && (point.y + bounds.bottom < map.getHeight());
    }

    /**
     * Creating menu object
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.search_map_menu, menu);
        return true;
    }

    /**
     * Called when menu element selected
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.menuDefaultZoom:
                resetZoom();
                return true;
            case R.id.menuStartCompass:
                NavigationManager.startCompassActivity(this);
                return true;
            case R.id.menuGeoCacheInfo:
                NavigationManager.startInfoActivity(this, Controller.getInstance().getPreferencesManager().getLastSearchedGeoCache());
                return true;
            case R.id.driving_directions:
                onDrivingDirectionsSelected();
                return true;
            case R.id.show_external_map:
                showExternalMap();
                return true;
            case R.id.stepByStep:
                NavigationManager.startCheckpointsFolder(this, Controller.getInstance().getPreferencesManager().getLastSearchedGeoCache().getId());
                return true;
            case R.id.searchMapSettings:
                startActivity(new Intent(this, DashboardPreferenceActivity.class));
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    private void onDrivingDirectionsSelected() {
        final Location location = Controller.getInstance().getLocationManager().getLastKnownLocation();
        if (location != null) {
            final GeoPoint destination = Controller.getInstance().getSearchingGeoCache().getLocationGeoPoint();
            final double sourceLat = location.getLatitude();
            final double sourceLng = location.getLongitude();
            final double destinationLat = destination.getLatitudeE6() / 1E6;
            final double destinationLng = destination.getLongitudeE6() / 1E6;
            final String uri = String.format(
                    Locale.ENGLISH,
                    "http://maps.google.com/maps?saddr=%f,%f&daddr=%f,%f&ie=UTF8&om=0&output=kml",
                    sourceLat, sourceLng, destinationLat, destinationLng);

            final Intent intent = new Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri));
            startActivity(intent);
        } else {
            Toast.makeText(getBaseContext(), getString(R.string.status_null_last_location), Toast.LENGTH_LONG).show();
        }
    }

    // TODO: implement menu item
    // Currently only google maps can handle this intent correctly, so it's kind of the same map
    private void showCacheOnExternalMap() {
        final GeoCache geoCache = Controller.getInstance().getSearchingGeoCache();
        final GeoPoint geoCachePoint = geoCache.getLocationGeoPoint();
        final double geoCacheLatitude = geoCachePoint.getLatitudeE6() / 1E6;
        final double geoCacheLongitude = geoCachePoint.getLongitudeE6() / 1E6;
        final String uri = String.format(Locale.ENGLISH, "geo:0,0?q=%f,%f (%s)", geoCacheLatitude, geoCacheLongitude, geoCache.getName());
        startActivity(new Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri)));
    }

    private void showExternalMap() {
        final double latitude = map.getMapCenter().getLatitudeE6() / 1E6;
        final double longitude = map.getMapCenter().getLongitudeE6() / 1E6;
        final String uri = String.format(Locale.ENGLISH, "geo:%f,%f?z=%d", latitude, longitude, map.getZoomLevel());
        startActivity(new Intent(android.content.Intent.ACTION_VIEW, Uri.parse(uri)));
    }

    /*
     * (non-Javadoc)
     *
     * @see com.google.android.maps.MapActivity#isRouteDisplayed()
     */
    @Override
    protected boolean isRouteDisplayed() {
        return false;
    }

    /*
     * (non-Javadoc)
     *
     * @see su.geocaching.android.ui.geocachemap.IConnectionAware#onConnectionLost()
     */
    @Override
    public void onConnectionLost() {
        connectionLostToast.show();
    }

    /*
     * (non-Javadoc)
     *
     * @see su.geocaching.android.controller.ILocationAware#onStatusChanged(java.lang.String, int, android.os.Bundle)
     */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        switch (status) {
            case UserLocationManager.GPS_EVENT_SATELLITE_STATUS:
                // just update status
                gpsStatusTextView.setText(Controller.getInstance().getLocationManager().getSatellitesStatusString());
                break;
            case UserLocationManager.OUT_OF_SERVICE:
                // provider unavailable
                progressBarView.show();
                gpsStatusTextView.setText(R.string.gps_status_unavailable);
                providerUnavailableToast.show();
                break;
            case UserLocationManager.TEMPORARILY_UNAVAILABLE:
                // gps connection lost. just show progress bar
                progressBarView.show();
                break;
            case UserLocationManager.EVENT_PROVIDER_DISABLED:
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    // gps has been turned off
                    showDialog(DIALOG_ID_TURN_ON_GPS);
                    progressBarView.hide();
                    UiHelper.setGone(gpsStatusTextView);
                }
                break;
            case UserLocationManager.EVENT_PROVIDER_ENABLED:
                if (LocationManager.GPS_PROVIDER.equals(provider)) {
                    // gps has been turned on
                    dismissDialog(DIALOG_ID_TURN_ON_GPS);
                    progressBarView.show();
                    UiHelper.setVisible(gpsStatusTextView);
                }
                break;
        }
    }

    /**
     * run animation for user location overlay
     */
    private void startAnimation() {
        if (animationThread == null) {
            animationThread = new SmoothCompassThread(userOverlay);
            animationThread.setRunning(true);
            animationThread.start();
        }
    }

    /**
     * Stop animation for user location overlay
     */
    private void stopAnimation() {
        if (animationThread != null) {
            animationThread.setRunning(false);
            try {
                animationThread.join(150);
            } catch (InterruptedException ignored) {
            }
            animationThread = null;
        }
    }

    public void onHomeClick(View v) {
        NavigationManager.startDashboardActivity(this);
    }

    /**
     * Set map center and zoom level from last using search geocache map
     */
    private void updateMapInfoFromSettings() {
        SearchMapInfo lastMapInfo = Controller.getInstance().getPreferencesManager().getLastSearchMapInfo();
        GeoCache geoCache = (GeoCache) getIntent().getParcelableExtra(GeoCache.class.getCanonicalName());
        if (lastMapInfo.getGeoCacheId() != geoCache.getId()) {
            resetZoom();
        } else {
            updateMap(lastMapInfo);
        }
    }

    private void updateMap(SearchMapInfo lastMapInfo) {
        GeoPoint lastCenter = new GeoPoint(lastMapInfo.getCenterX(), lastMapInfo.getCenterY());
        mapController.setCenter(lastCenter);
        mapController.setZoom(lastMapInfo.getZoom());
        map.invalidate();
    }

    /**
     * Save map center and zoom level to shared preferences
     */
    private void saveMapInfoToSettings() {
        SearchMapInfo searchMapInfo = getMapInfo();
        Controller.getInstance().getPreferencesManager().setLastSearchMapInfo(searchMapInfo);
    }

    private SearchMapInfo getMapInfo() {
        int centerX = map.getMapCenter().getLatitudeE6();
        int centerY = map.getMapCenter().getLongitudeE6();
        int zoom = map.getZoomLevel();
        int geocacheId = ((GeoCache) getIntent().getParcelableExtra(GeoCache.class.getCanonicalName())).getId();
        return new SearchMapInfo(centerX, centerY, zoom, geocacheId);
    }

    @Override
    public void onConnectionFound() {
        connectionLostToast.cancel();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putSerializable(SearchMapInfo.class.getCanonicalName(), getMapInfo());
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        SearchMapInfo mapInfo = (SearchMapInfo) savedInstanceState.getSerializable(SearchMapInfo.class.getCanonicalName());
        if (mapInfo != null) {
            updateMap(mapInfo);
        }
        super.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case CallbackManager.WHAT_LOCATION_DEPRECATED:
                // update distance text view
                final boolean isPrecise = Controller.getInstance().getLocationManager().hasPreciseLocation();
                if (distanceStatusTextView != null) {
                    distanceStatusTextView.setText(
                            CoordinateHelper.distanceToString(
                                    CoordinateHelper.getDistanceBetween(
                                            Controller.getInstance().getSearchingGeoCache().getLocationGeoPoint(),
                                            Controller.getInstance().getLocationManager().getLastKnownLocation()),
                                    isPrecise));
                }
                if (userOverlay != null) {
                    userOverlay.setLocationPrecise(isPrecise);
                }
                return true;
        }
        return false;
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_ID_TURN_ON_GPS:
                return NavigationManager.createTurnOnGpsDialog(this);
        }
        return super.onCreateDialog(id);
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        switch (id) {
            case DIALOG_ID_TURN_ON_GPS:
                Controller.getInstance().getGoogleAnalyticsManager().trackActivityLaunch("/EnableGpsDialog");
                break;
        }
        super.onPrepareDialog(id, dialog);
    }

    /**
     * Called when 'my location' image button was clicked
     *
     * @param v
     */
    public void onMyLocationClick(View v) {
        Location lastLocation = Controller.getInstance().getLocationManager().getLastKnownLocation();
        if (lastLocation != null) {
            GeoPoint center = CoordinateHelper.locationToGeoPoint(lastLocation);
            map.getController().animateTo(center);
            map.invalidate();
        } else {
            Toast.makeText(getBaseContext(), getString(R.string.status_null_last_location), Toast.LENGTH_SHORT).show();
        }
    }
}