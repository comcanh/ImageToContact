package com.example.imagetocontact

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.imagetocontact.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.tvGuide.text = "Hướng dẫn:\n1. Mở Samsung Gallery\n2. Chọn ảnh cần lấy số\n3. Bấm Chia sẻ (Share) -> Chọn 'Lưu vào Danh bạ'"
    }
}
