package com.smog.weatherapp;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 收藏城市弹窗：列出已收藏城市（点一行即切换过去），底部一行对「当前城市」收藏/取消收藏。
 *
 * <p>平铺普通类（同类先例见 {@link LoadOverlay}）：自己 inflate 弹窗内容、自己 findViewById、
 * 每次增删后 {@link #rebuild()} 整体重画。列表最多 {@link FavoritesStore#MAX_CITIES} 项，重画成本可忽略，
 * 换来的是一份「渲染只依赖当前存储状态」的简单实现——不需要 Adapter/notifyItemChanged 那套增量更新。
 *
 * <p>它【不】管切城的实际加载：只把选中的城市名回调给 Activity，由后者走
 * {@code searchWeatherByCity}（用户明确指定某城 → 失败只回落到该城自己的缓存，绝不跨城顶替）。
 */
final class FavoritesDialog {

    /** 用户点了某个收藏城市。 */
    interface Listener {
        void onPickCity(String city);
    }

    private final Activity activity;
    /** 打开弹窗那一刻正在看的城市；为空表示还没有城市可收藏。 */
    private final String currentCity;
    private final Listener listener;

    private LinearLayout container;
    private TextView tvEmpty;
    private TextView btnToggleCurrent;
    private AlertDialog dialog;

    FavoritesDialog(Activity activity, String currentCity, Listener listener) {
        this.activity = activity;
        this.currentCity = currentCity == null ? "" : currentCity;
        this.listener = listener;
    }

    void show() {
        View content = LayoutInflater.from(activity).inflate(R.layout.dialog_favorites, null);
        container = content.findViewById(R.id.favoritesContainer);
        tvEmpty = content.findViewById(R.id.tvFavoritesEmpty);
        btnToggleCurrent = content.findViewById(R.id.btnToggleCurrent);
        btnToggleCurrent.setOnClickListener(v -> toggleCurrent());

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.favorites_title)
                .setView(content)
                .setNegativeButton(R.string.action_close, null)
                .create();
        rebuild();
        dialog.show();
    }

    /** 按当前存储状态重画列表与底部行（收藏/取消/删除后调用）。 */
    private void rebuild() {
        List<String> cities = FavoritesStore.all(activity);
        container.removeAllViews();
        tvEmpty.setVisibility(cities.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (final String city : cities) {
            View row = inflater.inflate(R.layout.item_favorite, container, false);
            TextView name = row.findViewById(R.id.tvFavCity);
            name.setText(city.equals(currentCity)
                    ? activity.getString(R.string.favorites_current_suffix, city)
                    : city);
            // 整行 = 切到该城市
            row.setOnClickListener(v -> {
                if (dialog != null) {
                    dialog.dismiss();
                }
                listener.onPickCity(city);
            });
            ImageButton delete = row.findViewById(R.id.btnFavDelete);
            delete.setContentDescription(activity.getString(R.string.cd_favorites_delete, city));
            delete.setOnClickListener(v -> {
                FavoritesStore.remove(activity, city);
                toast(activity.getString(R.string.favorites_removed, city));
                rebuild();
            });
            container.addView(row);
        }

        updateToggleRow();
    }

    /** 底部行：无城市 → 置灰；已收藏 → 取消收藏；未收藏 → 收藏。 */
    private void updateToggleRow() {
        if (currentCity.isEmpty()) {
            btnToggleCurrent.setText(R.string.favorites_no_city);
            btnToggleCurrent.setEnabled(false);
            btnToggleCurrent.setAlpha(0.5f);
            return;
        }
        btnToggleCurrent.setEnabled(true);
        btnToggleCurrent.setAlpha(1f);
        boolean favorited = FavoritesStore.isFavorite(activity, currentCity);
        btnToggleCurrent.setText(favorited
                ? activity.getString(R.string.favorites_remove_current, currentCity)
                : activity.getString(R.string.favorites_add_current, currentCity));
    }

    private void toggleCurrent() {
        if (currentCity.isEmpty()) {
            return;
        }
        if (FavoritesStore.isFavorite(activity, currentCity)) {
            FavoritesStore.remove(activity, currentCity);
            toast(activity.getString(R.string.favorites_removed, currentCity));
        } else if (FavoritesStore.add(activity, currentCity)) {
            toast(activity.getString(R.string.favorites_added, currentCity));
        } else {
            // 未写入只可能是已到上限（已收藏的分支在上面走掉了）
            toast(activity.getString(R.string.favorites_full, FavoritesStore.MAX_CITIES));
        }
        rebuild();
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }
}
