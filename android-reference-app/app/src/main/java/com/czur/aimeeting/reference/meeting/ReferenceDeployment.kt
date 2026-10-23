package com.czur.aimeeting.reference.meeting

/**
 * 参考 App 的唯一部署配置：代码即配置。
 *
 * 构建不再接受任何 -P 部署参数。合作伙伴直接修改本文件的地址即可；终端用户不需要
 * 在设备上填写。调试用自签 CA 放在 app/src/debug/res/raw/reference_debug_ca.pem；
 * 运维提供正式 CA 后，替换该文件（或改走系统信任），不影响这里的地址配置。
 */
object ReferenceDeployment {
    /** 参考后端根地址，不带尾部斜杠。 */
    const val BACKEND_URL = "https://192.168.0.116:8443"

    /** 会议结果 Web 地址；局域网参考 Web 当前是 http。 */
    const val RESULT_WEB_URL = "http://192.168.0.116:8091/index.html"
}
