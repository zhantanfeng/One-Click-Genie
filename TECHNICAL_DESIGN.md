# Android 屏幕手势录制与回放 App 技术设计

## 1. 文档信息

- 文档状态：初版技术方案
- 目标平台：Android 真机
- MVP 最低系统：Android 13（API 33）
- 开发语言：Kotlin
- UI 框架：Jetpack Compose
- 核心能力：记录用户跨 App 的屏幕触摸轨迹，并在之后一键回放

## 2. 项目目标

本项目用于将一串固定、重复且繁琐的手机屏幕操作录制为一条操作记录。用户完成一次录制后，可以从主界面一键执行，应用按照原始触摸坐标、轨迹和时间间隔重放操作。

典型流程：

1. 用户新建一条手势记录并输入名称。
2. 用户开始录制并切换到目标 App。
3. 用户完成点击、长按、滑动或多指操作。
4. 连续 5 秒没有触摸操作时，应用自动结束录制。
5. 用户预览录制结果并保存。
6. 用户以后点击该记录的执行按钮，应用按原轨迹回放。

## 3. 已确认的产品约束

### 3.1 功能范围

- 只记录和回放屏幕触摸操作。
- 点击保存屏幕位置；滑动保存方向（左划、右划、上划、下划），不要求保存起止坐标。
- 不把 Home、返回、最近任务、通知栏等操作转换为专用系统动作。
- 如果系统边缘手势能够作为触摸轨迹采集，则仍按普通轨迹保存和尝试回放。
- 不分析用户执行该操作的业务意图。
- 不根据控件 ID、文字或页面结构生成语义步骤。
- 不针对壁纸或其他具体业务提供专用系统 API。

### 3.2 使用环境假设

- 默认目标手机已完成无障碍服务等必要配置。
- 产品主流程不提供完整权限教学和引导。
- 默认在固定手机、固定分辨率、固定屏幕方向和相对稳定的目标 App 界面上使用。
- 首版以个人安装和真机验证为主要分发方式。

### 3.3 暂不处理

- 名称重复、空录制等完整业务校验策略。
- Google Play 上架流程和权限声明页面。
- Root、系统签名、设备所有者模式。
- ADB 或 Shizuku 作为必要运行条件。
- 基于截图、OCR 或控件树的页面状态识别。
- 云同步、账号系统、跨设备迁移。
- 定时、通知、NFC 等自动触发方式。

## 4. 可行性结论

### 4.1 可以实现的能力

在启用无障碍服务后，应用可以：

- 记录触摸按下、移动、抬起及多指切换事件。
- 保存触摸点坐标、指针编号和相对时间。
- 还原单击、双击、长按、滑动和部分多指轨迹。
- 通过 `AccessibilityService.dispatchGesture()` 向当前屏幕发送合成手势。
- 跨普通第三方 App 回放触摸轨迹。

### 4.2 平台限制

普通第三方 Android App 不能获得与系统输入服务完全相同的权限。因此本项目实现的是基于 Android 公共无障碍 API 的手势重建，而不是原始内核输入事件的逐字节复制。

以下场景不能保证可用：

- 锁屏、密码、安全键盘、支付、生物识别等安全界面。
- 厂商拦截的系统边缘手势。
- 游戏或高频交互场景对输入设备、压力、触摸面积或采样频率有严格要求。
- 页面状态、弹窗、网络加载时间或目标 App 版本与录制时不同。
- 录制和执行时屏幕方向、显示缩放或导航模式不同。

### 4.3 系统边缘手势

例如从屏幕底部向上滑动返回主屏幕：

- 录制时按普通滑动轨迹保存。
- 回放时从接近屏幕底部的位置构建上滑路径。
- 部分手机可能允许该合成手势触发桌面。
- 部分厂商系统可能把无障碍注入限制在应用内容区域，导致它只触发页面滚动或完全无效。

首版接受这一兼容性风险，不自动替换成 `GLOBAL_ACTION_HOME`。

## 5. 用户界面设计

## 5.1 主界面

主界面采用顶部栏和记录列表结构。

顶部栏：

- 左侧：页面标题“操作轨迹”。
- 右侧：“添加手势”按钮，使用加号图标配合文字。

列表中的每条记录展示：

- 记录名称。
- 操作数量。
- 总录制时长。
- 右侧执行按钮，使用播放图标。

示意：

