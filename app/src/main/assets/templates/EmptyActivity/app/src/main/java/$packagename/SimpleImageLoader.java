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
 * Lightweight network image loader with memory cache.
 * Usage: SimpleImageLoader.getInstance().load(url, imageView);
 *
 * DO NOT modify or delete this file.
 */
public class SimpleImageLoader {

    private static SimpleImageLoader instance;
    private final LruCache<String, Bitmap> memoryCache;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private SimpleImageLoader() {
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
     * Load a network image into an ImageView.
     */
    public void load(final String url, final ImageView imageView) {
        load(url, imageView, 0, 0);
    }

    /**
     * Load a network image into an ImageView with placeholder and error drawables.
     * @param placeholderResId resource ID shown while loading, 0 for none
     * @param errorResId resource ID shown on failure, 0 for none
     */
    public void load(final String url, final ImageView imageView,
                     final int placeholderResId, final int errorResId) {
        imageView.setTag(url);

        Bitmap cached = memoryCache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }

        if (placeholderResId != 0) {
            imageView.setImageResource(placeholderResId);
        }

        executor.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = downloadBitmap(url);
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(imageView.getTag())) {
                            if (bitmap != null) {
                                imageView.setImageBitmap(bitmap);
                            } else if (errorResId != 0) {
                                imageView.setImageResource(errorResId);
                            }
                        }
                    }
                });
            }
        });
    }

    /**
     * Download an image and return the Bitmap. Must be called from a background thread.
     */
    public Bitmap downloadBitmap(String urlStr) {
        HttpURLConnection conn = null;
        InputStream input = null;
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

            input = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(input);

            if (bitmap != null) {
                memoryCache.put(urlStr, bitmap);
            }
            return bitmap;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        } finally {
            if (input != null) {
                try { input.close(); } catch (Exception ignored) {}
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
