package com.czur.aimeeting.reference.meeting

/**
 * 参考 App 的全部可配置参数，代码即配置。
 *
 * <p>部署方直接修改本文件的两个常量后重新构建；不再读取 .properties、Gradle 属性或
 * 环境变量，也不需要运行时输入。仓库里保留占位值，未替换时构建会打印警告，App 会
 * 提示“部署地址未配置”，不会静默指向某台开发机。</p>
 *
 * <p>Debug 构建如需信任本机自签 CA，把 PEM 放到
 * `app/src/debug/res/raw/reference_debug_ca.pem`（已被 Git 忽略），构建会自动生成
 * 对应的网络安全配置；文件不存在时只信任系统 CA。Release 不信任开发 CA。</p>
 */
object ReferenceDeployment {
    /** 设备可达的后端根地址，不带尾部斜杠。 */
    const val BACKEND_URL = "https://replace-with-your-backend.example"

    /** 会议结果网页地址。 */
    const val RESULT_WEB_URL = "https://replace-with-your-result-web.example/index.html"
}
