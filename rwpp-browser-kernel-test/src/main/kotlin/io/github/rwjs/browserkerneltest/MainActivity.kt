/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

package io.github.rwjs.browserkerneltest

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.util.Log
import android.widget.LinearLayout
import android.widget.TextView
import io.github.rwpp.app.UpdateDownloadSession
import io.github.rwpp.net.browser.*
import okhttp3.OkHttpClient
import org.mozilla.geckoview.*
import java.io.File
import java.util.concurrent.TimeUnit

/** 仅调试宿主：下载同一生产内核，验证真实多进程、认证响应、扩展和文件上传。 */
class MainActivity : Activity() {
    private val cancellation = UpdateDownloadSession()
    private var session: GeckoSession? = null
    private lateinit var status: TextView
    private var extension: WebExtension? = null
    private var nativeView: GeckoView? = null
    private var scenario = 0
    private var guestMode = false
    private var uploadTriggered = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(status) }
        setContentView(layout)
        val base = intent.getStringExtra("base") ?: "http://127.0.0.1:8765"
        Thread({
            try {
                val abi = if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS[0] else Build.SUPPORTED_32_BIT_ABIS[0]
                val requirement = BrowserKernelRequirement.read(assets.open("browser-kernels/android-$abi.json").bufferedReader().use { it.readText() })
                val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
                val manager = BrowserKernelManager(File(noBackupFilesDir, "browser/kernels"), client)
                val directory = manager.install(requirement, BrowserKernelSource { _, name, _ -> "$base/$name" }, cancellation) { update ->
                    runOnUiThread { status.text = "${update.stage} ${update.bytes}/${update.total}" }
                }
                manager.activate(requirement)
                Log.i("KernelTest", "INSTALLED ${directory.absolutePath}")
                runOnUiThread { openBrowser(layout, base) }
            } catch (error: Throwable) {
                Log.e("KernelTest", "FAILED", error)
                runOnUiThread { status.text = "FAILED: $error" }
            }
        }, "kernel-test-download").start()
    }
    private fun openBrowser(layout: LinearLayout, base: String) {
        val geckoRuntime = runtime ?: GeckoRuntime.create(applicationContext).also { runtime = it }
        val browser = GeckoSession().also { session = it }
        browser.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) { Log.e("KernelTest", "CONTENT CRASH") }
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                Thread({
                    val bytes = response.body?.use { it.readBytes() }
                    Log.i("KernelTest", "DOWNLOAD ${bytes?.contentEquals(byteArrayOf(1, 2, 3, 4))}")
                    runOnUiThread {
                        guestMode = true; scenario = 2
                        browser.loadUri("$base/sso/client")
                    }
                }, "kernel-test-response").start()
            }
        }
        browser.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                status.text = "PAGE $success"; Log.i("KernelTest", "PAGE $success")
            }
        }
        browser.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onFilePrompt(session: GeckoSession, prompt: GeckoSession.PromptDelegate.FilePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
                val file = File(cacheDir, "kernel-test.rwmod").apply { writeText("external-kernel-upload") }
                Log.i("KernelTest", "UPLOAD PROMPT")
                return GeckoResult.fromValue(prompt.confirm(this@MainActivity, android.net.Uri.fromFile(file)))
            }
        }
        geckoRuntime.webExtensionController.ensureBuiltIn("resource://android/assets/rwpp-forum-bridge/", "forum-bridge@rwpp").accept({ addon ->
            extension = addon
            val delegate = object : WebExtension.MessageDelegate {
                override fun onConnect(port: WebExtension.Port) {
                    port.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            Log.i("KernelTest", "BRIDGE $message")
                            if ((message as? org.json.JSONObject)?.optString("type") == "ready") {
                                port.postMessage(org.json.JSONObject().put("type", "handoff").put("url", port.sender.url)
                                    .put("handoff", if (guestMode || intent.getBooleanExtra("guest", false)) org.json.JSONObject.NULL else "a".repeat(64)))
                                if (scenario == 0) {
                                    scenario = 1
                                    nativeView?.postDelayed({ browser.loadUri("$base/protected.rwmod") }, 300)
                                } else if (scenario == 2 && !uploadTriggered) {
                                    uploadTriggered = true
                                    nativeView?.postDelayed({
                                        val view = nativeView ?: return@postDelayed
                                        val density = resources.displayMetrics.density
                                        val now = android.os.SystemClock.uptimeMillis()
                                        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 40 * density, 110 * density, 0)
                                        val up = android.view.MotionEvent.obtain(now, now + 100, android.view.MotionEvent.ACTION_UP, 40 * density, 110 * density, 0)
                                        down.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                                        up.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                                        view.dispatchTouchEvent(down); view.dispatchTouchEvent(up); down.recycle(); up.recycle()
                                    }, 800)
                                }
                            }
                        }
                    })
                }
            }
            if (addon != null) browser.webExtensionController.setMessageDelegate(addon, delegate, "rwppForum")
            browser.open(geckoRuntime)
            val view = GeckoView(this).apply { setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW); setSession(browser) }
            nativeView = view
            layout.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
            browser.loadUri("$base/sso/client")
        }, { error -> Log.e("KernelTest", "EXTENSION FAILED", error) })
    }
    override fun onDestroy() { cancellation.cancel(); session?.close(); super.onDestroy() }
    companion object { private var runtime: GeckoRuntime? = null }
}
