# 参考应用 UI 资源来源

本页说明参考应用中来自 CZUR 国内版 AI 字幕纪要的页面素材与显示设计，便于合作伙伴
识别和替换。下表原始路径用于标识资源出处；使用本工程无需取得原产品源码。
图片原样复制，参考工程内增加 `czur_ref_` 前缀；仅复用资源和纯显示逻辑，不引入原产品私有库。

| 参考文件（`src/main/res/drawable-nodpi/`） | 原始资源 |
| --- | --- |
| `czur_ref_translation_illustration.png` | `apps/transcription/src/main/res/drawable/ic_ai_trans_image.png` |
| `czur_ref_swap_languages.png` | `apps/transcription/src/main/res/drawable/ic_change.png` |
| `czur_ref_language_arrow.png` | `apps/transcription/src/main/res/drawable-tvdpi/ic_language_close_arrow_blue.png` |
| `czur_ref_language_selected.png` | `apps/transcription/src/main/res/drawable-tvdpi/ic_select_language.png` |
| `czur_ref_close.png` | `apps/transcription/src/main/res/drawable-tvdpi/ic_trans_close.png` |
| `czur_ref_stop.png` | `apps/transcription/src/main/res/drawable-tvdpi/ic_stop_trans.png` |
| `czur_ref_minimize.png` | `apps/transcription/src/main/res/drawable-tvdpi/ic_langs_scale.png` |
| `czur_ref_swap_columns.png` | `apps/transcription/src/main/res/drawable/ic_change_position.png` |
| `czur_ref_subtitle_background.9.png` | `apps/transcription/src/main/res/drawable-nodpi/bg_floating_subtitle.9.png` |
| `czur_ref_translation_background.9.png` | `apps/transcription/src/main/res/drawable-nodpi/bg_floating_trans_large.9.png` |
| `czur_ref_checked.png` | `baselib/src/main/res/drawable-nodpi/file_icon_blue_bg_focus_checked.png` |
| `czur_ref_unchecked.png` | `baselib/src/main/res/drawable-nodpi/file_icon_blue_bg_focus_unchecked.png` |

窗口资源和声音指示来源见[窗口资源说明](../docs/czur-overlay-resources.md)，
资源前缀为 `czur_overlay_`。

## 布局与纯显示代码

- 会前入口：参考工程独立设计的普通、不透明 Activity，沿用国内蓝白配色和1920×1080设计基准。顶部标题／账号、中部会议设置与结果分区、底部固定状态／开始；`reference_setup_*`是本页专用资源，不把原产品Dialog尺寸作为参考页约束。
- 语言、演示、结果、开始的业务入口来源于`fragment_ai_trans_subtitle.xml`／`fragment_ai_trans.xml`。参考页语言框56高、开始240×64；保留自身登录、默认Web结果插件及SDK能力控制，未复制原产品业务状态。
- 登录表单仍复用原`dialog_transcription_setting.xml`的白卡／蓝色标题配色和40圆角；它是普通起始Activity之上的独立账号弹窗，不改变起始页窗口类型。
- 语言条目：`item_language_list.xml`／`LanguageListAdapter.kt`的24字号、50行高、选中底色`#DADFFD`及选中图片。
- 按钮／开关：`base/UILib/.../CZButton.kt`、`CZSwitch.kt`与同库`values/colors.xml`。摘取蓝白配色、15按钮圆角和76×40开关；`CzurReferenceSwitch`保留原生CheckBox事件语义，没有复制私有扩展。
- 字幕／互译：原产品 Panel 与 `item_trans_sentence.xml`。原文白色、译文 `#FFDB10`，24字号／36行高。每段原译同侧上下显示，侧别来自 SDK `sourceSide` 及纯显示 `swapped`；应用不重新计算方向角或语言路由。
- `ReferenceUiScale`仅为参考页面实现局部设计尺寸映射，不修改系统DisplayMetrics；SDK字幕控件接收明确像素字号及行高。
- 蓝色提醒摘取`alert_warn_time.xml`／`common_float_double_btn.xml`的500×360卡片、36标题、24正文、180×50白色确认和50%黑色遮罩。`MeetingOverlay.renderReminder`只同步这两项窗口视觉尺寸与遮罩；提醒Notice、阈值、生命周期和确认回调保持。

## 伙伴修改入口

页面可替换`MeetingAppAssembly`中的页面工厂或本目录资源；窗口替换既有窗口工厂。修改UI资源无需改音源、模型Adapter、后端或会议收口。默认结果动作仍仅为Web，系统身份、会员、Launcher、邮件和小程序属于显式业务扩展。

## 许可与交付范围

源码的许可和声明见仓库根目录 [LICENSE](../../LICENSE) 与 [NOTICE](../../NOTICE)。
第三方依赖遵循各自许可，配套 SDK／UI 制品的使用范围以其交付约定为准。
本页用于记录出处和替换位置，不增加对原产品源码、商标或其他未交付材料的授权。