```text
操作轨迹                              ＋ 添加手势

设置流程
8 个操作 · 12 秒                              ▶

打开收藏
4 个操作 · 6 秒                               ▶
```

交互：

- 点击执行按钮：进入执行准备流程。
- 点击记录主体：进入记录详情页。

## 5.2 新建记录

点击“添加手势”后显示名称输入对话框。

内容：

- 标题：“添加手势”。
- 输入框：“名称”。
- 次要按钮：“取消”。
- 主要按钮：“开始记录”。

点击“开始记录”后：

1. 创建一条仅存在于内存中的待保存记录。
2. 启动录制服务。
3. 通过无障碍执行一次 Home，回到固定主屏幕。
4. 等待约 300ms 后，让用户从固定主屏幕开始操作。

## 5.3 录制状态提示

录制期间不提供屏幕上的“停止”或“保存”按钮，结束条件统一为连续 5 秒没有新的触摸事件。

应用显示一个不可点击的小型状态浮层：

```text
正在记录 · 2.4 秒后结束
```

要求：

- 浮层不得消费或遮挡用户触摸。
- 每次检测到新的触摸事件时，5 秒倒计时重新开始。
- 浮层不计入录制轨迹。
- 第一次触摸前不自动结束录制。

开始录制后一直等待第一次有效触摸，避免用户切换到目标页面时被提前结束。第一次有效触摸完成后，切换到正常的 5 秒空闲判定。

## 5.4 录制完成与保存

满足结束条件后：

1. 停止接收新的触摸事件。
2. 移除录制状态浮层。
3. 返回本 App 的录制预览页。
4. 展示记录名称、操作数量、总时长和轨迹摘要。

预览页：

```text
设置流程

已记录 8 个操作，总时长 12.4 秒

1. 点击 (512, 168)
2. 滑动 (420, 1360) -> (420, 420)
3. 长按 (860, 1180)，600 ms

                              取消    保存
```

“保存”将数据持久化。“取消”丢弃本次待保存记录。

## 5.5 记录详情页

详情页提供：

- 名称。
- 创建时间。
- 录制时的屏幕信息。
- 操作数量和总时长。
- 轨迹摘要列表。
- 执行。
- 重新录制。
- 重命名。
- 删除。

## 5.6 执行流程

点击执行后：

1. 回到固定主屏幕后显示 1 秒倒计时。
2. 通过无障碍执行一次 Home，回到固定主屏幕。
3. 等待约 300ms 后开始 1 秒执行倒计时。
4. 倒计时结束后开始回放。
4. 按录制顺序和原始时间间隔执行所有手势。
5. 执行完成后显示短暂的完成提示。

首版不自动判断用户是否位于正确页面。

## 6. 应用状态机

```text
IDLE
  -> NAMING
  -> RECORDING_GRACE_PERIOD
  -> RECORDING
  -> IDLE_COUNTDOWN
  -> RECORDED_PREVIEW
  -> SAVED

SAVED
  -> PLAYBACK_COUNTDOWN
  -> PLAYING
  -> PLAYBACK_COMPLETED
  -> IDLE
```

状态说明：

| 状态 | 含义 |
| --- | --- |
| `IDLE` | 正常显示记录列表 |
| `NAMING` | 输入新记录名称 |
| `RECORDING_GRACE_PERIOD` | 录制已准备，等待用户开始第一个操作，不自动结束 |
| `RECORDING` | 正在收集触摸事件 |
| `IDLE_COUNTDOWN` | 手指已抬起，等待连续 5 秒无新触摸 |
| `RECORDED_PREVIEW` | 录制结束但尚未保存 |
| `SAVED` | 记录已经持久化 |
| `PLAYBACK_COUNTDOWN` | 回到固定主屏幕后执行前 1 秒倒计时 |
| `PLAYING` | 正在回放轨迹 |
| `PLAYBACK_COMPLETED` | 所有轨迹执行完成 |

状态转换规则：

- `ACTION_DOWN` 到来时取消空闲倒计时。
- 收到第一个有效触摸后进入正常录制状态。
- 完整收到 `ACTION_UP` 或最终 `ACTION_POINTER_UP` 后启动 5 秒倒计时。
- 倒计时期间如果收到新的 `ACTION_DOWN`，恢复录制并重新计时。
- 连续 5 秒没有任何有效触摸事件时结束录制。
- 正在触摸或长按时不得因为持续时间超过 5 秒而结束录制。

## 7. 总体技术架构

