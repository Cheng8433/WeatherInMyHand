package com.smog.weatherapp.net;

import android.content.Context;
import android.content.res.Resources;

import com.smog.weatherapp.BuildConfig;
import com.smog.weatherapp.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 天气后端的 HTTP 客户端：发请求、解析统一 JSON 契约
 * （{@code {success:true,data}} / {@code {success:false,message}}，降级数据带同级 {@code stale}），
 * 把结果或失败原因回调给调用方。
 *
 * <p>它刻意【不】管以下几件事——这些属于 UI 层语义，留在 MainActivity：
 * <ul>
 *   <li>请求序号 {@code uiApplySeq} 的「最后发起者胜」裁定。并发返回时谁覆盖谁是界面问题，
 *       不是网络问题；跟进网络层搬走会把「旧城市覆盖新城市」的老坑重新挖开。</li>
 *   <li>加载条计数（beginLoad/endLoad）与错误浮层。</li>
 *   <li>失败后走哪种兜底：手动搜索、刷新当前城、GPS 定位三者的语义与话术都不同。</li>
 * </ul>
 *
 * <p>回调在 OkHttp 的工作线程上执行，需要切 UI 线程时由调用方自行 runOnUiThread（与抽取前一致）。
 */
public final class WeatherApi {

    /**
     * 结果回调。刻意区分「请求没送达」与「送到了但不可用」：调用方对这两种情况的处理与话术
     * 本来就不同（刷新当前城失败说「加载天气失败」，手动搜索失败则要区分「网络错误」与「未找到城市」）。
     */
    public interface ResultCallback {
        /** 成功解析出 data；stale=true 表示本次上游失败，后端降级返回了最近一次成功快照。 */
        void onData(JSONObject data, boolean stale);

        /** 请求未送达（连不上/超时/被取消）。文案由调用方按自身语义决定。 */
        void onTransportError();

        /** 收到了响应但不可用：success:false、非 2xx、解析失败、或响应体读取中断。message 可能为空串。 */
        void onFail(String message);
    }

    private final Resources res;
    private final OkHttpClient httpClient = new OkHttpClient();

    /** @param context 任意 Context，只取其 {@link Resources} 用于把错误翻成文案，不持有它 */
    public WeatherApi(Context context) {
        this.res = context.getResources();
    }

    /** 按城市名取综合天气。 */
    public void fetchByCity(String city, ResultCallback callback) {
        enqueue("weather/info?city=" + urlEncode(city), callback);
    }

    /** 按经纬度取综合天气；后端会逆地理编码，响应体里带回 cityName。 */
    public void fetchByLatLon(double lat, double lon, ResultCallback callback) {
        enqueue("weather/info?lat=" + lat + "&lon=" + lon, callback);
    }

    /**
     * 把搜索到的城市名上报后端，给服务端的城市缓存补一条。
     * 只报城市名——坐标由后端按城市名自填城市中心（位置属敏感个人信息，见 AGENTS.md）。
     * 失败静默：这只影响服务端缓存，与用户看到的任何内容无关。
     */
    public void saveCity(String cityName) {
        JSONObject json = new JSONObject();
        try {
            // latitude/longitude 置 0 是接口约定的「走城市名分支」标记
            // （后端 LocationController.saveLocation 先判 lat/lon 是否为 0，再落到 cityName 分支）
            json.put("cityName", cityName);
            json.put("latitude", 0);
            json.put("longitude", 0);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), json.toString());
        Request request = new Request.Builder()
                .url(BuildConfig.BACK_HOST_API + "location/save")
                .post(body)
                .build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    /** 界面销毁时取消所有在途请求，避免回调继续持有已销毁的 Activity。 */
    public void cancelAll() {
        httpClient.dispatcher().cancelAll();
    }

    private void enqueue(String query, ResultCallback callback) {
        Request request = new Request.Builder()
                .url(BuildConfig.BACK_HOST_API + query)
                .build();
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                callback.onTransportError();
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                handleJson(response, callback);
            }
        });
    }

    /** 解析统一 JSON 契约，在调用线程（OkHttp 工作线程）内执行回调。 */
    private void handleJson(Response response, ResultCallback callback) {
        boolean successful = response.isSuccessful();
        int code = response.code();
        String body;
        try {
            body = response.body() == null ? "" : response.body().string();
        } catch (IOException e) {
            // 读响应体途中连接中断（超时/断网）：必须走失败回调，否则调用方的加载条永远收不掉
            callback.onFail(res.getString(R.string.error_network_error));
            return;
        }
        if (successful) {
            try {
                JSONObject json = new JSONObject(body);
                if (json.optBoolean("success", false)) {
                    // stale 是契约里 data 的同级字段：true 表示上游失败、后端降级回了最近一次快照
                    callback.onData(json.optJSONObject("data"), json.optBoolean("stale", false));
                } else {
                    callback.onFail(json.optString("message", ""));
                }
            } catch (JSONException e) {
                callback.onFail(res.getString(R.string.error_parse_failed));
            }
        } else {
            callback.onFail(res.getString(R.string.error_server, code));
        }
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }
}
