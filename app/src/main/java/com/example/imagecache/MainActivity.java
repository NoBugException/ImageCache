package com.example.imagecache;

import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;

    private Button button1, button2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final String imageUrl = "https://ss3.baidu.com/9fo3dSag_xI4khGko9WTAnF6hhy/zhidao/pic/item/342ac65c10385343990b4d4b9213b07ecb808890.jpg";

        // 图片缓存管理初始化
        ImageCacheManager.getInstance().init(getApplicationContext());

        imageView = findViewById(R.id.iv_image);
        button1 = findViewById(R.id.button1);
        button1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 加载图片
                ImageCacheManager.getInstance().loadUrl(imageView, imageUrl);
            }
        });

        button2 = findViewById(R.id.button2);
        button2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 清除磁盘缓存
                ImageCacheManager.getInstance().removeCache(imageUrl);
                // 清空图片
                imageView.setImageBitmap(null);
            }
        });
    }
}