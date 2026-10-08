# 参考应用国内UI资源来源

2026-10-08。用户已授权直接复用Starry国内版资源。以下路径相对`starry/`，图片原样复制，公开工程中的名称增加`czur_ref_`前缀以便伙伴替换。仅复制资源及纯显示逻辑，不依赖Starry私有库。

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

窗口资源和共享声音指示来源另见窗口说明，资源前缀为`czur_overlay_`。

## 布局与纯显示代码

- 会前卡片／标题：`dialog_transcription_setting.xml`，988×856卡片、86标题，1920×1080设计基准。窗口标题蓝底叠10%黑色，白卡当前40圆角。
- 语言、演示、结果、开始：`fragment_ai_trans_subtitle.xml`／`fragment_ai_trans.xml`；语言50高、24字、浅蓝底，236／588宽；开始180×50。参考保留自身登录、单一Web结果及SDK能力控制。
- 语言条目：`item_language_list.xml`／`LanguageListAdapter.kt`的24字号、50行高、选中底色`#DADFFD`及选中图片。
- 按钮／开关：`base/UILib/.../CZButton.kt`、`CZSwitch.kt`与同库`values/colors.xml`。摘取蓝白配色、15按钮圆角和76×40开关；`CzurReferenceSwitch`保留原生CheckBox事件语义，没有复制私有扩展。
- 字幕／互译：实际Panel与`item_trans_sentence.xml`。原文白色、译文`#FFDB10`，24字号／36行高。每段原译同侧上下显示，侧别来自SDK `sourceSide`及纯显示`swapped`；应用没有重新计算DOA或语言路由。
- `ReferenceUiScale`仅为参考页面实现局部设计尺寸映射，不修改系统DisplayMetrics；SDK字幕控件接收明确像素字号及行高。
- 蓝色提醒摘取`alert_warn_time.xml`／`common_float_double_btn.xml`的500×360卡片、36标题、24正文、180×50白色确认和50%黑色遮罩。`MeetingOverlay.renderReminder`只同步这两项窗口视觉尺寸与遮罩；提醒Notice、阈值、生命周期和确认回调保持。

## 伙伴修改入口

页面可替换`MeetingAppAssembly`中的页面工厂或本目录资源；窗口替换既有窗口工厂。修改UI资源无需改音源、模型Adapter、后端或会议收口。默认结果动作仍仅为Web，系统身份、会员、Launcher、邮件和小程序属于显式业务扩展。
