package com.example.waferai

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.account.*
import com.example.waferai.data.ai.ChatPrompt
import com.example.waferai.data.ai.ChatTurn
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccountConnectionTest {
    @Test fun rejectsInsecureRemoteServicesAndSecretBearingAddresses() {
        assertEquals("https://account.example.com", accountOrigin("https://account.example.com/"))
        assertEquals("http://127.0.0.1:8010", accountOrigin("http://127.0.0.1:8010"))
        for (url in listOf("http://example.com", "https://user:password@example.com", "https://example.com/?key=secret", "https://example.com/path", "javascript:alert(1)")) {
            assertThrows(IllegalArgumentException::class.java) { accountOrigin(url) }
        }
    }

    @Test fun sendsOnlyTheAccountTokenAndReportsAuthenticationFailures() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"qa","name":"Reader","identities":[]}"""))
            val client = AccountClient()
            assertEquals("Reader", client.request(server.url("/").toString(), "/api/v1/auth/me", token = "qa-session").getString("name"))
            val request = server.takeRequest()
            assertEquals("Bearer qa-session", request.getHeader("Authorization"))
            assertEquals("/api/v1/auth/me", request.path)
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"请登录后继续。"}"""))
            try {
                client.request(server.url("/").toString(), "/api/v1/auth/me", token = "expired")
                fail("Expired session should fail")
            } catch (failure: AccountFailure) { assertEquals(401, failure.status) }
        }
    }

    @Test fun storesEncryptedSessionAndRetainsItAcrossStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-account-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefix + name, mode)
        }
        try {
            val store = AccountStore(isolated)
            store.endpoint("https://account.example.com")
            store.save(JSONObject("""{"token":"qa-secret-session","user":{"id":"qa","name":"Reader","identities":[]}}"""))
            assertNotEquals("qa-secret-session", isolated.getSharedPreferences("account_session", 0).getString("token", ""))
            val reopened = AccountStore(isolated)
            assertEquals("qa-secret-session", reopened.token())
            assertEquals("Reader", reopened.user()?.name)
            assertEquals("https://account.example.com", reopened.endpoint())
            reopened.clear()
            assertNull(AccountStore(isolated).token())
            assertNull(AccountStore(isolated).user())
        } finally { context.deleteSharedPreferences(prefix + "account_session") }
    }

    @Test fun generalConversationKeepsUserTopicAndHistoryWithoutAttachingAWafer() {
        val turns = listOf(ChatTurn("Help me write a story", "What genre?", "Test model"))
        val messages = ChatPrompt.messages(turns, "Write a space adventure", null)
        assertTrue(messages.getJSONObject(0).getString("content").contains("通用 AI 助手"))
        assertEquals("Help me write a story", messages.getJSONObject(1).getString("content"))
        assertEquals("Write a space adventure", messages.getJSONObject(messages.length()-1).getString("content"))
        assertFalse(messages.getJSONObject(messages.length()-1).getString("content").contains("晶圆"))
    }

    @Test fun pendingAuthorizationSurvivesRestartEncryptedAndCanBeRemoved() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-pending-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefix + name, mode)
        }
        try {
            val store = AccountStore(isolated)
            store.endpoint("https://account.example.com")
            val pending = PendingAccountLogin(store.endpoint(), "qa-poll-token-12345678901234567890", System.currentTimeMillis() + 60_000)
            store.pending(pending)
            assertEquals(pending, AccountStore(isolated).pending())
            assertFalse(isolated.getSharedPreferences("account_session", 0).getString("pending", "")!!.contains(pending.pollToken))
            store.clearPending()
            assertNull(AccountStore(isolated).pending())
        } finally { context.deleteSharedPreferences(prefix + "account_session") }
    }

    @Test fun multipleAccountsRemainEncryptedAndSwitchOnlyToVerifiedIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "qa-multi-${UUID.randomUUID()}-"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefix + name, mode)
        }
        try {
            val store = AccountStore(isolated)
            store.endpoint("https://account.example.com")
            store.save(JSONObject("""{"token":"first-secret","user":{"id":"first","name":"First","identities":[]}}"""))
            store.pause()
            assertNull(store.token())
            store.save(JSONObject("""{"token":"second-secret","user":{"id":"second","name":"Second","identities":[]}}"""))
            val reopened = AccountStore(isolated)
            assertEquals(2, reopened.savedAccounts().size)
            val first = reopened.savedAccounts().first { it.user.id == "first" }
            assertThrows(IllegalArgumentException::class.java) { reopened.activate(first.key, AccountUser("other", "Wrong")) }
            assertEquals("second", reopened.user()?.id)
            reopened.activate(first.key, first.user)
            assertEquals("first-secret", reopened.token())
            reopened.profile(first.user.copy(name = "Updated"))
            assertEquals("Updated", AccountStore(isolated).savedAccounts().first { it.user.id == "first" }.user.name)
            val serialized = isolated.getSharedPreferences("account_session", 0).getString("accounts", "")!!
            assertFalse(serialized.contains("first-secret")); assertFalse(serialized.contains("second-secret"))
            reopened.clear()
            assertEquals(listOf("second"), AccountStore(isolated).savedAccounts().map { it.user.id })
        } finally { context.deleteSharedPreferences(prefix + "account_session") }
    }
}
