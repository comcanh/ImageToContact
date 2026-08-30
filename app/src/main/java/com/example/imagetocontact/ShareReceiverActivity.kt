package com.example.imagetocontact

import android.Manifest
import android.accounts.AccountManager
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.MediaStore
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.imagetocontact.databinding.ActivityShareReceiverBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareReceiverBinding
    private var imageUri: Uri? = null
    private var rawNoteText: String = ""

    companion object {
        private const val PERMISSION_REQUEST_CONTACTS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShareReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }

            if (imageUri != null) {
                binding.imageViewPreview.setImageURI(imageUri)
                processImageWithMLKit(imageUri!!)
            } else {
                Toast.makeText(this, "Không tìm thấy dữ liệu ảnh", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        binding.btnSaveContact.setOnClickListener { checkPermissionAndSave() }
        binding.btnCancel.setOnClickListener { finish() }
    }

    private fun processImageWithMLKit(uri: Uri) {
        binding.progressBar.visibility = View.VISIBLE
        binding.layoutForm.visibility = View.GONE

        try {
            val image = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    binding.progressBar.visibility = View.GONE
                    binding.layoutForm.visibility = View.VISIBLE
                    extractContactInfo(visionText.text)
                }
                .addOnFailureListener { e ->
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this, "Lỗi nhận diện: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.layoutForm.visibility = View.VISIBLE
                }
        } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
            Toast.makeText(this, "Lỗi đọc file: ${e.message}", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun extractContactInfo(fullText: String) {
        rawNoteText = fullText
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        val phonePattern = Pattern.compile("(?i)(?:0|\\+84)[\\s.-]?[0-9]{2,4}[\\s.-]?[0-9]{3}[\\s.-]?[0-9]{3,4}")
        val matcher = phonePattern.matcher(fullText)
        var foundPhone = ""
        if (matcher.find()) {
            foundPhone = matcher.group().replace(Regex("[^0-9+]"), "")
        }

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

        binding.edtContactName.setText(candidateName)
        binding.edtPhoneNumber.setText(foundPhone)
        binding.edtRawText.setText(fullText)
    }

    private fun checkPermissionAndSave() {
        val permissions = arrayOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS
        )

        val needPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                needPermissions.toTypedArray(),
                PERMISSION_REQUEST_CONTACTS
            )
        } else {
            handleSaveFlow()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CONTACTS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                handleSaveFlow()
            } else {
                Toast.makeText(this, "Cần cấp quyền Danh bạ để tiếp tục!", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 1. Kiểm tra trùng lặp trước khi lưu
    private fun handleSaveFlow() {
        val phone = binding.edtPhoneNumber.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(this, "Không có số điện thoại hợp lệ", Toast.LENGTH_SHORT).show()
            return
        }

        val existingContactName = findExistingContact(phone)
        if (existingContactName != null) {
            AlertDialog.Builder(this)
                .setTitle("Số liên hệ đã tồn tại")
                .setMessage("Số '$phone' đã được lưu dưới tên: '$existingContactName'.\n\nBạn có muốn tiếp tục lưu thêm liên hệ mới này không?")
                .setPositiveButton("Tiếp tục lưu") { _, _ ->
                    saveContactDirectly()
                }
                .setNegativeButton("Hủy bỏ", null)
                .show()
        } else {
            saveContactDirectly()
        }
    }

    private fun findExistingContact(phone: String): String? {
        val normalizedPhone = phone.replace(Regex("[^0-9+]"), "")
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(normalizedPhone)
        )
        val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)

        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    // 3. Tìm tài khoản Google đã đăng nhập trên máy
    private fun getGoogleAccount(): Pair<String?, String?> {
        try {
            val accountManager = AccountManager.get(this)
            val accounts = accountManager.getAccountsByType("com.google")
            if (accounts.isNotEmpty()) {
                return Pair(accounts[0].name, accounts[0].type)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(null, null)
    }

    // 2. Chuyển đổi và nén ảnh để đính kèm vào Contact
    private fun getImageBytes(uri: Uri): ByteArray? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }

            val targetWidth = 720
            val targetHeight = (720f * bitmap.height / bitmap.width).toInt()
            val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            
            val stream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveContactDirectly() {
        val name = binding.edtContactName.text.toString().trim()
        val phone = binding.edtPhoneNumber.text.toString().trim()
        val note = binding.edtRawText.text.toString().trim()

        val ops = ArrayList<ContentProviderOperation>()
        val (googleAccountName, googleAccountType) = getGoogleAccount()

        // Gắn liên hệ vào tài khoản Google mặc định
        val rawContactInsertIndex = ops.size
        val rawContactBuilder = ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
        if (googleAccountName != null && googleAccountType != null) {
            rawContactBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, googleAccountName)
            rawContactBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, googleAccountType)
        } else {
            rawContactBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            rawContactBuilder.withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
        }
        ops.add(rawContactBuilder.build())

        // 1. Thêm Tên
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.ifEmpty { "Liên hệ mới" })
                .build()
        )

        // 2. Thêm Số điện thoại
        ops.add(
            ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                .build()
        )

        // 3. Thêm Ảnh đại diện / Ảnh nền của liên hệ
        imageUri?.let { uri ->
            val photoBytes = getImageBytes(uri)
            if (photoBytes != null) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
                        .build()
                )
            }
        }

        // 4. Thêm Ghi chú nội dung OCR
        if (note.isNotEmpty()) {
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                    .build()
            )
        }

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            val destText = if (googleAccountName != null) "tài khoản Google ($googleAccountName)" else "Danh bạ máy"
            Toast.makeText(this, " Đã lưu '$name' kèm ảnh vào $destText!", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi khi lưu danh bạ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
