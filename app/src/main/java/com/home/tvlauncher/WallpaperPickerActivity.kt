package com.home.tvlauncher

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

/** Pick any image on the TV (Pictures, Download, DCIM) as a fixed wallpaper. Free, no paywall. */
class WallpaperPickerActivity : Activity() {

    private lateinit var grid: GridLayout
    private lateinit var status: TextView
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1320.toInt())
            setPadding(dp(56), dp(36), dp(56), dp(24))
        }
        root.addView(TextView(this).apply {
            text = "Choose a wallpaper"; textSize = 28f; setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 15f; setTextColor(0xBBFFFFFF.toInt()); setPadding(0, dp(6), 0, dp(16))
            text = "Put images in the TV's Pictures or Download folder to see them here."
        }
        root.addView(status)
        grid = GridLayout(this).apply {
            columnCount = 4; clipChildren = false; clipToPadding = false
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val scroll = ScrollView(this).apply { clipChildren = false; clipToPadding = false; addView(grid) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            load()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        load()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun findImages(): List<File> {
        val base = Environment.getExternalStorageDirectory()
        val dirs = listOf("Pictures", "Download", "DCIM", "DCIM/Camera", "Wallpapers")
            .map { File(base, it) }
        val exts = setOf("jpg", "jpeg", "png", "webp")
        return dirs.flatMap { d ->
            d.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }.orEmpty()
        }.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    private fun load() {
        grid.removeAllViews()
        addTile(null) // built-in default
        val files = try { Targets.findImages() } catch (e: Exception) { emptyList() }
        if (files.isEmpty() && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            status.text = "Storage permission is needed to show your pictures."
        }
        files.forEach { addTile(it) }
        grid.getChildAt(0)?.requestFocus()
    }

    private fun addTile(file: File?) {
        val radius = dp(10).toFloat()
        val tile = FrameLayout(this).apply {
            isFocusable = true; isClickable = true
            background = GradientDrawable().apply { cornerRadius = radius; setColor(0xFF1C2333.toInt()) }
            outlineProvider = ViewOutlineProvider.BACKGROUND; clipToOutline = true
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        tile.addView(img, FrameLayout.LayoutParams(-1, -1))
        tile.addView(TextView(this).apply {
            text = file?.name ?: "Default (built in)"
            textSize = 12f; setTextColor(Color.WHITE); maxLines = 1
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(8), dp(4), dp(8), dp(4))
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        val ring = GradientDrawable().apply { cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(dp(3), Color.WHITE) }
        tile.setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) 1.08f else 1f).scaleY(if (has) 1.08f else 1f)
                .translationZ(if (has) dp(10).toFloat() else 0f).setDuration(140).start()
            tile.foreground = if (has) ring else null
        }
        tile.setOnClickListener { choose(file) }
        grid.addView(tile, GridLayout.LayoutParams().apply {
            width = dp(256); height = dp(144); setMargins(dp(10), dp(10), dp(10), dp(10))
        })

        if (file == null) {
            img.setImageResource(R.drawable.wallpaper_default)
        } else {
            Thread {
                val thumb = try { Targets.decodeScaled(file.absolutePath, 384, 216) } catch (e: Throwable) { null }
                runOnUiThread { if (thumb != null) img.setImageBitmap(thumb) }
            }.start()
        }
    }

    private fun choose(file: File?) {
        if (file == null) {
            prefs.wallpaperPath = null; prefs.slideshowMinutes = 0
            finish(); return
        }
        status.text = "Setting wallpaper…"
        Thread {
            val ok = try {
                val dm = resources.displayMetrics
                val bmp = Targets.decodeScaled(file.absolutePath, dm.widthPixels, dm.heightPixels)
                    ?: throw IllegalStateException("decode failed")
                // fresh file name each time so Home notices the change
                filesDir.listFiles()?.filter { it.name.startsWith("wallpaper_") }?.forEach { it.delete() }
                val out = File(filesDir, "wallpaper_${System.currentTimeMillis()}.jpg")
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 94, it) }
                prefs.wallpaperPath = out.absolutePath
                prefs.slideshowMinutes = 0
                true
            } catch (e: Throwable) { false }
            runOnUiThread {
                if (ok) finish()
                else { status.text = "Couldn't use that image."; Toast.makeText(this, "Couldn't use that image", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }
}
