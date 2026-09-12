package com.smog.weatherapp;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.smog.weatherapp.net.NetworkStatus;

/**
 * 一次性取实时定位：申请权限 → 注册监听（GPS 优先，退到网络定位）→ 10 秒拿不到 fix 就放弃。
 *
 * <p>单独成一个类，是因为这块代码的时序最容易出错，而它原本埋在 Activity 中间：监听器与超时任务
 * 都要保存引用、重复调用要先清理旧注册、拿到 fix 要顺手取消超时（否则超时会晚一步误报）、
 * Activity 销毁要注销监听。这些都是一段完整的状态机，散着看很难一眼确认对不对。
 *
 * <p>生命周期：{@link #checkLocationPermission()} 发起，Activity 销毁时必须调 {@link #stop()}。
 * 权限回调 {@code onRequestPermissionsResult} 是 Activity 的框架方法、不能搬，Activity 收到后转交
 * {@link #onPermissionResult}。
 *
 * <p>刻意【不】搬的事——这些要看界面当前状态，属于 Activity：
 * <ul>
 *   <li>顶栏回退成「未获取到位置」：是否还没城市可显示，只有 Activity 知道。</li>
 *   <li>坐标到手后怎么用（发请求）：走 {@link Callback#onLocation}。</li>
 *   <li>超时要不要提示：冷启动没城市才提示，已有城市（缓存）时静默——同上，由 Activity 判断。</li>
 * </ul>
 *
 * <p>不做「最后已知位置」兜底：陈旧坐标可能来自模拟器默认/异地（如 Mountain View），宁可不显示、
 * 也不误导，只信实时 fix。
 */
final class LocationHelper {

    static final int REQUEST_LOCATION_PERMISSION = 1;

    /** 界面回调：只把「要看界面状态才能决定」的两件事交回 Activity。 */
    interface Callback {
        /** 拿到实时坐标。 */
        void onLocation(double latitude, double longitude);

        /**
         * 定位到此为止、不会有后续回调（未授予权限 / 定位服务未开启）：界面应把顶栏回退成中性提示。
         */
        void onLocationAborted();

        /** 10 秒内没有实时 fix（可能随后才来）。界面可按「尚无城市可显示」决定是否提示一句。 */
        void onLocationTimeout();
    }

    private final Activity activity;
    private final Callback callback;

    /** 主线程 Handler 与定位超时任务：保存引用以便取消未执行的延时回调 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;
    /** 当前注册中的定位监听：保存引用以便注销，避免 Activity 销毁后仍回调 */
    private LocationListener locationListener;
    private Runnable timeoutRunnable;

    LocationHelper(Activity activity, Callback callback) {
        this.activity = activity;
        this.callback = callback;
    }

    /** Activity 销毁时调用：注销定位监听 + 取消定位超时回调，避免销毁后仍有位置回调或延时任务。 */
    void stop() {
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
        }
    }

    /** 有权限就立刻取定位，否则申请权限（结果转到 {@link #onPermissionResult}）。 */
    void checkLocationPermission() {
        boolean fineGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        boolean coarseGranted = ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
        if (fineGranted || coarseGranted) {
            getCurrentLocation();
            return;
        }
        // 同时申请精确 + 大致位置：任一被授权即可定位（Android 12+ 选「大致位置」/「仅使用时」也走得通）
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                REQUEST_LOCATION_PERMISSION);
    }

    /** 由 Activity 的 {@code onRequestPermissionsResult} 转交。 */
    void onPermissionResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_LOCATION_PERMISSION) {
            return;
        }
        if (anyLocationGranted(grantResults)) {
            getCurrentLocation();
        } else {
            Toast.makeText(activity, activity.getString(R.string.permission_denied), Toast.LENGTH_SHORT).show();
            callback.onLocationAborted();
        }
    }

    /** grantResults 与请求时的权限顺序一一对应，两个里任一命中即代表可定位。 */
    private static boolean anyLocationGranted(int[] grantResults) {
        if (grantResults == null) return false;
        for (int r : grantResults) {
            if (r == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    private void getCurrentLocation() {
        if (!NetworkStatus.isOnline(activity)) {
            Toast.makeText(activity, activity.getString(R.string.location_no_network), Toast.LENGTH_SHORT).show();
        }
        locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);

        if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        boolean isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER);
        boolean isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);

        if (!isGpsEnabled && !isNetworkEnabled) {
            Toast.makeText(activity, activity.getString(R.string.location_service_off), Toast.LENGTH_SHORT).show();
            callback.onLocationAborted();
            return;
        }

        // 重复调用时先清掉上一次的监听与超时任务，避免重复注册和回调泄漏
        if (locationListener != null) {
            locationManager.removeUpdates(locationListener);
            locationListener = null;
        }
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }

        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                locationManager.removeUpdates(this);
                // 已拿到定位，取消 10 秒超时任务，否则它会晚一步误报「定位不可用」
                if (timeoutRunnable != null) {
                    mainHandler.removeCallbacks(timeoutRunnable);
                    timeoutRunnable = null;
                }
                // 不再把原始坐标上报后端：既无必要（城市缓存由后端 getOrFetchLocation 按需自填，
                // 它按城市名落库的是城市中心坐标），也是隐私要求——位置属敏感个人信息，
                // 天气只要城市级粒度，坐标留在这部手机上就够。详见 AGENTS.md。
                callback.onLocation(location.getLatitude(), location.getLongitude());
            }

            @Override
            public void onProviderDisabled(@NonNull String provider) {
                Toast.makeText(activity, activity.getString(R.string.location_provider_off, provider),
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProviderEnabled(@NonNull String provider) {
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
            }
        };

        if (isGpsEnabled) {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        } else if (isNetworkEnabled) {
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener, Looper.getMainLooper());
        }

        timeoutRunnable = () -> {
            timeoutRunnable = null;
            if (locationManager != null) {
                locationManager.removeUpdates(locationListener);
            }
            // 不拿「最后已知位置」顶替（见类注释）；是否有城市可显示由 Activity 判断
            callback.onLocationTimeout();
        };
        mainHandler.postDelayed(timeoutRunnable, 10000);
    }
}
