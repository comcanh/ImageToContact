package com.example.imagetocontact

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.ArrayList
import java.util.regex.Pattern

class ShareReceiverActivity : AppCompatActivity() {

    private var imageUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (imageUri != null) {
                processImageAndOpenContactEditor(imageUri!!)
            } else {
                Toast.makeText(this, "Không tìm thấy dữ liệu ảnh", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun processImageAndOpenContactEditor(uri: Uri) {
        Toast.makeText(this, "Đang xử lý ảnh...", Toast.LENGTH_SHORT).show()
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    extractAndLaunchSystemContact(visionText.text, uri)
                }
                .addOnFailureListener {
                    // Dù quét lỗi vẫn tiếp tục mở màn hình Contact với chuỗi rỗng
                    extractAndLaunchSystemContact("", uri)
                }
        } catch (e: Exception) {
            // Trường hợp lỗi ngoại lệ vẫn mở Contact với ảnh gốc
            extractAndLaunchSystemContact("", uri)
        }
    }

private fun extractAndLaunchSystemContact(fullText: String, uri: Uri) {
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // 1. Tìm số điện thoại bằng Regex
        val phonePattern = Pattern.compile("(?i)(?:0|\\+84)[\\s.-]?[0-9]{2,4}[\\s.-]?[0-9]{3}[\\s.-]?[0-9]{3,4}")
        val matcher = phonePattern.matcher(fullText)
        var foundPhone = ""
        if (matcher.find()) {
            foundPhone = matcher.group().replace(Regex("[^0-9+]"), "")
        }

        // 2. Tìm tên hoặc gán tên mặc định
        var candidateName = ""
        for (line in lines) {
            val clean = line.replace(Regex("[^\\p{L}\\s]"), "").trim()
            if (clean.length > 4 && !line.contains(foundPhone) && !line.matches(Regex(".*[0-9]{4,}.*"))) {
                candidateName = clean
                break
            }
        }
        if (candidateName.isEmpty()) {
            candidateName = if (lines.isNotEmpty()) lines[0] else "Vị trí / Liên hệ mới"
        }

        // 3. Mở giao diện Danh bạ hệ thống và truyền thẳng File URI gốc (Giữ nguyên 100% độ nét)
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            
            putExtra(ContactsContract.Intents.Insert.NAME, candidateName)
            if (foundPhone.isNotEmpty()) {
                putExtra(ContactsContract.Intents.Insert.PHONE, foundPhone)
                putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            if (fullText.isNotEmpty()) {
                putExtra(ContactsContract.Intents.Insert.NOTES, fullText)
            }

            // Gán trực tiếp URI ảnh gốc độ phân giải cao + cấp quyền đọc cho app Danh bạ
            putExtra(ContactsContract.CommonDataKinds.Photo.PHOTO_URI, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở ứng dụng Danh bạ: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }
}