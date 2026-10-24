package com.czur.aimeeting.reference.meeting

import com.czur.aimeeting.reference.BuildConfig

/**
 * 参考 App 的部署地址：由构建期读取的本地配置注入，不在源码里写死。
 *
 * <p>配置来源是 `android-reference-app/deployment.local.properties`（已被 Git 忽略），
 * 也可以用环境变量 `REFERENCE_DEPLOYMENT_FILE` 指向另一份，用来区分测试与生产。
 * 缺少配置时构建直接失败，不会产出指向占位地址的包。</p>
 *
 * <p>Debug 构建如需信任本机自签 CA，把 PEM 放到
 * `app/src/debug/res/raw/reference_debug_ca.pem`（已被 Git 忽略），构建会自动生成对应的
 * 网络安全配置；文件不存在时只信任系统 CA。</p>
 */
object ReferenceDeployment {
    /** 设备可达的后端根地址，不带尾部斜杠。 */
    val BACKEND_URL: String = BuildConfig.BACKEND_URL

    /** 会议结果网页地址。 */
    val RESULT_WEB_URL: String = BuildConfig.RESULT_WEB_URL
}
