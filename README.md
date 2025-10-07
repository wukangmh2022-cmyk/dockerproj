# WeChat Service Notification Forwarder (Android)

本项目提供一个 **纯手机端、无需 ADB/服务器** 的微信小号自动转发机器人。应用通过辅助功能与 `MediaProjection` 截屏，在本地运行 OCR 识别并执行多场景点击脚本，将服务号通知复制后转发给指定联系人，支持 24×7 稳定运行。

> ⚠️ 示例工程仅用于学习与流程验证，请确保遵守微信协议及当地法律法规。

## 功能亮点

- **关键词优先识别，模板可选补充**：默认脚本仅依赖 Google ML Kit OCR，根据命中关键词达到 ≥75% 的比例来判断场景；如需模板匹配，可在独立的模板调试脚本或自定义脚本中引用 PNG/JPG 资源，无需维护 HSV 区间。
- **多场景脚本（10+ 步点击逻辑）**：默认配置覆盖主聊天列表、服务号会话、联系人会话三大界面，完整包含“进入服务号 → 复制通知 → 返回 → 进入联系人 → 粘贴 → 发送 → 返回”等 12 个动作，满足真实转发流程需求。
- **随机偏移防检测**：所有点击与长按均自带轻微随机偏移及可配置时序，模拟人工操作，降低被判定为脚本的风险。
- **可选模板图库**：应用会在 `Android/data/<package>/files/ocr_gallery/` 下维护“相册”，支持从系统相册导入 PNG/JPG/WebP 并在 JSON 中直接引用，便于针对特定按钮或图标单独调试。
- **配置化工作流**：`res/raw/default_profile.json` 使用“识别配置 + 动作列表”的轻量结构，默认全屏检测，坐标与节奏均可配置；应用内即可切换、编辑脚本。
- **调试脚本内置**：`res/raw/debug_profile.json` 用于关键词探测验证；`res/raw/image_probe_profile.json` 用于图片模板命中调试，两者命中时都会弹出带归一化坐标的悬浮提示（无动作）。
- **运行状态可视化**：前台服务持续显示通知，屏幕上方悬浮窗实时提示当前执行的场景与错误，并提供“开始/暂停”按钮便于随时控制。

## 工程结构

```
android-bot/
├── app/
│   ├── src/main/java/com/example/wechatbot/
│   │   ├── MainActivity.kt               # 首页：脚本列表、权限检测、开始/暂停
│   │   ├── ProfileEditorActivity.kt      # JSON 编辑器，可新建/修改脚本
│   │   ├── GalleryActivity.kt            # 模板图库，支持从系统相册导入
│   │   ├── WechatMonitoringService.kt    # 前台服务：截图、OCR、调度场景
│   │   ├── automation/
│   │   │   ├── AutomationOrchestrator.kt # 多场景动作编排、剪贴板管理
│   │   │   └── WechatAutomationService.kt# 辅助功能服务，执行带随机偏移的点击/长按/返回
│   │   ├── ocr/ImageAnalyzer.kt          # 模板匹配 + 坐标转换
│   │   ├── ocr/TemplateRepository.kt     # “相册”导入与缓存
│   │   ├── ocr/TextAnalyzer.kt           # ML Kit OCR 解析
│   │   └── profile/*                     # 配置数据类与加载器
│   ├── src/main/res/raw/default_profile.json   # 默认关键词转发工作流
│   ├── src/main/res/raw/debug_profile.json     # 关键词调试脚本（仅提示，不执行动作）
│   └── src/main/res/raw/image_probe_profile.json # 模板调试脚本（仅提示，不执行动作）
├── .github/workflows/android.yml         # GitHub Actions 自动构建
├── build.gradle.kts / settings.gradle.kts
└── README.md
```

## GitHub Actions 自动编译

仓库内置 `Android CI` workflow，提交到主分支或任意 PR 时会自动：

1. 安装 Temurin JDK 17 与 Gradle 8.5。
2. 同步 Android 依赖并执行 `./gradlew -p android-bot assembleDebug`。
3. 产出 `app-debug.apk` 并作为构建工件上传，便于直接下载侧载。

如需本地验证，可运行：

```bash
./gradlew -p android-bot assembleDebug
```

> 当前环境无法访问外网下载 Android 依赖，因此在仓库内未执行实际构建。

## 首次使用步骤

1. **安装与启动**：将 CI 生成或本地构建的 APK 安装到 Android 8.0+ 手机，启动应用。
2. **授权**：点击 `授权必要权限` 按钮，依次完成：
   - 通知权限（Android 13+）。
   - 无障碍服务授权，启用 “WeChat Automation”。
   - 录屏权限（勾选“允许记住选择”）。
   - 悬浮窗权限，用于显示状态提示。
3. **选择脚本并启动**：在首页脚本列表中选中“默认脚本”“调试脚本”或自定义脚本，点击 `开始` 下发给前台服务。悬浮窗会提示已加载场景与当前状态。
4. **保持微信在前台**：确保微信已登录目标小号，停留在主界面或最近的聊天列表即可运行；若需暂停，随时点击应用内或悬浮窗上的 `暂停` 按钮。

## 识别工作流说明

