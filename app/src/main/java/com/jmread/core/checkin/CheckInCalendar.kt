package com.jmread.core.checkin

import com.jmread.network.JmDailyDay

/**
 * 签到月历判据（纯函数，JVM 单测覆盖）。
 *
 * 月历矩阵本来就会包含“今天”这一格：未签到时 signed=false。
 * 因此只比较日期会把每次签到都判成“今日已签到”，/daily_chk 永远不会发出。
 */
internal fun isSignedOn(record: List<List<JmDailyDay>>, todayDayOfMonth: String): Boolean {
    val today = todayDayOfMonth.padStart(2, '0')
    return record.any { week ->
        week.any { day -> day.date.padStart(2, '0') == today && day.signed }
    }
}
