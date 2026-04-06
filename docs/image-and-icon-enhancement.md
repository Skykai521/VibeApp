# 生成 App 的图标、UI 配图与网络图片加载方案

> 提升生成 App 的视觉质量：更好看的 icon、精美的 UI 配图、从网络加载图片的能力。

## 背景与动机

当前生成的 App 存在以下视觉体验问题：

1. **App Icon** — 虽然已有 `update_project_icon` 工具，AI 可以生成 vector drawable XML 作为 icon，但生成质量参差不齐
2. **UI 配图** — 生成的 App 没有任何装饰性图片/插画，界面显得单调
3. **网络图片** — 没有从网络加载图片的能力，无法展示动态图片内容（如新闻配图、商品图片等）

## 一、App Icon 优化

### 现状

- `update_project_icon` 工具已存在，接受 `backgroundXml` 和 `foregroundXml` 两个完整的 vector drawable XML
- Agent system prompt 已有基本的 icon 指导（108x108 viewport，hex 颜色，避免 @color 引用）
- `ProjectIconRenderer` 支持渲染 vector path、group transform、填充/描边

### 优化方向：强化 System Prompt 的 Icon 设计指导

当前 system prompt 中的 icon 指导偏技术规范，缺少**设计质量**方面的引导。建议补充：

```markdown
## Icon 设计质量指南

### 设计原则
- 使用简洁的几何形状，避免过于复杂的路径
- 背景使用渐变色或纯色（推荐 Material Design 调色板）
- 前景图案应留有足够内边距（safe zone 66x66 within 108x108）
- 优先使用圆角矩形、圆形等规整形状作为主体
- 配色不超过 3 种颜色

### 常见 Icon 模式
- 天气 App：太阳/云朵图案 + 蓝色渐变背景
- 计算器：数字/运算符 + 深色背景
- 笔记 App：铅笔/文档图案 + 暖色背景
- 工具类 App：齿轮/扳手图案 + 灰色背景

### 反面示例（避免）
- 过于细小的线条（在小尺寸下不可辨识）
- 纯文字 icon（缩小后无法阅读）
- 过多细节的复杂图案
```

## 二、UI 配图方案

### 方案分析

生成 App 是 Java + XML，图片资源需要在编译时打入 APK 或运行时从网络加载。

| 方案 | 可行性 | 说明 |
|------|:---:|------|
| AI 生成 vector drawable XML 装饰图 | 已支持 | 适合简单图标、装饰图案，复杂插画难度高 |
| Emoji/Unicode 字符替代图标 | 已支持 | 零成本，适合按钮图标、列表前缀 |
| Material Icons 内置 | 可实现 | 打包常用图标的 vector XML 集合 |
| 网络加载图片 | 需实现 | 依赖下面第三部分的网络图片加载能力 |

### 推荐策略：多层组合

1. **Emoji/Unicode** — 最轻量，AI 生成代码时直接使用 `TextView` 显示 emoji 作为图标
2. **Vector Drawable** — AI 生成简单的装饰性 SVG path 作为 drawable 资源
3. **网络图片** — 对于需要真实照片/复杂配图的场景，运行时从网络加载

### System Prompt 补充建议

```markdown
## UI 配图技巧

### 使用 Emoji 作为图标
在 TextView 中直接使用 emoji 字符，零成本实现视觉效果：
- 天气：☀️ 🌤️ 🌧️ ❄️ 🌡️
- 导航：🏠 ⚙️ 👤 🔍 ➕
- 状态：✅ ❌ ⚠️ ℹ️ 🔄

示例：
  <TextView android:text="☀️" android:textSize="48sp"/>

### 使用 Vector Drawable 装饰
为简单的图标和装饰图案生成 vector drawable XML，放入 res/drawable/。
保持路径简洁，避免超过 5 个 path 元素。

### 网络图片
对于需要真实照片的场景（新闻配图、天气背景等），
使用内置的 ImageLoader 工具类从网络加载。详见网络图片加载部分。
```

## 三、网络图片加载

### 技术选型

| 方案 | JARs | 体积 | Android 兼容 | API 复杂度 | 功能 |
|------|:---:|:---:|:---:|:---:|------|
| **BitmapFactory + HttpURLConnection（选定）** | 0 | 0 KB | 完全兼容 | 中等 | 基础加载 + 内存缓存 |
| Picasso 2.5.2 | 1 | ~120 KB | 兼容 | 极低 | 加载 + 缓存 + 占位图 |
| Volley ImageLoader | 1 | ~100 KB | 兼容 | 低 | 加载 + 缓存 |
| Glide | 5+ | ~2 MB | AAR 依赖 | — | 不可行 |
| Coil | Kotlin | — | Java 8 不兼容 | — | 不可行 |
| Fresco | .so 依赖 | — | 需 native lib | — | 不可行 |

### 选定方案：内置 BitmapFactory 工具类（零依赖）

使用 Android SDK 内置的 `BitmapFactory` + `HttpURLConnection` + `LruCache`，以**模板工具类**的形式提供给生成的 App。

#### 选择理由

