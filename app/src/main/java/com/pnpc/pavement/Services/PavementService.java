package com.pnpc.pavement.Services;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.pnpc.pavement.DistanceCalculator;
import com.pnpc.pavement.Models.Reading;
import com.pnpc.pavement.Models.Ride;

import java.util.ArrayList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Created by jjshin on 2/21/16.
 */
public class PavementService extends Service implements com.google.android.gms.location.LocationListener,
                                                        SensorEventListener,
                                                        GoogleApiClient.ConnectionCallbacks,
                                                        GoogleApiClient.OnConnectionFailedListener {

    DistanceCalculator distanceCalculator = new DistanceCalculator();
    SharedPreferences.Editor editor;
    SharedPreferences sharedPreferences;
    SensorManager sensorManager;
    GoogleApiClient googleApiClient;
    PavementAPIService pavementAPIService;
    LocationRequest locationRequest;
    int calibrationId;
    int scoreboardId;
    ArrayList<Float> xArray = new ArrayList<>();
    ArrayList<Float> yArray = new ArrayList<>();
    ArrayList<Float> zArray = new ArrayList<>();
    Double startLat;
    double endLat;
    Double startLng;
    double endLng;
    float angleX;
    float angleY;
    float angleZ;
    double startTime;
    double endTime;
    int rideId;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
//        return super.onStartCommand(intent, flags, startId);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        editor = sharedPreferences.edit();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        Sensor accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyroscopeSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        sensorManager.registerListener(this, accelerometerSensor, 500000);
        sensorManager.registerListener(this, gyroscopeSensor, 500000);

        locationRequest = createLocationRequest();
        Log.i("network", "" + isOnline(this));
        if (googleApiClient == null) {
            googleApiClient = new GoogleApiClient.Builder(this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(LocationServices.API)
                    .build();
        }

        pavementAPIService = PavementAPIServiceGenerator.createService(PavementAPIService.class, "", "");
        final Ride ride = new Ride();
        ride.setStartTime(System.currentTimeMillis() / 1000);
        Call<Ride> createRideCall = pavementAPIService.createRide(ride);
        createRideCall.enqueue(new Callback<Ride>() {
            @Override
            public void onResponse(Call<Ride> call, Response<Ride> response) {
                Log.i("createRide onResponse", "Ride: onSuccess: " + response.body() + "; onError: " + response.errorBody());
                Ride savedRide = response.body();
                rideId = savedRide.getId();
                updateIds();
                ride.setCalibrationId(calibrationId);
                ride.setScoreboardId(scoreboardId);
                putRideRequest(rideId, ride);
//              googleApiClient connect called here to make sure rideId isn't null when collecting readings
                googleApiClient.connect();
            }

            @Override
            public void onFailure(Call<Ride> call, Throwable t) {
                Log.i("createRide onFailure", "Create ride failed");
                Toast.makeText(PavementService.this, "Something went wrong. Please restart Pavement.", Toast.LENGTH_SHORT).show();
            }
        });

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        sensorManager.unregisterListener(this);
        stopLocationUpdates();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            Log.i("Sensor Test", "SensorUpdate: " + event.values[0] + ", " + event.values[1] + ", " + event.values[2]);
            xArray.add(event.values[0]);
            yArray.add(event.values[1]);
            zArray.add(event.values[2]);
        } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
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
        if (startLat == null || startLng == null) {
            startLat = location.getLatitude();
            startLng = location.getLongitude();
            startTime = getCurrentTime();
            return;
        }

        endLat = location.getLatitude();
        endLng = location.getLongitude();

        endTime = getCurrentTime();

        xArray = trimArray(xArray);
        yArray = trimArray(yArray);
        zArray = trimArray(zArray);

        Reading reading = new Reading();
        reading.setRideId(rideId);
        reading.setAccelerations(xArray, yArray, zArray);
        reading.setEndLat(endLat);
        reading.setEndLon(endLng);
        reading.setStartLat(startLat);
        reading.setStartLon(startLng);
        reading.setAngles(angleX, angleY, angleZ);
        reading.setStartTime(startTime);
        reading.setEndTime(endTime);

        postReadingRequest(reading);

        double distance = calculateDistance(startLat, startLng, endLat, endLng);
        updateDistance(distance);

        startLat = endLat;
        startLng = endLng;

        startTime = endTime;

        clearArrays();
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

    private void stopLocationUpdates() {
        LocationServices.FusedLocationApi.removeLocationUpdates(googleApiClient, this);
    }

    private LocationRequest createLocationRequest() {
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(500);
        mLocationRequest.setFastestInterval(250);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        return mLocationRequest;
    }

    private void clearArrays() {
        xArray.clear();
        yArray.clear();
        zArray.clear();
    }

    private boolean isOnline(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        android.net.NetworkInfo networkinfo = cm.getActiveNetworkInfo();
        if (networkinfo != null && networkinfo.isConnected()) {
            return true;
        }
        return false;
    }

    private ArrayList<Float> trimArray(ArrayList<Float> array) {
        int arrayCount = array.size();
        ArrayList<Float> newArray = new ArrayList<Float>();
        if (arrayCount > 10) {
            for (int i = arrayCount - 10; i < arrayCount; i++) {
                newArray.add(array.get(i));
            }
            return newArray;
        } else {
            return array;
        }
    }

    private void getCalibrationAndScoreboardIds() {
        calibrationId = sharedPreferences.getInt("calibration_id", 0);
        scoreboardId = sharedPreferences.getInt("scoreboard_id", 0);
        Log.i("calibrationId", "calibration_id: " + calibrationId);
        Log.i("scoreboardId", "scoreboard_id: " + scoreboardId);
    }

    private void setCalibrationAndScoreboardIds() {
        if (calibrationId == 0) {
            calibrationId = rideId;
            editor.putInt("calibration_id", calibrationId);
            Log.i("calibration", "setCalibrationAndScoreboardIds called");
        }
        if (scoreboardId == 0) {
            scoreboardId = rideId;
            editor.putInt("scoreboard_id", scoreboardId);
        }
        editor.commit();
    }

    private void updateIds() {
        getCalibrationAndScoreboardIds();
        setCalibrationAndScoreboardIds();
    }

    private void putRideRequest(int id, Ride ride) {
        Log.i("putRide", "putRideRequest called");
        Call<Ride> calibrationAndScoreboardCall = pavementAPIService.putRide(id, ride);
        calibrationAndScoreboardCall.enqueue(new Callback<Ride>() {
            @Override
            public void onResponse(Call<Ride> call, Response<Ride> response) {
                Log.i("putRide onResponse", "Ride: onSuccess: " + response.body() + "; onError: " + response.errorBody());
                Ride savedRide = response.body();
                int savedCalibrationId = savedRide.getCalibrationId();
                Log.i("CalibrationID", "" + savedCalibrationId);
            }

            @Override
            public void onFailure(Call<Ride> call, Throwable t) {
                Log.i("putRide onFailure", "put ride failed");
            }
        });
    }

    private void postReadingRequest(Reading reading) {
        Call<Reading> call = pavementAPIService.postReading(reading);
        call.enqueue(new Callback<Reading>() {
            @Override
            public void onResponse(Call<Reading> call, Response<Reading> response) {
                Log.i("Reading onResponse", "response: onSuccess: " + response.body() + "; onError: " + response.errorBody());
            }

            @Override
            public void onFailure(Call<Reading> call, Throwable t) {
                Log.i("Reading onFailure", "Well, that didn't work");
            }
        });
    }

    private double getCurrentTime() {
        return System.currentTimeMillis() / 1000;
    }

    private double calculateDistance(double lat1, double lng1, double lat2, double lng2) {
        double distance = distanceCalculator.calculateDistance(lat1, lng1, lat2, lng2);
        return distance;
    }

    private void updateDistance(double miles) {
        double oldMilesMeasured = sharedPreferences.getFloat("miles_measured", (float) 0.0);
        if (oldMilesMeasured == 0.0) {
            editor.putFloat("miles_measured", (float) miles);
            editor.commit();
        } else {
            double newMilesMeasured = oldMilesMeasured + miles;
            editor.putFloat("miles_measured", (float) newMilesMeasured);
            editor.commit();
            Log.i("miles measured", "" + newMilesMeasured);
        }
    }


}