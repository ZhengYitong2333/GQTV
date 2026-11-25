package com.lizongying.mytv

import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lizongying.mytv.requests.Request
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
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

    private var supplyReady = true
    private var testLines: List<String>? = null
    private val localPlayback = ActivityLifecycleCallback { activity, stage ->
        if (activity is MainActivity && stage == Stage.CREATED) {
            browser(activity).fetchGuide = {}
            browser(activity).requestPrimary = activity::play
            activity.playbackSettings().lookupBackup = { alias -> testLines ?: backupSource(alias) }
            if (supplyReady) activity.onRequestFinished(null)
            else {
                Utils.clearListener(activity)
                Request.clearListener(activity)
            }
        }
    }

    @Before fun launch() {
        RouteQuality.enabled = false
        // The test APK owns the clip. Product code still tunes via the normal channel model and Media3.
        val file = File(instrumentation.targetContext.cacheDir, "remote-test.mp4")
        instrumentation.context.assets.open("documentary-test.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        catalog.forEach { channel ->
            channel.backupOnly = false
            channel.videoUrl = listOf(file.toURI().toString())
        }
        catalog.last().videoUrl = emptyList()
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().addLifecycleCallback(localPlayback)
            val qualityRecords = catalog.associate { it.pid to SP.routeRecords(it.pid) }
            val routeMemory = catalog.associate { it.pid to SP.lastBackup(it.pid) }
            qualityRecords.keys.forEach { SP.writeRouteRecords(it, emptyMap()); SP.rememberBackup(it, null) }
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
                qualityRecords.forEach { (pid, records) -> SP.writeRouteRecords(pid, records) }
                routeMemory.forEach { (pid, route) -> SP.rememberBackup(pid, route) }
                SP.flushRouteRecords()
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
        RouteQuality.enabled = true
        catalog.zip(originals).forEach { (channel, original) ->
            channel.backupOnly = original.backupOnly
            channel.videoUrl = original.videoUrl
        }
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().removeLifecycleCallback(localPlayback)
            if (::restorePreferences.isInitialized) restorePreferences()
        }
    }

    private fun key(code: Int, times: Int = 1) {
        repeat(times) {
            instrumentation.sendKeyDownUpSync(code)
            instrumentation.waitForIdleSync()
        }
    }

    private fun awaitUi(label: String, timeout: Long = 6_000, predicate: (MainActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
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

    @Test fun qualityLearnsAfterRebufferAndSurvivesRestartAndRouteRemoval() {
        LocalHls(instrumentation.context.assets).use { server ->
            val pid = catalog[documentaryIndex].pid
            val original = SP.routeRecords(pid)
            try {
                scenario.onActivity {
                    RouteQuality.enabled = true
                    SP.writeRouteRecords(pid, emptyMap())
                    SP.rememberBackup(pid, null)
                    catalog[documentaryIndex].backupOnly = true
                    testLines = listOf(server.stalled, server.healthy)
                    it.play(documentaryIndex)
                }
                awaitUi("fast first source renders") {
                    it.playbackSettings().backupPosition() == (1 to 2) &&
                        it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
                }
                awaitUi("rebuffer switches to healthy source", 25_000) {
                    it.playbackSettings().backupPosition() == (2 to 2) &&
                        it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
                }
                SystemClock.sleep(30_700)
                scenario.recreate()
                awaitUi("learned healthy source wins after recreation") {
                    it.playbackSettings().backupPosition() == (2 to 2) &&
                        it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
                }
                scenario.onActivity {
                    val records = SP.routeRecords(pid)
                    assertTrue(records.getValue(server.stalled).any { s -> s.severe > 0 })
                    assertTrue(records.getValue(server.healthy).any { s -> s.stable })
                    // Re-initialization reads the on-disk preference rather than an in-memory ranking.
                    SP.init(instrumentation.targetContext)
                    assertTrue(SP.routeRecords(pid).getValue(server.healthy).any { s -> s.stable })
                    testLines = listOf(server.stalled)
                    it.play(documentaryIndex)
                    assertEquals(1 to 1, it.playbackSettings().backupPosition())
                }
            } finally {
                scenario.close()
                SP.writeRouteRecords(pid, original)
                RouteQuality.enabled = false
            }
        }
    }

    @Test fun qualityPreviouslyStableSourceFailureAdvancesToRemainingRoute() {
        LocalHls(instrumentation.context.assets).use { server ->
            val pid = catalog[documentaryIndex].pid
            val original = SP.routeRecords(pid)
            try {
                scenario.onActivity {
                    RouteQuality.enabled = true
                    val stable = RouteSample("previously-stable", System.currentTimeMillis() - 60_000,
                        30_000, 1000, 960, 540, 0, 0, 0, 0, 0, "stopped", 29_000)
                    SP.writeRouteRecords(pid, mapOf(server.invalid to listOf(stable)))
                    testLines = listOf(server.healthy, server.invalid)
                    catalog[documentaryIndex].backupOnly = true
                    it.play(documentaryIndex)
                    assertEquals(2 to 2, it.playbackSettings().backupPosition())
                }
                awaitUi("failed previous favorite gives way to remaining line", 20_000) {
                    it.playbackSettings().backupPosition() == (1 to 2) &&
                        it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
                }
                scenario.onActivity {
                    assertTrue(SP.routeRecords(pid).getValue(server.invalid).any { s -> s.errors > 0 })
                }
            } finally {
                scenario.close()
                SP.writeRouteRecords(pid, original)
                RouteQuality.enabled = false
            }
        }
    }

    @Test fun qualityWaitsForPlayingBeforeRevealingDecodedFrame() {
        LocalHls(instrumentation.context.assets).use { server ->
            var decodedWhileBuffering = false
            scenario.onActivity { host ->
                RouteQuality.enabled = true
                testLines = listOf(server.throttled)
                catalog[documentaryIndex].backupOnly = true
                val engine = host.findViewById<PlayerView>(R.id.player_view).player!! as androidx.media3.exoplayer.ExoPlayer
                engine.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onRenderedFirstFrame(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        output: Any, renderTimeMs: Long) {
                        val view = host.findViewById<PlayerView>(R.id.player_view)
                        val surface = (view.videoSurfaceView as android.view.SurfaceView).holder.surface
                        if (output === surface && engine.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                            decodedWhileBuffering = true
                            assertEquals(View.VISIBLE, host.findViewById<View>(R.id.tune_cover).visibility)
                        }
                    }
                })
                host.play(documentaryIndex)
            }
            awaitUi("a decoded frame stays covered during startup buffering", 15_000) { decodedWhileBuffering }
            awaitUi("picture opens once playback can advance", 15_000) {
                it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true &&
                    it.findViewById<View>(R.id.tune_cover).visibility == View.GONE
            }
        }
    }

    @Test fun qualitySkipsAnAudioFormatTheDeviceCannotDecode() {
        org.junit.Assume.assumeTrue(androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
            .getDecoderInfos(androidx.media3.common.MimeTypes.AUDIO_MPEG_L2, false, false).isEmpty())
        LocalHls(instrumentation.context.assets).use { server ->
            val pid = catalog[documentaryIndex].pid
            scenario.onActivity {
                RouteQuality.enabled = true
                testLines = listOf(server.unsupportedAudio, server.healthy)
                catalog[documentaryIndex].backupOnly = true
                it.play(documentaryIndex)
            }
            awaitUi("audio-incompatible candidate gives way to playable route", 15_000) {
                it.playbackSettings().backupPosition() == (2 to 2) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            scenario.onActivity {
                assertTrue(SP.routeRecords(pid).getValue(server.unsupportedAudio).any { sample -> sample.unsupportedAudio && sample.errors > 0 })
                it.play(documentaryIndex)
                assertEquals(2 to 2, it.playbackSettings().backupPosition())
            }
        }
    }

    @Test fun qualityReusesAWorkingAvcModeWithoutCallingAShortVisitStable() {
        org.junit.Assume.assumeTrue(androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT
            .getDecoderInfos(androidx.media3.common.MimeTypes.VIDEO_H264, false, false).any { it.softwareOnly })
        LocalHls(instrumentation.context.assets).use { server ->
            var decoder = ""
            var released = false
            scenario.onActivity { host ->
                RouteQuality.enabled = true
                val short = RouteSample("short-configuration", System.currentTimeMillis(), 500, 20,
                    960, 540, 0, 0, 0, 0, 0, "retuned", 450, avcCompat = true, softwareVideo = true)
                assertFalse(short.stable)
                SP.writeRouteRecords(catalog[documentaryIndex].pid, mapOf(server.healthy to listOf(short)))
                testLines = listOf(server.healthy)
                catalog[documentaryIndex].backupOnly = true
                val engine = host.findViewById<PlayerView>(R.id.player_view).player!! as androidx.media3.exoplayer.ExoPlayer
                engine.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onVideoDecoderInitialized(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        name: String, initializedTimestampMs: Long, initializationDurationMs: Long) { decoder = name }
                    override fun onVideoDecoderReleased(time: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        name: String) { released = true }
                })
                host.play(documentaryIndex)
            }
            awaitUi("stored compatible decoder mode reaches real playback", 15_000) {
                decoder.startsWith("c2.android.") &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            scenario.onActivity {
                released = false
                it.playbackSettings().finishQualityObservation()
                it.findViewById<PlayerView>(R.id.player_view).player!!.stop()
            }
            awaitUi("previous decoder has been released") { released }
            scenario.onActivity {
                val stalled = RouteSample("stalled-configuration", System.currentTimeMillis(), 10000, 20,
                    960, 540, 1, 6000, 1, 0, 0, "retuned", 4000, avcCompat = true, softwareVideo = true)
                assertFalse(stalled.stable)
                SP.writeRouteRecords(catalog[documentaryIndex].pid, mapOf(server.healthy to listOf(stalled)))
                decoder = ""
                it.play(documentaryIndex)
            }
            awaitUi("network stall retains the usable decoder configuration", 15_000) {
                decoder.startsWith("c2.android.") &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
        }
    }

    @Test fun qualityTreatsAnEndedLiveBackupAsAFailureAndKeepsPlaying() {
        LocalHls(instrumentation.context.assets).use { server ->
            val pid = catalog[documentaryIndex].pid
            scenario.onActivity {
                RouteQuality.enabled = true
                val configuration = RouteSample("short-ended-stream", System.currentTimeMillis(), 500, 20,
                    960, 540, 0, 0, 0, 0, 0, "retuned", 450, avcCompat = true, softwareVideo = true)
                SP.writeRouteRecords(pid, mapOf(server.short to listOf(configuration)))
                testLines = listOf(server.short, server.healthy)
                catalog[documentaryIndex].backupOnly = true
                it.play(documentaryIndex)
            }
            awaitUi("short candidate is playing") {
                it.playbackSettings().backupPosition() == (1 to 2) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            awaitUi("finite live candidate yields to continuing source", 15_000) {
                it.playbackSettings().backupPosition() == (2 to 2) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            scenario.onActivity {
                assertTrue(SP.routeRecords(pid).getValue(server.short).any { sample -> sample.errors > 0 && !sample.stable })
            }
        }
    }

    @Test fun backupLadderStartsAtTheFirstLineAndReportsItsPosition() {
        scenario.onActivity { host ->
            val channel = browser(host).currentChannel!!
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
        key(KeyEvent.KEYCODE_4)
        awaitUi("single-digit tune", 2_500) { SP.itemPosition == 3 }
    }

    @Test fun migratesEveryLegacyPositionAndRestoresByPid() {
        val prefs = instrumentation.targetContext.getSharedPreferences("MainActivity", 0)
        val old = originals.filterNot { it.mustToken }
        assertEquals(51, old.size)
        scenario.onActivity {
            old.forEachIndexed { index, channel ->
                prefs.edit().remove("channel_pid").putInt("position", index).apply()
                assertEquals(channel.pid, catalog[SP.itemPosition].pid)
                assertFalse(prefs.contains("position"))
                assertEquals(channel.pid, prefs.getString("channel_pid", null))
            }
            for (position in listOf(null, -1, 999)) {
                prefs.edit().remove("channel_pid").remove("position").apply {
                    if (position != null) putInt("position", position)
                }.apply()
                assertEquals(0, SP.itemPosition)
            }
            prefs.edit().putString("channel_pid", "removed").apply()
            assertEquals(0, SP.itemPosition)
            prefs.edit().remove("channel_pid").putInt("position", 2).apply()
        }
        scenario.recreate()
        scenario.onActivity { assertEquals("CCTV4", browser(it).currentChannel!!.getTV().alias) }
        val phoenix = catalog.indexOfFirst { it.title == "凤凰卫视中文台" }
        scenario.onActivity { it.play(phoenix) }
        scenario.recreate()
        scenario.onActivity { assertEquals(catalog[phoenix].pid, browser(it).currentChannel!!.getTV().pid) }
    }

    @Test fun slowBackupRetriesOnceAfterPlaybackThenDecodesPhpLine() {
        LocalHls(instrumentation.context.assets).use { server ->
            scenario.onActivity { host ->
                testLines = listOf(server.broken, server.healthy)
                browser(host).currentChannel!!.addVideoUrl("file:///missing-primary.mp4")
                host.play(browser(host).currentChannel!!)
            }
            repeat(2) { attempt ->
                awaitUi("decoded broken line, attempt " + attempt, 30_000) {
                    val engine = it.findViewById<PlayerView>(R.id.player_view).player!!
                    server.firstLoads.get() == attempt + 1 && engine.isPlaying &&
                        engine.currentMediaItem?.localConfiguration?.uri.toString() == server.broken &&
                        engine.videoSize.width == 960
                }
                server.breakSegment.release()
            }
            awaitUi("decoded second line", 30_000) {
                SP.lastBackup(catalog[documentaryIndex].pid) == server.healthy &&
                    it.playbackSettings().backupPosition() == (2 to 2) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true &&
                    it.playbackSettings().videoResolution() == (960 to 540)
            }
            assertEquals(2, server.firstLoads.get())
            assertEquals(1, server.healthyLoads.get())
            supplyReady = false
            scenario.recreate()
            awaitUi("remembered second line plays on cold start") {
                it.playbackSettings().backupPosition() == (2 to 2) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            assertEquals(2, server.firstLoads.get())
            assertEquals(2, server.healthyLoads.get())
        }
    }

    @Test fun firstFrameTimeoutAndSilentRebufferAdvanceWithoutRememberingSlowLines() {
        LocalHls(instrumentation.context.assets).use { server ->
            val pid = catalog[documentaryIndex].pid
            scenario.onActivity {
                SP.rememberBackup(pid, server.silent)
                testLines = listOf(server.silent, server.stalled, server.healthy)
                it.play(documentaryIndex)
            }
            awaitUi("silent route expires and next route renders", 18_000) {
                it.playbackSettings().backupPosition() == (2 to 3) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            assertNull(SP.lastBackup(pid))
            awaitUi("post-frame buffering switches to healthy route", 25_000) {
                it.playbackSettings().backupPosition() == (3 to 3) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            assertNull(SP.lastBackup(pid))
        }
    }

    @Test fun authFailuresAreBoundedBeforeBackupPlayback() {
        val original = Request.yspApiService
        val count = java.util.concurrent.atomic.AtomicInteger()
        var status = 401
        val pending = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val idle = java.util.concurrent.CountDownLatch(1)
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            count.incrementAndGet()
            if (status == -1) throw java.net.SocketTimeoutException("fixture timeout")
            if (status == -2) {
                pending.countDown()
                release.await(5, java.util.concurrent.TimeUnit.SECONDS)
            }
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(if (status == -2) 503 else status).message("fixture").body(okhttp3.ResponseBody.create(null, "")).build()
        }.build()
        LocalHls(instrumentation.context.assets).use { server ->
            try {
                scenario.onActivity { host ->
                    Request.yspApiService = retrofit2.Retrofit.Builder().baseUrl("http://127.0.0.1/")
                        .client(client).addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                        .build().create(com.lizongying.mytv.api.YSPApiService::class.java)
                    browser(host).requestPrimary = { Request.fetchAuth(it, "") }
                    testLines = listOf(server.healthy)
                }
                for ((code, expected) in listOf(401 to 1, 403 to 1, 503 to 2, 408 to 2, -1 to 2)) {
                    scenario.onActivity { host ->
                        status = code
                        count.set(0)
                        host.play(documentaryIndex)
                    }
                    awaitUi("HTTP $code reaches backup") { it.playbackSettings().isUsingBackup() &&
                        it.findViewById<View>(R.id.tune_cover).visibility == View.GONE }
                    assertEquals(expected, count.get())
                }
                scenario.onActivity { host ->
                    testLines = emptyList()
                    status = 403
                    count.set(0)
                    host.play(catalog.indexOfFirst { it.alias.isEmpty() && it.pid.isNotEmpty() })
                }
                awaitUi("primary-only recovery keeps original retries") {
                    count.get() == 9 && !browser(it).currentChannel!!.errInfo.value.isNullOrEmpty()
                }
                scenario.onActivity { host ->
                    status = -2
                    count.set(0)
                    host.play(documentaryIndex)
                }
                assertTrue(pending.await(5, java.util.concurrent.TimeUnit.SECONDS))
                client.dispatcher().setIdleCallback { idle.countDown() }
                scenario.onActivity { host ->
                    browser(host).requestPrimary = host::play
                    host.play(documentaryIndex + 1)
                }
                release.countDown()
                assertTrue(idle.await(5, java.util.concurrent.TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(1, count.get())
            } finally {
                scenario.onActivity { Request.yspApiService = original }
            }
        }
    }

    @Test fun heldChannelKeyLimitsRetunesButFreshTapsAreImmediate() {
        scenario.onActivity { RouteQuality.enabled = true; testLines = emptyList() }
        scenario.onActivity { host ->
            val code = KeyEvent.KEYCODE_CHANNEL_DOWN
            val start = SystemClock.uptimeMillis()
            fun down(offset: Long, repeat: Int) {
                host.onKeyDown(code, KeyEvent(if (repeat == 0) start + offset else start,
                    start + offset, KeyEvent.ACTION_DOWN, code, repeat))
            }
            down(0, 0)
            down(50, 1)
            assertEquals(documentaryIndex + 1, SP.itemPosition)
            down(350, 2)
            down(650, 3)
            assertEquals(documentaryIndex + 1, SP.itemPosition)
            host.onKeyUp(code, KeyEvent(start, start + 660, KeyEvent.ACTION_UP, code, 0))
            assertEquals(documentaryIndex + 3, SP.itemPosition)
            down(675, 0)
            assertEquals(documentaryIndex + 4, SP.itemPosition)
        }
    }

    @Test fun delayedOldFrameCannotRevealRapidRetuneToSameUrl() {
        scenario.onActivity { host ->
            RouteQuality.enabled = true
            testLines = emptyList()
            val previousView = host.findViewById<PlayerView>(R.id.player_view)
            val oldSurface = (previousView.videoSurfaceView as android.view.SurfaceView).holder.surface
            val engine = previousView.player
            host.play(documentaryIndex + 1)
            host.play(documentaryIndex)
            val nextView = host.findViewById<PlayerView>(R.id.player_view)
            assertTrue(engine === nextView.player)
            assertFalse(previousView.videoSurfaceView === nextView.videoSurfaceView)
            host.playbackSettings().renderedOn(oldSurface)
            assertEquals(android.view.View.VISIBLE, host.findViewById<android.view.View>(R.id.tune_cover).visibility)
        }
        awaitUi("only the new surface reveals the picture") {
            it.findViewById<android.view.View>(R.id.tune_cover).visibility == android.view.View.GONE &&
                it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
        }
    }

    @Test fun pendingRetuneCoversOldVideoUntilNewFrame() {
        scenario.onActivity { host ->
            testLines = emptyList()
            browser(host).requestPrimary = {}
            host.play(documentaryIndex + 1)
        }
        val shutterId = R.id.tune_cover
        awaitUi("old surface covered") {
            it.findViewById<android.view.View>(shutterId).visibility == android.view.View.VISIBLE
        }
        val frame = instrumentation.uiAutomation.takeScreenshot()
        try {
            // Sample the composited picture away from the channel/time overlays.
            for (x in 3..7) for (y in 3..7) {
                val pixel = frame.getPixel(frame.width * x / 10, frame.height * y / 10)
                // The emulator compositor can quantize opaque black to RGB(1,1,1).
                assertTrue(android.graphics.Color.red(pixel) <= 1 && android.graphics.Color.green(pixel) <= 1 &&
                    android.graphics.Color.blue(pixel) <= 1)
            }
        } finally {
            frame.recycle()
        }
        scenario.onActivity { it.play(browser(it).currentChannel!!) }
        awaitUi("new video revealed") {
            it.findViewById<android.view.View>(shutterId).visibility != android.view.View.VISIBLE &&
                it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
        }
    }

    @Test fun soleSlowPrimaryCanFinishLoadingAfterTheDeadline() {
        scenario.onActivity { RouteQuality.enabled = true }
        scenario.onActivity { host ->
            testLines = emptyList()
            browser(host).requestPrimary = { channel ->
                host.window.decorView.postDelayed({ host.play(channel) }, 9_000)
            }
            host.play(documentaryIndex)
        }
        awaitUi("only route recovers without being restarted", 14_000) {
            it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true &&
                it.playbackSettings().backupPosition() == null
        }
    }

    @Test fun retuningBeforeTimeoutLeavesNewChannelPlaying() {
        LocalHls(instrumentation.context.assets).use { server ->
            scenario.onActivity { host ->
                testLines = listOf(server.silent)
                SP.rememberBackup(catalog[documentaryIndex].pid, server.silent)
                host.play(documentaryIndex)
                assertEquals(1 to 1, host.playbackSettings().backupPosition())
                host.play(documentaryIndex + 1)
            }
            SystemClock.sleep(9_000)
            scenario.onActivity { host ->
                assertEquals(catalog[documentaryIndex + 1].pid, browser(host).currentChannel!!.getTV().pid)
                assertNull(host.playbackSettings().backupPosition())
                assertTrue(host.findViewById<PlayerView>(R.id.player_view).player!!.isPlaying)
            }
        }
    }

    @Test fun expiredLiveWindowGetsOneRecoveryBeforeAdvancing() {
        LocalHls(instrumentation.context.assets).use { server ->
            scenario.onActivity {
                testLines = listOf(server.healthy, server.stalled)
                SP.rememberBackup(catalog[documentaryIndex].pid, server.healthy)
                it.play(documentaryIndex)
            }
            awaitUi("healthy route playing") {
                it.playbackSettings().backupPosition() == (1 to 2) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            scenario.onActivity {
                val error = androidx.media3.common.PlaybackException("expired", null,
                    androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
                val player = it.playbackSettings()
                player.onPlayerError(error)
                assertEquals(1 to 2, player.backupPosition())
                player.onPlayerError(error)
                assertEquals(2 to 2, player.backupPosition())
            }
        }
    }

    @Test fun failedRememberedLineFallsBackAndAnObsoleteLineIsIgnored() {
        LocalHls(instrumentation.context.assets).use { server ->
            val pid = catalog[documentaryIndex].pid
            scenario.onActivity {
                testLines = listOf(server.healthy, server.invalid)
                SP.rememberBackup(pid, server.invalid)
                it.play(documentaryIndex)
                assertEquals(2 to 2, it.playbackSettings().backupPosition())
            }
            awaitUi("remaining line becomes preferred", 30_000) {
                SP.lastBackup(pid) == server.healthy && it.playbackSettings().backupPosition() == (1 to 2)
            }
            scenario.onActivity {
                testLines = listOf(server.invalid)
                it.play(documentaryIndex)
                assertNull(it.playbackSettings().backupPosition())
            }
            awaitUi("removed route replaced by primary") { SP.lastBackup(pid) == null }
            scenario.onActivity {
                SP.rememberBackup(pid, server.invalid)
                it.play(documentaryIndex)
                assertEquals(1 to 1, it.playbackSettings().backupPosition())
            }
            awaitUi("exhausted remembered routes fall back to primary", 30_000) {
                it.playbackSettings().backupPosition() == null &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true &&
                    SP.lastBackup(pid) == null
            }
        }
    }

    @Test fun backupOnlyColdStartAndExhaustion() {
        LocalHls(instrumentation.context.assets).use { server ->
            val target = originals.indexOfFirst { it.backupOnly }
            scenario.onActivity {
                catalog[target].backupOnly = true
                testLines = listOf(server.healthy)
                SP.itemPosition = target
                supplyReady = false
            }
            scenario.recreate()
            awaitUi("backup plays before initialization") {
                it.playbackSettings().backupPosition() == (1 to 1) &&
                    it.findViewById<PlayerView>(R.id.player_view).player?.isPlaying == true
            }
            scenario.onActivity { it.onRequestFinished(null) }
            instrumentation.waitForIdleSync()
            assertEquals(1, server.healthyLoads.get())
            val errorsAt = mutableListOf<Pair<Int, Int>?>()
            scenario.onActivity { activity ->
                browser(activity).currentChannel!!.errInfo.observe(activity) { error ->
                    if (!error.isNullOrEmpty()) errorsAt.add(activity.playbackSettings().backupPosition())
                }
                testLines = listOf(server.invalid, server.invalid)
                activity.play(target)
            }
            awaitUi("exhausted both lines", 30_000) {
                it.playbackSettings().backupPosition() == (2 to 2) &&
                    it.supportFragmentManager.findFragmentByTag("ErrorFragment")?.isHidden == false
            }
            scenario.onActivity { assertEquals(listOf(2 to 2), errorsAt) }
        }
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
        testLines = listOf(File(directory, "master.m3u8").toURI().toString())
        catalog[documentaryIndex].backupOnly = true
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
                val dialog = choice?.dialog
                val options = choice?.view?.findViewById<android.view.ViewGroup>(R.id.options)
                dialog?.window?.decorView?.hasWindowFocus() == true &&
                    options?.getChildAt(selectedPosition)?.isFocused == true &&
                    ArrayList<android.view.View>().apply {
                        dialog.window!!.decorView.findViewsWithText(this, "1/1", android.view.View.FIND_VIEWS_WITH_TEXT)
                    }.isNotEmpty()
            }
        }
        openResolutionDialog(0)
        capture("resolution-on-tv")
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
