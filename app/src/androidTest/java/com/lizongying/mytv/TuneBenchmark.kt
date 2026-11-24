package com.lizongying.mytv

import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.lizongying.mytv.models.TVList
import com.lizongying.mytv.requests.Request
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live-network benchmark. Each invocation measures one activity launch and four subsequent tunes. */
class TuneBenchmark {
    @Test fun measure() {
        val args = InstrumentationRegistry.getArguments()
        val policy = args.getString("policy")
        assumeTrue(policy == "old" || policy == "new")
        val channels = listOf(1, 2, 4, 5, 8, 9)
        val round = args.getString("round")!!.toInt()
        val catalog = TVList.list.values.flatten()
        val oldPosition = SP.itemPosition
        val remembered = channels.associate { catalog[it - 1].pid.let { pid -> pid to SP.lastBackup(pid) } }
        val batch = "${round}_$policy"
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            Request.legacyAuthRetries = policy == "old"
            remembered.keys.forEach { SP.rememberBackup(it, null) }
            for (offset in 0 until 5) {
                val number = channels[(round + offset) % channels.size]
                val mode = if (offset == 0) "cold" else "warm"
                Log.i("TuneBench", "begin batch=$batch channel=CCTV$number mode=$mode")
                val start = SystemClock.elapsedRealtime()
                if (offset == 0) {
                    SP.itemPosition = number - 1
                    scenario = ActivityScenario.launch(MainActivity::class.java)
                } else scenario!!.onActivity { it.play(number - 1) }
                var shown = false
                while (!shown && SystemClock.elapsedRealtime() - start < 25_000) {
                    scenario!!.onActivity { host ->
                        val browser = host.supportFragmentManager.findFragmentByTag(MainFragment.TAG) as MainFragment
                        shown = browser.currentChannel?.getTV()?.alias == "CCTV$number" &&
                            host.findViewById<View>(R.id.tune_cover)?.visibility == View.GONE
                    }
                    if (!shown) SystemClock.sleep(50)
                }
                val elapsed = SystemClock.elapsedRealtime() - start
                if (shown) SystemClock.sleep(30_600)
                Log.i("TuneBench", "sample batch=$batch channel=CCTV$number mode=$mode shown=$shown waitMs=$elapsed")
            }
        } finally {
            scenario?.close()
            Request.legacyAuthRetries = false
            remembered.forEach { (pid, url) -> SP.rememberBackup(pid, url) }
            SP.itemPosition = oldPosition
        }
    }
}
