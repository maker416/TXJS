/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwpp.platform

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import com.sun.net.httpserver.HttpServer
import io.github.rwpp.widget.LocalDialogInteractive
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.cef.CefApp
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.awt.GraphicsEnvironment
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real CEF proof: same-origin portals cannot inherit forum storage or an earlier portal's cookies/DOM/IndexedDB. */
class GamePortalBrowserNativeTest {
    @Test fun isolatedContextsAndMainFrameBridgePreserveTheNormalBrowser() {
        assumeTrue(System.getenv("RWJS_BROWSER_NATIVE_TEST") == "1")
        assumeFalse(GraphicsEnvironment.isHeadless())
        System.setProperty("compose.layers.type", "WINDOW")
        val layers = Class.forName("io.github.rwpp.desktop.DesktopWindowLayers")
        layers.getMethod("install").invoke(layers.getField("INSTANCE").get(null))
        prepareDesktopKernelForTest()
        val probes = LinkedBlockingQueue<JsonObject>()
        val externalNavigations = LinkedBlockingQueue<String>()
        val externalNavigation: (String) -> Unit = { externalNavigations.offer(it) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun html(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }; exchange.close()
        }
        val storageScript = """
            function storageProbe(marker){return new Promise((resolve,reject)=>{
              const request=indexedDB.open('rwGame-isolation',1);
              request.onupgradeneeded=()=>request.result.createObjectStore('values');
              request.onerror=()=>reject(new Error('indexeddb'));
              request.onsuccess=()=>{const db=request.result,tx=db.transaction('values','readwrite'),store=tx.objectStore('values'),read=store.get('marker');
                read.onsuccess=()=>{const before=read.result||null;store.put(marker,'marker');tx.oncomplete=()=>{db.close();resolve(before);};};};
            });}
            async function report(marker,extra){const cookies=document.cookie,local=localStorage.getItem('marker'),indexed=await storageProbe(marker);
              document.cookie='marker='+marker+';path=/;SameSite=Lax';localStorage.setItem('marker',marker);
              await fetch('/probe',{method:'POST',body:JSON.stringify({marker,cookies,local,indexed,...extra})});}
        """.trimIndent()
        server.createContext("/probe") { exchange ->
            probes.offer(Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()) as JsonObject)
            exchange.sendResponseHeaders(204, -1); exchange.close()
        }
        server.createContext("/normal") { exchange ->
            val marker = exchange.requestURI.query?.substringAfter("stage=") ?: "normal"
            html(exchange, """<!doctype html><html><body style="background:#245f87"><script>$storageScript report('$marker',{bridge:typeof window.rwGame});</script></body></html>""")
        }
        server.createContext("/portal") { exchange ->
            val marker = exchange.requestURI.query?.substringAfter("stage=") ?: "portal"
            html(exchange, """<!doctype html><html><body style="background:#245f87"><script>
                $storageScript
                const diagnostic=value=>fetch('/probe',{method:'POST',body:JSON.stringify({marker:'diagnostic',stage:'$marker',...value})});
                window.addEventListener('error',e=>diagnostic({error:e.message}));
                window.addEventListener('unhandledrejection',e=>diagnostic({rejection:String(e.reason)}));
                diagnostic({loaded:true});
                setTimeout(()=>diagnostic({bridge:typeof window.rwGame,query:typeof window.rwGameQuery}),2000);
                async function start(){const player=await rwGame.getPlayer(),servers=await rwGame.getServers();
                  const auth=await rwGame.authorize({nonce:'a'.repeat(64)});await report('$marker',{bridge:typeof rwGame,player,servers,auth});
                  const iframe=document.createElement('iframe');iframe.src='/frame';document.body.appendChild(iframe);
                  const popup=window.open('about:blank','portal-bridge-test');
                  fetch('/probe',{method:'POST',body:JSON.stringify({marker:'popup',bridge:popup?typeof popup.rwGame:'blocked',closed:popup?popup.closed:true})});
                  if(popup)popup.close();
                  location.assign('http://127.0.0.1:9/game-portal-outside');
                  setTimeout(()=>fetch('/probe',{method:'POST',body:JSON.stringify({marker:'origin-retained',origin:location.origin,bridge:typeof window.rwGame})}),300);}
                if(window.rwGame)start();else window.addEventListener('rwGameReady',start,{once:true});
                </script></body></html>""")
        }
        server.createContext("/frame") { exchange -> html(exchange, """<!doctype html><script>
            fetch('/probe',{method:'POST',body:JSON.stringify({marker:'frame',bridge:typeof window.rwGame})});
            if(typeof window.rwGameQuery==='function')window.rwGameQuery({request:JSON.stringify({v:1,id:'frame-request',method:'getPlayer',params:{}}),persistent:false,
              onSuccess:()=>fetch('/probe',{method:'POST',body:JSON.stringify({marker:'frame-allowed'})}),
              onFailure:()=>fetch('/probe',{method:'POST',body:JSON.stringify({marker:'frame-denied'})})});
            </script>""") }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        val selected = mutableStateOf<EmbeddedBrowserState?>(null)
        var frame: JFrame? = null
        val evidence = mutableListOf<JsonObject>()
        val unmatched = mutableListOf<JsonObject>()
        fun binding(generation: String) = object : GamePortalBridge {
            override val origin = base
            override val generation = generation
            override fun isActive() = true
            override suspend fun request(method: String, params: JsonObject): JsonObject = when (method) {
                "getPlayer" -> buildJsonObject { put("account_id", "123456789012345678"); put("nickname", "测试玩家") }
                "authorize" -> buildJsonObject { put("code", "one-use-native-test"); put("expires_at", 1800000000); put("game_app_id", "game-native-test") }
                "getServers" -> Json.parseToJsonElement("""{"servers":[{"server_id":"server-1","name":"一区","available":true,"online":true,"current_players":1,"max_players":10,"closed_message":"","endpoint":"must-not-expose"}]}""") as JsonObject
                else -> error("Unexpected method")
            }
        }
        fun probe(marker: String): JsonObject {
            unmatched.firstOrNull { it["marker"]?.jsonPrimitive?.content == marker }?.let { unmatched.remove(it); return it }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (System.nanoTime() < deadline) {
                val found = probes.poll(500, TimeUnit.MILLISECONDS) ?: continue
                evidence.add(found)
                if (found["marker"]?.jsonPrimitive?.content == marker) return found
                check(found["marker"]?.jsonPrimitive?.content != "frame-allowed") { "Subframe reached native bridge" }
                unmatched.add(found)
            }
            error("Timed out waiting for $marker; browser error=${selected.value?.error}; probes=$evidence")
        }
        try {
            onEdt {
                val panel = ComposePanel()
                panel.setContent { MaterialTheme { Surface { CompositionLocalProvider(LocalDialogInteractive provides true, LocalGamePortalDesktopExternalNavigation provides externalNavigation) { selected.value?.let { state -> key(state) { EmbeddedBrowser(state, Modifier.fillMaxSize()) } } } } } }
                frame = JFrame("Game portal isolation verification").apply { contentPane = panel; setSize(640, 480); isVisible = true }
                selected.value = EmbeddedBrowserState("$base/normal?stage=normal-first")
            }
            assertEquals("undefined", probe("normal-first")["bridge"]?.jsonPrimitive?.content)
            fun replace(next: EmbeddedBrowserState) {
                val previous = onEdt { selected.value.also { selected.value = null } }
                runBlocking { withTimeout(15_000) { while (onEdt { previous?.controller != null }) delay(25) } }
                onEdt { selected.value = next }
            }
            for (stage in listOf("portal-first", "portal-second")) {
                replace(EmbeddedBrowserState("$base/portal?stage=$stage", gamePortalBridge = binding(stage)))
                val found = probe(stage)
                assertEquals("", found["cookies"]?.jsonPrimitive?.content)
                assertEquals(JsonPrimitive(null as String?), found["local"])
                assertEquals(JsonPrimitive(null as String?), found["indexed"])
                assertEquals("123456789012345678", (found["player"] as JsonObject)["accountId"]?.jsonPrimitive?.content)
                assertTrue(!found.toString().contains("must-not-expose"))
                assertEquals("undefined", probe("frame")["bridge"]?.jsonPrimitive?.content)
                probe("frame-denied")
                val popup = probe("popup")
                assertTrue(popup["bridge"]?.jsonPrimitive?.content in setOf("blocked", "undefined"))
                assertEquals("http://127.0.0.1:9/game-portal-outside", externalNavigations.poll(15, TimeUnit.SECONDS))
                val retained = probe("origin-retained")
                assertEquals(base, retained["origin"]?.jsonPrimitive?.content)
                assertEquals("object", retained["bridge"]?.jsonPrimitive?.content)
            }
            replace(EmbeddedBrowserState("$base/normal?stage=normal-return"))
            val normal = probe("normal-return")
            assertTrue(normal["cookies"]?.jsonPrimitive?.content.orEmpty().contains("marker=normal-first"))
            assertEquals("normal-first", normal["local"]?.jsonPrimitive?.content)
            assertEquals("normal-first", normal["indexed"]?.jsonPrimitive?.content)
            File("build/reports/game-portal/native-isolation.json").apply { parentFile.mkdirs(); writeText(evidence.joinToString(prefix = "[", postfix = "]")) }
        } finally {
            onEdt { selected.value = null; frame?.dispose() }
            if (CefApp.getState() == CefApp.CefAppState.INITIALIZED) CefApp.getInstance().dispose()
            server.stop(0)
        }
    }
    private fun <T> onEdt(action: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return action()
        var result: T? = null; var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { result = action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST") return result as T
    }
}
