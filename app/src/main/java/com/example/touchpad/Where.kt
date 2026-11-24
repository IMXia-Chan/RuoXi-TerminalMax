package com.example.touchpad

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import java.util.Locale

/**
 * 这台手机**在哪儿** —— 天气用它的经纬度，不再只靠出口 IP。
 *
 * ## 为什么要有
 *
 * 他 2026-11-07 说「最好还能开 gps（这个好像应该有了）」。查过了：**没有**。
 * 之前那条 `ACCESS_FINE_LOCATION` 是给蓝牙扫描用的，还带着 `maxSdkVersion="30"`，
 * 从 Android 11 起就没再要过。天气一直拿出口 IP 认城市 ——
 * 移动网络的出口常在省会，于是「人在上海、天气报北京」那种错，一直存在。
 *
 * ## 三条边界
 *
 * 1. **拿不到就用旧的**。权限没给、定位超时、室内没有星，全都退回 [Weather] 原来那条
 *    IP → 地理编码的路，功能不受影响。
 * 2. **只存经纬度和城市名**，不存轨迹、不存历史。落盘一份就够画一张天气卡。
 * 3. **只在本机用**。这两个数会跟着天气请求发给 open-meteo（那是天气服务本来就要的），
 *    除此之外一个字节都不往外走。
 *
 * ## 反向地理编码是「尽力而为」
 *
 * `Geocoder` 在国产 ROM 上不保证可用（有的直接抛 `Service not Available`）。
 * 拿不到城市名不当故障 —— 屏上会写「当前位置」，而经纬度照样能用。
 */
internal object Where {

    private const val PREF = "where"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_CITY = "city"
    private const val KEY_AT = "at"

    /**
     * 一次定位多久之后算「旧」。
     *
     * ★ 六小时。他一整天待在一个地方，没必要每次进房间都开一次 GPS（那是实打实的耗电）；
     *   而走了六个小时以上还没挪窝的情况本来也少。
     */
    private const val FIX_MAX_AGE_MS = 6 * 3600_000L

    /** 两条定位权限给一条就算有。COARSE 足够认城市。 */
    fun granted(ctx: Context): Boolean {
        val fine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    internal class Fix(val lat: Double, val lon: Double, val city: String)

    /**
     * 问过权限没有。
     *
     * ★ 问过一次就别再弹：他不给是有理由的，而每次进房间弹一次框，
     *   到他第三次就会开始烦这个 App —— 定位只是锦上添花，不值这个代价。
     */
    fun asked(ctx: Context): Boolean = try {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("asked", false)
    } catch (_: Exception) {
        true
    }

    fun markAsked(ctx: Context) {
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("asked", true).apply()
        } catch (_: Exception) {
        }
    }

    /** 上一次存下的位置；没有就是 null。 */
    fun cached(ctx: Context): Fix? {
        val p = try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return null
        }
        // ★ 用字符串存双精度:SharedPreferences 的 float 只有 7 位有效数字,
        //   在经纬度上约等于 1 米 —— 够用，但**读出来会变**，对不上时最难查。
        val lat = p.getString(KEY_LAT, "")?.toDoubleOrNull() ?: return null
        val lon = p.getString(KEY_LON, "")?.toDoubleOrNull() ?: return null
        if (lat.isNaN() || lon.isNaN()) return null
        return Fix(lat, lon, p.getString(KEY_CITY, "").orEmpty())
    }

    /** 上一次定位是不是够新。够新就不用再开 GPS。 */
    fun fresh(ctx: Context): Boolean {
        val p = try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return false
        }
        val at = p.getLong(KEY_AT, 0L)
        return at > 0 && System.currentTimeMillis() - at < FIX_MAX_AGE_MS
    }

    /**
     * 要一次位置。**必须在后台线程调**（反向地理编码会阻塞）。
     *
     * 顺序：拿一条还算新的历史位置 → 没有就现要一条（网络优先，室内也定得到）。
     * 两个都拿不到就什么都不做 —— 调用方那边有旧的会用旧的。
     */
    fun refresh(ctx: Context) {
        if (!granted(ctx)) return
        if (fresh(ctx)) return

        val lm = try {
            ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        } catch (_: Exception) {
            null
        } ?: return

        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val l = try {
                lm.getLastKnownLocation(p)
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || l.time > best.time) best = l
        }

        if (best != null && System.currentTimeMillis() - best.time < FIX_MAX_AGE_MS) {
            save(ctx, best.latitude, best.longitude)
            return
        }

        // 现要一条。★ 用 `getCurrentLocation`（API 30+）而不是 `requestSingleUpdate`：
        //   后者 30 起已废弃，而且回调挂在调用方的 Looper 上，在这条后台线程上根本没有。
        val wanted = if (Build.VERSION.SDK_INT >= 30) {
            try {
                lm.getCurrentLocation(LocationManager.NETWORK_PROVIDER, null, ctx.mainExecutor) { loc ->
                    if (loc != null) saveInBackground(ctx, loc.latitude, loc.longitude)
                }
                true
            } catch (_: SecurityException) {
                false
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        if (!wanted && best != null) {
            // 定不到新的，至少把旧的那条存下来（总比没有强，且不带假的新鲜度）
            save(ctx, best.latitude, best.longitude)
        }
    }

    private fun saveInBackground(ctx: Context, lat: Double, lon: Double) {
        Thread({ save(ctx, lat, lon) }, "where-save").apply { isDaemon = true }.start()
    }

    /** 存下来，并尽力换一个城市名。 */
    private fun save(ctx: Context, lat: Double, lon: Double) {
        val city = cityOf(ctx, lat, lon)
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAT, lat.toString())
                .putString(KEY_LON, lon.toString())
                .putString(KEY_CITY, city)
                .putLong(KEY_AT, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
        }
    }

    /** 经纬度 → 城市名。拿不到就空串（调用方会写「当前位置」）。 */
    private fun cityOf(ctx: Context, lat: Double, lon: Double): String = try {
        @Suppress("DEPRECATION")
        val list = Geocoder(ctx, Locale.CHINA).getFromLocation(lat, lon, 1)
        val a = list?.firstOrNull()
        val name = a?.locality ?: a?.subAdminArea ?: a?.adminArea ?: ""
        name.removeSuffix("市").removeSuffix("地区")
    } catch (_: Exception) {
        ""
    }
}
