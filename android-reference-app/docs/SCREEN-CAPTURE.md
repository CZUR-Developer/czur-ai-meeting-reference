# SDK 截图公开入口与资源约束

本页只说明随仓设备 AAR 已公开的截图入口、调用线程和资源归属。裁剪、过滤、去重、
编码等处理都在 SDK 内部完成；调用方不应依赖算法细节或判断依据。参考 App 的接线见
[`ScreenCaptureSourceFactory.kt`](../app/src/main/java/com/czur/aimeeting/reference/ScreenCaptureSourceFactory.kt)
和 [`MeetingService.kt`](../app/src/main/java/com/czur/aimeeting/reference/MeetingService.kt)。

## 公开 API

系统投影授权和前台 Service 由 App 自己申请、启动；SDK 只消费授权结果：

| 入口 | 说明 |
| --- | --- |
| `ScreenCaptureClient(context, resultCode, consentData, callbackExecutor, onStopped)` | 取得 `MediaProjection` 授权后创建；`onStopped` 在系统或用户结束共享时由 `callbackExecutor` 回调 |
| `client.mode()` | 实际采集模式：`SYSTEM_CAPTURE` 或 `MEDIA_PROJECTION` |
| `client.supportsWindowExclusion()` | 当前系统是否支持排除指定窗口；不支持时调用方应显式传空列表 |
| `client.createSession(enableProcessing = false)` | 为一场会议创建会话；`false` 取得原始 PNG，`true` 启用内置处理并交付 JPEG |
| `session.captureFile(directory, excludedWindowTokens = emptyList())` | 挂起函数，返回 `CapturedImage<File>?`；`null` 表示本次没有新图，是正常结果 |
| `session.captureBitmap(excludedWindowTokens = emptyList())` | 挂起函数，返回 `CapturedImage<Bitmap>?` |
| `CapturedImage.value` / `confirmAccepted()` | 已交付的 File／Bitmap，以及业务接受确认；确认不发起网络请求，也不转移所有权 |
| `client.captureFile(...)` / `client.captureBitmap(...)` | 单次请求回调入口，返回可取消的 `Request`；参考 App 按场使用会话入口 |

`excludedWindowTokens` 必须来自本次要排除的已挂载窗口；参考 App 每次截图前在主线程读取
`View.getWindowToken()`，不缓存跨挂载句柄。整屏回退的系统明确传空列表，不能把未挂载
句柄静默当成空列表。

## 线程约束

- `ScreenCaptureClient` 和 `ScreenCaptureSession` 在主线程创建与关闭；参考实现从主线程
  发起截图协程。
- `captureFile`／`captureBitmap` 是挂起函数，SDK 在自己的 worker 上完成截图与编码。
  调用方在协程中等待结果，不要在 UI 线程同步阻塞。
- 读回 SDK 交付的文件、删除临时文件、解码 Bitmap 属于调用方工作，应放到 IO 线程；
  参考实现在 `ScreenCaptureSourceFactory` 里用 `Dispatchers.IO` 读取和清理。
- 参考实现在回调线程（主线程）调用 `confirmAccepted()`；它只更新本张图片的接受事实，
  不执行 I/O 或网络，重复确认安全。
- `onStopped` 表示投影授权已结束。回调和后续截图可能并发，宿主要用同一关闭流程收口，
  不要在回调里重入截图或再次创建会话。

参考实现由 `PresentationCapture` 串行化：上一帧的业务回执（`Frame.close()`）结束后
才发起下一帧，周期触发在忙时排队，不会并行进入 SDK；同一会话不要跨会议复用。

## 资源所有权

| 对象 | 所有权与释放 |
| --- | --- |
| `CapturedImage<File>` | SDK 在调用方给出的目录里写临时文件，交付后所有权归调用方；调用方读取完必须删除，SDK 不代为清理 |
| `CapturedImage<Bitmap>` | 交付后归调用方，调用方自行 `recycle()`；SDK 不回收 |
| `confirmAccepted()` | 只表示业务保存成功；不删除 File、不回收 Bitmap，也不延长其生命周期 |
| 截图目录 | 由调用方创建并保证可写；参考实现放在 App 私有缓存目录，不写公共存储 |
| 排除窗口句柄 | 只用于本次请求，调用方不持有 SDK 内部引用 |

`confirmAccepted()` 必须在业务侧真实保存或确认成功后调用；失败、取消或没有新图时不要
确认，以免 SDK 把未交付的图片当作已接受。

## 关闭顺序

正常停止、用户结束投影或页面退出时，按以下顺序收口：

1. 停止继续发起截图（取消宿主的截图协程和周期任务）。
2. `session.close()`：结束本场截图会话。
3. `client.close()`：释放 SDK 客户端及其持有的采集资源。
4. 停止 App 自己的前台截图 Service，释放系统投影授权。
5. 清理调用方尚未删除的临时文件。

不要先关 `client` 再关 `session`，也不要在 `session.close()` 返回前删除它仍可能写入的
目录。参考 App 的薄端口把前三步封装在 `ScreenCaptureSource.close()`，前台 Service 与
投影授权在端口关闭后再释放；Debug 探针页同样先关闭会话和客户端，再停止前台 Service。

## 验证入口

Debug 包提供独立截图探针，不登录、不开会：

```bash
python3 tools/android-device/check-screen-capture-api.py \
  --serial '<ADB序列号>' --output '/tmp/screen-capture-api-check'
```

探针覆盖正常授权、File／Bitmap 交付、排除窗口、接受前可重试、接受后无新图、变化画面、
新会话隔离及关闭一场不影响底层授权。它只读取上述公开返回值，不读取 SDK 内部判断。
