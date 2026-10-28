package com.home.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** All launcher settings, stored on the TV only. */
class Prefs(context: Context) {
    val sp: SharedPreferences = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

    // apps
    var hidden: Set<String>
        get() = sp.getStringSet("hidden", null) ?: DEFAULT_HIDDEN
        set(v) = sp.edit().putStringSet("hidden", HashSet(v)).apply()
    var order: List<String>
        get() = sp.getString("order", "")!!.split(',').filter { it.isNotBlank() }
        set(v) = put("order", v.joinToString(","))
    fun customName(pkg: String): String? = sp.getString("name:$pkg", null)?.takeIf { it.isNotBlank() }
    fun setCustomName(pkg: String, name: String?) = put("name:$pkg", name)

    // wallpaper
    var wallpaperPath: String?
        get() = sp.getString("wallpaper", null)
        set(v) = put("wallpaper", v)
    var dim: Int get() = int("dim", 0); set(v) = put("dim", v)
    /** 0 = off, otherwise minutes */
    var slideshowMinutes: Int get() = int("slideshow", 0); set(v) = put("slideshow", v)

    // appearance
    var tileSize: Int get() = int("tileSize", 1); set(v) = put("tileSize", v)        // 0 S,1 M,2 L
    var rowPosition: Int get() = int("rowPos", 0); set(v) = put("rowPos", v)         // 0 bottom,1 middle
    var inputsBelow: Boolean get() = bool("inputsBelow", false); set(v) = put("inputsBelow", v)
    var accent: Int get() = int("accent", 0); set(v) = put("accent", v)
    var corners: Int get() = int("corners", 2); set(v) = put("corners", v)            // 0..2
    var zoom: Int get() = int("zoom", 2); set(v) = put("zoom", v)                    // 0..3
    var showLabels: Boolean get() = bool("showLabels", true); set(v) = put("showLabels", v)
    var showInputs: Boolean get() = bool("showInputs", true); set(v) = put("showInputs", v)
    var glass: Boolean get() = bool("glass", true); set(v) = put("glass", v)
    /** 0 = crystal clear, 100 = fully frosted */
    var glassOpacity: Int get() = int("glassOpacity", 30); set(v) = put("glassOpacity", v)
    /** 0 = flat glass, 100 = strong lens bending at the edges */
    var refraction: Int get() = int("refraction", 50); set(v) = put("refraction", v)
    var dock: Boolean get() = bool("dock", true); set(v) = put("dock", v)

    // clock
    var showClock: Boolean get() = bool("showClock", true); set(v) = put("showClock", v)
    var clock24: Boolean get() = bool("clock24", false); set(v) = put("clock24", v)
    var showDate: Boolean get() = bool("showDate", true); set(v) = put("showDate", v)
    var clockSize: Int get() = int("clockSize", 1); set(v) = put("clockSize", v)      // 0..2
    var clockLeft: Boolean get() = bool("clockLeft", false); set(v) = put("clockLeft", v)

    // screen saver
    var saverSeconds: Int get() = int("saverSeconds", 20); set(v) = put("saverSeconds", v)
    /** 0 = Apple TV and Netflix in turn, 1 = Apple TV, 2 = Netflix */
    var saverSource: Int get() = int("saverSource", 0); set(v) = put("saverSource", v)
    var saverClock: Boolean get() = bool("saverClock", true); set(v) = put("saverClock", v)
    var saverInfo: Boolean get() = bool("saverInfo", true); set(v) = put("saverInfo", v)

    // behaviour
    /** "" = stay on Home, "app:<pkg>", "input:<id>" */
    var startupTarget: String get() = sp.getString("startup", "")!!; set(v) = put("startup", v)
    var keyMap: Map<Int, String>
        get() = sp.getString("keymap", "")!!.split(';').mapNotNull {
            val p = it.split('=', limit = 2); p.getOrNull(0)?.toIntOrNull()?.let { k -> k to (p.getOrNull(1) ?: "") }
        }.filter { it.second.isNotBlank() }.toMap()
        set(v) = put("keymap", v.entries.joinToString(";") { "${it.key}=${it.value}" })

