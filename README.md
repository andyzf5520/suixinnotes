# 随心记事本 · suixinnotes

**版本 1.0.0 · 作者 andy**

轻量手机笔记、账号密码与独立加密分类。Android 原生 Kotlin/Compose；iOS 原生 SwiftUI。离线使用，无需注册。

## 功能

- 文本笔记、账号卡片、待办、图片、自定义分类与独立分类密码。
- 单行标题列表或卡片、标题/修改时间排序；无标题取正文首行。
- 顶部撤销/重做、字体字号样式；返回时自动保存。
- HTML/TXT/CSV 导出，密码默认隐藏；完整加密备份与恢复。
- 应用和设置页标注版本与作者 `andy`。

## 安装与版本状态

在 [GitHub Releases](https://github.com/andyzf5520/suixinnotes/releases) 下载 Android APK。Android 最低 8.0；安装包为调试签名开发版，核心自动测试通过，尚未完成真机验收和独立安全评审。

iOS 最低 16.0；源码位于 `ios/`。GitHub Actions 使用 macOS/Xcode 构建和测试，模拟器包属于 Mac 的 iOS 模拟器应用，**不能直接安装到 iPhone**。iPhone 真机版本需要 Apple 开发签名与配置文件，本仓库不包含签名私钥。

Android 默认导出/备份位置 `Download/Notes`。iOS 使用系统文件选择器，建议选择“下载/Notes”；iOS 没有共享的 Android Download 路径。

## 构建 Android

Java 17、Android SDK 35；本机配置 `local.properties` 中的 `sdk.dir`（不要提交）。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Windows 可用 `gradlew.bat`；当前工作区 `build.ps1` 可使用相邻工具目录的 Gradle。APK 输出 `app/build/outputs/apk/debug/app-debug.apk`，正式资产只附到 Release，不提交 Git。

## 构建 iOS

```sh
brew install xcodegen
cd ios
xcodegen generate
open ShijianNotes.xcodeproj
```

选择 scheme `ShijianNotes`，在 iPhone 模拟器运行。使用真机前设置自己的 Development Team，Apple 账号和证书由本人管理。`ios/project.yml` 是可复现的项目定义。

## 加密与兼容

AES-256-GCM + PBKDF2-HMAC-SHA256 600,000 轮，主库与私密分类分层认证加密。两端沿用 MOBX v1 格式及模型字段，保留旧版 Android 备份兼容；跨平台备份应先用虚构数据演练。主密码不能解锁独立分类。忘记密码没有云端找回，旧备份使用备份时的旧口令。

图片转 JPEG 缩图，每条最多 6 张，整库上限 48MB。格式作用于整篇正文。没有自动定时备份、回收站、同步、指纹解锁、贴纸和模板包。iOS 初版与 Android 尚有差异，见 [平台说明](ios/README.md)。

## 文档和测试

[设计方案](design/设计与开发测试方案.md) · [原型](design/prototype.html) · [测试报告](design/开发测试报告.md)

Android 32 项 JVM 自动测试；iOS XCTest 验证加密、导出与模型。CI 的实际结果以 Actions 为准，不把源码交付当成真机验收。

作者：**andy**。尚未指定开源许可，公开仓库不代表自动授予再分发许可。依赖组件分别遵循其自身许可证。
