package com.smog.weatherapp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.util.Random;

/**
 * 首页「今天」页的整屏动效天空背景。
 *
 * 设计原则：
 *  - 主题定色、天气定现象：天空渐变与整体色调跟随当前主题（顶部取 headerBackground，
 *    底部取 pageBackground，视觉上与顶栏/头部同色系延续）；具体动效类型由 {@link WeatherFormat.Kind}
 *    决定（晴出太阳、雨落雨丝、雪飘雪、雷闪屏…）。
 *  - 夜空主题（ThemeHelper index==1）当作夜间处理：出月亮 + 星星，其余主题为白昼。
 *  - 纯 Canvas 轻量绘制，帧循环由 {@link #postOnAnimation(Runnable)} 驱动，dt 计时推进，
 *    仅在可见时运行（切换 Tab / 退后台 / 脱离窗口即停），避免空转耗电。
 */
public class WeatherSceneView extends View {

    private WeatherFormat.Kind kind = WeatherFormat.Kind.DEFAULT;
    private boolean night;

    // 主题解析色
    private int headerColor;
    private int pageColor;
    private LinearGradient skyGradient;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random rand = new Random(20260907L);

    private float sceneTime;          // 累计动画时间（秒），驱动闪烁/摆动相位
    private long lastFrameMs;
    private boolean animating;
    private boolean needsRebuild = true;

    // ---- 粒子系统（按当前 kind 重建，见 rebuild） ----
    private int cloudCount;
    private float[] cloudX, cloudY, cloudR, cloudVx;
    private float cloudAlpha;         // 云主体不透明度
    private boolean showSun, showMoon;
    private int starCount;
    private float[] starX, starY, starR, starPh;
    private int dropCount;
    private float[] dropX, dropY, dropLen, dropVy;
    private int flakeCount;
    private float[] flakeX, flakeY, flakeR, flakeVy, flakeAmp, flakeFreq, flakePh;
    private int streakCount;
    private float[] streakX, streakY, streakLen, streakSpd;
    private int fogCount;
    private float[] fogY, fogRx, fogRy;
    private float veilAlpha;          // 阴/雨/雾等压暗氛围用（0=无）
    // 雷暴
    private float flash = 0f;         // >0 时亮屏强度，随时间衰减
    private float flashTimer = 3f;    // 距下次闪电的剩余时间
    private Path boltPath;
    private float boltX;
    private float boltY;

    public WeatherSceneView(Context context) {
        super(context);
        init();
    }

