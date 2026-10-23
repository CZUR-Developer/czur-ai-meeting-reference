package com.czur.aimeeting.reference

import android.app.Activity
import com.czur.aimeeting.reference.identity.AppLoginEntry
import com.czur.aimeeting.reference.identity.DefaultAppLoginEntry
import com.czur.aimeeting.reference.meeting.MeetingAppAssembly
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

/** 只验证登录交互接缝，不连接后端或启动真实外部账号。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLoginEntryTest {
    @After fun restoreDefault() {
        MeetingAppAssembly.registerLoginEntry(DefaultAppLoginEntry)
        MeetingAppAssembly.configureBackend("")
    }

    @Test fun defaultEntryDelegatesToReferenceFormOnly() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        var opened = 0
        var identityChanged = 0
        DefaultAppLoginEntry.open(activity, { opened++ }, { identityChanged++ })
        assertEquals(1, opened)
        assertEquals(0, identityChanged)
    }

    @Test fun accountButtonInvokesPartnerEntryWithoutOpeningDefaultForm() {
        var opened = 0
        var host: Activity? = null
        MeetingAppAssembly.registerLoginEntry(AppLoginEntry { activity, _, _ ->
            opened++
            host = activity
        })
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        activity.findViewById<android.view.View>(R.id.account_button).performClick()
        assertEquals(1, opened)
        assertSame(activity, host)
        assertNull(ShadowAlertDialog.getLatestAlertDialog())
        controller.destroy()
    }
}
