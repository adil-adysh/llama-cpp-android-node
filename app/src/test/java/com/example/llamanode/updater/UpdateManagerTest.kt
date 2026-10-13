package com.example.llamanode.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {

    private val sampleReleaseJson = """
        {
          "tag_name": "v0.3.0-b11429",
          "name": "llama.cpp Android Node - v0.3.0-b11429",
          "body": "Automated release update",
          "assets": [
            {
              "name": "llama-rpc-server-android-arm64-vulkan-v0.3.0-b11429.tar.gz",
              "browser_download_url": "https://github.com/adil-adysh/llama-cpp-android-node/releases/download/v0.3.0-b11429/llama-rpc-server-android-arm64-vulkan-v0.3.0-b11429.tar.gz"
            },
            {
              "name": "llama-cpp-android-node-v0.3.0-b11429.apk",
              "browser_download_url": "https://github.com/adil-adysh/llama-cpp-android-node/releases/download/v0.3.0-b11429/llama-cpp-android-node-v0.3.0-b11429.apk"
            },
            {
              "name": "checksums.txt",
              "browser_download_url": "https://github.com/adil-adysh/llama-cpp-android-node/releases/download/v0.3.0-b11429/checksums.txt"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun testParseReleaseJson() {
        val info = UpdateManager.parseReleaseJson(sampleReleaseJson, "0.2.0")
        assertEquals("v0.3.0-b11429", info.tagName)
        assertEquals("llama.cpp Android Node - v0.3.0-b11429", info.releaseName)
        assertEquals("llama-cpp-android-node-v0.3.0-b11429.apk", info.apkFileName)
        assertEquals(
            "https://github.com/adil-adysh/llama-cpp-android-node/releases/download/v0.3.0-b11429/llama-cpp-android-node-v0.3.0-b11429.apk",
            info.apkDownloadUrl
        )
        assertTrue(info.isNewer)
    }

    @Test
    fun testIsTagNewerThan() {
        assertTrue(UpdateManager.isTagNewerThan("v0.3.0-b11429", "0.2.0"))
        assertTrue(UpdateManager.isTagNewerThan("v0.2.1-b11429", "v0.2.0-b11429"))
        assertFalse(UpdateManager.isTagNewerThan("v0.2.0-b11429", "0.2.0-b11429"))
        assertFalse(UpdateManager.isTagNewerThan("v0.2.0-b11429", "v0.2.0-b11429"))
        assertFalse(UpdateManager.isTagNewerThan("", "0.2.0"))
        assertFalse(UpdateManager.isTagNewerThan("unknown", "0.2.0"))
    }
}
