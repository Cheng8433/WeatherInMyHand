package com.smog.weatherapp.net;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import androidx.core.content.ContextCompat;

/**
 * 系统联网状态查询。
 *
 * <p>单独成一个类，是因为它既不属于「后端接口调用」（{@link WeatherApi}），
 * 也不属于「定位」（发请求前与定位前都要先判一下，两处口径必须一致），
 * 谁都不该把它当私有工具兜着。
 */
public final class NetworkStatus {

    private NetworkStatus() {
    }

    /**
     * 是否可联网。Android 6+ 走 getActiveNetwork + NetworkCapabilities：
     * 旧的 getActiveNetworkInfo 在部分机型/瞬态下会返回 null，会把「有网」误判成「无网络」
     * 从而白白跳过请求，这里只判 NET_CAPABILITY_INTERNET，不要求 VALIDATED，
     * 避免门户认证等场景被误判；真连不通时交给 OkHttp 失败并按「网络错误」提示。
     * 拿不到 ConnectivityManager 时不阻断请求，宁可让它去试。
     */
    @SuppressWarnings("deprecation")
    public static boolean isOnline(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return true;   // 权限异常时不拦请求，交给真实请求去暴露结果
        }
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network active = cm.getActiveNetwork();
            if (active == null) {
                return false;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(active);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }
}