```text
Compose UI
    |
    +-- GestureListViewModel
    +-- RecordingViewModel
    +-- PlaybackViewModel
    |
Use Cases
    +-- StartRecording
    +-- FinishRecording
    +-- SaveGestureRecord
    +-- PlayGestureRecord
    |
Core Services
    +-- GestureAccessibilityService
    +-- TouchRecorder
    +-- GesturePlayer
    +-- RecordingOverlayController
    |
Data
    +-- Room Database
    +-- GestureRecordRepository
```

### 7.1 推荐项目结构

```text
app/src/main/java/.../
  data/
    db/
    entity/
    repository/
  domain/
    model/
    usecase/
  service/
    GestureAccessibilityService.kt
    TouchRecorder.kt
    GesturePlayer.kt
    RecordingOverlayController.kt
  ui/
    list/
    naming/
    preview/
    detail/
    playback/
  util/
```

## 8. 录制技术方案

### 8.1 主要方案

MVP 在 Android 13/API 33 及以上使用：

- `AccessibilityService`
- `TouchInteractionController`
- `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`
- `FLAG_SEND_MOTION_EVENTS`
- `AccessibilityGestureEvent.getMotionEvents()`

录制器需要让目标 App 正常获得用户操作，同时采集组成该手势的 `MotionEvent`。建议流程：

1. 无障碍服务注册 `TouchInteractionController.Callback`。
2. 新交互开始后请求委托，让系统把交互传递给正常输入管线。
3. 接收透传手势对应的 `AccessibilityGestureEvent`。
4. 从 `getMotionEvents()` 提取完整触摸序列。
5. 将事件转换为应用内部数据模型。

该能力涉及触摸探索模式，因此当前 MVP 将 `minSdk` 设为 33。不同厂商的实现可能有差异，必须在目标真机上验证：

- 单击是否只触发一次。
- 滑动是否实时作用于目标 App。
- 长按是否能完整记录。
- 多指操作是否丢失指针。
- 底部和侧边缘手势是否可见并可透传。

### 8.2 兼容性兜底

每次触摸交互先等待原始 `MotionEvent`。若目标设备没有返回原始轨迹，录制器在交互结束后短暂等待语义事件：

1. `TYPE_VIEW_CLICKED` 或 `TYPE_VIEW_LONG_CLICKED` 且带有有效 source 时，保存对应控件中心位置。
2. `TYPE_VIEW_SCROLLED` 且带有有效滚动位移时，保存一条合成的方向滑动。
3. 两者均不可用时，保存为“未知操作”，不伪造坐标。

未知操作会显示在预览中，但整条记录禁止自动执行，避免将错误坐标发送到其他 App。

### 8.3 事件采样

每个触摸点至少保存：

- `actionMasked`
- `actionIndex`
- `pointerId`
- 原始 `x`、`y`
- 归一化 `xRatio`、`yRatio`
- 相对当前手势的时间 `offsetMs`
- 相对整条记录的时间 `recordOffsetMs`
- 当前指针数量

对于高频 `ACTION_MOVE`，可以进行轻量降采样：

- 保留所有按下、抬起和指针变化事件。
- 相邻移动点时间差低于约 8 ms 且距离变化很小时，可以合并。
- 降采样后仍需保持轨迹形状和总持续时间。

首版可以先完整保存所有事件，以真机数据量为依据再决定是否降采样。

### 8.4 手势切分

手势开始：

- 第一个 `ACTION_DOWN`。

手势结束：

- 单指 `ACTION_UP`。
- 多指场景中最后一个指针抬起。
- `ACTION_CANCEL` 作为取消的手势保存或丢弃，由录制器统一决定。

连续两个手势之间的时间差单独保存，用于回放时恢复节奏。

## 9. 三秒无操作结束判定

结束规则是录制流程的核心业务规则。

### 9.1 规则定义

- `idleTimeoutMs = 5000`
- 第一次有效触摸前不启动自动结束计时器。
- 5 秒计时从一个完整手势结束后开始。
- 触摸仍处于按下状态时不运行结束计时器。
- 新的 `ACTION_DOWN` 会取消正在运行的计时器。

### 9.2 时间线示例

```text
0.0s  ACTION_DOWN
0.2s  ACTION_UP
0.2s  开始空闲计时
2.4s  新 ACTION_DOWN，取消计时
2.6s  ACTION_UP，重新开始空闲计时
5.6s  仍无新事件，自动结束录制
```

