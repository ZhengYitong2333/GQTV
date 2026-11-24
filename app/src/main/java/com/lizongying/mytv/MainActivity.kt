package com.lizongying.mytv

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.Keep
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.lizongying.mytv.models.TVViewModel
import com.lizongying.mytv.requests.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity(), Request.RequestListener, OnSharedPreferenceChangeListener {
    private lateinit var player: PlayerFragment
    private lateinit var errors: ErrorFragment
    private lateinit var clock: TimeFragment
    private lateinit var info: InfoFragment
    private lateinit var numbers: ChannelFragment
    private lateinit var browser: MainFragment
    private lateinit var gestures: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private val ready = mutableSetOf<String>()
    private var playbackStarted = false
    private var resumed = false
    private var lastBack = -2_000L
    private val hideBrowser = Runnable { closeBrowser() }

    private inline fun <reified T : Fragment> restoredOr(create: () -> T): T =
        supportFragmentManager.fragments.filterIsInstance<T>().firstOrNull() ?: create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        setContentView(R.layout.activity_main)
        Utils.setRequestListener(this)
        Request.setRequestListener(this)
        player = restoredOr(::PlayerFragment)
        errors = restoredOr(::ErrorFragment)
        clock = restoredOr(::TimeFragment)
        info = restoredOr(::InfoFragment)
        numbers = restoredOr(::ChannelFragment)
        browser = restoredOr(::MainFragment)
        if (!browser.isAdded) {
            val tx = supportFragmentManager.beginTransaction()
            for (fragment in listOf(player, errors, clock, info, numbers, browser)) {
                tx.add(R.id.main_browse_fragment, fragment, fragment.javaClass.simpleName)
            }
            tx.hide(browser).hide(errors)
            if (!SP.time) tx.hide(clock)
            tx.commitNow()
        }
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { openBrowser(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean { openSettings(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (browser.isHidden && velocityY != 0f) browser.step(if (velocityY > 0) -1 else 1)
                return true
            }
        })
        SP.setOnSharedPreferenceChangeListener(this)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event) || super.onTouchEvent(event)

    fun syncTime() { lifecycleScope.launch(Dispatchers.IO) { Utils.init() } }

    fun fragmentReady(tag: String) {
        ready.add(tag)
        startIfReady()
    }

    private fun startIfReady() {
        if (resumed && !playbackStarted && ready.containsAll(requiredParts) &&
            ("request" in ready || browser.currentChannel?.let { it.getTV().backupOnly || it.getTV().pid.isEmpty() || player.hasRememberedBackup(it) } == true)) {
            playbackStarted = true
            browser.fragmentReady()
        }
    }

    override fun onRequestFinished(message: String?) { fragmentReady("request") }

    // Called by the upstream JNI library during player initialization.
    @Keep
    private fun getAppSignature() = appSignature

    fun showInfoFragment(channel: TVViewModel) {
        if (!browser.isHidden || supportFragmentManager.findFragmentByTag(SettingFragment.TAG) != null) return
        info.show(channel)
        if (SP.channelNum) numbers.show(channel)
    }

    fun setPlaybackError(message: String) {
        if (supportFragmentManager.isStateSaved) return
        errors.show(message)
        val tx = supportFragmentManager.beginTransaction()
        if (message.isEmpty()) tx.hide(errors).show(player) else tx.hide(player).show(errors)
        tx.commit()
    }

    fun selectChannel(channel: TVViewModel, userTune: Boolean) = player.select(channel, userTune)
    fun play(channel: TVViewModel) = player.play(channel)
    fun tryBackup(channel: TVViewModel, beforePrimary: Boolean = false) = player.tryBackup(channel, beforePrimary)
    internal fun playbackSettings() = player
    fun play(position: Int) = browser.play(position)

    private fun openBrowser() {
        if (supportFragmentManager.isStateSaved || !browser.isHidden) return
        info.hide()
        numbers.clear()
        supportFragmentManager.beginTransaction().show(browser).commitNow()
        mainActive()
    }

    fun closeBrowser() {
        handler.removeCallbacks(hideBrowser)
        if (!supportFragmentManager.isStateSaved && !browser.isHidden) {
            supportFragmentManager.beginTransaction().hide(browser).commit()
        }
    }

    fun mainActive() {
        handler.removeCallbacks(hideBrowser)
        if (resumed && !browser.isHidden) handler.postDelayed(hideBrowser, 10_000)
    }

    private fun openSettings() {
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(SettingFragment.TAG) != null) return
        closeBrowser()
        info.hide()
        numbers.clear()
        SettingFragment().showNow(fm, SettingFragment.TAG)
    }

    private fun backFromPlayback() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBack < 2_000) finish()
        else Toast.makeText(this, R.string.press_back_again, Toast.LENGTH_SHORT).show()
        lastBack = now
    }

    override fun onBackPressed() {
        if (browser.isHidden) backFromPlayback() else closeBrowser()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (::browser.isInitialized && !browser.isHidden) mainActive()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        handleRemote(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        handleRemote(event) || super.onKeyUp(keyCode, event)

    private fun handleRemote(event: KeyEvent): Boolean {
        if (!::browser.isInitialized) return false
        val code = event.keyCode
        val onRelease = event.action == KeyEvent.ACTION_UP && !event.isCanceled
        if (code == KeyEvent.KEYCODE_MENU || code == KeyEvent.KEYCODE_SETTINGS ||
            code == KeyEvent.KEYCODE_HELP || code == KeyEvent.KEYCODE_BOOKMARK) {
            if (onRelease) openSettings()
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (onRelease) onBackPressed()
            return true
        }
        if (!browser.isHidden) {
            // Digits are a jump-to-channel shortcut while browsing; elsewhere
            // they open the number overlay and tune directly.
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                if (onRelease) browser.typeDigit((code - KeyEvent.KEYCODE_0).toString())
                return true
            }
            return false
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_DPAD_LEFT -> if (onRelease) openBrowser()
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (onRelease) openSettings()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP ->
                if (event.action == KeyEvent.ACTION_DOWN) browser.step(if (SP.channelReversal) 1 else -1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN ->
                if (event.action == KeyEvent.ACTION_DOWN) browser.step(if (SP.channelReversal) -1 else 1)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                if (onRelease && SP.channelNum) numbers.show((code - KeyEvent.KEYCODE_0).toString())
            else -> return false
        }
        return true
    }

    override fun onSharedPreferenceChanged(key: String) {
        if (key == SP.KEY_GRID) browser.changeMenu()
        if (key == SP.KEY_TIME && !supportFragmentManager.isStateSaved) {
            val tx = supportFragmentManager.beginTransaction()
            if (SP.time) tx.show(clock) else tx.hide(clock)
            tx.commit()
        }
    }

    override fun onStart() { super.onStart(); syncTime() }

    override fun onResumeFragments() {
        super.onResumeFragments()
        resumed = true
        startIfReady()
        if (!browser.isHidden && supportFragmentManager.findFragmentByTag(SettingFragment.TAG) == null) {
            browser.focusCurrent()
            mainActive()
        }
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(hideBrowser)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideBrowser)
        SP.clearListener(this)
        Utils.clearListener(this)
        Request.clearListener(this)
        super.onDestroy()
    }

    private companion object {
        val requiredParts = setOf("PlayerFragment", "ErrorFragment", "TimeFragment", "InfoFragment",
            "ChannelFragment", MainFragment.TAG)
    }
}