    public WeatherSceneView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        night = ThemeHelper.isDark(ThemeHelper.currentIndex(getContext()));
        headerColor = attrColor(com.smog.weatherapp.R.attr.headerBackground);
        pageColor = attrColor(com.smog.weatherapp.R.attr.pageBackground);
    }

    // ==================== 对外接口 ====================

    /** 由外部按天气文本设置现象（复用 WeatherFormat.kindOf 的关键字归类）。 */
    public void setWeather(String weatherText) {
        setKind(WeatherFormat.kindOf(weatherText));
    }

    public void setKind(WeatherFormat.Kind newKind) {
        if (newKind == null) return;
        if (kind == newKind && !needsRebuild) return;
        kind = newKind;
        needsRebuild = true;
        if (getWidth() > 0 && getHeight() > 0) {
            rebuild(getWidth(), getHeight());
        }
        updateRunning();
        invalidate();
    }

    // ==================== 生命周期与帧循环 ====================

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!animating) return;
            long now = SystemClock.elapsedRealtime();
            float dt = (now - lastFrameMs) / 1000f;
            lastFrameMs = now;
            if (dt <= 0f || dt > 0.1f) dt = 0.016f; // 长时间停顿后按单帧处理，避免粒子瞬间飞过
            sceneTime += dt;
            advance(dt);
            invalidate();
            postOnAnimation(frame);
        }
    };

    private boolean shouldAnimate() {
        return getWidth() > 0 && getHeight() > 0
                && getVisibility() == VISIBLE
                && isAttachedToWindow()
                && getWindowVisibility() == VISIBLE;
    }

    private void updateRunning() {
        boolean want = shouldAnimate();
        if (want && !animating) {
            animating = true;
            lastFrameMs = SystemClock.elapsedRealtime();
            removeCallbacks(frame);
            postOnAnimation(frame);
        } else if (!want && animating) {
            animating = false;
            removeCallbacks(frame);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateRunning();
    }

    @Override
    protected void onDetachedFromWindow() {
        animating = false;
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateRunning();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateRunning();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        rebuild(w, h);
        updateRunning();
    }

    // ==================== 粒子构建 ====================

    private void rebuild(int w, int h) {
        if (w <= 0 || h <= 0) return;
        needsRebuild = false;

        float d = getResources().getDisplayMetrics().density;
        // 按画布面积缩放粒子量，避免超大屏过多、小屏过少
        float scale = Math.max(0.35f, Math.min(1.4f, (w * h) / (1080f * 1920f)));
        float minDim = Math.min(w, h);

        // 先清空上一种现象残留的粒子，避免切城市/现象时旧的还在飘
        dropCount = 0;
        flakeCount = 0;
        streakCount = 0;
        fogCount = 0;
        starCount = 0;
        flash = 0f;
        boltPath = null;

        // 主题渐变（缓存）
        skyGradient = new LinearGradient(0, 0, 0, h, headerColor, pageColor, Shader.TileMode.CLAMP);

        showSun = !night;
        showMoon = night;
        veilAlpha = veilFor(kind, night);
        cloudAlpha = night ? 0.36f : 0.72f;

        // 云：阴/雨/雪等密些，晴天疏些；云主体压在上 2/3，便于与浅色卡片底形成层次
        cloudCount = cloudCountFor(kind, night);
        cloudX = new float[cloudCount];
        cloudY = new float[cloudCount];
        cloudR = new float[cloudCount];
        cloudVx = new float[cloudCount];
        for (int i = 0; i < cloudCount; i++) {
            float r = minDim * (0.07f + rand.nextFloat() * 0.10f);
            cloudX[i] = -r + rand.nextFloat() * (w + r * 2f);
            cloudY[i] = h * (0.05f + rand.nextFloat() * 0.62f);
            cloudR[i] = r;
            cloudVx[i] = d * (2f + rand.nextFloat() * 5f) * (rand.nextBoolean() ? 1f : -1f);
        }

        // 夜空天象：晴夜可见星星月亮；云厚的夜里让星月被遮（不做星）
        boolean celestialVisible = kind == WeatherFormat.Kind.SUNNY
                || kind == WeatherFormat.Kind.PARTLY_CLOUDY
                || kind == WeatherFormat.Kind.DEFAULT
                || kind == WeatherFormat.Kind.WIND;
        if (night && celestialVisible) {
            starCount = (int) (90 * scale);
            starX = new float[starCount];
            starY = new float[starCount];
            starR = new float[starCount];
            starPh = new float[starCount];
            for (int i = 0; i < starCount; i++) {
                starX[i] = rand.nextFloat() * w;
                starY[i] = rand.nextFloat() * h * 0.75f;
                starR[i] = d * (0.7f + rand.nextFloat() * 1.4f);
                starPh[i] = rand.nextFloat() * (float) (Math.PI * 2);
            }
            showMoon = true;
        } else if (night) {
            showMoon = false;
            starCount = 0;
        }

        // 雨 / 雷
        if (kind == WeatherFormat.Kind.RAIN || kind == WeatherFormat.Kind.THUNDER) {
            dropCount = (int) (120 * scale);
            dropX = new float[dropCount];
            dropY = new float[dropCount];
            dropLen = new float[dropCount];
            dropVy = new float[dropCount];
            for (int i = 0; i < dropCount; i++) {
                dropX[i] = rand.nextFloat() * w;
                dropY[i] = rand.nextFloat() * (h * 1.3f);
                dropLen[i] = d * (9f + rand.nextFloat() * 16f);
                dropVy[i] = d * (240f + rand.nextFloat() * 160f) * (0.6f + 0.4f * scale);
            }
            if (kind == WeatherFormat.Kind.THUNDER) {
                boltPath = new Path();
                buildBoltPath(boltPath, w, h, d);
                flashTimer = 1.2f + rand.nextFloat() * 3f;
            }
        }

        // 雪
        if (kind == WeatherFormat.Kind.SNOW) {
            flakeCount = (int) (70 * scale);
            flakeX = new float[flakeCount];
            flakeY = new float[flakeCount];
            flakeR = new float[flakeCount];
            flakeVy = new float[flakeCount];
            flakeAmp = new float[flakeCount];
            flakeFreq = new float[flakeCount];
            flakePh = new float[flakeCount];
            for (int i = 0; i < flakeCount; i++) {
                flakeX[i] = rand.nextFloat() * w;
                flakeY[i] = rand.nextFloat() * h;
                flakeR[i] = d * (1f + rand.nextFloat() * 2.4f);
                flakeVy[i] = d * (34f + rand.nextFloat() * 46f);
                flakeAmp[i] = d * (6f + rand.nextFloat() * 18f);
                flakeFreq[i] = 0.8f + rand.nextFloat() * 1.6f;
                flakePh[i] = rand.nextFloat() * (float) (Math.PI * 2);
            }
        }

        // 风
        if (kind == WeatherFormat.Kind.WIND) {
            streakCount = (int) (26 * scale);
            streakX = new float[streakCount];
            streakY = new float[streakCount];
            streakLen = new float[streakCount];
            streakSpd = new float[streakCount];
            for (int i = 0; i < streakCount; i++) {
                streakY[i] = rand.nextFloat() * h;
                streakLen[i] = d * (24f + rand.nextFloat() * 60f);
                streakSpd[i] = d * (160f + rand.nextFloat() * 220f);
                streakX[i] = w + rand.nextFloat() * w;
            }
        }

        // 雾 / 霾
        if (kind == WeatherFormat.Kind.FOG) {
            fogCount = 6;
            fogY = new float[fogCount];
            fogRx = new float[fogCount];
            fogRy = new float[fogCount];
            for (int i = 0; i < fogCount; i++) {
                fogY[i] = h * (0.12f + i * 0.15f + rand.nextFloat() * 0.06f);
                fogRx[i] = w * (0.7f + rand.nextFloat() * 0.5f);
                fogRy[i] = h * (0.05f + rand.nextFloat() * 0.04f);
            }
        }
    }

    private void buildBoltPath(Path p, int w, int h, float d) {
        p.reset();
        float len = h * (0.5f + rand.nextFloat() * 0.25f);
        float x = 0, y = 0;
        float seg = len / (5f + rand.nextInt(4));
        p.moveTo(x, y);
        for (int i = 0; i < 6; i++) {
            x += rand.nextFloat() * d * 14f - d * 7f;
            y += seg * (0.7f + rand.nextFloat() * 0.5f);
            p.lineTo(x, y);
        }
        boltY = h * (0.05f + rand.nextFloat() * 0.3f);
    }

    // ---- 各现象参数表 ----

    private static int cloudCountFor(WeatherFormat.Kind k, boolean night) {
        switch (k) {
            case SUNNY: return 2;
            case PARTLY_CLOUDY:
            case DEFAULT: return night ? 3 : 5;
            case OVERCAST: return 7;
            case RAIN:
            case THUNDER: return 6;
            case SNOW: return 5;
            case FOG: return 3;
            case WIND: return 3;
            default: return 3;
        }
    }

    private static float veilFor(WeatherFormat.Kind k, boolean night) {
        float base = night ? 0.30f : 0.15f;
        switch (k) {
            case OVERCAST: return base * 1.0f;
            case RAIN: return base * 1.15f;
            case THUNDER: return base * 1.3f;
            case SNOW: return base * 0.8f;
            case FOG: return base * 1.1f;
            default: return 0f;
        }
    }

    // ==================== 每帧推进 ====================

    private void advance(float dt) {
        int w = getWidth();
        int h = getHeight();

        if (cloudCount > 0) {
            for (int i = 0; i < cloudCount; i++) {
                cloudX[i] += cloudVx[i] * dt;
                float half = cloudR[i] * 1.8f;
                if (cloudVx[i] > 0 && cloudX[i] > w + half) cloudX[i] = -half;
                else if (cloudVx[i] < 0 && cloudX[i] < -half) cloudX[i] = w + half;
            }
        }

        if (dropCount > 0) {
            for (int i = 0; i < dropCount; i++) {
                dropY[i] += dropVy[i] * dt;
                if (dropY[i] > h * 1.15f) {
                    dropY[i] = -dropLen[i];
                    dropX[i] = rand.nextFloat() * w;
                }
            }
        }

        if (flakeCount > 0) {
            for (int i = 0; i < flakeCount; i++) {
                flakeY[i] += flakeVy[i] * dt;
                flakeX[i] += (float) Math.cos(sceneTime * flakeFreq[i] + flakePh[i]) * flakeAmp[i] * dt * 1.6f;
                if (flakeY[i] > h + flakeR[i]) {
                    flakeY[i] = -flakeR[i];
                    flakeX[i] = rand.nextFloat() * w;
                }
            }
        }

        if (streakCount > 0) {
            for (int i = 0; i < streakCount; i++) {
                streakX[i] -= streakSpd[i] * dt;
                if (streakX[i] + streakLen[i] < 0) streakX[i] = w + streakLen[i] * 0.3f;
            }
        }

        if (fogCount > 0) {
            for (int i = 0; i < fogCount; i++) {
                fogY[i] += (float) Math.sin(sceneTime * 0.15f + i) * 3f * dt;
                // fog bands 用 sin 漂移 + 循环长条（前进方向为 -x，超出后回绕）
            }
        }

        // 雷暴：计时触发闪屏与闪电
        if (kind == WeatherFormat.Kind.THUNDER) {
            flashTimer -= dt;
            if (flashTimer <= 0f) {
                flash = 1f;
                boltX = w * (0.15f + rand.nextFloat() * 0.6f);
                flashTimer = 2.5f + rand.nextFloat() * 3.5f;
            }
            if (flash > 0f) flash = Math.max(0f, flash - dt * 6f);
        }
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (needsRebuild) rebuild(w, h);

        // 天空渐变
        paint.setShader(skyGradient);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(null);

        // 夜：星星
        if (starCount > 0) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < starCount; i++) {
                float a = 0.25f + 0.6f * (0.5f + 0.5f * (float) Math.sin(sceneTime * 1.6f + starPh[i]));
                paint.setARGB((int) (255 * a), 255, 250, 235);
                canvas.drawCircle(starX[i], starY[i], starR[i], paint);
            }
        }

        // 日 / 月
        if (showMoon) {
            drawMoon(canvas, w, h);
        } else if (showSun) {
            drawSun(canvas, w, h);
        }

        // 云
        drawClouds(canvas, w, h);

        // 雨 / 雷（含闪电）
        if (dropCount > 0) {
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 1.1f));
            paint.setARGB((int) (255 * 0.5f), 225, 236, 246);
            float slant = 0.45f;
            for (int i = 0; i < dropCount; i++) {
                canvas.drawLine(dropX[i], dropY[i], dropX[i] - slant * dropLen[i], dropY[i] - dropLen[i], paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        // 雪
        if (flakeCount > 0) {
            paint.setStyle(Paint.Style.FILL);
            paint.setARGB((int) (255 * 0.8f), 255, 255, 255);
            for (int i = 0; i < flakeCount; i++) {
                canvas.drawCircle(flakeX[i], flakeY[i], flakeR[i], paint);
            }
        }

        // 风
        if (streakCount > 0) {
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.6f);
            int streakColor = night ? 0x60FFFFFF : 0x556B8CA8;
            paint.setColor(streakColor);
            for (int i = 0; i < streakCount; i++) {
                canvas.drawLine(streakX[i], streakY[i], streakX[i] + streakLen[i], streakY[i], paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }

        // 雾 / 霾（颜色随明暗主题自适应，保证浅色天空下也可见）
        if (fogCount > 0) {
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < fogCount; i++) {
                float cx = w * 0.5f + (float) Math.sin(sceneTime * 0.2f + i * 1.7f) * w * 0.3f;
                if (night) {
                    paint.setARGB(34, 220, 228, 240);
                } else {
                    paint.setARGB(30, 92, 112, 138);
                }
                canvas.drawOval(cx - fogRx[i], fogY[i] - fogRy[i], cx + fogRx[i], fogY[i] + fogRy[i], paint);
            }
        }

        // 氛围压暗（阴/雨/雪/雾）
        if (veilAlpha > 0f) {
            paint.setColor(0x000000);
            paint.setAlpha((int) (255 * veilAlpha));
            canvas.drawRect(0, 0, w, h, paint);
            paint.setAlpha(255);
        }

        // 雷暴：闪电 + 亮屏
        if (kind == WeatherFormat.Kind.THUNDER && boltPath != null) {
            if (flash > 0.6f) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(getResources().getDisplayMetrics().density * 3f);
                paint.setARGB((int) (255 * flash), 255, 244, 214);
                canvas.save();
                canvas.translate(boltX, boltY);
                canvas.drawPath(boltPath, paint);
                canvas.restore();
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xFFFFFFFF);
                paint.setAlpha((int) (255 * flash * 0.16f));
                canvas.drawRect(0, 0, w, h, paint);
                paint.setAlpha(255);
            }
        }
    }

    private void drawClouds(Canvas canvas, int w, int h) {
        if (cloudCount == 0) return;
        // 云的底色（深色投影），浅色天空下仍可见层次
        for (int i = 0; i < cloudCount; i++) {
            float cx = cloudX[i];
            float cy = cloudY[i];
            float r = cloudR[i];
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);

            // 主色
            int a = (int) (255 * cloudAlpha);
            if (night) {
                paint.setARGB(a, 232, 240, 250);
            } else {
                paint.setARGB(a, 255, 255, 255);
            }
            drawPuffs(canvas, cx, cy, r, 0, 0);

            // 云底阴影（让亮色天空下云朵不“消失”）
            if (!night) {
                paint.setARGB((int) (255 * 0.10f), 84, 110, 140);
                drawPuffs(canvas, cx, cy + r * 0.10f, r, 0.02f, 0.05f);
            }
        }
    }

    /** 用多个交叠圆拼一朵云；ox/oy 为整体偏移，用于叠一层云底阴影。 */
    private void drawPuffs(Canvas canvas, float cx, float cy, float r, float ox, float oy) {
        canvas.drawCircle(cx + ox, cy + oy, r * 0.62f, paint);
        canvas.drawCircle(cx - r * 0.55f + ox, cy + r * 0.08f + oy, r * 0.46f, paint);
        canvas.drawCircle(cx + r * 0.55f + ox, cy + r * 0.08f + oy, r * 0.46f, paint);
        canvas.drawCircle(cx - r * 0.28f + ox, cy - r * 0.22f + oy, r * 0.36f, paint);
        canvas.drawCircle(cx + r * 0.30f + ox, cy - r * 0.20f + oy, r * 0.36f, paint);
    }

    private void drawSun(Canvas canvas, int w, int h) {
        float minDim = Math.min(w, h);
        float r = minDim * 0.075f;
        float cx = w * 0.72f;
        float cy = h * 0.16f;
        paint.setStyle(Paint.Style.FILL);
        paint.setARGB(20, 255, 240, 200);
        canvas.drawCircle(cx, cy, r * 2.8f, paint);
        paint.setARGB(32, 255, 235, 175);
        canvas.drawCircle(cx, cy, r * 2.0f, paint);
        paint.setARGB(60, 255, 230, 150);
        canvas.drawCircle(cx, cy, r * 1.45f, paint);
        paint.setARGB(255, 255, 248, 214);
        canvas.drawCircle(cx, cy, r * 0.92f, paint);
        paint.setARGB(255, 255, 255, 255);
        canvas.drawCircle(cx, cy, r * 0.55f, paint);
    }

    private void drawMoon(Canvas canvas, int w, int h) {
        float minDim = Math.min(w, h);
        float r = minDim * 0.045f;
        float cx = w * 0.74f;
        float cy = h * 0.12f;
        paint.setStyle(Paint.Style.FILL);
        paint.setARGB(26, 255, 248, 220);
        canvas.drawCircle(cx, cy, r * 3.2f, paint);
        paint.setARGB(46, 255, 246, 210);
        canvas.drawCircle(cx, cy, r * 2.2f, paint);
        paint.setARGB(235, 248, 242, 222);
        canvas.drawCircle(cx, cy, r, paint);
    }

    // ==================== 辅助 ====================

    private int attrColor(int attrRes) {
        TypedValue tv = new TypedValue();
        if (getContext().getTheme().resolveAttribute(attrRes, tv, true)) {
            return tv.data;
        }
        return 0xFF2196F3;
    }
}
