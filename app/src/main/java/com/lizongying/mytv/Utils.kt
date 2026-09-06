package com.lizongying.mytv

import android.content.res.Resources
import android.os.Build
import android.util.TypedValue
import com.google.gson.Gson
import com.lizongying.mytv.api.TimeResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Utils {
    private var between: Long = 0

    fun getDateFormat(format: String): String {
        return SimpleDateFormat(
            format,
            Locale.CHINA
        ).format(Date(System.currentTimeMillis() - between))
    }

    fun getDateTimestamp(): Long {
        return (System.currentTimeMillis() - between) / 1000
    }

    fun setBetween(currentTimeMillis: Long) {
        between = System.currentTimeMillis() - currentTimeMillis
    }

    suspend fun init() {
        var serverTime = 0L
        try {
            serverTime = getTimestampFromServer()
        } catch (e: Exception) {
            println("Failed to retrieve timestamp from server: ${e.message}")
        }
        between = if (serverTime > 0) {
            val offset = System.currentTimeMillis() - serverTime
            // 服务器时间异常（偏差超过24小时）时忽略，使用本机时间
            if (offset in -86_400_000L..86_400_000L) offset else 0L
        } else {
            // 校时失败直接用本机时间，避免时钟显示成纪元零点
            0L
        }
    }

    /**
     * 从服务器获取时间戳，依次尝试多个接口，全部失败返回 0
     */
    private suspend fun getTimestampFromServer(): Long {
        return withContext(Dispatchers.IO) {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
            val apis = listOf(
                "https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp",
                "https://f.m.suning.com/api/ct.do",
            )
            for (api in apis) {
                try {
                    val request = okhttp3.Request.Builder().url(api).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("Unexpected code $response")
                        val body = response.body()?.string() ?: throw IOException("empty body")
                        val json = org.json.JSONObject(body)
                        when (api) {
                            apis[0] -> json.getJSONObject("data").optString("t").toLongOrNull() ?: 0L
                            else -> json.optLong("currentTime", 0L)
                        }?.takeIf { it > 0 } ?: throw IOException("bad payload")
                    }
                } catch (e: Exception) {
                    println("timestamp from $api failed: ${e.message}")
                } ?: continue
            }
            0L
        }
    }

    fun dpToPx(dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp, Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), Resources.getSystem().displayMetrics
        ).toInt()
    }

    fun pxToDp(px: Float): Int {
        val scale = Resources.getSystem().displayMetrics.density
        return (px / scale).toInt()
    }

    /**
     * 获取局域网 IPv4 地址，用于手机控制页访问。
     */
    fun getLocalIpAddress(): String? {
        return try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val addresses = en.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun pxToDp(px: Int): Int {
        val scale = Resources.getSystem().displayMetrics.density
        return (px / scale).toInt()
    }

    fun isTmallDevice() = Build.MANUFACTURER.equals("Tmall", ignoreCase = true)
}