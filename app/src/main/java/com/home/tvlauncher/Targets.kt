package com.home.tvlauncher

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Environment
import android.widget.Toast
import java.io.File

/** Things a button press or TV start-up can open: an app, an HDMI input or Home. */
object Targets {

    fun hdmiInputs(context: Context): List<TvInputInfo> = try {
        val tim = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
        tim?.tvInputList.orEmpty()
            .filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.id }
    } catch (e: Exception) { emptyList() }

    fun inputLabel(context: Context, inp: TvInputInfo, index: Int): String =
        inp.loadLabel(context)?.toString()?.takeIf { it.isNotBlank() } ?: "HDMI ${index + 1}"

    /** (target string, label) for every choice, Home first. */
    fun choices(context: Context): List<Pair<String, String>> {
        val list = mutableListOf("home" to "Home screen")
        hdmiInputs(context).forEachIndexed { i, inp -> list += "input:${inp.id}" to inputLabel(context, inp, i) }
        val prefs = Prefs(context)
        AppRepo.sort(AppRepo.allApps(context, prefs), prefs.order).forEach { list += "app:${it.pkg}" to it.label }
        return list
    }

    fun label(context: Context, target: String): String =
        if (target.isBlank()) "Nothing" else choices(context).firstOrNull { it.first == target }?.second ?: target

    fun open(context: Context, target: String) {
        try {
            val intent: Intent? = when {
                target == "home" -> Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(context.packageName)
                target.startsWith("input:") -> Intent(Intent.ACTION_VIEW,
                    TvContract.buildChannelUriForPassthroughInput(target.removePrefix("input:")))
                target.startsWith("app:") -> {
                    val pkg = target.removePrefix("app:")
                    context.packageManager.getLeanbackLaunchIntentForPackage(pkg)
                        ?: context.packageManager.getLaunchIntentForPackage(pkg)
                }
                else -> null
            }
            if (intent == null) { toast(context, "That app isn't installed any more"); return }
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) {
            toast(context, "Couldn't open it")
        }
    }

    private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- images

    fun findImages(): List<File> {
        val base = Environment.getExternalStorageDirectory()
        val exts = setOf("jpg", "jpeg", "png", "webp")
        return listOf("Pictures", "Download", "DCIM", "DCIM/Camera", "Wallpapers").map { File(base, it) }
            .flatMap { d -> d.listFiles()?.filter { it.isFile && it.extension.lowercase() in exts }.orEmpty() }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
    }

    fun decodeScaled(path: String, reqW: Int, reqH: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= reqW && o.outHeight / (sample * 2) >= reqH) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
