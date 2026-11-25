# GQTV

面向 Android 电视和投影的直播应用，基于 [lizongying/my-tv](https://github.com/lizongying/my-tv) 改造。

[下载安装包](https://github.com/ZhengYitong2333/GQTV/releases) · [上游项目](https://github.com/lizongying/my-tv)

## 功能

- 遥控器浏览频道、分组跳转、数字定位，确认后切台。播放中轻按上下键立即换台，长按滚动台号，松开后播放目标频道。
- 67 个内置频道，其中 64 个频道提供共 133 条备用线路。
- 按设备、频道保存近期线路表现，下次优先使用持续播放稳定的线路，失败后自动切换。旧版线路记忆自动作为初始选线提示。
- 支持选择源内可用的分辨率，显示当前备用线路序号。
- 半透明侧边设置面板、时间显示和网格频道列表。
- 从旧版 51 台目录迁移频道位置，之后按频道 PID 保存。

频道源会随时间和网络环境变化，画质与开播速度取决于实际线路。标称 4K、8K 的频道也可能使用 1080p 转播源。

## 界面

![频道浏览](screenshots/xiaop-channels.png)

![播放分辨率](screenshots/xiaop-resolution.png)

截图使用本地测试素材，展示频道浏览与设置界面。

## 安装

在 Releases 下载 GQTV.apk，通过 U 盘、设备安装工具或 ADB 安装。

```sh
adb install -r GQTV.apk
```

当前发布包为个人预览构建，应用名为“GQTV”，包名为 com.lizongying.mytv.preview。
可覆盖本项目此前同签名的预览版本，保留设置和频道记录。此包使用调试签名。
内置检查更新目前仅显示上游版本信息，后续版本请从本仓库 Releases 下载。

此前的预览版已在 TCL 65V8M Pro 与极米 Z7X 验证启动。部分 TCL 固件需要通过“多屏互动”安装。

## 构建与验证

使用 JDK 17、Android SDK 34 和本仓库 Gradle Wrapper。

```sh
./gradlew :app:assembleDebug -PIS_SO_BUILD=false
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

调试包输出在 app/build/outputs/apk/debug/app-debug.apk。
项目随附原生库预编译文件。设备测试使用本地测试视频与 HTTP HLS 服务，覆盖遥控器焦点、选台、分辨率、线路重试、线路记忆和频道迁移。
JVM 测试覆盖数字选台、换台计时、播放健康和线路排序，Android TV 测试覆盖播放器与遥控器的正常入口。

### 换台诊断

用 `adb logcat -s TuneTiming Request` 查看换台事件。一次确认换台对应一个编号，地址就绪、首个媒体分片的加载、首帧、超时和播放错误均带有频道与尝试序号。首帧后的缓冲观察窗为 30 秒，提前换走会输出实际观察时长。

`TuneBenchmark` 是可选的联网设备测试。安装调试 APK 与 androidTest APK 后，在空闲测试设备上执行以下命令。每个组合约 3 分钟，会切换频道，完成后恢复原选台和相应的线路记忆。将 round 从 0 跑到 5，每轮分别跑 old 与 new，奇数轮先跑 new。

```sh
adb -s SERIAL shell am instrument -w -e class com.lizongying.mytv.TuneBenchmark -e policy new -e round 0 com.lizongying.mytv.preview.test/androidx.test.runner.AndroidJUnitRunner
```

### 稳定性选源

每条线路保留最近 7 天内最多 8 次播放观察，其中最多保留两次短暂扫台，避免挤掉稳定与故障记录。播放中每 30 秒保存，切台、暂停、退出和失败时立即保存。确认出画后观察至少 30 秒、按实际渲染帧推进累计至少 20 秒（不使用会随 HLS 直播窗口滑动而回退的相对播放位置）、播放与缓冲能覆盖观察时长（容许 1.5 秒采样误差）、没有错误/超时/单次 3 秒缓冲且累计缓冲不超过 3 秒，才会产生稳定记录。短暂扫台保留已经发生的故障与缓冲。

首次失败降级 10 秒，连续失败逐步延长到 1 分钟、10 分钟。已声明音轨却缺少可用音频解码器的线路降级 10 分钟。已有稳定记录的线路优先；稳定性相近时比较首帧耗时（500 毫秒档位）和实际分辨率。降级期结束后允许按目录顺序重试，成功的后续观察可以恢复优先级。选线只在开始播放或故障恢复时执行。

解码首帧到达后，等待播放器就绪再揭开画面遮挡。日志 `decoded_frame` 记录解码时刻，`first_frame` 记录实际显示时刻，首帧后的缓冲从实际显示开始计数。

AVC 解析或解码失败时，在现有的一次线路重试内尝试连续 HLS 分帧兼容处理，并按设备能力选择可用的软件 AVC 解码器。成功出画后可以复用该线路的解码配置，流畅资格仍需要完整观察。声明的音轨无法解码或直播备用流提前结束时，自动尝试下一条线路。

`StableRouteBenchmark` 对照旧版 20 秒线路记忆和新版质量记录。每个频道、每种策略测 5 次，首次清空历史，其后 4 次保留学习记录，出画后观察 30 秒。测试结束恢复原设备记录。先对 1、2、4、5、8、9 执行 compare（逐次交替 old/new，两组独立保留历史），再执行 long；long 使用该频道新版对照中学到的记录，连续观察 5 分钟。

```sh
adb -s SERIAL shell am instrument -w -e class com.lizongying.mytv.StableRouteBenchmark -e policy compare -e channel 1 com.lizongying.mytv.preview.test/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL shell am instrument -w -e class com.lizongying.mytv.StableRouteBenchmark -e policy long -e channel 1 com.lizongying.mytv.preview.test/androidx.test.runner.AndroidJUnitRunner
```

日志 `QualityBench` 标记样本，`TuneTiming` 给出首帧和 30 秒缓冲数据，`RouteRecord` 给出按线路保存的观察，日志只标记线路序号。

`LinePlaybackProbe` 可在模拟器空闲时独立探测一个频道的一条线路，记录解码与播放就绪时刻、音频格式、缓冲、渲染帧数和丢帧。`compat` 启用连续 AVC 分帧，`software` 选择系统软件 AVC 解码器，探测结束恢复频道记录。加入 `-e progress true` 可记录相对播放位置和呈现帧数，用于区分直播窗口滑动与视频停帧。

```sh
adb -s SERIAL shell am instrument -w -e class com.lizongying.mytv.LinePlaybackProbe -e channel 4 -e line 1 -e compat true -e software true -e seconds 35 com.lizongying.mytv.preview.test/androidx.test.runner.AndroidJUnitRunner
```

## 上游与版权

本仓库保留原项目提交历史。原有代码、素材及版权归原作者所有，新增改动见提交记录。

以下保留上游版权说明。

本项目仅供学习研究，禁止用于商业用途，请于下载二十四小时内删除。

本项目可能随时终止，请大家谨慎使用，建议使用官方渠道进行观看。

本项目使用的部分代码、图片、文字等资源来源于网络，如有侵权，请联系删除。
