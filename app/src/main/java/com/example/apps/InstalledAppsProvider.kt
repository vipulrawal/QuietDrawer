package com.example.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppInfo(
    val packageName: String,
    val appName: String,
    val isEssentialCandidate: Boolean = false,
)

/**
 * Play-policy-compliant app discovery using ACTION_MAIN + CATEGORY_LAUNCHER queries
 * declared in AndroidManifest.xml (no QUERY_ALL_PACKAGES required).
 */
class InstalledAppsProvider(private val context: Context) {

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun loadLaunchableApps(): List<InstalledAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(mainIntent, 0)
        }

        val ownPackage = context.packageName
        resolveInfos
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == ownPackage) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                InstalledAppInfo(
                    packageName = pkg,
                    appName = label,
                    isEssentialCandidate = isEssentialPackage(pkg),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.appName.lowercase() }
            .toList()
    }

    fun resolveAppName(packageName: String): String {
        return try {
            val pm = context.packageManager
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            pm.getApplicationLabel(appInfo).toString().trim().ifEmpty { packageName }
        } catch (_: Exception) {
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    suspend fun getAppIcon(packageName: String): ImageBitmap? {
        iconCache[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = drawableToBitmap(drawable, targetSizePx = 96).asImageBitmap()
                iconCache[packageName] = bitmap
                bitmap
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable, targetSizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val bmp = drawable.bitmap
            if (bmp.width <= targetSizePx * 2 && bmp.height <= targetSizePx * 2) {
                return bmp
            }
            return Bitmap.createScaledBitmap(bmp, targetSizePx, targetSizePx, true)
        }
        val bitmap = Bitmap.createBitmap(targetSizePx, targetSizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private val ESSENTIAL_KEYWORDS = listOf(
            "dialer",
            "phone",
            "telecom",
            "messaging",
            "messages",
            "mms",
            "deskclock",
            "clock",
            "alarm",
            "calendar",
            "whatsapp",
            "signal",
            "authenticator",
        )

        fun isEssentialPackage(packageName: String): Boolean {
            val lower = packageName.lowercase()
            return ESSENTIAL_KEYWORDS.any { keyword -> lower.contains(keyword) }
        }
    }
}