`default_profile.json` 采用如下结构：

```json
{
  "name": "默认关键词转发",
  "heartbeatSeconds": 45,
  "scenes": [
    {
      "id": "main_open_service",
      "title": "主界面-打开服务号会话",
      "keywords": ["服务号"],
      "minMatchRatio": 0.75,
      "actions": [ { "type": "TAP", ... }, { "type": "WAIT", ... } ],
      "nextSceneId": "service_copy_message"
    },
    ...
  ]
}
```

- **keywords**：OCR 命中所需关键字列表，满足 `minMatchRatio` 即判定命中；默认全屏检测，可留空表示此场景仅依赖图片模板。
- **imageTargets**：可选模板列表，`name` 对应 `ocr_gallery` 中的文件名，`threshold` 为相似度阈值（0~1，建议 ≥0.65）。当 `keywords` 与 `imageTargets` 同时存在时，以关键词识别结果为准，模板命中仅用于辅助生成点击偏移范围。
- **actions**：
  - `TAP` / `LONG_PRESS`：带有 `x`、`y`（像素坐标）、`durationMs`（长按时长）与 `delayMs`（动作后的延迟）。
  - `WAIT`：纯延迟，单位毫秒。
  - `COPY_REGION_TO_CLIPBOARD`：将指定区域内 OCR 文本写入剪贴板，保证后续粘贴稳定。
  - `PASTE_CLIPBOARD`：点击“粘贴”菜单项或图标，可与 `LONG_PRESS` 组合实现长按输入框 → 选择粘贴。
  - `GLOBAL_BACK`：调用系统返回，配合微信原生返回键或手势。
- **nextSceneId**：可选字段，在执行完成后优先解锁下一场景的冷却，形成完整链路。
- **cooldownMs**：场景冷却时间，避免重复触发。
- **debugOnly**：调试脚本专用，命中后只弹出归一化坐标提示，不执行任何动作。

默认工作流共 4 个场景、18 个动作，覆盖：

1. 主聊天界面：锁定服务号头像并进入会话。
2. 服务号会话：长按最新通知、复制文本、点击菜单“复制”、返回列表。
3. 主聊天界面：进入目标联系人会话。
4. 联系人会话：点击输入框、长按弹出菜单、粘贴、点击发送、返回。

## 导入自定义图片 & 调参与调试

- **应用内导入**：点击首页右上角的图库图标进入“模板图库”，再点右上角 `添加图片` 从系统相册选择素材，应用会复制到 `Android/data/<package>/files/ocr_gallery/` 并提示文件名，可直接在 JSON `imageTargets` 中引用。
- **屏幕坐标测量**：可用系统录屏 + 截图标注工具测量关键按钮位置；亦可在 `default_profile.json` 中根据分辨率缩放。
- **OCR 关键词调优**：微信 UI 更新或多语言环境下可修改 `keywords`；默认整屏检测，如需限定范围可在场景或动作中额外指定 `region`。
- **图片模板调优**：建议对同一元素准备 2~3 张不同状态截图（未读、已读等），分别放入 `imageTargets` 以提升稳定性。
- **模板调试脚本**：选择“模板调试脚本”即可快速验证导入的 PNG/JPG 是否能命中，命中后悬浮提示会显示归一化坐标，便于微调阈值。
- **脚本节奏调整**：通过 `delayMs` 与 `cooldownMs` 控制节奏，确保页面完成跳转后再执行下一步。
- **日志与状态**：悬浮窗会显示“执行场景: xxx”，调试脚本额外弹出淡出提示，可验证识别区域是否正确。

## 脚本管理与编辑

- 首页展示脚本列表，可快速切换“默认脚本 / 调试脚本 / 模板调试脚本 / 自定义脚本”，点击 `开始` 即可运行，`暂停` 则立即停止识别。
- 支持新建、编辑、删除脚本：点击 `新建脚本` 进入编辑器，可直接修改 JSON 并 `保存`；对自定义脚本点击 `编辑` 可再次调整，`删除` 会移除本地文件。
- 所有点击与长按动作都会按照检测区域自动加入约 10% 的随机偏移，无需手动配置偏移参数。

## 识别分辨率与性能

- 截屏默认以整屏分辨率采集，`TextAnalyzer` 会在短边超过 1080 像素时自动缩放后再 OCR，并将坐标映射回原始尺寸，因此素材分辨率无须与设备完全一致。
- 图片模板匹配同样会按需缩放屏幕与模板，并尝试不同缩放比例（0.75~1.25 倍）来适配不同 DPI，找到最佳匹配得分。
- 若设备性能有限，可提升 `heartbeatSeconds` 或减少场景数量，以降低识别频率；调试脚本也可用于快速观察识别是否稳定。

## 稳定性建议

- 将应用添加到系统电池优化白名单，避免后台被杀。
- 建议保持手机充电、锁定微信在前台或多任务后台，减少被系统回收的概率。
- 定期人工抽检 OCR 精度，必要时更新关键词或坐标。

## 免责声明

使用自动化脚本存在封号与法律风险，请务必取得账号所有者授权并遵守相关条款。项目作者不承担因使用该项目造成的任何直接或间接损失。
