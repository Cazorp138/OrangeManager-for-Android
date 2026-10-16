# 橘manager

一个用 Kotlin + Jetpack Compose 写的个人生活管理 App：把首页时间线、待办、事件、记事、专注、购物、运动放在同一个底栏里，所有功能都可以自由开关和排序。

> 单 Activity、无网络、无后端，数据全部存在本机。没有第三方 UI 库，界面全部是 Compose 原生组件。

## 功能

**首页** —— 全屏底图 + 未来 24 小时时间线，把待办和事件按时间混排在一起：

- 每条显示状态：`还未开始` / `正在进行` / `已结束`
- 待办显示起止时间（`09:00` + 下方 `10:30`）
- 事件一旦开始就不再显示状态文字，并整行变淡
- 已结束的条目左滑露出红色删除按钮

**待办** —— 名称、地点、重要等级（必做 / 一般 / 无所谓）、起止时间、可选提醒。

**事件** —— 支持两种：

- **普通事件**：单次，指定日期时间
- **每周事件**：勾选星期（一~日）后每周重复，每个命中的星期都会照常通知

**记事** —— 参考小米备忘录：两列网格卡片（标题 + 摘要），点卡片进全屏编辑页，返回时自动落盘。标题可以不填，列表里显示「未命名」。

**专注** —— 滚动选时长（5 分钟一档，最长 300 分钟），倒计时全屏显示。可在设置里关掉「允许提前结束」，关掉后点屏幕会蹦一个 ㊗️ 作为反馈。

**购物 / 运动** —— 清单勾选、运动计划（项目 + 目标 + 时段）。

**主题** —— 全局白天 / 黑夜模式。黑夜模式只反黑白两色，橙 / 绿 / 红 / 黄这些语义色保持不变。

**底栏自定义** —— 设置里勾选要显示哪些页面，不限个数，顺序固定。

## 提醒机制（这块踩过的坑最多）

| 做法 | 原因 |
|---|---|
| `AlarmManager.setAlarmClock` | 早期的 `setExactAndAllowWhileIdle` 属于 while-idle 类闹钟，Doze 下官方限制「每应用每 9 分钟最多放行一次」。一条待办同时排了「提前 N 分钟」和「开始时刻」两个闹钟时，后者常被压住 —— 表现就是「提前的通知收到了，开始那条没响」。`setAlarmClock` 不受这个限制（代价是状态栏会出现闹钟图标）。 |
| 同时声明 `USE_EXACT_ALARM` 和 `SCHEDULE_EXACT_ALARM` | Android 13+ 用前者（普通权限，装完即生效）；Android 12 只能靠后者（需用户手动授权）。只声明后者的话默认拿不到，闹钟会被降级成非精确、晚几分钟。 |
| 每周事件用**链式重排** | 每周事件只排一次的话响过就再也不响了。所以「已经开始」那条闹钟响完之后，接收器会算出下一次发生时刻并重新排上，如此循环。只有这一条带重排标记，否则两条通知各自往下链会指数增长。 |
| 重排以**这一响所属的发生时刻**为基准，而不是 `System.currentTimeMillis()` | 闹钟可能早几十毫秒触发，用当前时刻当基准会把刚响完的今天这次又算成「下一次」，重复排一遍并多发一条通知。 |
| `setFullScreenIntent` + `setTurnScreenOn` | 普通横幅**不会**唤醒屏幕。锁屏时要亮屏只能靠全屏通知。 |

三条通知：`即将开始`（开始前 N 分钟）、`已经开始`（开始时刻）、`已结束`（结束时刻，仅待办有）。

## 技术选型说明

- **持久化用 SharedPreferences + `org.json`**，没有引入 Room / DataStore / kotlinx.serialization。这个 App 的数据量很小（几个列表），一次序列化成单个 JSON 就够了，而且能避免额外的依赖和版本冲突。
- **滚轮用系统 `NumberPicker`（通过 `AndroidView`）**，而不是自己拿 `LazyColumn` 搭 —— 吸附、惯性、边界回弹这些行为由平台保证，不用自己调对齐误差。
- **`NumberPicker` 的文字颜色取自应用主题且改不动**，所以我们用了 Light 主题，滚轮底色恒为浅色（`LocalLightField` 这个作用域开关就是干这个的）。
- **Compose BOM 钉在 `2024.12.01`**：再新的版本里 `material-icons-extended` 被移除了。

## 构建

需要的环境：

- JDK **17–24**（Gradle 8.x 不支持 JDK 25）
- Android SDK：`compileSdk 35`，`minSdk 26`
- Android Studio 或命令行均可

```bash
# 1) 告诉 Gradle SDK 在哪（这个文件不会被提交）
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2) 构建
./gradlew assembleDebug          # Linux / macOS
gradlew.bat assembleDebug        # Windows

# 3) 装到手机
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**如果同步时报 JDK 版本错误**：你的 Android Studio 自带的 JBR 可能是 25。装一个 JDK 21，然后在**用户级**配置里指定（不要改仓库里的 `gradle.properties`，写绝对路径会让别人构建不了）：

```
~/.gradle/gradle.properties     ->   org.gradle.java.home=/path/to/jdk-21
```

或直接在 Android Studio：`Settings → Build Tools → Gradle → Gradle JDK` 选 21。

## 权限

| 权限 | 用途 |
|---|---|
| `POST_NOTIFICATIONS` | 发提醒（Android 13+ 需运行时授权，首次启动会弹） |
| `VIBRATE` | 通知震动。**漏了这条 `vibrate()` 会抛 `SecurityException` 且被 catch 吞掉，表现就是「有横幅但永远不震」** |
| `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` | 精确闹钟 |
| `RECEIVE_BOOT_COMPLETED` | 重启后重排闹钟 |
| `USE_FULL_SCREEN_INTENT` | 锁屏亮屏。Android 14+ 对非闹钟类应用默认不授予，需手动开启 |

## 已知限制

- **没有自动化测试**。核心流程（新增/编辑/删除/通知/主题切换/每周事件）在真机上手动验证过，但没有单元测试或 UI 测试，改动后需要人工回归。
- **国产 ROM 的省电策略**会冻结后台、延迟甚至吞掉闹钟。需要在系统里允许自启动和后台运行 —— 这一层应用代码解决不了。
- **通知渠道的重要性一旦创建就改不了**（Android 的限制），应用只能改名字。
- 数据只在本机 SharedPreferences 里，**没有加密、没有导出/导入、没有云同步**。卸载即丢失。
- 主界面文件仍然偏大（`MainActivity.kt` 约 1200 行），页面拆分重构只完成了第一、二步（`SharedUi.kt` / `HomeScreen.kt`）。

## 许可证

[MIT](LICENSE) —— 可以自由使用、修改、商用，保留版权声明即可。
