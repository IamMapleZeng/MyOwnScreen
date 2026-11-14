# MyOwnScreen

MyOwnScreen 是面向 Xiaomi HyperOS 的 LSPosed 模块，使用现代 libxposed API 102。系统范围的 Hook 适用于各 App 的窗口，无需将目标 App 加入作用域。模块处理 Android 安全窗口、设备管理截屏策略、HyperOS 截屏检查、录屏虚拟显示，以及 Android 的截屏和录屏回调。

## 安装与验证

公开版包名为 `io.github.iammaplezeng.myownscreen`。早期本地测试版使用 `com.myownscreen`。若两版同时存在，请仅在 LSPosed 中启用需要测试的版本。

1. 安装 MyOwnScreen APK。在 LSPosed 管理器中启用 MyOwnScreen。
2. 勾选推荐作用域中的「系统框架」（System Framework）、「系统界面」、小米「截屏」和「屏幕录制」，然后重启。若系统已有处理截屏限制的模块，验证时请先停用旧模块，以便判断实际生效来源。
3. 打开桌面的 MyOwnScreen。这个测试页主动启用了 `FLAG_SECURE`。用系统快捷键截图，再用控制中心的「屏幕录制」录制数秒。分别检查截图和视频是否包含测试文字，视频中的计数应随时间变化。
4. 回到原先禁止截屏或录屏的 App 重测。锁屏和安全支付等特殊页面请分别验证。

已核对 HyperOS 4.0.3.0.XOACNXM（Android 17）上的主要 Hook 签名，并在该设备上通过 ADB 安装和启动测试页。启用后的截屏和录屏仍待验证。硬件 DRM、安全解码图层和系统之外的内容保护仍可能显示黑屏，取决于设备的显示与解码实现。

## 构建

使用 JDK 21、Android SDK Platform 37.1 和 Build Tools 36.1.0。设置 `ANDROID_HOME` 或由 Android Studio 写入 `local.properties`，然后运行：

```powershell
.\gradlew.bat :app:assembleDebug
```

输出在 `app/build/outputs/apk/debug/`。随附 APK 使用本地签名，Android 要求覆盖安装时使用一致的签名。模块日志标签为 `MyOwnScreen`。需要回退时，在 LSPosed 中停用模块并重启手机。

## 来源和许可

本项目修改自 [LSPosed/DisableFlagSecure](https://github.com/LSPosed/DisableFlagSecure/tree/7cf0f1fd826dad1e419cc02a2ef94c4eba591be1)，采用 GPL-3.0。参见 LICENSE。
