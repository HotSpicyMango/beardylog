package com.hsm.beardylog

import android.content.Context
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.ConcurrentHashMap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

internal class KoreanHolidayRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val memoryCache = ConcurrentHashMap<YearMonth, Map<LocalDate, String>>()

    val isConfigured: Boolean
        get() = BuildConfig.KOREA_HOLIDAY_API_KEY.isNotBlank()

    fun cached(month: YearMonth): Map<LocalDate, String> = memoryCache.getOrPut(month) {
        preferences.getString(cacheKey(month), null)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val parts = line.split("|", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                runCatching { LocalDate.parse(parts[0]) to parts[1] }.getOrNull()
            }
            ?.toMap()
            .orEmpty()
    }

    fun fetch(month: YearMonth): Map<LocalDate, String> = runCatching {
        val configuredKey = BuildConfig.KOREA_HOLIDAY_API_KEY
        val serviceKey = if (Regex("%[0-9A-Fa-f]{2}").containsMatchIn(configuredKey)) {
            configuredKey
        } else {
            URLEncoder.encode(configuredKey, "UTF-8")
        }
        val url = URL(
            "https://apis.data.go.kr/B090041/openapi/service/SpcdeInfoService/getRestDeInfo" +
                "?serviceKey=$serviceKey&solYear=${month.year}&solMonth=${"%02d".format(month.monthValue)}&numOfRows=100&pageNo=1"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 5000
        }
        try {
            connection.inputStream.use { stream ->
                val parser = XmlPullParserFactory.newInstance().newPullParser().apply {
                    setInput(stream, "UTF-8")
                }
                parseHolidays(parser)
            }
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(emptyMap()).also { holidays ->
        if (holidays.isNotEmpty()) {
            memoryCache[month] = holidays
            preferences.edit()
                .putString(cacheKey(month), holidays.entries.joinToString("\n") { "${it.key}|${it.value}" })
                .apply()
        }
    }

    private fun parseHolidays(parser: XmlPullParser): Map<LocalDate, String> {
        val holidays = linkedMapOf<LocalDate, String>()
        var event = parser.eventType
        var currentTag = ""
        var dateName = ""
        var locdate = ""
        var isHoliday = ""
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    if (currentTag == "item") {
                        dateName = ""
                        locdate = ""
                        isHoliday = ""
                    }
                }
                XmlPullParser.TEXT -> when (currentTag) {
                    "dateName" -> dateName = parser.text.orEmpty()
                    "locdate" -> locdate = parser.text.orEmpty()
                    "isHoliday" -> isHoliday = parser.text.orEmpty()
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" && isHoliday == "Y" && locdate.length == 8) {
                        val date = LocalDate.of(
                            locdate.substring(0, 4).toInt(),
                            locdate.substring(4, 6).toInt(),
                            locdate.substring(6, 8).toInt()
                        )
                        holidays[date] = dateName
                    }
                    currentTag = ""
                }
            }
            event = parser.next()
        }
        return holidays
    }

    private fun cacheKey(month: YearMonth): String = "${month.year}_${"%02d".format(month.monthValue)}"

    private companion object {
        const val PREFERENCES_NAME = "korea_holidays"
    }
}
