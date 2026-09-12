package com.smog.weatherapp;

import android.app.Activity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 全局加载条 + 错误重试浮层：顶部细加载条的计数与显隐、「加载失败 + 重试」卡片的状态与文案，
 * 以及「重试」按钮点了之后该重做什么。
 *
 * <p>单独成一个类，是因为这套状态是<b>跨所有请求共用的</b>——搜索、刷新当前城、GPS 定位三条路径
 * 都会 {@code beginLoad/endLoad}，都可能 {@code showErrorPanel}。留在 Activity 里时，这块状态与
 * 「三个请求各自怎么兜底」的语义混在一处，改加载条要在一千行里翻找。
 *
 * <p>它【不】管的事（这些是 Activity 的活儿）：
 * <ul>
 *   <li>失败后走哪种兜底（缓存 / toast / 错误浮层）：那是 {@code fallbackToCache}、
 *       {@code handleSearchFailure} 的判断，且各调用点语义不同。</li>
 *   <li>重试要重发哪个请求：由调用方通过 {@link #setRetryAction} 注入，本类只负责在按钮被点时执行它。</li>
 * </ul>
 *
 * <p>构造须在 {@code setContentView} 之后。视图的 {@code != null} 判断照搬自抽取前的
 * {@code MainActivity}（保持零行为变化），并非本类新加的防御。
 */
final class LoadOverlay {

    private final Activity activity;
    private final View loadingBar;
    private final View errorPanel;
    private final TextView tvErrorMsg;

    /** 在途请求计数：多个请求重叠时，全部结束才收加载条 */
    private int pendingLoads = 0;

    /** 「重试」按钮要重做的事；未设过时为 null，按钮点了也不做事 */
    private Runnable retryAction = null;

    LoadOverlay(Activity activity) {
        this.activity = activity;
        this.loadingBar = activity.findViewById(R.id.loadingBar);
        this.errorPanel = activity.findViewById(R.id.errorPanel);
        this.tvErrorMsg = activity.findViewById(R.id.errorMsg);
        activity.findViewById(R.id.btnRetry).setOnClickListener(v -> {
            // 不提前收起卡片：若重试仍失败，showErrorPanel 会因“卡片已可见”而补一条 toast 反馈
            if (retryAction != null) {
                retryAction.run();
            }
        });
        activity.findViewById(R.id.btnCancelRetry)
                .setOnClickListener(v -> errorPanel.setVisibility(View.GONE));
    }

    /** 设定「重试」按钮要重做的事。各请求的重试语义不同，故由发起方每次注入。 */
    void setRetryAction(Runnable action) {
        this.retryAction = action;
    }

    /** 一次天气请求开始（计数式，支持多个请求重叠，全部结束才收加载条）。线程安全。 */
    void beginLoad() {
        activity.runOnUiThread(() -> {
            pendingLoads++;
            if (pendingLoads == 1 && loadingBar != null) {
                loadingBar.setVisibility(View.VISIBLE);
            }
        });
    }

    /** 一次天气请求结束。线程安全（可能由 OkHttp 回调线程调用）。 */
    void endLoad() {
        activity.runOnUiThread(() -> {
            if (pendingLoads > 0) {
                pendingLoads--;
            }
            if (pendingLoads <= 0 && loadingBar != null) {
                pendingLoads = 0;
                loadingBar.setVisibility(View.GONE);
            }
        });
    }

    void hideErrorPanel() {
        if (errorPanel != null) {
            errorPanel.setVisibility(View.GONE);
        }
    }

    /** 显示错误浮层；若浮层本就可见（说明这次是重试又失败）则补一条 toast 反馈。线程安全。 */
    void showErrorPanel(final String msg) {
        activity.runOnUiThread(() -> {
            boolean retried = errorPanel != null && errorPanel.getVisibility() == View.VISIBLE;
            tvErrorMsg.setText(msg == null || msg.isEmpty()
                    ? activity.getString(R.string.error_load_failed_default) : msg);
            if (errorPanel != null) {
                errorPanel.setVisibility(View.VISIBLE);
            }
            if (retried) {
                Toast.makeText(activity,
                        msg == null || msg.isEmpty() ? activity.getString(R.string.error_load_failed_short) : msg,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }
}