    val accentColor: Int get() = ACCENTS[accent.coerceIn(0, ACCENTS.size - 1)].second

    // backup
    fun exportTo(file: File) {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            when (v) {
                is Set<*> -> o.put(k, JSONObject().put("set", JSONArray(v.map { it.toString() })))
                null -> {}
                else -> o.put(k, v)
            }
        }
        file.writeText(o.toString(2))
    }

    fun importFrom(file: File) {
        val o = JSONObject(file.readText())
        val e = sp.edit().clear()
        for (k in o.keys()) {
            when (val v = o.get(k)) {
                is JSONObject -> if (v.has("set")) {
                    val a = v.getJSONArray("set"); e.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet())
                }
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putInt(k, v.toInt())
                is Double -> e.putInt(k, v.toInt())
                is String -> e.putString(k, v)
            }
        }
        e.apply()
    }

    companion object {
        val ACCENTS = listOf(
            "White" to 0xFFFFFFFF.toInt(),
            "Sky blue" to 0xFF4FC3F7.toInt(),
            "Marvel red" to 0xFFE53935.toInt(),
            "Gold" to 0xFFFFC107.toInt(),
            "Green" to 0xFF66BB6A.toInt(),
            "Purple" to 0xFFAB47BC.toInt(),
        )
        val TILE_SIZES = listOf("Small" to (120 to 68), "Medium" to (144 to 81), "Large" to (176 to 99))
        val ZOOMS = listOf("Off" to 1.0f, "Subtle" to 1.06f, "Normal" to 1.12f, "Big" to 1.2f)
        val CORNERS = listOf("Square" to 4, "Rounded" to 12, "Round" to 22)
        val CLOCK_SIZES = listOf("Small" to 26f, "Medium" to 36f, "Large" to 54f)
        val SLIDESHOW = listOf(0, 1, 5, 15, 30, 60)
        val SAVER_SECONDS = listOf(10, 20, 30, 60)

        val DEFAULT_HIDDEN = setOf(
            "me.efesser.flauncher", "com.spocky.projengmenu", "com.google.android.tvlauncher",
            "com.liskovsoft.leankeyboard", "com.smartdevice.dtv.icast", "com.smartdevice.duplebluetooth",
            "com.tianci.localmedia", "com.lonelycatgames.Xplore", "flar2.homebutton",
        )
        val DEFAULT_ORDER = listOf(
            "com.apple.atve.androidtv.appletv", "com.netflix.ninja", "com.google.android.youtube.tv",
            "com.google.android.youtube.tvmusic", "com.smartdevice.livetv", "com.android.vending",
        )
    }
}

data class AppEntry(
    val pkg: String,
    val label: String,
    val originalLabel: String,
    val launch: Intent,
    val banner: Drawable?,
    val icon: Drawable?,
)

object AppRepo {
    /** Every TV app installed (leanback launchable), except this launcher. */
    fun allApps(context: Context, prefs: Prefs = Prefs(context)): List<AppEntry> {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { ri ->
                val ai = ri.activityInfo
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                val original = ri.loadLabel(pm).toString()
                AppEntry(
                    pkg = ai.packageName,
                    label = prefs.customName(ai.packageName) ?: original,
                    originalLabel = original,
                    launch = launch,
                    banner = safe { ai.loadBanner(pm) } ?: safe { ai.applicationInfo.loadBanner(pm) },
                    icon = safe { ri.loadIcon(pm) },
                )
            }
    }

    fun visibleApps(context: Context, prefs: Prefs): List<AppEntry> {
        val hidden = prefs.hidden
        return sort(allApps(context, prefs).filter { it.pkg !in hidden }, prefs.order)
    }

    fun sort(apps: List<AppEntry>, saved: List<String>): List<AppEntry> =
        apps.sortedWith(compareBy<AppEntry>(
            { val i = saved.indexOf(it.pkg); if (i >= 0) i else Int.MAX_VALUE },
            { val i = Prefs.DEFAULT_ORDER.indexOf(it.pkg); if (i >= 0) i else Int.MAX_VALUE },
            { it.label.lowercase() },
        ))

    private inline fun <T> safe(block: () -> T?): T? = try { block() } catch (e: Exception) { null }
}
