package com.example.sdide.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicGithubSdLibraryClientTest {
  @Test
  fun acceptsOnlyPublicHttpsGithubReleasePackages() {
    val uri = PublicGithubSdLibraryClient.validateReleaseAssetUri(
      "https://github.com/acme/sd-libs/releases/download/v1.2.0/mathplus.sdlib",
    )

    assertEquals("github.com", uri.host)
    assertEquals("https", uri.scheme)
  }

  @Test
  fun rejectsNonHttpsAndNonReleaseUrls() {
    listOf(
      "http://github.com/acme/sd-libs/releases/download/v1/math.sdlib",
      "https://example.com/acme/sd-libs/releases/download/v1/math.sdlib",
      "https://github.com/acme/sd-libs/archive/refs/heads/main.zip",
      "https://github.com/acme/sd-libs/releases/download/v1/tool.exe",
      "https://user@github.com/acme/sd-libs/releases/download/v1/math.sdlib",
    ).forEach { url ->
      assertThrows(IllegalArgumentException::class.java) {
        PublicGithubSdLibraryClient.validateReleaseAssetUri(url)
      }
    }
  }
}
