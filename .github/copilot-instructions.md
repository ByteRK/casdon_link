# Casdon Link 项目约定

面向售后维护的 Android 应用（Kotlin + XML 布局，单 `app` 模块）。改动前请遵守以下硬性约定。

## 版本号：每次改动必须递增

- 版本号为三段式 `MAJOR.MINOR.PATCH`，定义在 `app/build.gradle.kts` 顶部的 `appMajor`/`appMinor`/`appPatch`；`versionName` 与 `versionCode` 均由它们派生，**不要**直接写 `versionName` 字符串。
- 每次改动都必须递增其中一位，不允许「代码变了、版本号没变」：
  - `PATCH`：缺陷修复、样式/文案调整、纯重构、文档或构建脚本调整（行为不变）。
  - `MINOR`：新增功能或新交互，且保持向后兼容（新页面、新能力、列表/弹窗行为变化）。
  - `MAJOR`：不兼容变更（修复包清单格式、已保存数据结构/加密方式、需用户重新配置、整体重写）。
- 一次提交只递增一次，按改动中的最高级别算；递增某位时右侧低位归零（`1.1.3` 升 MINOR → `1.2.0`）。
- 详细规则见 `docs/TECHNICAL_DESIGN.md` 第 11 节。

## 界面样式

- 颜色、间距、字号只引用 `res/values/colors.xml`、`dimens.xml` 的令牌；控件外观走 `styles.xml`（`Text.CasdonLink.*` / `Widget.CasdonLink.*`）。布局里不写裸字号、颜色、圆角、内边距。
- 列表行放独立 `row_*.xml`，Kotlin 只绑数据与事件，不在代码里拼样式。
- 列表统一用 `RecyclerView` + `ListAdapter`/`DiffUtil`，整页列表调 `ui/ListSetup.kt` 的 `configureAsList()` 加分隔线。
- 弹窗统一 `MaterialAlertDialogBuilder`（主题 `ThemeOverlay.CasdonLink.MaterialAlertDialog`），禁止 `android.app.AlertDialog`；悬浮菜单用 `ThemeOverlay.CasdonLink.PopupMenu`，禁止默认 `PopupMenu` 外观。
- 每个 Activity 启动时调用 `ui/SystemBars.kt` 的 `applyDarkSystemBars()`，不要直接调 `enableEdgeToEdge()`。

## 构建与验证

```powershell
$env:JAVA_HOME="E:\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

- APK 产物：`app/build/outputs/apk/debug/CasdonLink_debug_V<版本名>_<时间戳>.apk`。
- 真机：`E:\Android\Sdk\platform-tools\adb.exe`（不在 PATH），测试机 `192.168.137.122:43393`，维护目标板 `192.168.137.118:23`。
