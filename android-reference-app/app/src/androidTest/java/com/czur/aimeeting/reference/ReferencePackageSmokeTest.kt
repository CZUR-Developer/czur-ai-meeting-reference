package com.czur.aimeeting.reference

import android.test.InstrumentationTestCase

/**
 * 设备侧最小冒烟门禁：证明 debug/minified APK 可安装并启动 instrumentation。
 * 真实会议、音频和模型链仍由局域网联调与人工验收覆盖。
 */
class ReferencePackageSmokeTest : InstrumentationTestCase() {
    fun testTargetPackageIsReferenceApp() {
        assertEquals("com.czur.aimeeting.reference",
            instrumentation.targetContext.packageName)
    }
}
