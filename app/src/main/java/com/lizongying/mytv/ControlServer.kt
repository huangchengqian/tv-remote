package com.lizongying.mytv

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 手机控制服务：手机与电视处于同一局域网时，浏览器访问 http://电视IP:9958 即可遥控。
 *
 * 接口：
 * - GET  /            控制页
 * - GET  /status      当前频道、音量等状态
 * - GET  /channels    频道列表（按分组）
 * - POST /play        {"position": 序号} 播放指定频道
 * - POST /command     {"action": "prev"|"next"|"menu"|"ok"|"back"}
 * - POST /volume      {"percent": 0-100}
 * - POST /source      {"url": "地址"} 或 {"content": "m3u/txt 内容"}，成功后电视端自动换源
 */
class ControlServer(
    private val activity: MainActivity,
    port: Int,
) : NanoHTTPD(port) {

    private val handler = Handler(Looper.getMainLooper())

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.method) {
                Method.GET -> when (session.uri) {
                    "/", "/index.html" -> serveIndex()
                    "/status" -> newJsonResponse(statusJson())
                    "/channels" -> newJsonResponse(channelsJson())
                    "/crash" -> newJsonResponse(crashJson())
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
                }

                Method.POST -> when (session.uri) {
                    "/play" -> handlePlay(session)
                    "/command" -> handleCommand(session)
                    "/volume" -> handleVolume(session)
                    "/source" -> handleSource(session)
                    "/crash" -> handleCrashClear(session)
                    else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "not found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "serve ${session.uri} error", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "error: ${e.message}"
            )
        }
    }

    private fun readBody(session: IHTTPSession): JSONObject {
        // 不用 session.parseBody：其按 ISO-8859-1 解码，中文会乱码，这里按 UTF-8 直接读原始字节
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        require(length in 1..10_000_000) { "bad content-length $length" }
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n < 0) {
                break
            }
            read += n
        }
        return JSONObject(String(buf, 0, read, Charsets.UTF_8))
    }

    private fun newJsonResponse(obj: JSONObject): Response {
        return newFixedLengthResponse(
            Response.Status.OK, "application/json; charset=utf-8", obj.toString()
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    private fun newOkResponse(message: String): Response {
        val obj = JSONObject().put("ok", true).put("message", message)
        return newJsonResponse(obj)
    }

    private fun runOnMain(block: () -> Unit) {
        handler.post(block)
    }

    private fun statusJson(): JSONObject {
        val obj = JSONObject()
        val current = activity.currentChannelPosition()
        obj.put("position", current)
        obj.put("title", activity.currentChannelTitle() ?: "")
        obj.put("group", activity.currentChannelGroup() ?: "")
        obj.put("volume", activity.currentVolumePercent())
        obj.put("sourceUrl", SP.sourceUrl)
        return obj
    }

    private fun channelsJson(): JSONObject {
        val groups = linkedMapOf<String, JSONArray>()
        var position = 0
        activity.forEachChannel { group, title ->
            val arr = groups.getOrPut(group.ifEmpty { DEFAULT_GROUP }) { JSONArray() }
            val item = JSONObject()
            item.put("position", position)
            item.put("name", title)
            arr.put(item)
            position++
        }
        val obj = JSONObject()
        obj.put("current", activity.currentChannelPosition())
        val groupArr = JSONArray()
        for ((name, channels) in groups) {
            val g = JSONObject()
            g.put("name", name)
            g.put("channels", channels)
            groupArr.put(g)
        }
        obj.put("groups", groupArr)
        return obj
    }

    private fun handlePlay(session: IHTTPSession): Response {
        val body = readBody(session)
        val position = body.optInt("position", -1)
        if (position < 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad position")
        }
        runOnMain { activity.controlPlay(position) }
        return newOkResponse("play $position")
    }

    private fun handleCommand(session: IHTTPSession): Response {
        val body = readBody(session)
        when (body.optString("action")) {
            "prev" -> runOnMain { activity.controlPrevChannel() }
            "next" -> runOnMain { activity.controlNextChannel() }
            "menu" -> runOnMain { activity.controlShowSetting() }
            "ok" -> runOnMain { activity.controlOk() }
            "back" -> runOnMain { activity.controlBack() }
            else -> return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "unknown action"
            )
        }
        return newOkResponse("command done")
    }

    private fun handleVolume(session: IHTTPSession): Response {
        val body = readBody(session)
        val percent = body.optInt("percent", -1)
        if (percent !in 0..100) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad percent")
        }
        runOnMain { activity.controlSetVolume(percent) }
        return newOkResponse("volume $percent")
    }

    @SuppressLint("AuthLeak")
    private fun handleSource(session: IHTTPSession): Response {
        val body = readBody(session)
        val url = body.optString("url").trim()
        val content = body.optString("content")

        return if (url.isNotEmpty()) {
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "bad url"
                )
            }
            SP.sourceUrl = url
            activity.reloadSourceFromNetwork()
            newOkResponse("source url saved: $url")
        } else if (content.isNotBlank()) {
            activity.savePushedSource(content)
            newOkResponse("source content pushed")
        } else {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "empty source")
        }
    }

    private fun crashJson(): JSONObject {
        val obj = JSONObject()
        val file = File(activity.filesDir, MyApplication.CRASH_FILE)
        obj.put("crash", if (file.isFile) file.readText() else JSONObject.NULL)
        return obj
    }

    private fun handleCrashClear(session: IHTTPSession): Response {
        File(activity.filesDir, MyApplication.CRASH_FILE).delete()
        return newOkResponse("crash log cleared")
    }

    private fun serveIndex(): Response {
        return newFixedLengthResponse(
            Response.Status.OK, "text/html; charset=utf-8", INDEX_HTML
        ).apply {
            addHeader("Access-Control-Allow-Origin", "*")
        }
    }

    companion object {
        private const val TAG = "ControlServer"
        private const val DEFAULT_GROUP = "其他"

        // language=HTML
        private const val INDEX_HTML = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
<title>TV遥控 · 遥控</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: #14181d; color: #e8eaed; font-family: -apple-system, sans-serif;
         padding: 12px; padding-bottom: 40px; }
  h1 { font-size: 18px; text-align: center; margin: 6px 0 12px; }
  .current { background: #1f2630; border-radius: 10px; padding: 12px; text-align: center;
             margin-bottom: 12px; }
  .current .name { font-size: 17px; font-weight: bold; }
  .current .group { font-size: 12px; color: #9aa0a6; margin-top: 4px; }
  .pad { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-bottom: 12px; }
  .pad button { background: #2a323d; color: #e8eaed; border: none; border-radius: 8px;
                padding: 12px 0; font-size: 14px; }
  .pad button:active { background: #3a4553; }
  .vol { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
  .vol input { flex: 1; }
  .search { width: 100%; background: #1f2630; color: #e8eaed; border: 1px solid #2a323d;
            border-radius: 8px; padding: 10px; font-size: 14px; margin-bottom: 12px; }
  details { margin-bottom: 8px; }
  summary { background: #1f2630; border-radius: 8px; padding: 10px 12px; font-size: 14px;
            cursor: pointer; list-style: none; }
  summary::before { content: "▸ "; color: #9aa0a6; }
  details[open] summary::before { content: "▾ "; }
  .ch { display: block; width: 100%; text-align: left; background: none; color: #c7cbd1;
        border: none; border-bottom: 1px solid #232a33; padding: 10px 12px; font-size: 14px; }
  .ch:active { background: #2a323d; }
  .ch.on { color: #66aaff; }
  .src { background: #1f2630; border-radius: 10px; padding: 12px; margin-top: 16px; }
  .src h3 { font-size: 14px; margin-bottom: 8px; }
  .src input, .src textarea { width: 100%; background: #14181d; color: #e8eaed;
        border: 1px solid #2a323d; border-radius: 8px; padding: 8px; font-size: 13px;
        margin-bottom: 8px; }
  .src textarea { height: 80px; }
  .src .row { display: flex; gap: 8px; }
  .src button { flex: 1; background: #2a323d; color: #e8eaed; border: none;
        border-radius: 8px; padding: 10px 0; font-size: 13px; }
  .tip { text-align: center; color: #6b7280; font-size: 11px; margin-top: 14px; }
</style>
</head>
<body>
<h1>TV遥控 · 遥控</h1>
<div class="current"><div class="name" id="curName">加载中…</div>
  <div class="group" id="curGroup"></div></div>
<div class="pad">
  <button onclick="cmd('prev')">◀ 上台</button>
  <button onclick="cmd('next')">下台 ▶</button>
  <button onclick="cmd('menu')">设置</button>
  <button onclick="cmd('back')">返回</button>
</div>
<div class="vol">🔊 <input type="range" id="vol" min="0" max="100" value="50"
  oninput="volTimer()"></div>
<input class="search" id="search" placeholder="搜索频道" oninput="render()">
<div id="groups"></div>
<div class="src">
  <h3>直播源配置</h3>
  <input id="srcUrl" placeholder="直播源地址 (m3u/txt URL)">
  <div class="row"><button onclick="pushUrl()">设置地址</button></div>
  <textarea id="srcContent" placeholder="或直接粘贴 m3u / txt 源内容"></textarea>
  <div class="row"><button onclick="pushContent()">推送源内容</button></div>
</div>
<div class="src" id="crashBox" style="display:none; border-color:#B00020;">
  <h3 style="color:#FF7B72;">⚠ 电视端崩溃日志（请截图反馈）</h3>
  <pre id="crashLog" style="white-space:pre-wrap; font-size:11px; color:#FF7B72;
    max-height:200px; overflow:auto; margin-bottom:8px;"></pre>
  <div class="row"><button onclick="clearCrash()">清除崩溃日志</button></div>
</div>
<div class="tip">电视与手机需在同一局域网 · TV Remote</div>
<script>
let channels = [];
let openGroups = new Set();
let currentPos = -1;
function jpost(url, data) {
  return fetch(url, {method: 'POST', headers: {'Content-Type': 'application/json'},
                     body: JSON.stringify(data)});
}
async function refresh() {
  try {
    const r = await fetch('/channels');
    const d = await r.json();
    currentPos = d.current;
    const all = d.groups.flatMap(g => g.channels);
    const cur = all.find(c => c.position === d.current);
    document.getElementById('curName').textContent = cur ? cur.name : '未知频道';
    // 列表只在频道数据变化时重建，避免页面跳动
    if (JSON.stringify(channels) !== JSON.stringify(d.groups)) {
      channels = d.groups;
      render();
    }
    updateHighlight();
    const s = await (await fetch('/status')).json();
    document.getElementById('curGroup').textContent = s.group || '';
    if (document.activeElement !== document.getElementById('vol')) {
      document.getElementById('vol').value = s.volume;
    }
    document.getElementById('srcUrl').placeholder =
        '直播源地址 (当前: ' + (s.sourceUrl || '默认源') + ')';
  } catch (e) { document.getElementById('curName').textContent = '连接失败'; }
}
function render() {
  const q = document.getElementById('search').value.trim();
  const box = document.getElementById('groups');
  box.innerHTML = '';
  for (const g of channels) {
    const list = g.channels.filter(c => !q || c.name.toLowerCase().includes(q.toLowerCase()));
    if (!list.length) continue;
    const d = document.createElement('details');
    if (openGroups.has(g.name)) d.open = true;
    d.addEventListener('toggle', () => {
      if (d.open) openGroups.add(g.name); else openGroups.delete(g.name);
    });
    const s = document.createElement('summary');
    s.textContent = g.name + ' (' + list.length + ')';
    d.appendChild(s);
    for (const c of list) {
      const b = document.createElement('button');
      b.className = 'ch';
      b.textContent = c.name;
      b.dataset.pos = c.position;
      b.onclick = () => play(c.position);
      d.appendChild(b);
    }
    box.appendChild(d);
  }
  updateHighlight();
}
function updateHighlight() {
  document.querySelectorAll('.ch').forEach(b =>
      b.classList.toggle('on', +b.dataset.pos === currentPos));
}
async function play(pos) {
  currentPos = pos;
  const cur = channels.flatMap(g => g.channels).find(c => c.position === pos);
  if (cur) document.getElementById('curName').textContent = cur.name;
  updateHighlight();
  await jpost('/play', {position: pos});
}
async function cmd(action) { await jpost('/command', {action}); refresh(); }
let vt = null;
function volTimer() { clearTimeout(vt); vt = setTimeout(async () => {
  await jpost('/volume', {percent: +document.getElementById('vol').value}); }, 300); }
async function pushUrl() {
  const url = document.getElementById('srcUrl').value.trim();
  if (!url) return alert('请输入地址');
  const r = await jpost('/source', {url});
  alert((await r.json()).message || '已设置');
}
async function pushContent() {
  const content = document.getElementById('srcContent').value;
  if (!content.trim()) return alert('请粘贴源内容');
  const r = await jpost('/source', {content});
  alert((await r.json()).message || '已推送');
}
async function checkCrash() {
  try {
    const d = await (await fetch('/crash')).json();
    if (d.crash) {
      document.getElementById('crashLog').textContent = d.crash;
      document.getElementById('crashBox').style.display = '';
    }
  } catch (e) {}
}
async function clearCrash() {
  await jpost('/crash', {clear: true});
  document.getElementById('crashBox').style.display = 'none';
}
checkCrash();
refresh();
setInterval(refresh, 5000);
</script>
</body>
</html>"""
    }
}
