package com.obvyr.gradle

import com.obvyr.gradle.http.ObvyrApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObvyrApiClientTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `submit sends POST to collect endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ObvyrApiClient("test-key", server.url("/").toString().trimEnd('/'), 10.0, true)
        client.submit("archive-data".toByteArray())
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.path).isEqualTo("/collect")
    }

    @Test
    fun `submit sends Authorization Bearer header`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ObvyrApiClient("my-agent-key", server.url("/").toString().trimEnd('/'), 10.0, true)
        client.submit("archive-data".toByteArray())
        val request = server.takeRequest()
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer my-agent-key")
    }

    @Test
    fun `submit sends multipart form data with field named archive`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ObvyrApiClient("test-key", server.url("/").toString().trimEnd('/'), 10.0, true)
        client.submit("archive-data".toByteArray())
        val request = server.takeRequest()
        val contentType = request.getHeader("Content-Type") ?: ""
        assertThat(contentType).startsWith("multipart/form-data")
        val body = request.body.readUtf8()
        assertThat(body).contains("name=\"archive\"")
    }

    @Test
    fun `submit includes artifacts tar zst filename`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ObvyrApiClient("test-key", server.url("/").toString().trimEnd('/'), 10.0, true)
        client.submit("archive-data".toByteArray())
        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertThat(body).contains("artifacts.tar.zst")
    }

    @Test
    fun `submit returns true on 2xx response`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ObvyrApiClient("test-key", server.url("/").toString().trimEnd('/'), 10.0, true)
        val result = client.submit("archive-data".toByteArray())
        assertThat(result).isTrue()
    }

    @Test
    fun `submit returns false on non-2xx response`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val client = ObvyrApiClient("test-key", server.url("/").toString().trimEnd('/'), 10.0, true)
        val result = client.submit("archive-data".toByteArray())
        assertThat(result).isFalse()
    }

    @Test
    fun `submit returns false without throwing on network error`() {
        server.shutdown() // Force connection failure
        val client = ObvyrApiClient("test-key", "http://localhost:1", 1.0, true)
        val result = client.submit("archive-data".toByteArray())
        assertThat(result).isFalse()
    }

    @Test
    fun `submit with verifySsl false succeeds against HTTP server`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = ObvyrApiClient("test-key", server.url("/").toString().trimEnd('/'), 10.0, verifySsl = false)
        val result = client.submit("archive-data".toByteArray())
        assertThat(result).isTrue()
    }

    @Test
    fun `submit with verifySsl false succeeds against HTTPS server with self-signed cert`() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCerts = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val httpsServer = MockWebServer()
        httpsServer.useHttps(serverCerts.sslSocketFactory(), false)
        httpsServer.enqueue(MockResponse().setResponseCode(200))
        httpsServer.start()

        val client = ObvyrApiClient(
            "test-key",
            "https://localhost:${httpsServer.port}",
            10.0,
            verifySsl = false,
        )
        val result = client.submit("archive-data".toByteArray())

        assertThat(result).isTrue()
        httpsServer.shutdown()
    }
}
