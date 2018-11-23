/*
 * Copyright (C) 2018 Pixel Experience
 * Copyright (C) 2018 CypherOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.providers.weather;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.util.Log;

import com.android.providers.weather.utils.Constants;
import com.android.providers.weather.utils.Utilities;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static com.android.providers.weather.utils.Constants.DEBUG;
import static com.android.providers.weather.WeatherProvider.WEATHER_UPDATE_ERROR;
import static com.android.providers.weather.WeatherProvider.WEATHER_UPDATE_SUCCESS;

public class WeatherChannelApi implements LocationListener {

    private String TAG = "WeatherChannelApi";
    private boolean mRunning;
    private Handler mHandler;
    private Context mContext;
    private String mSunCondition;

	protected Location mLastLocation;
	private LocationManager mLocationManager;
	private WeatherChannelApi sInstance = null;

	private Runnable removeLocationUpdates = new Runnable() {
        @Override
        public void run() {
            if (DEBUG) Log.d(TAG, "removeLocationUpdates");
			mLocationManager.removeUpdates(sInstance);
			mLastLocation = null;
			mRunning = false;
			sInstance = null;
        }
    };

    WeatherChannelApi(Context context) {
        mRunning = false;
        mHandler = new Handler(Looper.getMainLooper());
		mLocationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        mContext = context;
    }

    boolean isRunning() {
        return mRunning;
    }

    private final Interceptor REWRITE_RESPONSE_INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Response originalResponse = chain.proceed(chain.request());
            String cacheControl = originalResponse.header("Cache-Control");
            if (cacheControl == null || cacheControl.contains("no-store") || cacheControl.contains("no-cache") ||
                    cacheControl.contains("must-revalidate") || cacheControl.contains("max-age=0")) {
                return originalResponse.newBuilder()
                        .header("Cache-Control", "public, max-age=" + 10)
                        .build();
            } else {
                return originalResponse;
            }
        }
    };

    private final Interceptor OFFLINE_INTERCEPTOR = new Interceptor() {
        @Override
        public Response intercept(Chain chain) throws IOException {
            Request request = chain.request();
            if (!(Utilities.isNetworkAvailable(mContext))) {
                request = request.newBuilder()
                        .header("Cache-Control", "public, only-if-cached, max-stale=" + Constants.API_CACHE_NO_CONNECTION_MAX_TIME)
                        .build();
            }
            return chain.proceed(request);
        }
    };

    WeatherProvider getResult() {
        if (isRunning()) {
			Log.d(TAG, "Query thread is still running");
            return new WeatherProvider(WEATHER_UPDATE_ERROR, "", 0, 0);
        }
		Location location = mLastLocation != null ? mLastLocation : mLocationManager.getLastLocation();
		if (location == null) {
			Log.d(TAG, "Last location is null");
            return new WeatherProvider(WEATHER_UPDATE_ERROR, "", 0, 0);
		}
        Log.d(TAG, "Getting weather results");
        if (DEBUG) Log.d(TAG, "latitude=" + location.getLatitude() + ",longitude=" + location.getLongitude());
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .addNetworkInterceptor(REWRITE_RESPONSE_INTERCEPTOR)
                .addInterceptor(OFFLINE_INTERCEPTOR)
                .cache(new Cache(new File(mContext.getCacheDir(),
                        "WeatherChannelApiCache"), 10 * 1024 * 1024))
                .build();
        try {
            Response response = httpClient.newCall(new Request.Builder()
                    .tag("WeatherChannelApi")
                    .url("https://weather.com/weather/today/l/" + location.getLatitude() + "," + location.getLongitude() + "?par=google")
                    .build()).execute();
            if (response.body() != null && response.isSuccessful()) {
                mSunCondition = getSunCondition(location.getLatitude(), location.getLongitude());
                String result = response.body().string();
                Document doc = Jsoup.parse(result);
                Element tempElement = doc.selectFirst("div[class=today_nowcard-temp] span");
                Element conditionIconElement = doc.selectFirst("div[class=today_nowcard-section today_nowcard-condition] div icon");
                String conditionIconElementClassName = conditionIconElement.className().replace("", "");
                String tempImperial = tempElement.text().replace("°", "");
                if (tempImperial.equals("") || conditionIconElementClassName.equals("")) {
                    throw new Exception("tempImperial or conditionIconElementClassName is empty");
                }
                String parsedConditions = parseCondition(conditionIconElement.className());
                if (Utilities.isLegacyMode()) {
                    parsedConditions = parseConditionLegacy(parsedConditions);
                }
                int tempMetric = (int) Math.round((Integer.valueOf(tempImperial) - 32.0) * 5 / 9);
                if (DEBUG)
                    Log.d(TAG, "tempImperial: " + tempImperial + " tempMetric: " + tempMetric + " parsedConditions: " + parsedConditions);
                return new WeatherProvider(WEATHER_UPDATE_SUCCESS, parsedConditions, tempMetric, Integer.valueOf(tempImperial));
            }
        } catch (Exception e) {
            if (DEBUG) Log.e(TAG, "Exception", e);
        }
        return new WeatherProvider(WEATHER_UPDATE_ERROR, "", 0, 0);
    }

    private String parseCondition(String toCompare) {
        String nightFix = mSunCondition.equals("n") ? "-night" : "";
        if (DEBUG)
            Log.d(TAG, "parseCondition: toCompare: " + toCompare + " nightFix: " + nightFix);
        Map<String, String> conditions = new HashMap<>();
        conditions.put("icon-partly-cloudy", "partly-cloudy");
        conditions.put("icon-partly-cloudy-night", "partly-cloudy-night");
        conditions.put("icon-mostly-cloudy", "mostly-cloudy");
        conditions.put("icon-mostly-cloudy-night", "mostly-cloudy-night");
        conditions.put("icon-cloudy", "cloudy");
        conditions.put("icon-clear-night", "clear-night");
        conditions.put("icon-mostly-clear-night", "mostly-clear-night");
        conditions.put("icon-sunny", "sunny");
        conditions.put("icon-mostly-sunny", "mostly-sunny");
        conditions.put("icon-scattered-showers", "scattered-showers" + nightFix);
        conditions.put("icon-isolated-showers", "rain");
        conditions.put("icon-showers", "rain");
        conditions.put("icon-rain", "rain");
        conditions.put("icon-wind", "windy");
        conditions.put("icon-snow", "snow");
        conditions.put("icon-rain-snow", "snow");
        conditions.put("icon-scattered-snow", "snow");
        conditions.put("icon-isolated-snow", "snow");
        conditions.put("icon-freezing-drizzle", "snow");
        conditions.put("icon-scattered-thunderstorms", "scattered-thunderstorms" + nightFix);
        conditions.put("icon-isolated-thunderstorms", "isolated-thunderstorms" + nightFix);
        conditions.put("icon-thunderstorms", "thunderstorms");
        conditions.put("icon-foggy", "foggy");
        for (String condition : conditions.keySet()) {
            if (toCompare.contains(condition + " ")) {
                return conditions.get(condition);
            }
        }
        return "mostly-cloudy" + nightFix;
    }

    private String getSunCondition(double latitude, double longitude) {
        Calendar currentCalendar = GregorianCalendar.getInstance();
        int currentHour = currentCalendar.get(Calendar.HOUR_OF_DAY);
        String sunCondition = (currentHour >= 7 && currentHour <= 18) ? "d" : "n";
        SunriseSunsetRestApi sunriseSunsetRestApi = new SunriseSunsetRestApi(mContext);
        int sunriseSunsetRestApiResult = sunriseSunsetRestApi.queryApi(Double.toString(latitude), Double.toString(longitude));
        if (sunriseSunsetRestApiResult == SunriseSunsetRestApi.RESULT_DAY) {
            sunCondition = "d";
        } else if (sunriseSunsetRestApiResult == SunriseSunsetRestApi.RESULT_NIGHT) {
            sunCondition = "n";
        } else {
            try {
                TimeZone tz = TimeZone.getDefault();
                com.luckycatlabs.sunrisesunset.dto.Location location = new com.luckycatlabs.sunrisesunset.dto.Location(latitude, longitude);
                SunriseSunsetCalculator calculator = new SunriseSunsetCalculator(location, tz.getID());
                Calendar officialSunrise = calculator.getOfficialSunriseCalendarForDate(currentCalendar);
                Calendar officialSunset = calculator.getOfficialSunsetCalendarForDate(currentCalendar);
                if (DEBUG) {
                    Log.d("SunriseSunsetCalculator", "Current time is: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(currentCalendar.getTime()));
                    Log.d("SunriseSunsetCalculator", "Sunrise time is: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(officialSunrise.getTime()));
                    Log.d("SunriseSunsetCalculator", "Sunset time is: " + new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(officialSunset.getTime()));
                }
                if (currentCalendar.getTimeInMillis() >= officialSunrise.getTimeInMillis() && currentCalendar.getTimeInMillis() < officialSunset.getTimeInMillis()) {
                    if (DEBUG) Log.d(TAG, "It's day");
                    sunCondition = "d";
                } else {
                    if (DEBUG) Log.d(TAG, "It's night");
                    sunCondition = "n";
                }
            } catch (Exception e) {
                if (DEBUG)
                    Log.e(TAG, "Exception when calculating sunset/sunrise", e);
            }
        }
        return sunCondition;
    }

    private String parseConditionLegacy(String newCondition) {
        if (DEBUG) Log.d(TAG, "parseCondition: newCondition: " + newCondition);
        Map<String, String> conditions = new HashMap<>();
        conditions.put("partly-cloudy", "d,2");
        conditions.put("partly-cloudy-night", "n,2");
        conditions.put("mostly-cloudy", "d,0");
        conditions.put("mostly-cloudy-night", "n,0");
        conditions.put("clear-night", "n,1");
        conditions.put("mostly-clear-night", "n,12");
        conditions.put("sunny", "d,1");
        conditions.put("mostly-sunny", "d,12");
        conditions.put("scattered-showers", "d,6");
        conditions.put("scattered-showers-night", "n,6");
        conditions.put("rain", "d,6");
        conditions.put("snow", "d,57");
        conditions.put("scattered-thunderstorms", "d,8");
        conditions.put("scattered-thunderstorms-night", "n,8");
        conditions.put("isolated-thunderstorms", "d,8");
        conditions.put("isolated-thunderstorms-night", "n,8");
        conditions.put("thunderstorms", "d,8");
        conditions.put("foggy", "d,34");
        conditions.put("windy", mSunCondition + ",0");
        conditions.put("cloudy", mSunCondition + ",0");
        for (String condition : conditions.keySet()) {
            if (newCondition.equals(condition)) {
                return conditions.get(condition);
            }
        }
        return mSunCondition + ",0";
    }

    @SuppressLint("MissingPermission")
    void queryLocation() {
        if (mRunning) {
            return;
        }
        mRunning = true;
		if (sInstance == null) {
			sInstance = new WeatherChannelApi(mContext);
		}
		mLocationManager.requestLocationUpdates(null, this, Looper.getMainLooper());
		// Request the device's location immediately if a previous location isn't available.
        if (mLocationManager.getLastLocation() == null) {
            if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER, this, Looper.getMainLooper());
            } else if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestSingleUpdate(
                        LocationManager.GPS_PROVIDER, this, Looper.getMainLooper());
            }
        }
        mHandler.postDelayed(removeLocationUpdates, Constants.LOCATION_QUERY_MAX_TIME);
    }
	
	@Override
    public void onLocationChanged(Location location) {
        if (location != null
                && !(location.getLongitude() == 0.0 && location.getLatitude() == 0.0)) {
            Log.d(TAG, "onLocationChanged:"
                    + " provider=" + location.getProvider()
                    + " accuracy=" + location.getAccuracy()
                    + " time=" + location.getTime());
            mLastLocation = location;
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }
}