### 9.3 已接受的限制

如果用户点击后等待页面加载超过 5 秒，录制会提前结束。首版不通过页面加载检测解决该问题，也不提供超时配置。

## 10. 回放技术方案

### 10.1 手势重建

使用 `GestureDescription` 和 `AccessibilityService.dispatchGesture()`。

单指轨迹：

1. 将采样点按时间排序。
2. 使用 `Path.moveTo()` 设置起点。
3. 使用 `Path.lineTo()` 或平滑后的路径连接移动点。
4. 使用原始持续时间创建 `StrokeDescription`。
5. 调用 `dispatchGesture()`。

点击可以使用极短路径表示；长按使用固定坐标和原始持续时间表示。

多指轨迹：

- 每个指针转换为一个 `StrokeDescription`。
- 所有指针使用相对于手势开始时间的起始偏移。
- 必须遵守系统允许的最大笔画数量和最大持续时间。
- 超长手势需要通过可连续的 `StrokeDescription` 分段发送。

### 10.2 调度策略

不能只依赖一串固定 `delay()`。每次调用 `dispatchGesture()` 后都应等待：

- `GestureResultCallback.onCompleted()`：继续下一步。
- `GestureResultCallback.onCancelled()`：停止本次执行并报告失败。

两次手势之间再按照录制的原始间隔等待。

### 10.3 坐标映射

首版优先保证同一设备回放：

```text
playbackX = recordedXRatio * currentContentWidth
playbackY = recordedYRatio * currentContentHeight
```

同时保留原始像素坐标用于同分辨率精确回放。

执行前检查：

- 当前方向是否与录制一致。
- 当前屏幕尺寸是否与录制一致。
- 系统显示缩放和状态栏区域是否发生明显变化。

首版检测到不一致时可以继续执行，但应在内部日志中记录警告。

## 11. 数据模型

建议使用 Room 保存记录元数据和手势数据。轨迹点数量可能较多，可将一条手势的点序列序列化为二进制或 JSON 字段；首版为了可调试性可以使用 JSON，稳定后再评估紧凑二进制格式。

### 11.1 记录表

```kotlin
data class GestureRecord(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val screenWidth: Int,
    val screenHeight: Int,
    val orientation: Int,
    val densityDpi: Int,
    val totalDurationMs: Long,
    val gestureCount: Int
)
```

### 11.2 手势表

```kotlin
data class RecordedGesture(
    val id: Long,
    val recordId: Long,
    val sequence: Int,
    val startOffsetMs: Long,
    val durationMs: Long,
    val pointerCount: Int,
    val direction: GestureDirection?,
    val points: List<TouchPoint>
)
```

### 11.3 触摸点

```kotlin
data class TouchPoint(
    val actionMasked: Int,
    val actionIndex: Int,
    val pointerId: Int,
    val x: Float,
    val y: Float,
    val xRatio: Float,
    val yRatio: Float,
    val offsetMs: Long,
    val recordOffsetMs: Long
)
```

## 12. 无障碍服务配置

服务至少需要声明：

- 能够执行手势。
- 能够请求触摸探索模式。
- 接收触摸交互和手势事件。
- 根据最终录制实现决定是否读取窗口内容；纯坐标方案本身不依赖控件树。

概念配置：

```xml
<accessibility-service
    android:canPerformGestures="true"
    android:canRequestTouchExplorationMode="true"
    android:accessibilityFlags="flagRequestTouchExplorationMode|flagSendMotionEvents"
    android:accessibilityEventTypes="typeTouchInteractionStart|typeTouchInteractionEnd"
    android:notificationTimeout="0" />
```

最终 XML 需要以实际 `compileSdk` 支持的属性名称和目标真机行为为准。

## 13. 运行日志与诊断

虽然主流程不设计复杂异常处理，开发版本仍应保存必要日志：

- 录制开始和结束时间。
- 收到的事件类型和数量。
- 每条手势的指针数量、点数量和持续时间。
- 5 秒计时器的启动、取消和触发。
- `dispatchGesture()` 是否提交成功。
- 回放完成或取消回调。
- 当前屏幕尺寸、方向和目标包名。

日志不得保存用户屏幕截图、输入文本或无关的页面内容。

## 14. 安全设计

即使以个人使用为主要目标，仍需提供基本保护：

