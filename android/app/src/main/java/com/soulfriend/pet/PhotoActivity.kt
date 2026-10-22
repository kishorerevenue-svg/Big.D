package com.soulfriend.pet

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.io.FileOutputStream

/** Invisible screen that lets you pick a photo, shrinks it, and hands it to the puppy. */
class PhotoActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            try { startActivityForResult(Intent.createChooser(pick, "Choose a photo"), 7) } catch (e: Exception) { finish() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode == RESULT_OK && uri != null) {
            val file = shrink(uri)
            if (file != null) {
                try { startService(Intent(this, PetService::class.java).setAction(PetService.ACTION_PHOTO).putExtra("path", file.absolutePath)) } catch (e: Exception) { }
            }
        }
        finish()
    }

    /** Max 1568 px on the long side, JPEG: what the AI reads best, and small to upload. */
    private fun shrink(uri: Uri): File? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1568) sample *= 2
        val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        if (bmp == null) null else {
            val scale = 1568f / maxOf(bmp.width, bmp.height)
            val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val f = File(cacheDir, "photo.jpg")
            FileOutputStream(f).use { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            f
        }
    } catch (e: Exception) { null }
}
