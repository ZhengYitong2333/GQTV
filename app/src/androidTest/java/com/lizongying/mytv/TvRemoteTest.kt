package com.lizongying.mytv

import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lizongying.mytv.models.EPG
import com.lizongying.mytv.models.TVList
import com.lizongying.mytv.models.currentProgramIndex
import com.tencent.videolite.android.datamodel.cctvjce.TVProgram
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TvRemoteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val catalog = TVList.list.values.flatten()
    private val originals = catalog.map { it.copy() }
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var restorePreferences: () -> Unit
    private val documentaryIndex = catalog.indexOfFirst { it.alias == "CCTV9" }

    @Before fun launch() {
        // The test APK owns the clip. Product code still tunes via the normal channel model and Media3.
        val file = File(instrumentation.targetContext.cacheDir, "remote-test.mp4")
        instrumentation.context.assets.open("documentary-test.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        catalog.forEach { channel ->
            channel.pid = ""
            channel.videoUrl = listOf(file.toURI().toString())
        }
        catalog.last().videoUrl = emptyList()
        instrumentation.runOnMainSync {
            val last = SP.itemPosition
            val time = SP.time
            val grid = SP.grid
            val reverse = SP.channelReversal
            val number = SP.channelNum
            val boot = SP.bootStartup
            val guid = SP.guid
            restorePreferences = {
                SP.itemPosition = last
                SP.time = time
                SP.grid = grid
                SP.channelReversal = reverse
                SP.channelNum = number
                SP.bootStartup = boot
                SP.guid = guid
            }
            SP.time = true
            SP.grid = false
            SP.channelReversal = false
            SP.channelNum = true
            SP.bootStartup = false
            SP.itemPosition = documentaryIndex
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitUi("first decoded frame") { activity ->
            val player = activity.findViewById<PlayerView>(R.id.player_view).player
            player?.isPlaying == true && player.videoSize.width == 960 && player.videoSize.height == 540
        }
    }

    @After fun close() {
        if (::scenario.isInitialized) scenario.close()
        catalog.zip(originals).forEach { (channel, original) ->
            channel.pid = original.pid
            channel.videoUrl = original.videoUrl
        }
        if (::restorePreferences.isInitialized) instrumentation.runOnMainSync { restorePreferences() }
    }

    private fun key(code: Int, times: Int = 1) {
        repeat(times) {
            instrumentation.sendKeyDownUpSync(code)
            instrumentation.waitForIdleSync()
        }
    }

    private fun awaitUi(label: String, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 6_000
        do {
            var passed = false
            scenario.onActivity { passed = predicate(it) }
            if (passed) return
            SystemClock.sleep(40)
        } while (SystemClock.elapsedRealtime() < deadline)
        var detail = ""
        scenario.onActivity { activity ->
            val dialog = settings(activity)?.dialog
            val focusId = dialog?.currentFocus?.id ?: -1
            val row = if (focusId > 0) activity.resources.getResourceEntryName(focusId) else "none"
            detail = " (focused row: " + row + ", dialog has window focus: " + dialog?.window?.decorView?.hasWindowFocus() + ")"
        }
        fail("Timed out waiting for " + label + detail)
    }

    private fun settings(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentByTag(SettingFragment.TAG) as? SettingFragment

    private fun confirmation(activity: MainActivity) =
        (settings(activity)?.childFragmentManager?.findFragmentByTag("confirm") as? ConfirmationFragment)?.dialog

    private fun focus(id: Int) = awaitUi("settings focus on " + instrumentation.targetContext.resources.getResourceEntryName(id)) {
        val dialog = settings(it)?.dialog
        dialog?.window?.decorView?.hasWindowFocus() == true && dialog.currentFocus?.id == id
    }

    private fun browser(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentByTag(MainFragment.TAG) as MainFragment

    private fun focusedChannel(activity: MainActivity): Int {
        val list = activity.findViewById<RecyclerView>(R.id.items)
        val card = list.focusedChild ?: return RecyclerView.NO_POSITION
        return list.getChildAdapterPosition(card)
    }

    private fun capture(name: String) {
        // Wait for the focused item to reach the compositor before capturing the video surface.
        SystemClock.sleep(160)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(instrumentation.targetContext.cacheDir, name + ".png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun settingsKeepsFocusAndPersistsAllSwitches() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        focus(R.id.switch_channel_reversal)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(R.id.switch_channel_num)
        key(KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(R.id.switch_time)
        capture("settings-on-tv")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        SystemClock.sleep(16_000) // The original dialog dismissed after 15 s.
        focus(R.id.switch_time)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        key(KeyEvent.KEYCODE_BACK)
        awaitUi("settings dismissed") { settings(it) == null }
        assertTrue(SP.channelReversal && !SP.channelNum && !SP.time && SP.grid && SP.bootStartup)
        scenario.recreate()
        key(KeyEvent.KEYCODE_MENU)
        focus(R.id.switch_channel_reversal)
        awaitUi("saved preferences") {
            val view = settings(it)?.view ?: return@awaitUi false
            !view.findViewById<android.widget.Switch>(R.id.switch_time).isChecked &&
                view.findViewById<android.widget.Switch>(R.id.switch_grid).isChecked
        }
    }

    private fun openMoreSettings() {
        key(KeyEvent.KEYCODE_MENU)
        focus(R.id.switch_channel_reversal)
        key(KeyEvent.KEYCODE_DPAD_DOWN, 6)
        focus(R.id.more)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        focus(R.id.check_version)
    }

    @Test fun moreRestoresParentFocusAndHandlesBack() {
        openMoreSettings()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(R.id.clear)
        scenario.recreate()
        focus(R.id.clear)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("reset confirmation") { confirmation(it)?.window?.decorView?.hasWindowFocus() == true }
        key(KeyEvent.KEYCODE_BACK)
        focus(R.id.clear)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(R.id.exit)
        key(KeyEvent.KEYCODE_BACK)
        focus(R.id.more)
        key(KeyEvent.KEYCODE_BACK)
        awaitUi("returned to playback") { settings(it) == null && browser(it).isHidden }
    }

    @Test fun confirmResetWithRemoteKeepsTheChannelAndRestoresDefaults() {
        scenario.onActivity {
            SP.channelReversal = true
            SP.channelNum = false
            SP.time = false
            SP.grid = true
            SP.bootStartup = true
        }
        openMoreSettings()
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        focus(R.id.clear)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("reset confirmation window") { confirmation(it)?.window?.decorView?.hasWindowFocus() == true }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitUi("positive confirmation button") { confirmation(it)?.currentFocus?.id == android.R.id.button1 }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("confirmed settings reset") { confirmation(it) == null && SP.time && SP.channelNum &&
            !SP.grid && !SP.bootStartup && !SP.channelReversal && SP.itemPosition == documentaryIndex }
    }

    @Test fun browseWithoutRetuningAndReopenAtOffscreenChannel() {
        scenario.onActivity { host ->
            val now = Utils.getDateTimestamp()
            fun program(name: String, start: Long) = TVProgram().apply {
                this.name = name
                start_time_stamp = start
            }
            browser(host).currentChannel!!.addYJceEPG(mutableListOf(
                program("自然的力量", now + 1_800), program("蓝色星球", now - 1_200)))
        }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitUi("currently tuned channel") { focusedChannel(it) == documentaryIndex }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitUi("preview only") { focusedChannel(it) == documentaryIndex + 1 && SP.itemPosition == documentaryIndex }
        capture("channels-on-tv")
        key(KeyEvent.KEYCODE_DPAD_RIGHT, 7)
        awaitUi("scroll offscreen") { focusedChannel(it) == documentaryIndex + 8 && SP.itemPosition == documentaryIndex }
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("committed tune") { browser(it).isHidden && SP.itemPosition == documentaryIndex + 8 }
        key(KeyEvent.KEYCODE_ENTER)
        awaitUi("focus on reopen") { focusedChannel(it) == SP.itemPosition }
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        key(KeyEvent.KEYCODE_BACK)
        awaitUi("cancel browse") { browser(it).isHidden && SP.itemPosition == documentaryIndex + 8 }
    }

    // ---- group strip / number jump / backup ladder -------------------------

    /** Group name -> index of its first channel, matching the strip's order. */
    private fun groupStarts(): List<Pair<String, Int>> {
        var offset = 0
        return TVList.list.map { (name, list) -> name to offset.also { offset += list.size } }
    }

    private fun strip(activity: MainActivity) =
        activity.findViewById<android.widget.LinearLayout>(R.id.groups)

    private fun stripFocus(activity: MainActivity): Int {
        val groups = strip(activity) ?: return -1
        for (i in 0 until groups.childCount) if (groups.getChildAt(i).isFocused) return i
        return -1
    }

    private fun openBrowserAndFocusStrip() {
        key(KeyEvent.KEYCODE_DPAD_LEFT)
        awaitUi("browser open") { !browser(it).isHidden }
        key(KeyEvent.KEYCODE_DPAD_UP)
        awaitUi("focus entered the strip") { stripFocus(it) >= 0 }
    }

    @Test fun groupStripWalksEveryGroupAndJumpsToItsFirstChannel() {
        val groups = groupStarts()
        assertEquals(listOf("央视", "地方", "港澳台", "国际"), groups.map { it.first })
        openBrowserAndFocusStrip()

        // LEFT/RIGHT step along the strip and stop at the ends instead of
        // dropping focus into the card row below.
        key(KeyEvent.KEYCODE_DPAD_LEFT, groups.size)
        awaitUi("left edge holds") { stripFocus(it) == 0 }
        key(KeyEvent.KEYCODE_DPAD_RIGHT, groups.size - 1)
        awaitUi("last chip reached") { stripFocus(it) == groups.size - 1 }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        awaitUi("right edge holds") { stripFocus(it) == groups.size - 1 }

        groups.forEachIndexed { i, (name, first) ->
            scenario.onActivity { strip(it)!!.getChildAt(i).requestFocus() }
            awaitUi("chip $name focused") { stripFocus(it) == i }
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitUi("jumped to $name") { focusedChannel(it) == first }
            assertEquals("jumping must not tune", documentaryIndex, SP.itemPosition)
        }
    }

    @Test fun digitsInBrowserMoveFocusWithoutTuning() {
        openBrowserAndFocusStrip()
        val target = catalog.indexOfFirst { it.id + 1 == 13 }
        assertTrue(target >= 0)
        // DPAD_DOWN puts focus back in the card row before typing.
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_1)
        key(KeyEvent.KEYCODE_3)
        awaitUi("two digits jump the card focus") { focusedChannel(it) == target }
        assertEquals("digit jump must not tune", documentaryIndex, SP.itemPosition)
        awaitUi("digit overlay hidden") {
            it.findViewById<TextView>(R.id.jump)?.visibility == android.view.View.GONE
        }
    }

    @Test fun backupLadderStartsAtTheFirstLineAndReportsItsPosition() {
        scenario.onActivity { host ->
            val channel = browser(host).currentChannel!!
            // tryBackup only applies to channels that arrived with their own
            // source, so give this one a pid back for the duration of the test.
            channel.getTV().pid = "test"
            assertTrue(host.tryBackup(channel))
        }
        awaitUi("backup line one of the ladder") {
            val line = it.playbackSettings().backupPosition()
            line != null && line.first == 1 && line.second == backupStreams.getValue("CCTV9").size
        }
        scenario.onActivity { host ->
            // Already walking the ladder: the caller must not re-enter it.
            assertFalse(host.tryBackup(browser(host).currentChannel!!))
            assertTrue(host.playbackSettings().isUsingBackup())
        }
    }

    @Test fun gridAndPlaybackShortcutsUseTheSameChannelOrder() {
        scenario.onActivity { SP.grid = true }
        key(KeyEvent.KEYCODE_ENTER)
        awaitUi("grid focus") { focusedChannel(it) == documentaryIndex }
        var columns = 0
        scenario.onActivity { columns = (it.findViewById<RecyclerView>(R.id.items).layoutManager as GridLayoutManager).spanCount }
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitUi("grid second row") { focusedChannel(it) == documentaryIndex + columns && SP.itemPosition == documentaryIndex }
        key(KeyEvent.KEYCODE_ENTER)
        awaitUi("grid tune") { browser(it).isHidden && SP.itemPosition == documentaryIndex + columns }
        key(KeyEvent.KEYCODE_CHANNEL_UP)
        awaitUi("channel up") { SP.itemPosition == documentaryIndex + columns - 1 }
        key(KeyEvent.KEYCODE_0)
        key(KeyEvent.KEYCODE_2)
        awaitUi("two-digit tune") { SP.itemPosition == 1 }
    }

    @Test fun programBoundariesAndMissingSchedule() {
        val entries = listOf(EPG("一", 100), EPG("二", 200), EPG("三", 300))
        assertEquals(-1, emptyList<EPG>().currentProgramIndex(100))
        assertEquals(-1, entries.currentProgramIndex(99))
        assertEquals(1, entries.currentProgramIndex(200))
        assertEquals(1, entries.currentProgramIndex(299))
        assertEquals(2, entries.currentProgramIndex(301))
    }

    @Test fun resolutionMenuSelectsSupportedTracksWithRemote() {
        val assets = instrumentation.context.assets
        val directory = File(instrumentation.targetContext.cacheDir, "resolution-test")
        directory.mkdirs()
        for (name in assets.list("resolution-test").orEmpty()) {
            assets.open("resolution-test/" + name).use { input ->
                File(directory, name).outputStream().use { output -> input.copyTo(output) }
            }
        }
        catalog[documentaryIndex].videoUrl = listOf(File(directory, "master.m3u8").toURI().toString())
        scenario.recreate()
        awaitUi("both video resolutions") {
            it.playbackSettings().resolutionOptions() == listOf(960 to 540, 480 to 270)
        }
        key(KeyEvent.KEYCODE_DPAD_RIGHT)
        focus(R.id.switch_channel_reversal)
        key(KeyEvent.KEYCODE_DPAD_DOWN, 5)
        focus(R.id.resolution)
        fun openResolutionDialog(selectedPosition: Int) {
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            awaitUi("resolution dialog focus") {
                val choice = settings(it)?.childFragmentManager?.findFragmentByTag(ResolutionFragment.TAG) as? ResolutionFragment
                val dialog = choice?.dialog as? AlertDialog
                dialog?.window?.decorView?.hasWindowFocus() == true &&
                    dialog.listView.selectedItemPosition == selectedPosition
            }
        }
        openResolutionDialog(0)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("960 by 540 decoded frames") {
            it.playbackSettings().resolutionSelection() == 540 && it.playbackSettings().videoResolution() == (960 to 540)
        }
        focus(R.id.resolution)
        openResolutionDialog(1)
        key(KeyEvent.KEYCODE_DPAD_DOWN)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        awaitUi("480 by 270 decoded frames") {
            it.playbackSettings().resolutionSelection() == 270 && it.playbackSettings().videoResolution() == (480 to 270)
        }
    }

    @Test fun idlePlaybackAvoidsMenuInflationAndReleasesTheDecoderOnStop() {
        var clockUpdates = 0
        scenario.onActivity { activity ->
            assertNull(activity.findViewById<RecyclerView>(R.id.items).adapter)
            val clock = activity.supportFragmentManager.findFragmentByTag("TimeFragment")!!.view!!
                .findViewById<TextView>(R.id.content)
            clock.doAfterTextChanged { clockUpdates++ }
        }
        SystemClock.sleep(2_300)
        scenario.onActivity { assertTrue("Clock should update only at minute boundaries", clockUpdates <= 1) }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.onActivity { assertNull(it.findViewById<PlayerView>(R.id.player_view).player) }
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitUi("playback after returning to the app") { it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true }
        scenario.onActivity { it.play(catalog.lastIndex) }
        awaitUi("previous media cleared while the new channel has no source") {
            val player = it.findViewById<PlayerView>(R.id.player_view).player
            player != null && !player.isPlaying && player.mediaItemCount == 0
        }
    }
}
