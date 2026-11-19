package com.example.sdide.desktop

import com.example.sdide.data.SdLibraryPackage
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object PublicGithubSdLibraryClient {
  private const val CONNECT_TIMEOUT_MILLIS = 10_000
  private const val READ_TIMEOUT_MILLIS = 30_000
  private const val MAX_REDIRECTS = 3
  private val redirectHosts = setOf(
    "github.com",
    "release-assets.githubusercontent.com",
    "objects.githubusercontent.com",
  )

  fun validateReleaseAssetUri(input: String): URI {
    val uri = try {
      URI(input.trim())
    } catch (e: URISyntaxException) {
      throw IllegalArgumentException("Đường dẫn GitHub không hợp lệ.", e)
    }
    require(uri.scheme?.equals("https", ignoreCase = true) == true) {
      "Chỉ hỗ trợ liên kết HTTPS."
    }
    require(uri.userInfo == null && (uri.port == -1 || uri.port == 443)) {
      "Liên kết không được chứa thông tin đăng nhập hoặc cổng tùy chỉnh."
    }
    require(uri.host?.equals("github.com", ignoreCase = true) == true) {
      "Chỉ hỗ trợ release asset công khai từ github.com."
    }
    val path = uri.path.orEmpty()
    require(RELEASE_ASSET_PATH.matches(path) && path.endsWith(".sdlib", ignoreCase = true)) {
      "Hãy nhập URL tệp .sdlib trong mục GitHub Releases."
    }
    return uri
  }

  fun downloadReleaseAsset(uri: URI): ByteArray {
    validateReleaseAssetUri(uri.toString())
    var current = uri
    repeat(MAX_REDIRECTS + 1) { redirectCount ->
      val connection = current.toURL().openConnection() as? HttpURLConnection
        ?: throw IOException("Không thể tạo kết nối HTTPS tới GitHub.")
      try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/octet-stream")
        connection.setRequestProperty("User-Agent", "SD-SDPP-Desktop")
        val statusCode = connection.responseCode
        if (statusCode in 300..399) {
          if (redirectCount == MAX_REDIRECTS) {
            throw IOException("GitHub chuyển hướng quá nhiều lần.")
          }
          val location = connection.getHeaderField("Location")
            ?: throw IOException("GitHub trả về chuyển hướng không có đích.")
          val destination = current.resolve(location)
          requireRedirectDestination(destination)
          current = destination
          return@repeat
        }
        if (statusCode != HttpURLConnection.HTTP_OK) {
          throw IOException("GitHub trả về HTTP $statusCode khi tải thư viện.")
        }
        requireRedirectDestination(current)
        if (connection.contentLengthLong > SdLibraryPackage.MAX_PACKAGE_BYTES) {
          throw IOException("Gói thư viện vượt quá giới hạn 8 MiB.")
        }
        return connection.inputStream.use(SdLibraryPackage::readPackageBytes)
      } finally {
        connection.disconnect()
      }
    }
    throw IOException("Không nhận được tệp thư viện từ GitHub.")
  }

  private fun requireRedirectDestination(uri: URI) {
    val host = uri.host?.lowercase(Locale.ROOT)
    require(uri.scheme?.equals("https", ignoreCase = true) == true &&
      host != null && host in redirectHosts &&
      uri.userInfo == null &&
      (uri.port == -1 || uri.port == 443)
    ) {
      "GitHub chuyển hướng tới máy chủ không được phép."
    }
  }

  private val RELEASE_ASSET_PATH =
    Regex("""/[^/]+/[^/]+/releases/download/[^/]+/[^/]+\.sdlib""", RegexOption.IGNORE_CASE)
}
