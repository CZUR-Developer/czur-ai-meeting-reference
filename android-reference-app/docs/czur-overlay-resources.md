# 参考窗口资源来源

本页说明参考窗口中来自 CZUR 国内版 AI 字幕纪要的显示资源。
下表原始路径相对原应用资源目录，仅用于标识出处；使用参考工程无需取得原产品源码。
原图逐字节复制，只修改参考工程内的资源名称和目录。
图片放入 `drawable-nodpi/`，尺寸由 App 的 1920×1080 设计数值统一换算；不再按原
tvdpi 或默认 drawable 密度二次缩放。动画放入 `raw/`。

| 原始文件 | 参考资源名 | SHA-256（源／目标相同） |
| --- | --- | --- |
| `drawable-nodpi/bg_floating_subtitle.9.png` | `czur_overlay_subtitle_background.9.png` | `ad04407431d29a7ac1e632ef2b382ae5605f5f5650dda33455f52130cd558816` |
| `drawable-nodpi/bg_floating_trans_normal.9.png` | `czur_overlay_normal_background.9.png` | `3877d9ac7acbd83f9d660edf10e5480ddfb47aec750475c612d5dd1ee9c04888` |
| `drawable-nodpi/bg_floating_trans_large.9.png` | `czur_overlay_large_background.9.png` | `eb789f4db070977141af4e7c79480ca50b92f4d19133b4882b34e15669676f05` |
| `drawable-tvdpi/ic_stop_trans.png` | `czur_overlay_stop.png` | `85a41ee6e74df21d326cc5e7f417aca6b6b97946bd90659cf0b6d3d6ba236d05` |
| `drawable-tvdpi/ic_langs_scale.png` | `czur_overlay_collapse.png` | `04d2eca4b4c3e74ce868c7c644bb0820f70136087a24088b8e51e08c97ba9b7d` |
| `drawable-tvdpi/ic_langs_max.png` | `czur_overlay_large.png` | `d337bcd3bba90d564613e6ea233ac1f9047d9bcfcbf81d7205d3257b12928826` |
| `drawable-tvdpi/ic_langs_normal.png` | `czur_overlay_normal.png` | `fbfbaa0e761eeb4e1837dd303364200758683b34a130c0ec2ea932db8cbc9df7` |
| `drawable/ic_change_position.png` | `czur_overlay_swap.png` | `b270726b9afb642d87f5a7e2d4bf049d03375ecf71664e331f2264b63971f8e2` |
| `drawable/ic_trans_simple_mode.png` | `czur_overlay_simple.png` | `98beff5d82706d8d5e99850e696148568a17e24e03af143f458e563079d7e214` |
| `drawable/ic_trans_simple_mode_exit.png` | `czur_overlay_simple_exit.png` | `178bd99462139069c78795aae60e2dd7b46354f7bdcb118c85891a55355f3072` |
| `drawable/ic_trans_simple_stop.png` | `czur_overlay_simple_stop.png` | `4e399e46d57e11767e759d83d7a33dfd54997b5e1e7ce46cf5a791179afd2bd2` |
| `drawable/ic_trans_simple_swap.png` | `czur_overlay_simple_swap.png` | `08ee5a4d43b535cdb8f9d4a3e5e3f20c85cec9dd813d2de32e74fc26c8d91c77` |
| `drawable/ic_trans_simple_fullscreen.png` | `czur_overlay_simple_large.png` | `e1c13e9da883a0276cd135db31ecd6a6a8aede7f3ea8c7f8733faa718a05db17` |
| `raw/ai_trans_voice.webp` | `czur_overlay_voice.webp` | `678f7784fb76e1ef1e964bc2443d166d879c3bf673125bebc556b86803b3a8b7` |
| `raw/aicc_subtitle_generating_anim.webp` | `czur_overlay_recording.webp` | `51c64eb9913f76070f552cba815586c7b9172ac0ed04203f4031ee5d78399947` |
| `raw/aicc_mini_anim.apng` | `czur_overlay_mini.apng` | `79cdf9136809d5b1c0f0ddb5d411e89b953c4f90b525cf52ab0be77b84b54728` |

## 几何与显示代码的来源

`CzurOverlayViews`是公开、可替换的 App 视图，按照原 `layout_floating_subtitle.xml`、
`layout_floating_trans_normal.xml`、`layout_floating_trans_large.xml`、
`layout_floating_trans_mini.xml`及`item_trans_sentence.xml`用 Android 基础容器实现。
普通互译简洁模式的164高／透明背景／两行／控制图标来自`TransNormalPanel.kt`。
原文白色、译文黄色及同侧原译组合来自`TransNormalPanel`和`TransLargePanel`，
左右决策使用已有 SDK 快照的`sourceSide`和应用显示交换开关，不在 View 重算路由。

字幕模式的计时按文字宽度测量，右边缘对齐音量区域左边缘，上下与音量区域对齐，
对应原布局的`wrap_content`和`endToStart`关系；不能改为固定宽框内居中，否则短计时会左偏。
互译普通／大窗仍使用中间区域的居中计时，不受字幕模式的对齐方式影响。

`CzurVoiceLevelView`只迁移`AnimatedImageCloudsView.kt`的声音图和高度响应曲线，
订阅、采音和 PCM 计算由 SDK 完成。WebP 由 Android 平台解码，声音图接收 SDK 的
0～100 电平；Mini 使用独立 APNG 3.0.5 显示库。参考应用最低支持 Android 14。

参考工程不引入原产品基础业务库、系统账号、Launcher 或平台专属联动。
伙伴可以改资源和这两种`OverlayViewFactory`；SDK的窗口管理、会议停止及模型接口
无需随 UI 修改。当前没有独立 UI AAR：设备 AAR 只提供 `SubtitleTextView`，参考 App 的
basic-ui 提供字幕普通/Mini、互译普通/大窗/Mini；窗口、权限、Service 和文案都留在 App。

## 许可与交付范围

源码的许可和声明见仓库根目录 [LICENSE](../../LICENSE) 与 [NOTICE](../../NOTICE)。
第三方显示库遵循其自身许可，配套 SDK／UI 制品以各自交付约定为准。
本页的来源记录不扩大对未交付原产品源码或其他材料的使用权限。
