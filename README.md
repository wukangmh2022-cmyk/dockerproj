# WeChat Service Notification Forwarder (Android)

本项目提供一个运行在安卓手机本地的微信服务号通知转发器。应用通过 **辅助功能服务 + 屏幕录制授权** 获取界面信息，在本地执行 HSV 找色与模板比对后，触发模拟点击，从而把符合正则 / 颜色规则的服务号通知转发给指定联系人。整个流程无需 ADB、无需外部服务器，只要在手机上安装并授权即可 24/7 运行。

> ⚠️ 注意：示例代码仅用于学习/验证流程，请确保使用前阅读并遵守微信及所在地法律法规的相关条款。

## 功能概览

- **手机本地找色**：利用 `MediaProjection` 定期抓取屏幕帧，通过 `ColorAnalyzer` 对指定区域做 HSV 匹配，并可加载 `drawable` 下的模板占位资源（本仓库内含 `placeholder_*` 示例）。
- **辅助功能点击**：`WechatAutomationService` 继承 `AccessibilityService`，在识别到目标颜色后可延迟执行手势点击，实现“打开通知-选择联系人-确认转发”等自动化流程。
- **配置化策略**：`res/raw/default_profile.json` 中定义了转发联系人、允许的通知来源、找色区域、HSV 阈值以及对应的点击坐标，可自行复制修改；应用启动后点击 “Load automation profile” 即可加载。
- **可视化状态**：前台服务常驻通知栏，并在屏幕角落浮动提示当前状态（例如“Profile loaded” 或 “Detected confirm_button …”），方便观察运行情况。

## 工程结构

```
android-bot/
├── app/
│   ├── src/main/java/com/example/wechatbot/
│   │   ├── MainActivity.kt              # UI 入口，请求权限 & 触发配置加载
│   │   ├── SettingsActivity.kt          # 辅助功能设置入口
│   │   ├── WechatMonitoringService.kt   # 前台服务，负责截图、找色、调度动作
│   │   ├── automation/WechatAutomationService.kt  # 辅助功能服务，执行点击
│   │   ├── color/ColorAnalyzer.kt       # HSV 匹配与模板缓存
│   │   └── profile/*                    # 配置数据类与加载器
│   ├── src/main/res/drawable/placeholder_*.xml   # 找色模板占位资源
│   └── src/main/res/raw/default_profile.json     # 示例配置
├── build.gradle.kts
└── settings.gradle.kts
```

## 编译与部署

1. **导入工程**：使用 Android Studio Electric Eel 或更高版本，`File` → `Open...` 选择 `android-bot` 目录。
2. **同步依赖**：等待 Gradle 同步完成；项目默认使用 Kotlin + Material3，并依赖 `kotlinx-coroutines`、`gson` 等常见库。
3. **连接设备**：准备一台 Android 8.0 (API 26) 及以上手机，打开开发者选项中的 *安装未知应用* 权限，以便侧载 apk。
4. **构建安装**：在 Android Studio 中选择 `app` 模块，点击 *Run* 或执行 `gradle assembleDebug`（或在 IDE 中触发同名任务），将生成的 `app-debug.apk` 安装到手机。

## GitHub Actions 自动编译

仓库已经内置 `.github/workflows/android.yml`，会在 `main`/`master` 分支 push 以及任意 PR 时自动：

1. 检出仓库并安装 Temurin JDK 17。
2. 通过 `gradle/gradle-build-action` 下载 Gradle 8.5，定位到 `android-bot` 目录执行 `assembleDebug`。
3. 在 `Artifacts` 中上传调试版 APK（路径：`android-bot/app/build/outputs/apk/debug/app-debug.apk`），便于下载侧载。

如需在本地复现 CI，可使用 `gradle assembleDebug` 或在 Android Studio 的 Gradle 面板运行同名任务。

## 首次授权步骤

1. 打开应用后点击 **Grant permissions**：
   - 授权通知权限（Android 13+）。
   - 自动跳转到系统的无障碍设置页面，启用 “WeChat Automation”。
   - 弹出系统录屏授权对话框，勾选“不要再次提示”，允许截屏。
   - 首次启动会提示悬浮窗权限，需手动允许，以显示状态浮窗。
2. 返回应用点击 **Load automation profile**，加载 `default_profile.json` 中的示例配置。
3. 确保微信已登录目标小号，并打开通知的聊天窗口。此后服务会在后台持续运行，状态可在通知栏及浮窗查看。

## 自定义配置

复制 `app/src/main/res/raw/default_profile.json`，修改以下字段即可适配不同设备与流程：

- `forwardContact`：需要转发到的联系人名称，后续可在辅助功能逻辑中使用。
- `allowedSenders`：可选列表，用于二次校验通知来源。
- `targets`：找色规则数组，每项含义如下：
  - `sampleRegion`：取色区域的左上角坐标与宽高（单位：像素，基于屏幕原始分辨率）。
  - `hsvRange`：HSV 的上下限，建议通过手机截图 + 图像工具（如 Photoshop）取色后填写。
  - `templateAsset`：指向 `res/drawable` 中的占位资源，可用来快速可视化目标颜色。
  - `tapAction`：命中后要执行的点击位置（像素坐标）与延迟毫秒数，利用辅助功能手势完成。
- `heartbeatSeconds`：截图与分析的间隔时间，默认 90 秒。

自定义完成后将文件放入 `res/raw` 并更新 `MainActivity` 中的 `R.raw.default_profile` 引用即可。

## 运行时建议

- 为防止系统在后台回收服务，可在电池管理中把应用加入白名单，并保持手机接通电源与稳定网络。
- 建议定期验证 HSV 匹配效果，因为微信 UI 版本更新可能导致配色变化。
- 如果需要更复杂的动作（例如输入文本、滚动选择联系人等），可在 `WechatMonitoringService.handleMatches` 中扩展逻辑，例如结合节点查找、正则过滤等手段。

## 升级方向

- 接入 OCR 或富文本解析，实现更精确的通知过滤。
- 引入 WorkManager 定时唤醒与自恢复逻辑，增强长时间稳定性。
- 将配置改为远程下发或本地可视化编辑，方便非技术人员调整规则。

## 免责声明

自动化脚本具有潜在风险，请确保已获得账号所有者授权，并符合微信及当地法律法规要求。因使用此项目造成的任何损失由使用者自行承担。
