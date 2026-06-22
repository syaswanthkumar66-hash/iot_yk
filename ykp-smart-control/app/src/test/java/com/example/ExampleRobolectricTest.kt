package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ui.ScreenRoute
import com.example.ui.YkpViewModel
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("YKP Smart Control", appName)
  }

  @Test
  fun `test end-to-end launch navigation and repository seeding`() = runBlocking {
    val app = ApplicationProvider.getApplicationContext<Application>()
    val viewModel = YkpViewModel(app)

    // Initial state should be login screen
    assertEquals(ScreenRoute.Login, viewModel.currentRoute)

    // Enable demo mode to run locally in the unit test environment
    viewModel.toggleDemoMode(true)

    // Authenticate with valid email
    var success = false
    viewModel.authenticate("developer@ykp.io", "demo_password") { res ->
      success = res
    }
    assertTrue(success)

    // Verify it navigates to Home screen
    assertEquals(ScreenRoute.Home, viewModel.currentRoute)

    // Verify devices were seeded correctly and loaded
    val actualList = viewModel.devices.filter { it.isNotEmpty() }.first()
    assertTrue(actualList.isNotEmpty())
    val livingRoomSwitch = actualList.find { it.deviceId == "SW001" }
    assertEquals("Living Room Switch", livingRoomSwitch?.deviceName)
  }
}
