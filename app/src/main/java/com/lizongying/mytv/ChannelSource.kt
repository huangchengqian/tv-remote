package com.lizongying.mytv

import android.content.Context
import android.util.Log
import com.lizongying.mytv.models.ProgramType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 自定义直播源加载与解析。
 *
 * 支持格式：
 * - M3U：#EXTINF 行取 tvg-logo / group-title 属性，下一非注释行为地址
 * - TXT：分组行 `分组名,#genre#`，频道行 `频道名,地址` 或 `分组名,频道名,地址`
 *
 * 加载优先级（任一成功即生效）：
 * 1. 设置页/手机端配置的远程地址（SP.sourceUrl）
 * 2. 本地文件（手机推送 > /sdcard、/sdcard/Download、U盘、应用外部私有目录）
 * 3. 预置远程源（仅本地构建携带，见 BuildConfig.PRESET_SOURCE_URLS）
 * 4. 上次成功加载的缓存（启动时用于秒开，见 loadCacheFirst）
 * 全部失败时返回 null，调用方回退到内置频道列表。
 */
object ChannelSource {

    private const val TAG = "ChannelSource"

    private const val DEFAULT_GROUP = "其他"

    private const val CACHE_FILE = "source.cache"

    // 手机推送的源内容写入此文件，优先级最高
    private const val PUSH_FILE = "my-tv-push.txt"

    private val NAMES =
        listOf("my-tv.txt", "my-tv.m3u", "tv.txt", "tv.m3u", "live.txt", "live.m3u")

    // 预置直播源：来自本地构建配置（source.local.properties，不入库）。
    // 未配置时为空，App 为纯播放器模式，由用户自行配置源。
    private val DEFAULT_SOURCE_URLS: List<String> =
        BuildConfig.PRESET_SOURCE_URLS.split(',').map { it.trim() }
            .filter { it.isNotEmpty() }

    private val urlPrefixes =
        listOf("http://", "https://", "rtmp://", "rtsp://", "rtp://", "udp://")

    // 源里混入的公告/推广伪频道（如"更新时间"分组的宣传视频），过滤掉
    private val bannedGroups = setOf("更新时间", "发布时间", "广告", "推广")

    private val bannedNameRegexes = listOf(
        Regex("""^\d{4}[-/年.]\d{1,2}"""), // 伪装成时间戳的频道名
        Regex("""更新时间|发布时间|关注订阅|公众号"""),
    )

    private val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private data class Entry(
        val group: String,
        val name: String,
        val url: String,
        val logo: String,
    )

    enum class SourceOrigin { LOCAL, CACHE, REMOTE }

    class SourceResult(
        val channels: Map<String, List<TV>>,
        val origin: SourceOrigin,
    )

