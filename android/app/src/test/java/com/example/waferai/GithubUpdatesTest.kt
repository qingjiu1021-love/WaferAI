package com.example.waferai

import com.example.waferai.data.account.GithubUpdates
import org.junit.Assert.*
import org.junit.Test

class GithubUpdatesTest {
    private val fixture = """{"tag_name":"v4.3.0","draft":false,"prerelease":false,
        "html_url":"https://github.com/qingjiu1021-love/WaferAI/releases/tag/v4.3.0",
        "body":"Account updates\n<!-- waferai-version-code:34 -->\n<!-- waferai-apk-sha256:${"a".repeat(64)} -->",
        "assets":[{"name":"WaferAI-4.3.0.apk","size":123,"browser_download_url":"https://github.com/qingjiu1021-love/WaferAI/releases/download/v4.3.0/WaferAI-4.3.0.apk"}]}"""

    @Test fun comparesInstalledVersionAndIgnoresMachineMetadataInNotes() {
        assertTrue(GithubUpdates.parse(fixture, 33).newer)
        assertFalse(GithubUpdates.parse(fixture, 34).newer)
        assertFalse(GithubUpdates.parse(fixture, 35).newer)
        assertEquals("Account updates", GithubUpdates.parse(fixture, 33).notes)
    }
    @Test fun rejectsForeignDownloadsMissingAssetsAndUnpublishedReleases() {
        for (invalid in listOf(fixture.replace("\"draft\":false", "\"draft\":true"), fixture.replace("\"prerelease\":false", "\"prerelease\":true"),
            fixture.replace("github.com/qingjiu1021-love/WaferAI/releases/download", "evil.example/WaferAI/releases/download"),
            fixture.replace("WaferAI-4.3.0.apk", "other.apk"), fixture.replace("waferai-version-code:34", "missing-version"),
            fixture.replace("waferai-apk-sha256:", "missing-hash:"))) {
            assertThrows(Exception::class.java) { GithubUpdates.parse(invalid, 33) }
        }
    }
}
