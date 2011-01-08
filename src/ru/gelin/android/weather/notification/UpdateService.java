package ru.gelin.android.weather.notification;

import static ru.gelin.android.weather.notification.Tag.TAG;

import java.io.IOException;
import java.util.List;

import ru.gelin.android.weather.Location;
import ru.gelin.android.weather.SimpleLocation;
import ru.gelin.android.weather.Weather;
import ru.gelin.android.weather.WeatherSource;
import ru.gelin.android.weather.google.AndroidGoogleLocation;
import ru.gelin.android.weather.google.GoogleWeatherSource;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.Address;
import android.location.Geocoder;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

/**
 *  Service to update weather.
 *  Just start it. The new weather values will be wrote to SharedPreferences
 *  (use {@link WeatherStorage} to extract them).
 */
public class UpdateService extends Service implements Runnable {

    /** Auto location preferences key */
    static final String AUTO_LOCATION = "auto_location";
    /** Manual location preferences key */
    static final String LOCATION = "location";
    /** Verbose extra name for the service start intent. */
    public static String EXTRA_VERBOSE = "verbose";
    /** Success update message */
    static final int SUCCESS = 0;
    /** Failure update message */
    static final int FAILURE = 1;
    /** Update message when location is unknown */
    static final int UNKNOWN_LOCATION = 2;
    
    /**
     *  Lock used when maintaining update thread.
     */
    private static Object staticLock = new Object();
    /**
     *  Flag if there is an update thread already running. We only launch a new
     *  thread if one isn't already running.
     */
    static boolean threadRunning = false;
    
    /** Verbose flag */
    boolean verbose = false;
    /** Queried location */
    Location location;
    /** Updated weather */
    Weather weather;
    /** Weather update error */
    Exception updateError;
    
    /**
     *  Starts the service. Convenience method.
     */
    public static void start(Context context) {
        start(context, false);
    }
    
    /**
     *  Starts the service. Convenience method.
     *  If the verbose is true, the update errors will be displayed as toasts. 
     */
    public static void start(Context context, boolean verbose) {
        Intent startIntent = new Intent(context, UpdateService.class);
        startIntent.putExtra(EXTRA_VERBOSE, verbose);
        context.startService(startIntent);
    }
    
    @Override
    public void onStart(Intent intent, int startId) {
        super.onStart(intent, startId);
        
        synchronized(this) {
            this.verbose = intent.getBooleanExtra(EXTRA_VERBOSE, false);
        }

        WeatherStorage storage = new WeatherStorage(UpdateService.this);
        
        synchronized(staticLock) {
            if (threadRunning) {
                return;     // only start processing thread if not already running
            }
            if (!isNetworkAvailable()) {    //no network
                stopSelf();
                Log.d(TAG, "skipping update, no network");
                storage.updateTime();
                if (verbose) {
                    Toast.makeText(UpdateService.this, 
                            getString(R.string.weather_update_no_network), 
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
            //TODO: insert conditions to skip update
            if (!threadRunning) {
                threadRunning = true;
                new Thread(this).start();
            }
        }
    }
    
    @Override
    public void run() {
        SharedPreferences preferences =
            PreferenceManager.getDefaultSharedPreferences(this);
        
        boolean autoLocation = preferences.getBoolean(AUTO_LOCATION, true);
        Location location = null;
        if (autoLocation) {
            location = queryLocation();
        } else {
            String manualLocation = preferences.getString(LOCATION, "");
            if (manualLocation.length() > 0) {
                location = new SimpleLocation(manualLocation);
            }
        }
        synchronized(this) {
            this.location = location;
        }
        
        if (location == null) {
            internalHandler.sendEmptyMessage(UNKNOWN_LOCATION);
            return;
        }
 
        WeatherSource source = new GoogleWeatherSource();
        try {
            Weather weather = source.query(location);
            synchronized(this) {
                this.weather = weather;
            }
            internalHandler.sendEmptyMessage(SUCCESS);
        } catch (Exception e) {
            synchronized(this) {
                this.updateError = e;
            }
            internalHandler.sendEmptyMessage(FAILURE);
        }
    }
    
    /**
     *  Handles weather update result.
     */
    final Handler internalHandler = new Handler() {
        public void handleMessage(Message msg) {
            synchronized(staticLock) {
                threadRunning = false;
            }
            
            WeatherStorage storage = new WeatherStorage(UpdateService.this);
            switch (msg.what) {
            case SUCCESS:
                synchronized(UpdateService.this) {
                    Log.i(TAG, "received weather: " + 
                            weather.getLocation().getText() + " " + weather.getTime());
                    storage.save(weather);
                    if (verbose && weather.isEmpty()) {
                        Toast.makeText(UpdateService.this, 
                                getString(R.string.weather_update_empty, location.getText()), 
                                Toast.LENGTH_LONG).show();
                    }
                }
                break;
            case FAILURE:
                synchronized(UpdateService.this) {
                    Log.w(TAG, "failed to update weather", updateError);
                    storage.updateTime();
                    if (verbose) {
                        Toast.makeText(UpdateService.this, 
                                getString(R.string.weather_update_failed, updateError.getMessage()), 
                                Toast.LENGTH_LONG).show();
                    }
                }
                break;
            case UNKNOWN_LOCATION:
                synchronized(UpdateService.this) {
                    Log.w(TAG, "failed to get location");
                    storage.updateTime();
                    if (verbose) {
                        Toast.makeText(UpdateService.this, 
                                getString(R.string.weather_update_unknown_location), 
                                Toast.LENGTH_LONG).show();
                    }
                }
                break;
            }
            stopSelf();
        }
    };
    
    /**
     *  Check availability of network connections.
     *  Returns true if any network connection is available.
     */
    boolean isNetworkAvailable() {
        ConnectivityManager manager = 
            (ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = manager.getActiveNetworkInfo();
        if (info == null) {
            return false;
        }
        return info.isAvailable();
    }
    
    /**
     *  Queries current location using android services.
     */
    Location queryLocation() {
        LocationManager manager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        android.location.Location androidLocation = 
                manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
        //TODO: query location if last location is unknown or too old (check update interval)
        if (androidLocation == null) {
            return null;
        }
        
        Geocoder coder = new Geocoder(this);
        List<Address> addresses = null;
        try {
            addresses = coder.getFromLocation(androidLocation.getLatitude(),
                    androidLocation.getLongitude(), 1);
        } catch (IOException e) {
            Log.w(TAG, "cannot decode location", e);
        }
        if (addresses == null || addresses.size() == 0) {
            return new AndroidGoogleLocation(androidLocation);
        }
        
        return new AndroidGoogleLocation(androidLocation, addresses.get(0));
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

}
