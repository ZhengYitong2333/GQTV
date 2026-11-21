package com.lizongying.mytv.models


data class EPG(
    val title: String,
    val beginTime: Int,
)

/** Index of the last program that has started in a schedule sorted by start time. */
fun List<EPG>.currentProgramIndex(nowSeconds: Long): Int {
    var low = 0
    var high = size
    while (low < high) {
        val mid = low + (high - low) / 2
        if (this[mid].beginTime <= nowSeconds) low = mid + 1 else high = mid
    }
    return low - 1
}
