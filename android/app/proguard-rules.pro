# ============================================================
# 掌中天气 release 混淆/压缩规则
#
# release 开启 R8（minifyEnabled + shrinkResources）后，凡是「名字在运行时才被解析」
# 的东西都会被剪掉或改名：XML 布局里的自定义 View、@JavascriptInterface 桥接方法、
# 反射调用、以及被 JS 侧回读的方法签名。下面按来源分类列出保留项，每条都写了「为什么」，
# 后续有人再动这个文件时能判断该不该删。
# ============================================================

# ------------------------------------------------------------
# AnyChart-Android 1.1.2
#
# 它是 WebView + assets/anychart-bundle.min.js 的实现，图表 API 靠「拼 JS 字符串」下发，
# 因此类名/方法名被混淆不影响出图：MainActivity 只直连 AnyChart.line()、Cartesian、
# ValueDataEntry、AnyChartView（XML 引用）这一小撮 API，R8 顺着直接引用就能保全都保住了，
# 不需要整库 -keep（实测整库保留会把 639 个类全拖进包里，白胖一大截）。
#
# 唯一「名字在运行期才解析」的是 JS 注入桥：AnyChartView 里
#     webView.addJavascriptInterface(bridge, "android")
# JS 侧按固定名字回调桥上的方法，所以带 @JavascriptInterface 注解的方法必须保名。
# AGP 默认 proguard-common.txt 已含此规则，这里显式写一遍兜底，
# 避免将来有人替换默认文件时静默失效（症状是图表交互回调永远不触发）。
#
# 另：com.anychart.charts.* 里的 xScale(Class)/yScale(Class) 会用
# Class#getDeclaredConstructor().newInstance() 反射造实例，但这两个方法本项目不调用，
# 随不可达代码一起被剪掉；不做额外保留。
# ------------------------------------------------------------
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# AnyChart 声明了指向可选依赖的签名，本项目未引入，抑制警告。
-dontwarn com.anychart.**

# ------------------------------------------------------------
# 自定义 View
# WeatherSceneView 在 page_today.xml 里以全限定类名实例化。AGP 会依据 aapt 生成
# 的资源引用规则自动保留，这里再显式保留三个构造器，防止极端情况下（比如以后改成
# 只在代码 new、或布局被 assets 动态加载）出现 ClassNotFoundException 崩溃。
# ------------------------------------------------------------
-keep class com.smog.weatherapp.WeatherSceneView {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ------------------------------------------------------------
# OkHttp / Okio
# 二者自带 META-INF/proguard 消费规则（含 Kotlin Metadata 保留），这里只补 -dontwarn，
# 免得它们对未引入的可选平台（Conscrypt/BouncyCastle/OpenJSSE 等）产生警告。
# ------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ------------------------------------------------------------
# 崩溃栈可读性
# 保留源文件名与行号，线上异常栈才能定位到具体行（否则只剩 a.b.c 与无行号的堆栈）。
# 不保留 LocalVariableTable：收益极小、体积代价明显。
# ------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
