package com.example.waferai

import com.example.waferai.data.remote.ServerAddress
import com.example.waferai.data.remote.HealthResponse
import com.example.waferai.data.repository.verifyHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun presetsRemainUsable() {
        assertEquals(ServerAddress.EMULATOR, ServerAddress.normalize(ServerAddress.EMULATOR))
        assertEquals(ServerAddress.USB, ServerAddress.normalize(ServerAddress.USB))
    }

    @Test fun normalizesWhitespaceAndTrailingSlash() {
        assertEquals("http://192.168.1.10:8000/", ServerAddress.normalize("  http://192.168.1.10:8000  "))
        assertEquals("https://example.com/", ServerAddress.normalize("HTTPS://Example.com:443/"))
    }

    @Test fun acceptsIpv6Loopback() {
        assertEquals("http://[::1]:8000/", ServerAddress.normalize("http://[::1]:8000"))
    }

    @Test fun rejectsPathsQueriesAndFragments() {
        listOf("http://localhost:8000/docs", "http://localhost/api/v1", "http://localhost/?x=1", "http://localhost/#top").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize(it) }
        }
    }

    @Test fun rejectsCredentials() {
        assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize("https://user:password@example.com") }
    }

    @Test fun rejectsMalformedOrUnsupportedAddresses() {
        listOf("", "localhost:8000", "ftp://localhost", "http://local host:8000", "http://localhost:70000", "http://localhost:0", "http://host\\path").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize(it) }
        }
    }

    @Test fun rejectsBindAllAddresses() {
        listOf("http://0.0.0.0:8000", "http://[::]:8000").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.normalize(it) }
        }
    }

    @Test fun onlyAcceptsCompatibleHealthResponse() {
        verifyHealth(HealthResponse("ok", "WaferAI", "v1"))
        listOf(HealthResponse("error", "WaferAI", "v1"), HealthResponse("ok", "Other", "v1"), HealthResponse("ok", "WaferAI", "v2")).forEach {
            assertThrows(IllegalStateException::class.java) { verifyHealth(it) }
        }
    }
}
