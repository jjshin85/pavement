package com.pnpc.pavement;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;

import okhttp3.OkHttpClient;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;

/**
 * Created by jjshin on 2/21/16.
 */
public class PavementService extends Service implements com.google.android.gms.location.LocationListener, SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    SensorManager sensorManager;
    LocationManager locManager;
    GoogleApiClient googleApiClient;
    LocationRequest locationRequest;
    ArrayList<Float> xArray = new ArrayList<>();
    ArrayList<Float> yArray = new ArrayList<>();
    ArrayList<Float> zArray = new ArrayList<>();
    Double startLat;
    Double endLat;
    Double startLng;
    Double endLng;
    Float angleX;
    Float angleY;
    Float angleZ;
    final static int RIDE_ID = 179;
    Retrofit retrofit;
    ReadingService readingService;
    public static OkHttpClient.Builder httpClient = new OkHttpClient.Builder();


    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
//        return super.onStartCommand(intent, flags, startId);
        Toast.makeText(this, "service starting", Toast.LENGTH_SHORT).show();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        sensorManager.registerListener(this, accelerometerSensor, 1000000);
        sensorManager.registerListener(this, gyroscopeSensor, 10000000);

        locationRequest = createLocationRequest();
        Log.i("network", "" + isOnline(this));
        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
            googleApiClient.connect();
            Log.i("GoogleAPIClient", "" + googleApiClient);
        }

        readingService = ServiceGenerator.createService(ReadingService.class, "", "");

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        stopLocationUpdates();
    }


    public void setupLocationRequest() {
        Log.i("GPS Permission", "" + ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION));
        Log.i("GPS Permission result", "" + PackageManager.PERMISSION_GRANTED);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
        }

        return;

    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            Log.i("Sensor Test", "SensorUpdate: " + event.values[0] + ", " + event.values[1] + ", " + event.values[2]);
            xArray.add(event.values[0]);
            yArray.add(event.values[1]);
            zArray.add(event.values[2]);
        }
        else if(event.sensor.getType() == Sensor.TYPE_GYROSCOPE){
            angleX = event.values[0];
            angleY = event.values[1];
            angleZ = event.values[2];
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLocationChanged(Location location) {
        Log.i("GPS on location changed", "Latitude: " + location.getLatitude());
        Log.i("GPS on location changed", "Longitude: = " + location.getLongitude());
        if(startLat == null || startLng == null){
            startLat = location.getLatitude();
            startLng = location.getLongitude();
            return;
        }

        endLat = location.getLatitude();
        endLng = location.getLongitude();
//        Log.i("Reading", readingService.createReading(startLat, startLng, endLat, endLng, xArray.toString(), yArray.toString(), zArray.toString(), RIDE_ID).toString());
        xArray = trimArray(xArray);
        yArray = trimArray(yArray);
        zArray = trimArray(zArray);

        Reading reading = new Reading();
        reading.setAccelerationX(xArray);
        reading.setAccelerationY(yArray);
        reading.setAccelerationZ(zArray);
        reading.setEndLat(endLat);
        reading.setEndLon(endLng);
        reading.setStartLat(startLat);
        reading.setStartLon(startLng);
        reading.setRideId(RIDE_ID);
        reading.setAngleX(angleX);
        reading.setAngleY(angleY);
        reading.setAngleZ(angleZ);
//        Call<Reading> reading = readingService.createReading(startLat, startLng, endLat, endLng, xArray.toString(), yArray.toString(), zArray.toString(), RIDE_ID);
//        Reading reading = new Reading(startLat, startLng, endLat, endLng, xArray.toString(), yArray.toString(), zArray.toString(), RIDE_ID);
        Call<Reading> call = readingService.postReading(reading);
        Log.i("Reading", call.toString());
        call.enqueue(new Callback<Reading>() {
            @Override
            public void onResponse(Call<Reading> call, Response<Reading> response) {
                Log.i("Reading onResponse", "response: " + response.body() + "; " + response.errorBody());

            }
            @Override
            public void onFailure(Call<Reading> call, Throwable t) {
                Log.i("Reading onFailure", "Well, that didn't work");

            }
        });
        clearArrays();

        startLat = endLat;
        startLng = endLng;
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Log.i("GPS googleclient", "GoogleClientApi connected");
        startLocationUpdates();
    }

    @Override
    public void onConnectionSuspended(int i) {
        Log.i("GPS googleclient", "GoogleClientApi connection suspended");

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.i("GPS googleclient", "GoogleClientApi connection failed");

    }

    protected void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            Log.i("GPS startupdates", "Somehow the permissions are missing");
            return;
        }
        Log.i("GPS startUpdates", "startLocationUpdates called");
        LocationServices.FusedLocationApi.requestLocationUpdates(
                googleApiClient, locationRequest, this);
    }
    protected void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
    }
    protected LocationRequest createLocationRequest() {
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(500);
        mLocationRequest.setFastestInterval(500);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        return mLocationRequest;
    }

    protected void clearArrays(){
        Log.i("Array x", "" + xArray.size());
        Log.i("Array y", "" + yArray.size());
        Log.i("Array z", "" + zArray.size());

        xArray.clear();
        yArray.clear();
        zArray.clear();
    }
    public boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo networkinfo = cm.getActiveNetworkInfo();
        if (networkinfo != null && networkinfo.isConnected()) {
            return true;
        }
        return false;
    }

    public ArrayList<Float> trimArray(ArrayList<Float> array){
        int arrayCount = array.size();
        ArrayList<Float> newArray = new ArrayList<Float>();
        if(arrayCount > 10){
            for(int i = arrayCount - 10; i < arrayCount; i ++){
                newArray.add(array.get(i));
            }
            Log.i("array new", "" + newArray.size());
            return newArray;
        }
        else{
            return array;
        }
    }

}
