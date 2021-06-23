package com.example.imagecache;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;
import com.example.imagecache.disk.DiskLruCache;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图片缓存类封装
 */
public class ImageCacheManager {

    private static final String TAG = ImageCacheManager.class.getSimpleName();

    private BitmapFactory.Options options;

    // 内存缓存
    private LruCache<String, Bitmap> memoryCache;

    /**
     * Bitmap 复用池
     * 使用 inBitmap 复用选项
     * 需要获取图片时 , 优先从 Bitmap 复用池中查找
     * 这里使用弱引用保存该 Bitmap , 每次 GC 时都会回收该 Bitmap
     * 创建一个线程安全的 HashSet , 其中的元素是 Bitmap 弱引用
     *
     * 该 Bitmap 复用池的作用是 , 假如 Bitmap 对象长时间不使用 , 就会从内存缓存中移除
     *
     * Bitmap 回收策略 :
     * 3.0 以下系统中 , Bitmap 内存在 Native 层
     * 3.0 以上系统中 , Bitmap 内存在 Java 层
     * 8.0 及以上的系统中 , Bitmap 内存在 Native 层
     *
     * 因此这里需要处理 Bitmap 内存在 Native 层的情况 , 监控到 Java 层的弱引用被释放了
     * 需要调用 Bitmap 对象的 recycle 方法 , 释放 Native 层的内存
     *
     * 需要使用引用队列监控弱引用的释放情况
     */
    private Set<WeakReference<Bitmap>> reusePool;

    // 引用队列
    private ReferenceQueue referenceQueue;

    // 磁盘缓存
    private DiskLruCache mDiskLruCache;

    // 线程池
    private ExecutorService mCachedThreadPool;

    private String defaultDir = Environment.getExternalStorageDirectory().getAbsolutePath() +"/" + "imageCache" +"/";

    //线程池
    private ExecutorService singleThreadExecutor;

    private ImageCacheManager() {}

    public static class ImageCacheHolder {
        public static ImageCacheManager imageCache = new ImageCacheManager();
    }

    public static ImageCacheManager getInstance() {
        return ImageCacheHolder.imageCache;
    }

    /**
     * 初始化
     * @param context
     * @param defaultDir
     */
    public void init(Context context, String defaultDir) {
        init(context);
        this.defaultDir = defaultDir;
    }

    /**
     * 初始化
     * @param context
     */
    public void init(Context context) {

        // 初始化常用对象
        initCommon();

        // 初始化内存缓存
        initMemoryCache(context);

        // 单独开启一个线程，加快回收复用池中对象的引用
        removeBitmapReference();

        // 初始化磁盘缓存
        initDiskCache();
    }

    /**
     * 加载Url
     * @param imageUrl
     * @param imageView
     * @return
     */
    public void loadUrl(ImageView imageView, String imageUrl) {
        // 根据Url生成key
        String key = hashKeyForDisk(imageUrl);
        // 从内存缓存中拿Bitmap
        Bitmap bitmap = getBitmapFromMemory(key);
        if (bitmap == null) { // 如果内存缓存中取不到，那么从硬盘缓存中取
            // 从复用池中拿Bitmap
            Bitmap bitmapReusePool = getBitmapFromReusePool(imageView.getWidth(), imageView.getHeight(), 1);
            // 从磁盘缓存中拿Bitmap
            bitmap = getBitmapFromDisk(key, bitmapReusePool);
            if (bitmap == null) { // 如果硬盘缓存中取不到，那么从网络中获取
                // 下载并保存到磁盘缓存
                if (Looper.myLooper() == Looper.getMainLooper()) { // 如果是主线程，需要放在子线程中从网络下载图片
                    mCachedThreadPool.execute(new DownloadImageListener(imageUrl, key, imageView));
                } else {
                    // 下载并保存到磁盘中，图片可能很大
                    downloadToDisk(imageUrl);
                    // 再从磁盘缓存中获取bitmap
                    bitmap = getBitmapFromDisk(key, bitmapReusePool);
                    if (bitmap != null) { // 保存到内存缓存
                        saveToMemory(key, bitmap);
                        // 加载图片
                        intoTarget(bitmap, imageView);
                    }
                }
            } else { // 保存到内存缓存
                saveToMemory(key, bitmap);
                // 加载图片
                intoTarget(bitmap, imageView);
            }
        } else {
            // 加载图片
            intoTarget(bitmap, imageView);
        }
    }

