package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.reference.BuildConfig

/**
 * 参考 App 的部署地址入口。
 *
 * 仓内只放占位值；本机或部署方在 `android-reference-app/deployment.local.properties`
 * （已被 Git 忽略）里填 `backendUrl`、`resultWebUrl`、可选的 `debugCaFile`，构建时注入。
 * 没有本地文件时构建仍会成功，但地址是占位域名，App 会提示联系应用提供方。
 * 终端用户不需要、也没有手填地址的入口。
 */
object ReferenceDeployment {
    /** 参考后端根地址，不带尾部斜杠。 */
    val BACKEND_URL: String = BuildConfig.BACKEND_URL

    /** 会议结果 Web 地址。 */
    val RESULT_WEB_URL: String = BuildConfig.RESULT_WEB_URL
}