1. **零额外依赖** — 不增加 APK 体积，不引入新 JAR
2. **Android 原生 API** — 完全兼容 API 29+，无兼容性风险
3. **模板内置** — AI 生成代码时直接调用，不需要了解底层实现
4. **可控性强** — 代码完全自有，可按需调整缓存策略

### 工具类设计

#### SimpleImageLoader 模板类

放入模板项目的 `$packagename/` 目录，生成 App 时自动包含。

```java
package $packagename;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 轻量级网络图片加载器，支持内存缓存。
 * 用法：SimpleImageLoader.getInstance().load(url, imageView);
 */
public class SimpleImageLoader {

    private static SimpleImageLoader instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private SimpleImageLoader() {
        // 使用可用内存的 1/8 作为缓存
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
        executor = Executors.newFixedThreadPool(3);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public static synchronized SimpleImageLoader getInstance() {
        if (instance == null) {
            instance = new SimpleImageLoader();
        }
        return instance;
    }

    /**
     * 加载网络图片到 ImageView
     */
    public void load(String url, ImageView imageView) {
        load(url, imageView, 0, 0);
    }

    /**
     * 加载网络图片到 ImageView，支持占位图和错误图
     * @param placeholderResId 加载中显示的资源 ID，0 表示无
     * @param errorResId 加载失败显示的资源 ID，0 表示无
     */
    public void load(String url, ImageView imageView, int placeholderResId, int errorResId) {
        // 设置 tag 防止列表复用错乱
        imageView.setTag(url);

        // 先查缓存
        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        // 显示占位图
        if (placeholderResId != 0) {
            imageView.setImageResource(placeholderResId);
        }

        // 异步加载
        executor.execute(() -> {
            Bitmap bitmap = downloadBitmap(url);
            mainHandler.post(() -> {
                // 检查 imageView 是否仍对应当前 url
                if (url.equals(imageView.getTag())) {
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap);
                    } else if (errorResId != 0) {
                        imageView.setImageResource(errorResId);
                    }
                }
            });
        });
    }

    /**
     * 仅下载图片，返回 Bitmap（需在子线程调用）
     */
    public Bitmap downloadBitmap(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                return null;
            }

            InputStream input = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            input.close();

            if (bitmap != null) {
                memoryCache.put(urlStr, bitmap);
            }
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
```

#### 使用方式

AI 生成代码时直接调用：

```java
// 加载网络图片到 ImageView
SimpleImageLoader.getInstance().load(
    "https://example.com/image.jpg",
    imageView
);

// 带占位图和错误图
SimpleImageLoader.getInstance().load(
    "https://example.com/image.jpg",
    imageView,
    R.drawable.placeholder,
    R.drawable.error
);

// 在子线程中直接获取 Bitmap
Bitmap bitmap = SimpleImageLoader.getInstance().downloadBitmap(url);
```

### 集成方式

#### 需要变更的文件

| 文件 | 变更内容 |
|------|----------|
| `templates/.../java/$packagename/SimpleImageLoader.java` | 新增模板工具类 |
| `agent-system-prompt.md` | 添加 SimpleImageLoader 使用说明 |
| `templates/.../AndroidManifest.xml` | 添加 INTERNET 权限（与 Jsoup 方案共享） |

#### Agent System Prompt 补充

```markdown
## 网络图片加载

项目内置了 SimpleImageLoader 工具类，可直接加载网络图片：

### 基本用法
  SimpleImageLoader.getInstance().load(imageUrl, imageView);

### 带占位图
  SimpleImageLoader.getInstance().load(imageUrl, imageView,
      R.drawable.placeholder, R.drawable.error);

### 特性
- 自动内存缓存（LruCache，应用内存的 1/8）
- 自动子线程加载 + 主线程回调
- 防止列表复用时图片错乱（tag 机制）
- 支持占位图和错误图
- 自动跟随重定向

### 注意事项
- 需要 INTERNET 权限（已默认声明）
- 不支持 GIF 动图
- 无磁盘缓存（应用重启后需重新加载）
- 大图建议先缩放再显示，避免 OOM
```

## 四、与 Jsoup 网络方案的关系

本方案与 `docs/jsoup-network-integration.md` 中的 Jsoup 方案是互补关系：

| 能力 | Jsoup | SimpleImageLoader |
|------|:---:|:---:|
| HTTP 请求 | 通用 HTML/JSON | 仅图片 |
| HTML 解析 | 支持 | 不支持 |
| 图片加载 | 不支持 | 支持 |
| 内存缓存 | 无 | LruCache |
| 额外 JAR | ~440 KB | 0 KB |

两者共享 INTERNET 权限，可独立或配合使用（如用 Jsoup 爬取页面中的图片 URL，再用 SimpleImageLoader 加载展示）。

## 五、实施优先级

1. **INTERNET 权限** — 与 Jsoup 方案共享，优先实施
2. **SimpleImageLoader 模板类** — 新增模板文件
3. **Agent system prompt 更新** — 添加图片加载 + UI 配图指导
4. **Icon 设计指导增强** — 优化 system prompt 中的 icon 质量指引