    private void intoTarget(Bitmap bitmap, ImageView imageView) {
        if (bitmap != null && imageView != null) {
            imageView.setImageBitmap(bitmap);
        }
    }

    private class DownloadImageListener implements Runnable {

        private String imageUrl;

        private String key;

        private ImageView imageView;

        public DownloadImageListener(String imageUrl, String key, ImageView imageView) {
            this.imageUrl = imageUrl;
            this.key = key;
            WeakReference<ImageView> weakReference = new WeakReference<ImageView>(imageView);
            this.imageView = weakReference.get();
        }

        @Override
        public void run() {
            downloadToDisk(imageUrl);
            // 再从磁盘缓存中获取bitmap
            final Bitmap bitmap = getBitmapFromDisk(key, getBitmapFromReusePool(imageView.getWidth(), imageView.getHeight(), 1));
            if (bitmap != null) { // 保存到内存缓存
                saveToMemory(key, bitmap);
                // 加载图片
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        intoTarget(bitmap, imageView);
                    }
                });
            }
        }
    }

    /**
     * 初始化磁盘缓存
     */
    private void initDiskCache() {
        File cacheDir = new File(defaultDir);
        if (!cacheDir.exists()) {
            cacheDir.mkdirs();
        }
        try {
            mDiskLruCache = DiskLruCache.open(cacheDir, BuildConfig.VERSION_CODE, 1, 10 * 1024 * 1024);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 初始化常用对象
     */
    private void initCommon() {
        // 复用池
        reusePool = Collections.synchronizedSet(new HashSet<WeakReference<Bitmap>>());
        // 引用队列
        referenceQueue = new ReferenceQueue<Bitmap>();
        //并行处理事件
        singleThreadExecutor = Executors.newSingleThreadExecutor();

        options = new BitmapFactory.Options();
        // 使图片可复用
        options.inMutable = true;
        //并发处理事件
        mCachedThreadPool = Executors.newCachedThreadPool();
    }

    /**
     * 初始化内存缓存
     */
    private void initMemoryCache(Context context) {
        Context mContext = context.getApplicationContext();
        ActivityManager activityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        int memoryClass = activityManager.getMemoryClass(); // 以M为单位
        // lruCache最大只不超过该应用最大内存的1/8
        memoryCache = new LruCache<String, Bitmap>(memoryClass * 1024 * 1024 / 8) {

            /**
             * 每存入一张图片就计算图片占用内存的大小
             * @param key
             * @param bitmap
             * @return
             */
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return getBitmapSize(bitmap);
            }

            /**
             * 当超出最大缓存时，移除一张图片
             * @param evicted
             * @param key
             * @param oldValue 被移除的图片
             * @param newValue 新加入链表的图片
             */
            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                /*
                    如果从 LruCache 内存缓存中移除的 Bitmap 是可变的
                    才能被复用 , 否则只能回收该 Bitmap 对象

                    Bitmap 回收策略 :
                    3.0 以下系统中 , Bitmap 内存在 Native 层
                    3.0 以上系统中 , Bitmap 内存在 Java 层
                    8.0 及以上的系统中 , Bitmap 内存在 Native 层

                    因此这里需要处理 Bitmap 内存在 Native 层的情况 , 监控到 Java 层的弱引用被释放了
                    需要调用 Bitmap 对象的 recycle 方法 , 释放 Native 层的内存
                 */
                if(oldValue.isMutable()){   // 可以被复用
                    if(referenceQueue == null) {
                        throw new RuntimeException("referenceQueue is null");
                    }
                    // 将其放入弱引用中 , 每次 GC 启动后 , 如果该弱引用没有被使用 , 都会被回收
                    reusePool.add(new WeakReference<Bitmap>(oldValue, referenceQueue));
                }else{  // 不可被复用 , 直接回收
                    oldValue.recycle();
                }
            }
        };
    }

    /**
     * 移除Bitmap引用，加快回收
     */
    private void removeBitmapReference() {
        singleThreadExecutor.execute(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Reference<Bitmap> reference = referenceQueue.remove();
                        Bitmap bitmap = reference.get();
                        if (bitmap != null && bitmap.isRecycled()) {
                            bitmap.recycle();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
    }

    /**
     * 得到bitmap的大小（对版本的兼容）
     */
    private int getBitmapSize(Bitmap bitmap) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {    //API 19
            return bitmap.getAllocationByteCount();
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {//API 12
            return bitmap.getByteCount();
        }
        // 在低版本中用一行的字节x高度
        return bitmap.getRowBytes() * bitmap.getHeight();
    }

    /**
     * 将Key值MD5加密
     * @param key
     * @return
     */
    private String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }

    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * 从磁盘中拿Bitmap
     * @param key
     * @param bitmapReusePool
     * @return
     */
    private Bitmap getBitmapFromDisk(String key, Bitmap bitmapReusePool) {
        Log.i(TAG, "get bitmap from disk");
        DiskLruCache.Snapshot mSnapshot = null;
        Bitmap mBitmap = null;
        try {
            mSnapshot = mDiskLruCache.get(key);
            if(mSnapshot == null){
                Log.i(TAG, "get bitmap from disk, but mSnapshot is null");
                return null;
            }
            InputStream mInputStream = mSnapshot.getInputStream(0);
            options.inBitmap = bitmapReusePool;
            mBitmap = BitmapFactory.decodeStream(mInputStream,null, options);
            if(mBitmap != null){
                memoryCache.put(key, mBitmap);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally{
            if(mSnapshot != null){
                mSnapshot.close();
            }
        }
        return mBitmap;
    }

    /**
     * 从复用池
     * @param width
     * @param height
     * @param inSampleSize
     * @return
     */
    private Bitmap getBitmapFromReusePool(int width, int height, int inSampleSize) {
        Log.d(TAG, "从复用池中获取");
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB){
            return null;
        }
        Bitmap reuseable = null;
        Iterator<WeakReference<Bitmap>> iterator = reusePool.iterator();
        while(iterator.hasNext()) {
            Bitmap bitmap = iterator.next().get();
            if(bitmap != null) {
                //可以复用
                if(checkInBitmap(bitmap, width, height, inSampleSize)){
                    reuseable = bitmap;
                    iterator.remove();
                    break;
                }else{
                    iterator.remove();
                }
            } else {
                iterator.remove();
            }
        }
        return reuseable;
    }

    /**
     * 检查图片是否符合复用条件
     * @param bitmap
     * @param width
     * @param height
     * @param inSampleSize
     * @return
     */
    private boolean checkInBitmap(Bitmap bitmap, int width,int height,int inSampleSize) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                    /*
                        Android 4.4（API 级别 19）以下的版本 : 在 Android 4.4（API 级别 19） 之前的代码中,
                        复用的前提是必须同时满足以下 3 个条件 :
                            1. 被解码的图像必须是 JPEG 或 PNG 格式
                            2. 被复用的图像宽高必须等于 解码后的图像宽高
                            3. 解码图像的 BitmapFactory.Options.inSampleSize 设置为 1 , 也就是不能缩放
                        才能复用成功 , 另外被复用的图像的像素格式 Config ( 如 RGB_565 ) 会覆盖设置的BitmapFactory.Options.inPreferredConfig 参数 ;
                     */
            if(bitmap.getWidth() == width && bitmap.getHeight() == height && inSampleSize == 1){
                return true;
            }
        } else if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT){
                    /*
                        在 Android 4.4（API 级别 19）及以上的版本中 ,
                        只要被解码后的 Bitmap 对象的字节大小 , 小于等于 inBitmap 的字节大小 , 就可以复用成功 ;
                        解码后的图像可以是缩小后的 , 即 BitmapFactory.Options.inSampleSize 可以大于1 ;
                     */
                // 首先要计算图像的内存占用 , 先要计算出图像的宽高 , 如果图像需要缩放 , 计算缩放后的宽高
                if(inSampleSize > 1){
                    width = width / inSampleSize ;
                    height = height / inSampleSize;
                }

                // 计算内存占用 , 默认 ARGB_8888 格式
                int byteInMemory = width * height * 4;
                if (bitmap.getConfig() == Bitmap.Config.ARGB_8888){
                    // 此时每个像素占 4 字节
                    byteInMemory = width * height * 4;
                } else if(bitmap.getConfig() == Bitmap.Config.RGB_565 || bitmap.getConfig() == Bitmap.Config.ARGB_4444){
                    // 此时每个像素占 2 字节
                    byteInMemory = width * height * 2;
                }
                // 如果解码后的图片内存小于等于被复用的内存大小 , 可以复用
                if(byteInMemory <= bitmap.getAllocationByteCount()){
                    //符合要求
                    return true;
                }
            }
        return false;
    }

    /**
     * 从内存中拿Bitmap
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemory(String key) {
        Log.d(TAG, "从内存中获取");
        if (memoryCache == null) {
            throw new RuntimeException("memoryCache is null，in method getBitmapFromMemory");
        }
        return memoryCache.get(key);
    }

    /**
     * 保存到内存
     * @param key
     * @param bitmap
     */
    private void saveToMemory(String key, Bitmap bitmap) {
        if (memoryCache == null) {
            throw new RuntimeException("memoryCache is null，in method saveToMemory");
        }
        Log.d(TAG, "保存到内存");
        memoryCache.put(key, bitmap);
    }

    /**
     * 下载并保存到磁盘
     * @param imageUrl
     * @return
     */
    private void downloadToDisk(String imageUrl) {
        String key = hashKeyForDisk(imageUrl);
        DiskLruCache.Snapshot mSnapshot = null;
        OutputStream mOutputStream = null;
        try {
            // 从磁盘缓存中获取数据
            mSnapshot = mDiskLruCache.get(key);
            if(mSnapshot == null) { // 如果磁盘缓存中没有对应的数据
                //如果没有这个文件，就生成这个文件
                DiskLruCache.Editor mEditor = mDiskLruCache.edit(key);
                if(mEditor != null) {
                    mOutputStream = mEditor.newOutputStream(0);
                    if (downloadImageFormNetword(imageUrl, mOutputStream)) {
                        Log.i(TAG, "download image success, save to disk cache");
                        mEditor.commit();
                    } else {
                        Log.i(TAG, "download image failed");
                        mEditor.abort();
                    }
                    //频繁的flush
                    mDiskLruCache.flush();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }finally {
            if(mSnapshot != null){
                mSnapshot.close();
            }
            if(mOutputStream != null){
                try {
                    mOutputStream.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * 从网络下载图片
     * @param urlString
     * @param outputStream
     * @return
     */
    private boolean downloadImageFormNetword(String urlString, OutputStream outputStream) {
        HttpURLConnection urlConnection = null;
        BufferedOutputStream out = null;
        BufferedInputStream in = null;
        try {
            final URL url = new URL(urlString);
            urlConnection = (HttpURLConnection) url.openConnection();
            in = new BufferedInputStream(urlConnection.getInputStream(), 8 * 1024);
            out = new BufferedOutputStream(outputStream, 8 * 1024);
            int b;
            while ((b = in.read()) != -1) {
                out.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 清除缓存
     * @param imageUrl
     */
    public void removeCache(String imageUrl) {
        String key = hashKeyForDisk(imageUrl);
        try {
            memoryCache.remove(key);
            mDiskLruCache.remove(key);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