- 回到固定主屏幕后执行前等待 1 秒，避免误触后立即运行。
- 同一时间只允许一个录制任务或一个执行任务。
- 执行过程中新的执行请求直接拒绝。
- 回放超过记录时长加合理余量后自动终止。
- App 进程重启后不得自动恢复未完成的执行。
- 录制数据仅保存在本机应用私有目录。

首版不把屏幕上的用户触摸作为“立即中止回放”的条件，因为回放与用户触摸事件的区分需要额外验证。开发调试阶段可通过停止应用或关闭服务终止。

## 15. 测试方案

### 15.1 单元测试

- `MotionEvent` 到内部模型的转换。
- 多指事件的指针 ID 保持。
- 坐标归一化和反归一化。
- 手势切分。
- 5 秒空闲计时器状态转换。
- 路径降采样不改变首尾点和总时长。
- 数据序列化与反序列化。

### 15.2 真机功能测试

必须在目标手机上逐项验证：

1. 单击录制和回放。
2. 双击录制和回放。
3. 长按 6 秒，确认不会被 5 秒规则中断。
4. 慢速滑动和快速甩动。
5. 曲线路径。
6. 两指缩放。
7. 连续操作间隔小于 5 秒。
8. 操作间隔超过 5 秒后自动结束。
9. 底部上滑返回桌面。
10. 侧边返回手势。
11. 横屏录制和回放。
12. 录制时切换多个 App。

### 15.3 验收标准

首版 MVP 的最低验收标准：

- 可以创建、保存、展示和删除记录。
- 可以连续录制至少 20 个单指手势。
- 连续 5 秒无操作后可以自动结束。
- 长按超过 5 秒不会提前结束。
- 同一手机、相同页面状态下，普通点击和滑动序列可重复回放。
- 执行按钮有 3 秒准备倒计时。
- 回放失败不会导致执行器永久卡死。
- 真机验证底部上滑是否能够触发桌面，并如实记录结果。

## 16. 开发阶段划分

### 阶段一：技术验证

- 创建最小 Android 项目。
- 实现无障碍服务。
- 验证 `TouchInteractionController` 事件采集和委托。
- 打印并持久化一段单指轨迹。
- 使用 `dispatchGesture()` 回放该轨迹。
- 验证目标手机的底部上滑手势。

此阶段是项目最重要的风险验证点。在确认目标真机可以同时录制和透传触摸之前，不应投入复杂 UI。

### 阶段二：完整录制闭环

- 实现名称输入。
- 实现首次操作等待和 5 秒自动结束。
- 实现录制状态浮层。
- 实现预览和保存。
- 使用 Room 持久化数据。

### 阶段三：列表和执行

- 实现主列表。
- 实现执行倒计时。
- 实现串行手势回放。
- 实现记录详情、重命名、重新录制和删除。

### 阶段四：兼容性与稳定性

- 验证多指和长手势。
- 优化坐标映射。
- 增加回放取消和超时保护。
- 针对目标厂商系统调整后台和无障碍行为。

## 17. 主要技术风险

| 风险 | 影响 | 当前方案 |
| --- | --- | --- |
| 无法同时捕获并透传完整触摸 | 核心录制不可用 | 优先验证 API 33 `TouchInteractionController`，透明浮层作为兜底 |
| 系统边缘手势不接受注入 | 无法回放返回桌面等操作 | 首版实际尝试并记录设备兼容结果，不转换为系统动作 |
| 5 秒内页面未加载完成 | 录制提前结束 | 作为已接受限制，首版不做页面识别 |
| 目标页面状态变化 | 坐标回放错误 | 要求用户从相同初始页面执行 |
| 屏幕方向或显示区域变化 | 坐标偏移 | 保存屏幕信息和归一化坐标 |
| 厂商终止无障碍服务 | 录制或回放中断 | 假定使用环境已配置，保留诊断日志 |

## 18. 最终实现原则

1. 原始触摸轨迹优先；只有原始轨迹缺失时才使用语义事件兜底。
2. 录制数据同时保留坐标、归一化坐标和相对时间。
3. 连续 5 秒无操作即结束，但正在进行的长按或手势不受影响。
4. 自动结束后必须由用户点击“保存”才写入正式记录。
5. 回放严格串行执行，并等待每个手势的系统完成回调；包含未知操作的记录不得自动执行。
6. 首版优先保证同一台真机上的可重复性，不承诺跨设备兼容。
7. 在正式开发 UI 前，先完成触摸采集、透传和边缘手势回放的真机技术验证。