    /**
     * 启动加载：设置页地址 -> 本地文件 -> 缓存 -> 默认源。有缓存时不等网络，秒开。
     */
    suspend fun loadCacheFirst(context: Context): SourceResult? = withContext(Dispatchers.IO) {
        val url = SP.sourceUrl.trim()
        if (url.isNotEmpty()) {
            fetchRemote(url)?.let { text ->
                parse(text)?.let { parsed ->
                    cache(context, text)
                    Log.i(TAG, "loaded from $url, groups=${parsed.size}")
                    return@withContext SourceResult(parsed, SourceOrigin.REMOTE)
                }
            }
            Log.w(TAG, "configured source unavailable, try local files")
        }

        loadLocal(context)?.let { return@withContext SourceResult(it, SourceOrigin.LOCAL) }

        val cache = cacheFile(context)
        if (cache.isFile && cache.length() > 0) {
            try {
                parse(cache.readText())?.let { parsed ->
                    Log.i(TAG, "loaded from cache, groups=${parsed.size}")
                    return@withContext SourceResult(parsed, SourceOrigin.CACHE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "read cache error $e")
            }
        }

        loadRemote(context)?.let { return@withContext SourceResult(it, SourceOrigin.REMOTE) }

        Log.w(TAG, "no usable custom source")
        null
    }

    /**
     * 全新加载：设置页地址 -> 本地文件 -> 默认源链，不走缓存。
     */
    suspend fun loadFresh(context: Context): Map<String, List<TV>>? = withContext(Dispatchers.IO) {
        val url = SP.sourceUrl.trim()
        if (url.isNotEmpty()) {
            fetchRemote(url)?.let { text ->
                parse(text)?.let { parsed ->
                    cache(context, text)
                    Log.i(TAG, "loaded from $url, groups=${parsed.size}")
                    return@withContext parsed
                }
            }
        }
        loadLocal(context) ?: loadRemote(context)
    }

    /**
     * 仅从远程刷新（设置页配置地址优先，其次默认源链），成功后写缓存。
     * 供后台静默刷新使用，返回 null 表示远程全部不可达。
     */
    suspend fun refreshRemote(context: Context): Map<String, List<TV>>? = withContext(Dispatchers.IO) {
        val url = SP.sourceUrl.trim()
        val urls = if (url.isNotEmpty()) listOf(url) + DEFAULT_SOURCE_URLS else DEFAULT_SOURCE_URLS
        for (u in urls) {
            fetchRemote(u)?.let { text ->
                parse(text)?.let { parsed ->
                    cache(context, text)
                    Log.i(TAG, "refreshed from $u, groups=${parsed.size}")
                    return@withContext parsed
                }
            }
            Log.w(TAG, "source unavailable: $u")
        }
        null
    }

    /**
     * 保存手机推送的源内容，写入应用私有文件（本地文件中优先级最高）。
     */
    suspend fun savePushContent(context: Context, content: String) = withContext(Dispatchers.IO) {
        pushFile(context).writeText(content)
        cache(context, content)
    }

    private suspend fun loadLocal(context: Context): Map<String, List<TV>>? =
        withContext(Dispatchers.IO) {
            for (file in localCandidates(context)) {
                if (!file.isFile || file.length() == 0L) {
                    continue
                }
                try {
                    val text = file.readText()
                    parse(text)?.let { parsed ->
                        cache(context, text)
                        Log.i(TAG, "loaded from ${file.absolutePath}, groups=${parsed.size}")
                        return@withContext parsed
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read ${file.absolutePath} error $e")
                }
            }
            null
        }

    private suspend fun loadRemote(context: Context): Map<String, List<TV>>? =
        withContext(Dispatchers.IO) {
            val url = SP.sourceUrl.trim()
            val urls = if (url.isNotEmpty()) listOf(url) + DEFAULT_SOURCE_URLS else DEFAULT_SOURCE_URLS
            for (u in urls) {
                fetchRemote(u)?.let { text ->
                    parse(text)?.let { parsed ->
                        cache(context, text)
                        Log.i(TAG, "loaded from $u, groups=${parsed.size}")
                        return@withContext parsed
                    }
                }
                Log.w(TAG, "source unavailable: $u")
            }
            null
        }

    /**
     * 生成源内容指纹，用于比较两次加载的频道是否变化。
     */
    fun channelsKey(channels: Map<String, List<TV>>): String {
        return channels.entries.joinToString("|") { (group, list) ->
            group + ":" + list.joinToString(",") { it.title + "@" + it.videoUrl.firstOrNull() }
        }
    }

    fun parse(text: String): Map<String, List<TV>>? {
        val entries = if (text.contains("#EXTINF")) parseM3u(text) else parseTxt(text)
        if (entries.isEmpty()) {
            return null
        }

        val groups = linkedMapOf<String, MutableList<Entry>>()
        val seen = mutableSetOf<String>()
        for (entry in entries) {
            if (entry.group in bannedGroups || bannedNameRegexes.any { it.containsMatchIn(entry.name) }) {
                continue
            }
            if (!seen.add("${entry.name}|${entry.url}")) {
                continue
            }
            groups.getOrPut(entry.group) { mutableListOf() }.add(entry)
        }

        val result = linkedMapOf<String, List<TV>>()
        var id = 0
        for ((group, list) in groups) {
            val tvs = list.map {
                TV(
                    id++,
                    it.name,
                    "",
                    listOf(it.url),
                    it.group,
                    it.logo,
                    "",
                    "",
                    ProgramType.NONE,
                )
            }
            if (tvs.isNotEmpty()) {
                result[group] = tvs
            }
        }
        return if (result.isEmpty()) null else result
    }

    private fun parseM3u(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var group = DEFAULT_GROUP
        var name = ""
        var logo = ""
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) {
                continue
            }
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(',').trim()
                logo = Regex("""tvg-logo="([^"]*)""").find(line)?.groupValues?.get(1) ?: ""
                group = Regex("""group-title="([^"]*)""").find(line)?.groupValues?.get(1)
                    ?.trim()?.takeUnless { it.isEmpty() } ?: DEFAULT_GROUP
            } else if (!line.startsWith("#")) {
                if (name.isNotEmpty() && isUrl(line)) {
                    entries.add(Entry(group, name, line, logo))
                }
                name = ""
                logo = ""
            }
        }
        return entries
    }

    private fun parseTxt(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        var group = DEFAULT_GROUP
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("//")) {
                continue
            }
            if (line.contains("#genre#")) {
                group = line.substringBefore(',').trim().takeUnless { it.isEmpty() }
                    ?: DEFAULT_GROUP
                continue
            }
            if (line.startsWith("#")) {
                continue
            }
            if (!line.contains(",")) {
                continue
            }
            val parts = line.split(",")
            val (g, name, url) = when {
                parts.size >= 3 && isUrl(parts[2].trim()) -> {
                    Triple(parts[0].trim().takeUnless { it.isEmpty() } ?: DEFAULT_GROUP,
                        parts[1].trim(), parts[2].trim())
                }

                parts.size >= 2 && isUrl(parts[1].trim()) -> {
                    Triple(group, parts[0].trim(), parts[1].trim())
                }

                else -> null
            } ?: continue
            if (name.isNotEmpty()) {
                entries.add(Entry(g, name, url, ""))
            }
        }
        return entries
    }

    private fun isUrl(s: String): Boolean {
        return urlPrefixes.any { s.startsWith(it) }
    }

    private fun fetchRemote(url: String): String? {
        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("user-agent", "Mozilla/5.0")
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body()?.string()
                } else {
                    Log.e(TAG, "fetch $url status ${response.code()}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch $url error $e")
            null
        }
    }

    private fun localCandidates(context: Context): List<File> {
        val files = mutableListOf(pushFile(context))

        // 应用外部私有目录，无需存储权限
        context.getExternalFilesDir(null)?.let { dir ->
            NAMES.forEach { files.add(File(dir, it)) }
        }

        val roots = mutableListOf(File("/sdcard"), File("/sdcard/Download"))

        // U盘等挂载目录
        File("/storage").listFiles()
            ?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
            ?.let { roots.addAll(it) }

        for (root in roots) {
            NAMES.forEach { files.add(File(root, it)) }
        }
        return files
    }

    private fun pushFile(context: Context): File {
        return File(context.filesDir, PUSH_FILE)
    }

    private fun cacheFile(context: Context): File {
        return File(context.filesDir, CACHE_FILE)
    }

    private fun cache(context: Context, text: String) {
        try {
            cacheFile(context).writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "cache error $e")
        }
    }
}
