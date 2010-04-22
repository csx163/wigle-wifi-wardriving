package net.wigle.wigleandroid;

import static android.location.LocationManager.GPS_PROVIDER;
import static android.location.LocationManager.NETWORK_PROVIDER;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.graphics.Color;
import android.location.GpsSatellite;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.GpsStatus.Listener;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnErrorListener;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

public class WigleAndroid extends Activity {
    // state. anything added here should be added to the retain copy-construction
    private ArrayAdapter<Network> listAdapter;
    private Set<String> runNetworks;
    private GpsStatus gpsStatus;
    private Location location;
    private Location networkLocation;
    private Handler wifiTimer;
    private DatabaseHelper dbHelper;
    private ServiceConnection serviceConnection;
    private AtomicBoolean finishing;
    private String savedStats;
    private long prevNewNetCount;
    private Long satCountLowTime;
    private Long lastLocationTime;
    private Long lastNetworkLocationTime;
    private MediaPlayer soundPop;
    private MediaPlayer soundNewPop;
    private WifiLock wifiLock;
    
    // created every time, even after retain
    private Listener gpsStatusListener;
    private LocationListener locationListener;
    private BroadcastReceiver wifiReceiver;
    private NumberFormat numberFormat1;
    private NumberFormat numberFormat8;
    private TTS tts;
    private AudioManager audioManager;
    private long previousTalkTime = System.currentTimeMillis();
    
    public static final String FILE_POST_URL = "http://wigle.net/gps/gps/main/confirmfile/";
    private static final String LOG_TAG = "wigle";
    private static final int MENU_SETTINGS = 10;
    private static final int MENU_EXIT = 11;
    public static final String ENCODING = "ISO8859_1";
    private static final long GPS_TIMEOUT = 15000L;
    private static final long NET_LOC_TIMEOUT = 60000L;
    
    // color by signal strength
    public static final int COLOR_1 = Color.rgb( 70, 170,  0);
    public static final int COLOR_2 = Color.rgb(170, 170,  0);
    public static final int COLOR_3 = Color.rgb(170,  95, 30);
    public static final int COLOR_4 = Color.rgb(180,  60, 40);
    public static final int COLOR_5 = Color.rgb(180,  45, 70);
    
    // preferences
    static final String SHARED_PREFS = "WiglePrefs";
    static final String PREF_USERNAME = "username";
    static final String PREF_PASSWORD = "password";
    static final String PREF_SHOW_CURRENT = "showCurrent";
    static final String PREF_BE_ANONYMOUS = "beAnonymous";
    static final String PREF_DB_MARKER = "dbMarker";
    static final String PREF_SCAN_PERIOD = "scanPeriod";
    static final String PREF_FOUND_SOUND = "foundSound";
    static final String PREF_SPEECH_PERIOD = "speechPeriod";
    static final String PREF_SPEECH_GPS = "speechGPS";
    static final String PREF_MUTED = "muted";
    static final String PREF_WIFI_WAS_OFF = "wifiWasOff";
    
    static final long DEFAULT_SPEECH_PERIOD = 60L;
    
    static final String ANONYMOUS = "anonymous";
    private static final String WIFI_LOCK_NAME = "wigleWifiLock";
    //static final String THREAD_DEATH_MESSAGE = "threadDeathMessage";
    
    // cache
    private static ThreadLocal<CacheMap<String,Network>> networkCache = new ThreadLocal<CacheMap<String,Network>>() {
      protected CacheMap<String,Network> initialValue() {
          return new CacheMap<String,Network>( 16, 64 );
      }
    };
    
    private static final Comparator<Network> signalCompare = new Comparator<Network>() {
      public int compare( Network a, Network b ) {
        return b.getLevel() - a.getLevel();
      }
    };
    
    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        
//        Thread.setDefaultUncaughtExceptionHandler( new Thread.UncaughtExceptionHandler(){
//          public void uncaughtException( Thread thread, Throwable throwable ) {
//            String error = "Thread: " + thread + " throwable: " + throwable;
//            WigleAndroid.error( error );
//            throwable.printStackTrace();
//            
//            WigleAndroid.writeError( thread, throwable );
//            
//            // throw new RuntimeException( error, throwable );
//            WigleAndroid.this.finish();
//            System.exit( -1 );
//          }
//        });
        
