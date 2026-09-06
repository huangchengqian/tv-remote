# 我的电视

电视直播软件，支持自定义视频源（M3U/TXT），可配合手机遥控使用。

> 本仓库为社区自维护 fork。原版内置的央视频取流接口已失效（返回 401），本 fork 重做了直播源链路：**仓库本身不包含、不预置任何直播源**，频道内容请自行获取并通过设置页/本地文件/手机推送配置。

## 直播源配置

支持 M3U 和 TXT 两种格式：

```text
# M3U 格式
#EXTINF:-1 tvg-logo="logo地址" group-title="央视",CCTV1
http://example.com/cctv1.m3u8

# TXT 格式
央视,#genre#
CCTV1,http://example.com/cctv1.m3u8
```

配置方式（按优先级依次尝试，任一成功即生效）：

1. **设置页配置地址**：打开设置（按菜单键），在「直播源地址」填入 m3u/txt 的 URL，点「保存并刷新直播源」；也可用手机控制页远程设置
2. **本地文件**：将直播源文件命名为 `my-tv.txt` 或 `my-tv.m3u`，放到以下任一位置
    * `/sdcard/my-tv.txt`
    * `/sdcard/Download/my-tv.txt`
    * U盘根目录
    * 应用私有目录（无需存储权限）：`/sdcard/Android/data/com.lizongying.mytv/files/my-tv.txt`
       ```shell
       adb push my-tv.txt /sdcard/Android/data/com.lizongying.mytv/files/
       ```
3. **手机推送**：设置页扫码打开手机控制页，把源内容直接粘贴推送给电视（电视访问不了源地址时用）
4. **缓存回退**：任一来源曾成功加载过，断网时会使用上次的缓存
