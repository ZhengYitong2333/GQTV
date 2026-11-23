package com.lizongying.mytv

// Public HLS fallbacks, re-verified on 2026-11-23.
//
// Verification method: pull the playlist, follow the best variant, download the
// last segment, read the real size with `ffprobe`, then capture a frame and
// compare the on-screen logo with the channel name. The farms do NOT number
// their streams the way their URLs suggest — 183.129.255.66 hls/6 is CCTV-5+,
// hls/8 is CCTV-7, hls/9 is CCTV-8, hls/18 is CCTV-17, and 221.7.175.154
// tsfile 1018_1 is CCTV-5+ rather than CCTV-5.
//
// Every entry below measured 1920x1080 unless it ends with "(720p)".
// Order matters: lines are tried top to bottom, so the best measured line comes
// first and the hosts are deliberately mixed for redundancy.
//
// Channels with no own source (the pay channels above all) are keyed by alias
// exactly like the rest, so a lookup keyed by alias is all the player needs.

/** Ordered fallback lines for [alias]; the 凤凰 group carries no alias and has none. */
internal fun backupSource(alias: String): List<String>? =
    if (alias.isEmpty()) null else backupStreams[alias]

internal val backupStreams: Map<String, List<String>> = mapOf(
    // ---- 央视 --------------------------------------------------------------
    "CCTV1" to listOf(
        "http://1.24.39.180:9003/hls/1/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv1hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/1/index.m3u8",
        "http://221.7.175.154:8445/tsfile/live/1000_1.m3u8?key=txiptv&playlive=1&authid=0",
        "http://74.91.26.218:82/live/cctv1hd.m3u8", // 720p
    ),
    "CCTV2" to listOf(
        "http://1.24.39.180:9003/hls/2/index.m3u8",
        "http://222.223.41.27:8888/hls/2/index.m3u8",
        "http://74.91.26.218:82/live/cctv2hd.m3u8", // 720p
    ),
    "CCTV3" to listOf(
        "http://1.24.39.180:9003/hls/3/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv3hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/3/index.m3u8",
        "http://74.91.26.218:82/live/cctv3hd.m3u8", // 720p
    ),
    "CCTV4" to listOf(
        "http://1.24.39.180:9003/hls/4/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv4hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/4/index.m3u8",
    ),
    "CCTV5" to listOf(
        "http://1.24.39.180:9003/hls/5/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv5hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/5/index.m3u8",
    ),
    "CCTV5+" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/cctv5p.m3u8?auth=testpub",
        "http://183.129.255.66:8480/hls/6/index.m3u8",
        "http://221.7.175.154:8445/tsfile/live/1018_1.m3u8?key=txiptv&playlive=1&authid=0",
        "http://59.39.89.130:60901/tsfile/live/0016_1.m3u8?key=txiptv&playlive=1&authid=0",
    ),
    "CCTV6" to listOf(
        "http://1.24.39.180:9003/hls/6/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv6hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/6/index.m3u8",
        "http://74.91.26.218:82/live/cctv6hd.m3u8", // 720p
    ),
    "CCTV7" to listOf(
        "http://1.24.39.180:9003/hls/7/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv7hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/7/index.m3u8",
        "http://183.129.255.66:8480/hls/8/index.m3u8",
        "http://74.91.26.218:82/live/cctv7hd.m3u8", // 720p
    ),
    "CCTV8" to listOf(
        "http://1.24.39.180:9003/hls/8/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv8hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/8/index.m3u8",
        "http://183.129.255.66:8480/hls/9/index.m3u8",
        "http://74.91.26.218:82/live/cctv8hd.m3u8", // 720p
    ),
    "CCTV9" to listOf(
        "http://1.24.39.180:9003/hls/9/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv9hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/9/index.m3u8",
        "http://221.7.175.154:8445/tsfile/live/1007_1.m3u8?key=txiptv&playlive=1&authid=0",
        "http://74.91.26.218:82/live/cctv9hd.m3u8", // 720p
    ),
    "CCTV10" to listOf(
        "http://1.24.39.180:9003/hls/10/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv10hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/10/index.m3u8",
        "http://74.91.26.218:82/live/cctv10hd.m3u8", // 720p
    ),
    "CCTV11" to listOf(
        "http://1.24.39.180:9003/hls/11/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv11hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/11/index.m3u8",
        "http://74.91.26.218:82/live/cctv11hd.m3u8", // 720p
    ),
    "CCTV12" to listOf(
        "http://1.24.39.180:9003/hls/12/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv12hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/12/index.m3u8",
        "http://221.7.175.154:8445/tsfile/live/1010_1.m3u8?key=txiptv&playlive=1&authid=0",
        "http://74.91.26.218:82/live/cctv12hd.m3u8", // 720p
    ),
    "CCTV13" to listOf(
        "http://1.24.39.180:9003/hls/13/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv13hd.m3u8?auth=testpub",
        "http://222.223.41.27:8888/hls/13/index.m3u8",
        "http://74.91.26.218:82/live/cctv13hd.m3u8", // 720p
    ),
    "CCTV14" to listOf(
        "http://1.24.39.180:9003/hls/14/index.m3u8",
        "http://222.223.41.27:8888/hls/14/index.m3u8",
        "http://74.91.26.218:82/live/cctv14hd.m3u8", // 720p
    ),
    "CCTV15" to listOf(
        "http://1.24.39.180:9003/hls/15/index.m3u8",
        "http://222.223.41.27:8888/hls/15/index.m3u8",
        "http://74.91.26.218:82/live/cctv15hd.m3u8", // 720p
    ),
    "CCTV16-HD" to listOf(
        "http://1.24.39.180:9003/hls/60/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv16hd.m3u8?auth=testpub",
        "http://183.129.255.66:8480/hls/17/index.m3u8",
        "http://112.27.5.218:9901/tsfile/live/faacts/1001_1.m3u8?key=txiptv&playlive=1&authid=0",
    ),
    "CCTV17" to listOf(
        "http://221.7.175.154:8445/tsfile/live/1009_1.m3u8?key=txiptv&playlive=1&authid=0",
        "http://218.104.239.114:9901/tsfile/live/0019_2.m3u8?key=txiptv&playlive=1&authid=0",
        "http://183.129.255.66:8480/hls/18/index.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/cctv17hd.m3u8?auth=testpub", // 720p
    ),
    "CCTV4K" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/cctv4k.m3u8?auth=testpub",
    ),
    "CCTV8K" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/cctv8k.m3u8?auth=testpub",
    ),

    // ---- 央视付费频道 (13 channels, all 1080p except 第一剧场/高尔夫网球) -----
    "CCTV风云剧场频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/fyjc.m3u8?auth=testpub",
    ),
    "CCTV第一剧场频道" to listOf(
        "http://204.12.221.218:8181/3m1080p/dyjc.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/dyjc.m3u8?auth=testpub", // 720p
    ),
    "CCTV怀旧剧场频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/hjjc.m3u8?auth=testpub",
    ),
    "CCTV世界地理频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/sjdl.m3u8?auth=testpub",
    ),
    "CCTV风云音乐频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/fyyy.m3u8?auth=testpub",
    ),
    "CCTV兵器科技频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/gfjs.m3u8?auth=testpub",
    ),
    "CCTV风云足球频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/fyzq.m3u8?auth=testpub",
    ),
    "CCTV高尔夫·网球频道" to listOf(
        "http://204.12.221.218:8181/3m1080p/gefwq.m3u8",
        "http://38.75.136.137:98/gslb/dsdqpub/gefwq.m3u8?auth=testpub", // 720p
    ),
    "CCTV女性时尚频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/nxss.m3u8?auth=testpub",
    ),
    "CCTV央视文化精品频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/ysjp.m3u8?auth=testpub",
    ),
    "CCTV央视台球频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/ystq.m3u8?auth=testpub",
    ),
    "CCTV电视指南频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/dszn.m3u8?auth=testpub",
    ),
    "CCTV卫生健康频道" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/wsjk.m3u8?auth=testpub",
    ),

    // ---- 地方卫视 ----------------------------------------------------------
    "东方卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/dfwshd.m3u8?auth=testpub"),
    "湖南卫视" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/hnwshd.m3u8?auth=testpub",
        "http://hlsal-ldvt.qing.mgtv.com/nn_live/nn_x64/dWlwPTEyNy4wLjAuMSZ1aWQ9cWluZy1jbXMmbm5fdGltZXpvbmU9OCZjZG5leF9pZD1hbF9obHNfbGR2dCZ1dWlkPTliODY4NmU5ZTM2YzYwMmMmZT02OTE0NjA0JnY9MSZpZD1ITldTWkdTVCZzPTcwN2RiYTc2YzJjNmJmMTQ4MmUyZGYzOWU2NWM3YWFi/HNWSZGST.m3u8", // HEVC 2160p
    ),
    "湖北卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/hbwshd.m3u8?auth=testpub"),
    "辽宁卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/lnwshd.m3u8?auth=testpub"),
    "江苏卫视" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/jswshd.m3u8?auth=testpub",
        "http://8.138.7.223/tv/jsws4k.php",
    ),
    "江西卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/jxwshd.m3u8?auth=testpub"),
    "山东卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/sdwshd.m3u8?auth=testpub"),
    "广东卫视" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/gdwshd.m3u8?auth=testpub",
        "https://h5cul1yar48um3t.wcetv.com/hls/gdsatellite.m3u8", // 720p
    ),
    "广西卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/gxwshd.m3u8?auth=testpub"),
    "重庆卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/cqwshd.m3u8?auth=testpub"),
    "河南卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/hawshd.m3u8?auth=testpub"),
    "海南卫视" to listOf("http://204.12.221.218:8181/3m1080p/lyws.m3u8"),
    "河北卫视" to listOf(
        "https://event.pull.hebtv.com/live/live101.m3u8", // H.264 2160p
    ),
    "贵州卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/gzwshd.m3u8?auth=testpub"),
    "北京卫视" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/bjwshd.m3u8?auth=testpub",
        "http://go.bkpcp.top/mg/bjws", // 720p
    ),
    "黑龙江卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/hljwshd.m3u8?auth=testpub"),
    "浙江卫视" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/zjwshd.m3u8?auth=testpub",
        "https://ali-xwl.cztv.com/live/channel4k2160p.m3u8", // HEVC 2160p
    ),
    "安徽卫视" to listOf(
        "http://38.75.136.137:98/gslb/dsdqpub/ahwshd.m3u8?auth=testpub",
        "https://restream.pdtvhd.com/memfs/c1cd2ed0-3b2b-4c25-af98-a7fb0b1eb130.m3u8",
    ),
    "深圳卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/szwshd.m3u8?auth=testpub"),
    "四川卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/scwshd.m3u8?auth=testpub"),
    "福建东南卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/dnwshd.m3u8?auth=testpub"),
    "天津卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/tjwshd.m3u8?auth=testpub"),
    "新疆卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/xjws.m3u8?auth=testpub"),
    "兵团卫视" to listOf("http://38.75.136.137:98/gslb/dsdqpub/btws.m3u8?auth=testpub"),
    "CETV1" to listOf("http://38.75.136.137:98/gslb/dsdqpub/cetv1hd.m3u8?auth=testpub"),

    // ---- 国际 --------------------------------------------------------------
    "CGTN" to listOf(
        "https://hlspackager.akamaized.net/live/DB/CGTN_ENGLISH/HLS/CGTN_ENGLISH.m3u8",
    ),
    "CGTN法语频道" to listOf(
        "https://dash4.antik.sk/live/test_cgtn_fra_tizen/playlist.m3u8", // 720p
    ),
    "CGTN俄语频道" to listOf(
        "https://dash4.antik.sk/live/test_cgtn_rus_tizen/playlist.m3u8", // 720p
    ),
    "CGTN阿拉伯语频道" to listOf(
        "https://hlspackager.akamaized.net/live/DB/CGTN_ARABIC/HLS/CGTN_ARABIC.m3u8",
        "https://dash3.antik.sk/live/test_cgtn_arb_tizen/playlist.m3u8",
    ),
    "CGTN西班牙语频道" to listOf(
        "https://dash4.antik.sk/live/test_cgtn_esp_tizen/playlist.m3u8", // 720p
        "https://dash3.antik.sk/live/test_cgtn_esp_tizen/playlist.m3u8", // 720p
    ),
    "CGTN外语纪录频道" to listOf(
        "https://hlspackager.akamaized.net/live/DB/CGTN_DOCUMENTARY/HLS/CGTN_DOCUMENTARY.m3u8",
    ),
)