        Object stored = getLastNonConfigurationInstance();
        if ( stored != null && stored instanceof WigleAndroid ) {
          // pry an orientation change, which calls destroy, but we set this in onRetainNonConfigurationInstance
          WigleAndroid retained = (WigleAndroid) stored;
          this.listAdapter = retained.listAdapter;
          this.runNetworks = retained.runNetworks;
          this.gpsStatus = retained.gpsStatus;
          this.location = retained.location;
          this.wifiTimer = retained.wifiTimer;
          this.dbHelper = retained.dbHelper;
          this.serviceConnection = retained.serviceConnection;
          this.finishing = retained.finishing;
          this.savedStats = retained.savedStats;
          this.prevNewNetCount = retained.prevNewNetCount;
          this.soundPop = retained.soundPop;
          this.soundNewPop = retained.soundNewPop;
          this.wifiLock = retained.wifiLock;
          
          TextView tv = (TextView) findViewById( R.id.stats );
          tv.setText( savedStats );
        }
        else {
          runNetworks = new HashSet<String>();
          finishing = new AtomicBoolean( false );
        }
        
        numberFormat1 = NumberFormat.getNumberInstance( Locale.US );
        if ( numberFormat1 instanceof DecimalFormat ) {
          ((DecimalFormat) numberFormat1).setMaximumFractionDigits( 1 );
        }
        
        numberFormat8 = NumberFormat.getNumberInstance( Locale.US );
        if ( numberFormat8 instanceof DecimalFormat ) {
          ((DecimalFormat) numberFormat8).setMaximumFractionDigits( 8 );
        }
        
