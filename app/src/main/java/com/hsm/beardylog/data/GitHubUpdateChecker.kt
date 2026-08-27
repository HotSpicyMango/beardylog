package com.hsm.beardylog.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** GitHub Releases를 앱 업데이트 소스로 쓴다. 공개 저장소라 로그인/토큰 없이 REST API만으로 확인할 수 있어서,
 *  기존 Firebase App Distribution(테스터 로그인 필요)보다 오픈 베타에 훨씬 가볍게 맞는다. */
class GitHubUpdateChecker(
    private val owner: String = REPO_OWNER,
    private val repo: String = REPO_NAME
) {
    data class ReleaseInfo(
        val versionName: String,
        val notes: String,
        val downloadUrl: String,
        val assetName: String
    )

    /** 베타 채널을 지원해야 해서, 프리릴리즈/드래프트를 아예 제외하는 /releases/latest 대신
     *  전체 목록(/releases, 최신순)에서 채널에 맞는 첫 항목을 직접 찾는다.
     *  @param includePrereleases 베타 채널이면 true — 프리릴리즈도 업데이트 대상에 포함한다. */
    fun fetchLatestRelease(includePrereleases: Boolean): ReleaseInfo? {
        val json = request("https://api.github.com/repos/$owner/$repo/releases?per_page=$RELEASE_LIST_PAGE_SIZE")
        val releases = JSONArray(json)
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue // 드래프트는 공개 API로는 안 보이지만 혹시 몰라 방어
            if (release.optBoolean("prerelease") && !includePrereleases) continue // 안정 채널은 프리릴리즈 건너뜀
            val info = release.toReleaseInfoOrNull() ?: continue
            return info
        }
        return null
    }

    /** 이 릴리즈에 .apk 에셋이 없으면(아직 빌드 첨부 전 등) null. 최신 릴리즈부터 훑다가 건너뛸 수 있게 한다. */
    private fun JSONObject.toReleaseInfoOrNull(): ReleaseInfo? {
        val tagName = optString("tag_name")
        if (tagName.isBlank()) return null
        val assets = optJSONArray("assets") ?: return null
        var downloadUrl: String? = null
        var assetName: String? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val name = asset.optString("name")
            if (name.endsWith(".apk", ignoreCase = true)) {
                downloadUrl = asset.optString("browser_download_url").takeIf { it.isNotBlank() }
                assetName = name
                break
            }
        }
        if (downloadUrl == null || assetName == null) return null
        return ReleaseInfo(
            versionName = tagName.removePrefix("v"),
            notes = optString("body").trim(),
            downloadUrl = downloadUrl,
            assetName = assetName
        )
    }

    private fun request(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            val responseCode = connection.responseCode
            val bytes = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (responseCode !in 200..299) {
                throw GitHubUpdateException("GitHub 업데이트 확인 요청에 실패했습니다 ($responseCode)", responseCode)
            }
            return String(bytes, StandardCharsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    class GitHubUpdateException(message: String, val responseCode: Int) : Exception(message)

    companion object {
        private const val REPO_OWNER = "HotSpicyMango"
        private const val REPO_NAME = "beardylog"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
        // 최신 몇 개만 보면 되니 굳이 페이지네이션 없이 한 번에 넉넉히 받아온다.
        private const val RELEASE_LIST_PAGE_SIZE = 5

        /** "1.2.3" 형태의 버전 문자열을 숫자 단위로 비교한다. "-beta" 같은 접미사는 비교에서 제외. */
        fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
            val remote = remoteVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val current = currentVersion.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val length = maxOf(remote.size, current.size)
            for (index in 0 until length) {
                val remotePart = remote.getOrElse(index) { 0 }
                val currentPart = current.getOrElse(index) { 0 }
                if (remotePart != currentPart) return remotePart > currentPart
            }
            return false
        }
    }
}
