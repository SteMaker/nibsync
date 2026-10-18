package de.nibsync.app.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class GoogleLoginErrorTest {
    @Test
    fun missingAndroidClientIsRecognised() {
        // As reported on the tablet after choosing the account.
        assertThat(isUnregisteredApp(8, "8: Unknown error [status=UNREGISTERED_ON_API_CONSOLE]")).isTrue()
        assertThat(isUnregisteredApp(10, null)).isTrue()
        assertThat(isUnregisteredApp(7, "network error")).isFalse()
    }

    @Test
    fun certificateFingerprintHasTheConsoleFormat() {
        val sha1 = signingSha1(RuntimeEnvironment.getApplication())
        assertThat(sha1 == "?" || Regex("([0-9A-F]{2}:){19}[0-9A-F]{2}").matches(sha1)).isTrue()
    }
}