        setupService();
        setupDatabase();
        setupMaxidDebug();
        setupUploadButton();
        setupList();
        setupSound();
        setupWifi();
        setupLocation();
    }
    
    @Override
    public Object onRetainNonConfigurationInstance() {
      // return this whole class to copy data from
      return this;
    }
    
    @Override
    public void onPause() {
      info( "paused. networks: " + runNetworks.size() );
      super.onPause();
    }
    
    @Override
    public void onResume() {
      info( "resumed. networks: " + runNetworks.size() );
      super.onResume();
    }
    
    @Override
    public void onStart() {
      info( "start. networks: " + runNetworks.size() );
      super.onStart();
    }
    
    @Override
    public void onStop() {
      info( "stop. networks: " + runNetworks.size() );
      super.onStop();
    }

    @Override
    public void onRestart() {
      info( "restart. networks: " + runNetworks.size() );
      super.onRestart();
    }
    
    @Override
    public void onDestroy() {
      info( "destroy. networks: " + runNetworks.size() );
      try {
        this.unregisterReceiver( wifiReceiver );
      }
      catch ( IllegalArgumentException ex ) {
        info( "wifiReceiver not registered: " + ex );
      }
      // stop the service, so when we die it's both stopped and unbound and will die
      Intent serviceIntent = new Intent( this, WigleService.class );
      this.stopService( serviceIntent );
      try {
        this.unbindService( serviceConnection );
      }
      catch ( IllegalArgumentException ex ) {
        info( "serviceConnection not registered: " + ex );
      }
      if ( wifiLock != null && wifiLock.isHeld() ) {
        wifiLock.release();
      }
      
      super.onDestroy();
    }
    
    @Override
    public void finish() {
      info( "finish. networks: " + runNetworks.size() );
      finishing.set( true );
      
      // close the db. not in destroy, because it'll still write after that.
      dbHelper.close();
      
      LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
      if ( gpsStatusListener != null ) {
        locationManager.removeGpsStatusListener( gpsStatusListener );
      }
      if ( locationListener != null ) {
        locationManager.removeUpdates( locationListener );
      }
      
      try {
        this.unregisterReceiver( wifiReceiver );
      }
      catch ( IllegalArgumentException ex ) {
        info( "wifiReceiver not registered: " + ex );
      }

      // stop the service, so when we die it's both stopped and unbound and will die
      Intent serviceIntent = new Intent( this, WigleService.class );
      this.stopService( serviceIntent );
      try {
        this.unbindService( serviceConnection );
      }
      catch ( IllegalArgumentException ex ) {
        info( "serviceConnection not registered: " + ex );
      }    
      
      final SharedPreferences prefs = this.getSharedPreferences( SHARED_PREFS, 0 );
      boolean wifiWasOff = prefs.getBoolean( PREF_WIFI_WAS_OFF, false );
      if ( wifiWasOff ) {
        // well turn it of now that we're done
        final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        wifiManager.setWifiEnabled( false );
      }
      
      if ( tts != null ) {
        tts.shutdown();
      }
      if ( wifiLock != null && wifiLock.isHeld() ) {
        wifiLock.release();
      }
      
      super.finish();
    }
    
    /* Creates the menu items */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuItem item = menu.add(0, MENU_EXIT, 0, "Exit");
        item.setIcon( android.R.drawable.ic_menu_close_clear_cancel );
        
        item = menu.add(0, MENU_SETTINGS, 0, "Settings");
        item.setIcon( android.R.drawable.ic_menu_preferences );
        return true;
    }

    /* Handles item selections */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch ( item.getItemId() ) {
          case MENU_SETTINGS:
            info("settings");
            Intent intent = new Intent( this, SettingsActivity.class );
            this.startActivity( intent );
            return true;
          case MENU_EXIT:
            // stop the service, so when we die it's both stopped and unbound and will die
            Intent serviceIntent = new Intent( this, WigleService.class );
            this.stopService( serviceIntent );
            // call over to finish
            finish();
            // actually kill            
            //System.exit( 0 );
            return true;
        }
        return false;
    }
    
    private void setupDatabase() {
      // could be set by nonconfig retain
      if ( dbHelper == null ) {
        dbHelper = new DatabaseHelper( this );
        dbHelper.open();
        dbHelper.start();
      }
      
      dbHelper.checkDB();
    }
    
    private void setupList() {
      final LayoutInflater mInflater = (LayoutInflater) getSystemService( Context.LAYOUT_INFLATER_SERVICE );
        
      // may have been set by nonconfig retain
      if ( listAdapter == null ) {
        listAdapter = new ArrayAdapter<Network>( this, R.layout.row ) {
//          @Override
//          public boolean areAllItemsEnabled() {
//            return false;
//          }
//          
//          @Override
//          public boolean isEnabled( int position ) {
//            return false;
//          }
          
          @Override
          public View getView( int position, View convertView, ViewGroup parent ) {
            // long start = System.currentTimeMillis();
            View row;
            
            if ( null == convertView ) {
              row = mInflater.inflate( R.layout.row, null );
            } 
            else {
              row = convertView;
            }
        
            Network network = getItem(position);
            // info( "listing net: " + network.getBssid() );
            
            ImageView ico = (ImageView) row.findViewById( R.id.wepicon );   
            switch ( network.getCrypto() ) {
              case Network.CRYPTO_WEP:
                ico.setImageResource( R.drawable.wep_ico );
                break;
              case Network.CRYPTO_WPA:
                ico.setImageResource( R.drawable.wpa_ico );
                break;
              case Network.CRYPTO_NONE:
                ico.setImageResource( R.drawable.no_ico );
                break;
              default:
                throw new IllegalArgumentException( "unhanded crypto: " + network.getCrypto() 
                    + " in network: " + network );
            }
              
            TextView tv = (TextView) row.findViewById( R.id.ssid );              
            tv.setText( network.getSsid() );
              
            tv = (TextView) row.findViewById( R.id.level_string );
            int level = network.getLevel();
            if ( level <= -90 ) {
              tv.setTextColor( COLOR_5 );
            }
            else if ( level <= -80 ) {
              tv.setTextColor( COLOR_4 );
            }
            else if ( level <= -70 ) {
              tv.setTextColor( COLOR_3 );
            }
            else if ( level <= -60 ) {
              tv.setTextColor( COLOR_2 );
            }
            else {
              tv.setTextColor( COLOR_1 );
            }
            tv.setText( Integer.toString( level ) );
            
            tv = (TextView) row.findViewById( R.id.detail );
            String det = network.getDetail();
            tv.setText( det );
            // status( position + " view done. ms: " + (System.currentTimeMillis() - start ) );
        
            return row;
          }
        };
      }
               
      ListView listView = (ListView) findViewById( R.id.ListView01 );
      listView.setAdapter( listAdapter ); 
    }
    
    private void setupWifi() {
      // warn about turning off network notification
      String notifOn = Settings.Secure.getString(getContentResolver(), 
          Settings.Secure.WIFI_NETWORKS_AVAILABLE_NOTIFICATION_ON );
      if ( notifOn != null && "1".equals( notifOn ) ) {
        Toast.makeText( this, "For best results, unset \"Network notification\" in"
            + " \"Wireless & networks\"->\"Wi-Fi settings\"", 
            Toast.LENGTH_LONG ).show();
      }
    
      final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
      final SharedPreferences prefs = this.getSharedPreferences( SHARED_PREFS, 0 );
      Editor edit = prefs.edit();
      
      if ( ! wifiManager.isWifiEnabled() ) {
        // save so we can turn it back off when we exit  
        edit.putBoolean(PREF_WIFI_WAS_OFF, true);
        
        // just turn it on
        wifiManager.setWifiEnabled( true );
      }
      else {
        edit.putBoolean(PREF_WIFI_WAS_OFF, false);
      }
      edit.commit();
      
      // wifi scan listener
      wifiReceiver = new BroadcastReceiver(){
          public void onReceive( Context context, Intent intent ){
            long start = System.currentTimeMillis();
            List<ScanResult> results = wifiManager.getScanResults(); // Returns a <list> of scanResults
            
            long period = prefs.getLong( PREF_SCAN_PERIOD, 1000L );
            if ( period < 1000L ) {
              // under a second is hard to hit, treat as "continuous", so request scan in here
              wifiManager.startScan();
            }
            
            boolean showCurrent = prefs.getBoolean( PREF_SHOW_CURRENT, true );
            if ( showCurrent ) {
              listAdapter.clear();
            }
            
            int preQueueSize = dbHelper.getQueueSize();
            
            CacheMap<String,Network> networkCache = getNetworkCache();
            boolean somethingAdded = false;
            for ( ScanResult result : results ) {
              Network network = networkCache.get( result.BSSID );
              if ( network == null ) {
                network = new Network( result );
                networkCache.put( network.getBssid(), network );
              }
              else {
                // cache hit, just set the level
                network.setLevel( result.level );
              }
              boolean added = runNetworks.add( result.BSSID );
              somethingAdded |= added;
              
              // if we're showing current, or this was just added, put on the list
              if ( showCurrent || added ) {
                listAdapter.add( network );
                // load test
                // for ( int i = 0; i< 10; i++) {
                //  listAdapter.add( network );
                // }
                
              }
              else {
                // not showing current, and not a new thing, go find the network and update the level
                // this is O(n), ohwell, that's why showCurrent is the default config.
                for ( int index = 0; index < listAdapter.getCount(); index++ ) {
                  Network testNet = listAdapter.getItem(index);
                  if ( testNet.getBssid().equals( network.getBssid() ) ) {
                    testNet.setLevel( result.level );
                  }
                }
              }
              
              if ( location != null && dbHelper != null ) {
                dbHelper.addObservation( network, location );
              }
            }

            // check if there are more "New" nets
            long newNetCount = dbHelper.getNewNetworkCount();
            boolean newNet = newNetCount > prevNewNetCount;
            prevNewNetCount = newNetCount;
            
            boolean play = prefs.getBoolean( PREF_FOUND_SOUND, true );
            if ( play && ! isMuted() ) {
              if ( newNet ) {
                if ( ! soundNewPop.isPlaying() ) {
                  // play sound on something new
                  soundNewPop.start();
                }
                else {
                  info( "soundNewPop is playing" );
                }
              }
              else if ( somethingAdded ) {
                if ( ! soundPop.isPlaying() ) {
                  // play sound on something new
                  soundPop.start();
                }
                else {
                  info( "soundPop is playing" );
                }
              }
            }
            
            // sort by signal strength
            listAdapter.sort( signalCompare );

            // update stat
            TextView tv = (TextView) findViewById( R.id.stats );
            StringBuilder builder = new StringBuilder( 40 );
            builder.append( "Run: " ).append( runNetworks.size() );
            builder.append( " New: " ).append( newNetCount );
            builder.append( " DB: " ).append( dbHelper.getNetworkCount() );
            builder.append( " Locs: " ).append( dbHelper.getLocationCount() );
            savedStats = builder.toString();
            tv.setText( savedStats );
            
            // info( savedStats );
            
            // notify
            listAdapter.notifyDataSetChanged();
            
            long now = System.currentTimeMillis();
            status( results.size() + " scanned in " + (now - start) + "ms. DB Queue: " + preQueueSize );
            
            long speechPeriod = prefs.getLong( PREF_SPEECH_PERIOD, DEFAULT_SPEECH_PERIOD );
            if ( speechPeriod != 0 && now - previousTalkTime > speechPeriod * 1000L ) {
              String gps = "";
              if ( location == null ) {
                gps = ", no gps fix";
              }
              speak("run " + runNetworks.size() + ", new " + newNetCount + gps );
              previousTalkTime = now;
            }
          }
        };
      
      // register
      IntentFilter intentFilter = new IntentFilter();
      intentFilter.addAction (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
      this.registerReceiver( wifiReceiver, intentFilter );
      
      if ( wifiLock == null ) {
        // lock the radio on
        wifiLock = wifiManager.createWifiLock( WifiManager.WIFI_MODE_SCAN_ONLY, WIFI_LOCK_NAME );
        wifiLock.acquire();
      }
      
      // might not be null on a nonconfig retain
      if ( wifiTimer == null ) {
        wifiTimer = new Handler();
        Runnable mUpdateTimeTask = new Runnable() {
          public void run() {              
              // make sure the app isn't trying to finish
              if ( ! finishing.get() ) {
                // info( "timer start scan" );
                wifiManager.startScan();
                long period = prefs.getLong( PREF_SCAN_PERIOD, 1000L);
                // info("wifitimer: " + period );
                wifiTimer.postDelayed( this, period );
              }
              else {
                info( "finishing timer" );
              }
          }
        };
        wifiTimer.removeCallbacks(mUpdateTimeTask);
        wifiTimer.postDelayed(mUpdateTimeTask, 100);

        // starts scan, sends event when done
        boolean scanOK = wifiManager.startScan();
        info( "startup finished. wifi scanOK: " + scanOK );
      }
    }
    
    private void speak( String string ) {
      if ( ! isMuted() && tts != null ) {
        tts.speak( string );
      }
    }
    
    private void setupLocation() {
      // set on UI if we already have one
      updateLocationData( (Location) null );
      
      final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
      
      if ( ! locationManager.isProviderEnabled( GPS_PROVIDER ) ) {
        Toast.makeText( this, "Please turn on GPS", Toast.LENGTH_SHORT ).show();
        Intent myIntent = new Intent( Settings.ACTION_SECURITY_SETTINGS );
        startActivity(myIntent);
      }
      if ( ! locationManager.isProviderEnabled( NETWORK_PROVIDER ) ) {
        Toast.makeText( this, "For best results, set \"Use wireless networks\" in \"Location & security\"", 
            Toast.LENGTH_LONG ).show();
      }
      
      gpsStatusListener = new Listener(){
        public void onGpsStatusChanged( int event ) {
          updateLocationData( (Location) null );
        } 
      };
      locationManager.addGpsStatusListener( gpsStatusListener );
      
      List<String> providers = locationManager.getAllProviders();
      locationListener = new LocationListener(){
          public void onLocationChanged( Location newLocation ) {
            updateLocationData( newLocation );
          }
          public void onProviderDisabled( String provider ) {}
          public void onProviderEnabled( String provider ) {}
          public void onStatusChanged( String provider, int status, Bundle extras ) {}
        };
        
      for ( String provider : providers ) {
        info( "provider: " + provider );
        locationManager.requestLocationUpdates( provider, 1000L, 0, locationListener );
      }
    }
    
    /** newLocation can be null */
    private void updateLocationData( Location newLocation ) {
      final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
      // see if we have new data
      gpsStatus = locationManager.getGpsStatus( gpsStatus );
      int satCount = getSatCount();
      
      final boolean newOK = newLocation != null;
      final boolean locOK = locationOK( location, satCount );
      final long now = System.currentTimeMillis();
      
      if ( newOK ) {
        if ( NETWORK_PROVIDER.equals( newLocation.getProvider() ) ) {
          // save for later, in case we lose gps
          networkLocation = newLocation;
          lastNetworkLocationTime = now;
        }
        else {
          lastLocationTime = now;
        }
      }
      
      final boolean netLocOK = locationOK( networkLocation, satCount );
      
      boolean wasProviderChange = false;
      if ( ! locOK ) {
        if ( newOK ) {
          location = newLocation;
          wasProviderChange = true;
        }
        else if ( netLocOK ) {
          location = networkLocation;
          wasProviderChange = true;
        }
        else if ( location != null ) {
          // transition to null
          info( "nulling location: " + location );
          location = null;
          wasProviderChange = true;
        }
      }
      else if ( newOK && GPS_PROVIDER.equals( newLocation.getProvider() ) ) {
        if ( NETWORK_PROVIDER.equals( location.getProvider() ) ) {
          // this is an upgrade from network to gps
          wasProviderChange = true;
        }
        location = newLocation;
      }
      else if ( newOK && NETWORK_PROVIDER.equals( newLocation.getProvider() ) ) {
        if ( NETWORK_PROVIDER.equals( location.getProvider() ) ) {
          // just a new network provided location over an old one
          location = newLocation;
        }
      }
      
      info( "run: " + this.runNetworks.size() + " satCount: " + satCount 
          + " newOK: " + newOK + " locOK: " + locOK + " netLocOK: " + netLocOK
          + " wasProviderChange: " + wasProviderChange
          + (newOK ? " newProvider: " + newLocation.getProvider() : "")
          + (locOK ? " locProvider: " + location.getProvider() : "") 
          + " newLocation: " + newLocation );
      
      if ( wasProviderChange ) {
        String announce = location == null ? "Lost Location" 
            : "Now have location from \"" + location.getProvider() + "\"";
        Toast.makeText( this, announce, Toast.LENGTH_SHORT ).show();
        SharedPreferences prefs = this.getSharedPreferences( SHARED_PREFS, 0 );
        boolean speechGPS = prefs.getBoolean( PREF_SPEECH_GPS, true );
        if ( speechGPS ) {
          // no quotes or the voice pauses
          announce = location == null ? "Lost Location" 
            : "Now have location from " + location.getProvider() + ".";
          speak( announce );
        }
      }
      
      // update the UI
      setLocationUI();
    }
    
    private boolean locationOK( final Location location, final int satCount ) {
      boolean retval = false;
      long now = System.currentTimeMillis();
      
      if ( location == null ) {
        // bad!
      }
      else if ( GPS_PROVIDER.equals( location.getProvider() ) ) {
        if ( satCount < 3 ) {
          if ( satCountLowTime == null ) {
            satCountLowTime = now;
          }
        }
        else {
          // plenty of sats
          satCountLowTime = null;
        }
        boolean gpsLost = satCountLowTime != null && (now - satCountLowTime) > GPS_TIMEOUT;
        gpsLost |= now - lastLocationTime > GPS_TIMEOUT;
        retval = ! gpsLost;
      }
      else if ( NETWORK_PROVIDER.equals( location.getProvider() ) ) {
        boolean gpsLost = now - lastNetworkLocationTime > NET_LOC_TIMEOUT;
        retval = ! gpsLost;
      }
      
      return retval;
    }
    
    private int getSatCount() {
      int satCount = 0;
      if ( gpsStatus != null ) {
        for ( GpsSatellite sat : gpsStatus.getSatellites() ) {
          if ( sat.usedInFix() ) {
            satCount++;
          }
        }
      }
      return satCount;
    }
    
    private void setLocationUI() {
      if ( gpsStatus != null ) {
        int satCount = getSatCount();
        TextView tv = (TextView) this.findViewById( R.id.LocationTextView06 );
        tv.setText( "Sats: " + satCount );
      }
      
      TextView tv = (TextView) this.findViewById( R.id.LocationTextView01 );
      tv.setText( "Lat: " + (location == null ? "  (Waiting for GPS sync..)" 
          : numberFormat8.format( location.getLatitude() ) ) );
      
      tv = (TextView) this.findViewById( R.id.LocationTextView02 );
      tv.setText( "Lon: " + (location == null ? "" : numberFormat8.format( location.getLongitude() ) ) );
      
      tv = (TextView) this.findViewById( R.id.LocationTextView03 );
      tv.setText( "Speed: " + (location == null ? "" : numberFormat1.format( location.getSpeed() * 2.23693629f ) + "mph" ) );
      
      tv = (TextView) this.findViewById( R.id.LocationTextView04 );
      tv.setText( location == null ? "" : ("+/- " + numberFormat1.format( location.getAccuracy() ) + "m") );
      
      tv = (TextView) this.findViewById( R.id.LocationTextView05 );
      tv.setText( location == null ? "" : ("Alt: " + numberFormat1.format( location.getAltitude() ) + "m") );
    }
    
    private void setupUploadButton() {
      Button button = (Button) findViewById( R.id.upload_button );
      button.setOnClickListener( new OnClickListener() {
          public void onClick( View view ) {
            uploadFile( WigleAndroid.this, dbHelper );
          }
        });
    }
    
    private void setupService() {
      Intent serviceIntent = new Intent( this, WigleService.class );
      
      // could be set by nonconfig retain
      if ( serviceConnection == null ) {
        ComponentName compName = startService( serviceIntent );
        if ( compName == null ) {
          WigleAndroid.error( "startService() failed!" );
        }
        else {
          WigleAndroid.info( "service started ok: " + compName );
        }
        
        serviceConnection = new ServiceConnection(){
          public void onServiceConnected( ComponentName name, IBinder iBinder) {
            WigleAndroid.info( name + " service connected" ); 
          }
          public void onServiceDisconnected( ComponentName name ) {
            WigleAndroid.info( name + " service disconnected" );
          }
        };  
      }
      
      int flags = 0;
      this.bindService(serviceIntent, serviceConnection, flags);
    }
    
    private void setupSound() {
      // could have been retained
      if ( soundPop == null ) {
        soundPop = createMediaPlayer( R.raw.pop );
      }
      if ( soundNewPop == null ) {
        soundNewPop = createMediaPlayer( R.raw.newpop );
      }
      audioManager = (AudioManager) this.getSystemService(Context.AUDIO_SERVICE);
      this.setVolumeControlStream(AudioManager.STREAM_MUSIC);  
      
      if ( TTS.hasTTS() ) {
        tts = new TTS( this );        
      }
      
      final Button mute = (Button) this.findViewById(R.id.mute);
      final SharedPreferences prefs = this.getSharedPreferences(SHARED_PREFS, 0);
      boolean muted = prefs.getBoolean(PREF_MUTED, false);
      if ( muted ) {
        mute.setText("Play");
      }
      mute.setOnClickListener(new OnClickListener(){
        public void onClick( View buttonView ) {
          boolean muted = prefs.getBoolean(PREF_MUTED, false);
          muted = ! muted;
          Editor editor = prefs.edit();
          editor.putBoolean( PREF_MUTED, muted );
          editor.commit();
          
          if ( muted ) {
            mute.setText("Play");
          }
          else {
            mute.setText("Mute");
          }
        }
      });
    }

    private MediaPlayer createMediaPlayer( int soundId ) {
      MediaPlayer sound = MediaPlayer.create( this, soundId );
      // try to figure out why sounds stops after a while
      sound.setOnErrorListener( new OnErrorListener() {
        public boolean onError( MediaPlayer mp, int what, int extra ) {
          String whatString = null;
          switch ( what ) {
            case MediaPlayer.MEDIA_ERROR_UNKNOWN:
              whatString = "error unknown";
              break;
            case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
              whatString = "server died";
              break;
            default:
              whatString = "not defined";
          }
          info( "media player error \"" + whatString + "\" what: " + what
            + " extra: " + extra + " mp: " + mp );
          return false;
        }
      } );

      return sound;
    }
    
    @SuppressWarnings("unused")
    private boolean isRingerOn() {
      boolean retval = false;
      if ( audioManager != null ) {
        retval = audioManager.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
      }
      return retval;
    }
    
    private boolean isMuted() {
      boolean retval = this.getSharedPreferences(SHARED_PREFS, 0).getBoolean(PREF_MUTED, false);
      // info( "ismuted: " + retval );
      return retval;
    }
    
    private static void uploadFile( Context context, DatabaseHelper dbHelper ){
      info( "upload file" );
      FileUploaderTask task = new FileUploaderTask( context, dbHelper );
      task.start();
    }
    
    private void status( String status ) {
      // info( status );
      TextView tv = (TextView) findViewById( R.id.status );
      tv.setText( status );
    }
    
    public static void sleep( long sleep ) {
      try {
        Thread.sleep( sleep );
      }
      catch ( InterruptedException ex ) {
        // no worries
      }
    }
    public static void info( String value ) {
      Log.i( LOG_TAG, Thread.currentThread().getName() + "] " + value );
    }
    public static void error( String value ) {
      Log.e( LOG_TAG, Thread.currentThread().getName() + "] " + value );
    }

    /**
     * get the per-thread network LRU cache
     * @return per-thread network cache
     */
    public static CacheMap<String,Network> getNetworkCache() {
      return networkCache.get();
    }
    
    public static void writeError( Thread thread, Throwable throwable ) {
      try {
        String error = "Thread: " + thread + " throwable: " + throwable;
        error( error );
        if ( hasSD() ) {
          File file = new File( Environment.getExternalStorageDirectory().getCanonicalPath() + "/wiglewifi/" );
          file.mkdirs();
          file = new File(Environment.getExternalStorageDirectory().getCanonicalPath() 
              + "/wiglewifi/errorstack_" + System.currentTimeMillis() + ".txt" );
          if ( ! file.exists() ) {
            file.createNewFile();
          }
          FileOutputStream fos = new FileOutputStream( file );
          fos.write( error.getBytes( ENCODING ) );
          throwable.printStackTrace( new PrintStream( fos ) );
          fos.close();
        }
      }
      catch ( Exception ex ) {
        error( "error logging error: " + ex );
        ex.printStackTrace();
      }
    }
    
    public static boolean hasSD() {
      File sdCard = null;
      try {
        sdCard = new File( Environment.getExternalStorageDirectory().getCanonicalPath() + "/" );
      }
      catch ( IOException ex ) {
        // ohwell
      }
      return sdCard != null && sdCard.exists() && sdCard.isDirectory() && sdCard.canRead() && sdCard.canWrite();
    }
    
    private void setupMaxidDebug() {
      final SharedPreferences prefs = WigleAndroid.this.getSharedPreferences( SHARED_PREFS, 0 );
      long maxid = prefs.getLong( PREF_DB_MARKER, -1L );
      if ( maxid == -1L ) {
        // load up the local value
        dbHelper.getLocationCountFromDB();
        long loccount = dbHelper.getLocationCount();
        if ( loccount > 0 ) {
          // there is no preference set, yet there are locations, this is likely
          // a developer testing a new install on an old db, so set the pref.
          info( "setting db marker to: " + loccount );
          Editor edit = prefs.edit();
          edit.putLong( PREF_DB_MARKER, loccount );
          edit.commit();
        }
      }
    }
}
