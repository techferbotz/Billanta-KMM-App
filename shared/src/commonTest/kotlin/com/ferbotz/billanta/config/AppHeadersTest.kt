package com.ferbotz.billanta.config

import com.ferbotz.billanta.data.api.installAppHeaders
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.http.Headers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The targeting headers (BE-014) go on every request to the Billanta API — and nowhere else. The
 * same clients fetch images straight from S3, where a per-install id has no business being logged.
 */
class AppHeadersTest {

    private val identity = AppIdentity(platform = AppIdentity.ANDROID, version = "1.0", build = 12, deviceId = "install-1")

    private suspend fun headersFor(url: String, who: AppIdentity = identity): Headers {
        var seen: Headers? = null
        val client = HttpClient(MockEngine { request -> seen = request.headers; respond("{}") }) {
            installAppHeaders(who, apiHost = "billanta.ferbotz.com")
            defaultRequest { url("https://billanta.ferbotz.com/") }
        }
        client.get(url)
        return assertNotNull(seen)
    }

    @Test
    fun a_request_to_the_api_carries_all_four() = runTest {
        // A relative path: the host only exists once the base URL has been applied, which is why
        // the check runs at send time rather than when the request is first built.
        val headers = headersFor("config")
        assertEquals("android", headers[AppIdentity.HEADER_PLATFORM])
        assertEquals("1.0", headers[AppIdentity.HEADER_VERSION])
        assertEquals("12", headers[AppIdentity.HEADER_BUILD])
        assertEquals("install-1", headers[AppIdentity.HEADER_DEVICE_ID])
    }

    @Test
    fun a_request_to_s3_carries_none() = runTest {
        val headers = headersFor("https://billanta-media.s3.ap-south-1.amazonaws.com/templates/amber/thumbnails/v1.webp")
        assertNull(headers[AppIdentity.HEADER_DEVICE_ID])
        assertNull(headers[AppIdentity.HEADER_PLATFORM])
        assertNull(headers[AppIdentity.HEADER_BUILD])
    }

    /** "0" would read as a real build to a version rule; an unknown build is left unsaid. */
    @Test
    fun an_unknown_build_is_not_sent() = runTest {
        val headers = headersFor("config", identity.copy(build = 0))
        assertNull(headers[AppIdentity.HEADER_BUILD])
        assertEquals("install-1", headers[AppIdentity.HEADER_DEVICE_ID])
    }
}
