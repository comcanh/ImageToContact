package com.example.imagetocontact

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
        Toast.makeText(this, "Đang trích xuất thông tin...", Toast.LENGTH_SHORT).show()
        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    extractAndLaunchSystemContact(visionText.text, uri)
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "Không nhận diện được chữ: ${e.message}", Toast.LENGTH_SHORT).show()
                    extractAndLaunchSystemContact("", uri)
                }
        } catch (e: Exception) {
            e.printStackTrace()
            extractAndLaunchSystemContact("", uri)
        }
    }

    private fun extractAndLaunchSystemContact(fullText: String, uri: Uri) {
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // Bóc tách số điện thoại
        val phonePattern = Pattern.compile("(?i)(?:0|\\+84)[\\s.-]?[0-9]{2,4}[\\s.-]?[0-9]{3}[\\s.-]?[0-9]{3,4}")
        val matcher = phonePattern.matcher(fullText)
        var foundPhone = ""
        if (matcher.find()) {
            foundPhone = matcher.group().replace(Regex("[^0-9+]"), "")
        }

        // Bóc tách tên dịch vụ / tên người
        var candidateName = ""
        for (line in lines) {
            val clean = line.replace(Regex("[^\\p{L}\\s]"), "").trim()
            if (clean.length > 4 && !line.contains(foundPhone) && !line.matches(Regex(".*[0-9]{4,}.*"))) {
                candidateName = clean
                break
            }
        }
        if (candidateName.isEmpty() && lines.isNotEmpty()) {
            candidateName = lines[0]
        }

        // Mở màn hình tạo Contact mặc định của điện thoại
        val intent = Intent(Intent.ACTION_INSERT).apply {
            type = ContactsContract.Contacts.CONTENT_TYPE
            
            // Điền trước Tên và Số điện thoại
            putExtra(ContactsContract.Intents.Insert.NAME, candidateName)
            putExtra(ContactsContract.Intents.Insert.PHONE, foundPhone)
            putExtra(ContactsContract.Intents.Insert.PHONE_TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            
            // Gắn nội dung chữ OCR vào mục Ghi chú
            if (fullText.isNotEmpty()) {
                putExtra(ContactsContract.Intents.Insert.NOTES, fullText)
            }

            // Gắn ảnh làm Avatar liên hệ
            val bitmap = getBitmapFromUri(uri)
            if (bitmap != null) {
                val data = ArrayList<android.content.ContentValues>()
                val row = android.content.ContentValues().apply {
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                    val stream = java.io.ByteArrayOutputStream()
                    // Nén ảnh nhỏ gọn để vừa bộ nhớ Intent
                    val scaled = Bitmap.createScaledBitmap(bitmap, 480, (480f * bitmap.height / bitmap.width).toInt(), true)
                    scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
                    put(ContactsContract.CommonDataKinds.Photo.PHOTO, stream.toByteArray())
                }
                data.add(row)
                putParcelableArrayListExtra(ContactsContract.Intents.Insert.DATA, data)
            }
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Không thể mở ứng dụng Danh bạ", Toast.LENGTH_SHORT).show()
        } finally {
            finish()
        }
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            null
        }
    }
}
