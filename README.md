# 云听 CloudMusic

云听是一个独立开发的 Android 音乐播放器。在线曲目资料来自网易云音乐。云听与网易无隶属关系，在线播放以服务端实际开放的音源为准。

## 安装

在 [GitHub Releases](https://github.com/4c304af/cloudmusic/releases) 下载 APK。Android 包名为 `io.github.cloudmusic.expressive`，最低支持 Android 8.0。

## 构建

使用 JDK 17 和 Android SDK Platform 37，在项目根目录执行 `gradlew.bat :app:assembleRelease`（Windows）或 `./gradlew :app:assembleRelease`。APK 输出在 `app/build/outputs/apk/release/`。

音频指纹组件的来源见 [NOTICE.txt](app/src/main/assets/audio-fingerprint/NOTICE.txt)。
