package com.lizongying.mytv

import android.content.Context
import android.content.SharedPreferences
import com.lizongying.mytv.models.TVList

object SP {
    // Name of the sp file TODO Should use a meaningful name and do migrations
    private const val SP_FILE_NAME = "MainActivity"

    // If Change channel with up and down in reversed order or not
    private const val KEY_CHANNEL_REVERSAL = "channel_reversal"

    // If use channel num to select channel or not
    private const val KEY_CHANNEL_NUM = "channel_num"

    const val KEY_TIME = "time"

    // If start app on device boot or not
    private const val KEY_BOOT_STARTUP = "boot_startup"

    const val KEY_GRID = "grid"

    // Position in list of the selected channel item
    private const val KEY_POSITION = "position"

    // guid
    private const val KEY_GUID = "guid"

    private lateinit var sp: SharedPreferences

    private lateinit var playbackRoutes: SharedPreferences

    private lateinit var routeQuality: SharedPreferences

    private var listener: OnSharedPreferenceChangeListener? = null

    /**
     * The method must be invoked as early as possible(At least before using the keys)
     */
    fun init(context: Context) {
        sp = context.getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE)
        playbackRoutes = context.getSharedPreferences("stable_playback_routes", Context.MODE_PRIVATE)
        routeQuality = context.getSharedPreferences("route_quality_v1", Context.MODE_PRIVATE)
    }

    fun setOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener) {
        this.listener = listener
    }

    fun clearListener(owner: OnSharedPreferenceChangeListener) {
        if (listener === owner) listener = null
    }

    fun lastBackup(pid: String): String? = playbackRoutes.getString(pid, null)

    fun rememberBackup(pid: String, url: String?) {
        if (pid.isNotEmpty() && lastBackup(pid) != url) playbackRoutes.edit().putString(pid, url).apply()
    }

    internal fun routeRecords(pid: String): Map<String, List<RouteSample>> {
        val type = object : com.google.gson.reflect.TypeToken<Map<String, List<RouteSample>>>() {}.type
        return runCatching {
            com.google.gson.Gson().fromJson<Map<String, List<RouteSample>>>(routeQuality.getString(pid, "{}"), type)
        }.getOrNull() ?: emptyMap()
    }

    internal fun saveRoute(pid: String, route: String, sample: RouteSample) {
        if (!RouteQuality.enabled || pid.isEmpty()) return
        val records = routeRecords(pid).mapValues { (_, samples) -> RouteQuality.recent(samples, sample.at) }.toMutableMap()
        records[route] = (records[route].orEmpty().filter { it.id != sample.id } + sample).takeLast(8)
        writeRouteRecords(pid, records)
    }

    internal fun writeRouteRecords(pid: String, records: Map<String, List<RouteSample>>) {
        routeQuality.edit().putString(pid, com.google.gson.Gson().toJson(records)).apply()
    }

    // Wait for pending apply() writes only after playback stops.
    internal fun flushRouteRecords(): Boolean = routeQuality.edit().commit()

    fun resetSettings() {
        playbackRoutes.edit().clear().apply()
        routeQuality.edit().clear().apply()
        sp.edit().remove(KEY_CHANNEL_REVERSAL).remove(KEY_CHANNEL_NUM).remove(KEY_TIME)
            .remove(KEY_GRID).remove(KEY_BOOT_STARTUP).remove(KEY_GUID).apply()
        listener?.onSharedPreferenceChanged(KEY_GRID)
        listener?.onSharedPreferenceChanged(KEY_TIME)
    }

    var channelReversal: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_REVERSAL, false)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_REVERSAL, value).apply()

    var channelNum: Boolean
        get() = sp.getBoolean(KEY_CHANNEL_NUM, true)
        set(value) = sp.edit().putBoolean(KEY_CHANNEL_NUM, value).apply()

    var time: Boolean
        get() = sp.getBoolean(KEY_TIME, true)
        set(value) {
            if (value != this.time) {
                sp.edit().putBoolean(KEY_TIME, value).apply()
                listener?.onSharedPreferenceChanged(KEY_TIME)
            }
        }

    var bootStartup: Boolean
        get() = sp.getBoolean(KEY_BOOT_STARTUP, false)
        set(value) = sp.edit().putBoolean(KEY_BOOT_STARTUP, value).apply()

    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, false)
        set(value) {
            if (value != this.grid) {
                sp.edit().putBoolean(KEY_GRID, value).apply()
                listener?.onSharedPreferenceChanged(KEY_GRID)
            }
        }

    private const val KEY_CHANNEL_PID = "channel_pid"

    // Frozen order from the last 51-channel release (5bd8c3f).
    private val legacyPids = listOf(
        "600001859", "600001800", "600001814", "600001818",
        "600004092", "600004078", "600001805", "600001806",
        "600001807", "600001811", "600001809", "600001815",
        "600098637", "600001810", "600001817", "600002264",
        "600156816", "600002483", "600002475", "600002508",
        "600002505", "600002521", "600002503", "600002513",
        "600002485", "600002509", "600002531", "600002525",
        "600002493", "600002490", "600002309", "600002498",
        "600002520", "600002532", "600002481", "600002516",
        "600002484", "600002506", "600152137", "600152138",
        "600170344", "600171827", "7c96b084-60e1-40a9-89c5-682b994fb680", "f7f48462-9b13-485b-8101-7b54716411ec",
        "15e02d92-1698-416c-af2f-3e9a872b4d78", "600014550", "600084704", "600084758",
        "600084782", "600084744", "600084781",
    )
    private val catalog by lazy { TVList.list.values.flatten() }

    var itemPosition: Int
        get() {
            val saved = sp.getString(KEY_CHANNEL_PID, null)
            val pid = saved ?: legacyPids.getOrNull(sp.getInt(KEY_POSITION, 0))
            val position = catalog.indexOfFirst { it.pid == pid }.coerceAtLeast(0)
            if (saved != catalog[position].pid) itemPosition = position
            return position
        }
        set(value) {
            val channel = catalog.getOrNull(value) ?: return
            sp.edit().putString(KEY_CHANNEL_PID, channel.pid).remove(KEY_POSITION).apply()
        }

    var guid: String
        get() = sp.getString(KEY_GUID, "") ?: ""
        set(value) = sp.edit().putString(KEY_GUID, value).apply()
}
