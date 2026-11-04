package com.example.waferai

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.example.waferai.data.account.*
import com.example.waferai.ui.account.AccountViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AccountSwitchTest {
    @Test fun validatesSavedSessionAndKeepsCurrentAccountWhenAnotherSessionHasExpired() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefix = "qa-switch-${UUID.randomUUID()}-"
        val application = object : Application() {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefix + name, mode)
        }
        val models = ViewModelStore()
        try {
            MockWebServer().use { server ->
                val store = AccountStore(application)
                store.endpoint(server.url("/").toString())
                store.save(JSONObject("""{"token":"first-token","user":{"id":"first","name":"First","identities":[]}}"""))
                store.pause()
                store.save(JSONObject("""{"token":"second-token","user":{"id":"second","name":"Second","identities":[]}}"""))
                val model = AccountViewModel(application)
                instrumentation.runOnMainSync { models.put("qa", model) }
                val first = model.state.value.saved.first { it.user.id == "first" }
                server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"Session expired"}"""))
                instrumentation.runOnMainSync { model.switchAccount(first.key) }
                withTimeout(5000) { while (model.state.value.busy) delay(10) }
                assertEquals("second", model.state.value.user?.id)
                assertEquals(listOf("second"), model.state.value.saved.map { it.user.id })
                assertEquals("Bearer first-token", server.takeRequest().getHeader("Authorization"))
                store.pause()
                store.save(JSONObject("""{"token":"first-renewed","user":{"id":"first","name":"First","identities":[]}}"""))
                store.activate("${store.endpoint()}|second", AccountUser("second", "Second"))
                server.enqueue(MockResponse().setBody("""{"id":"first","name":"Verified","identities":[]}"""))
                instrumentation.runOnMainSync { model.switchAccount(first.key) }
                withTimeout(5000) { while (model.state.value.busy) delay(10) }
                assertEquals("first", model.state.value.user?.id)
                assertEquals("Verified", AccountStore(application).user()?.name)
                assertEquals("first-renewed", AccountStore(application).token())
                assertEquals("Bearer first-renewed", server.takeRequest().getHeader("Authorization"))
            }
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            context.deleteSharedPreferences(prefix + "account_session")
        }
    }
}
