package com.smog.weatherapp;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 隐私政策同意状态（本地持久化）。首次启动未同意前，App 不发起定位/联网取数。
 */
public final class PrivacyStore {

    private static final String PREFS = "weather_privacy";
    private static final String KEY_ACCEPTED = "policy_accepted_v1";

    private PrivacyStore() {
    }

    public static boolean accepted(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getBoolean(KEY_ACCEPTED, false);
    }

    public static void setAccepted(Context context, boolean accepted) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_ACCEPTED, accepted).apply();
    }
}
