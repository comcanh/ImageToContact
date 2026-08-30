package com.example.imagetocontact

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.imagetocontact.databinding.ActivityShareReceiverBinding
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern

class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var binding: ActivityShareReceiverBinding
    private var imageUri: Uri? = null

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
                    Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_SHORT).show()
                    binding.layoutForm.visibility = View.VISIBLE
                }
        } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
            finish()
        }
    }

    private fun extractContactInfo(fullText: String) {
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

        if (candidateName.isEmpty() && lines.isNotEmpty()) candidateName = lines[0]

        binding.edtContactName.setText(candidateName)
        binding.edtPhoneNumber.setText(foundPhone)
        binding.edtRawText.setText(fullText)
    }

    private fun checkPermissionAndSave() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_CONTACTS), PERMISSION_REQUEST_CONTACTS)
        } else {
            saveContactDirectly()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CONTACTS && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            saveContactDirectly()
        }
    }

    private fun saveContactDirectly() {
        val name = binding.edtContactName.text.toString().trim()
        val phone = binding.edtPhoneNumber.text.toString().trim()
        val note = binding.edtRawText.text.toString().trim()

        if (phone.isEmpty()) {
            Toast.makeText(this, "Không tìm thấy số điện thoại", Toast.LENGTH_SHORT).show()
            return
        }

        val ops = ArrayList<ContentProviderOperation>()
        val rawContactInsertIndex = ops.size
        ops.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
            .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name.ifEmpty { "Liên hệ mới" })
            .build())

        ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
            .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            .build())

        if (note.isNotEmpty()) {
            ops.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, rawContactInsertIndex)
                .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Note.NOTE, note)
                .build())
        }

        try {
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Toast.makeText(this, "Đã lưu '$name' vào danh bạ!", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "Lỗi: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
