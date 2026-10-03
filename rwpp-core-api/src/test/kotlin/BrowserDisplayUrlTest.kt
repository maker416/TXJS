/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */
import io.github.rwpp.net.browser.browserCanonicalDisplayUrl
import java.net.IDN
import kotlin.test.*

class BrowserDisplayUrlTest {
    private val host = "zyz.xn--rhqr8xvr4ahqsgka.com"
    private val display = IDN.toUnicode(host)
    private val bootstrap = "https://$host/sso/client"

    @Test fun configuredUnicodeDisplayHostRestoresTheExactBootstrap() {
        assertEquals(bootstrap, browserCanonicalDisplayUrl("https://$display/sso/client", bootstrap))
        assertEquals(bootstrap, browserCanonicalDisplayUrl(bootstrap, bootstrap))
        assertEquals("https://$host/", browserCanonicalDisplayUrl("https://$display/", bootstrap))
    }
    @Test fun pathsQueriesFragmentsAndSchemesAreNeverDiscarded() {
        for (suffix in listOf("/sso/client/", "/sso/client?other=1", "/sso/client#frame", "/d/3")) {
            assertEquals("https://$host$suffix", browserCanonicalDisplayUrl("https://$display$suffix", bootstrap))
            assertNotEquals(bootstrap, browserCanonicalDisplayUrl("https://$display$suffix", bootstrap))
        }
        assertEquals("http://$host/sso/client", browserCanonicalDisplayUrl("http://$display/sso/client", bootstrap))
    }
    @Test fun differentAuthoritiesCredentialsAndPortsDoNotMatch() {
        for (url in listOf("https://evil.example/sso/client", "https://user@$display/sso/client", "https://$display:8443/sso/client", "not a URI")) {
            assertEquals(url, browserCanonicalDisplayUrl(url, bootstrap))
        }
        assertEquals("https://$host:8443/sso/client", browserCanonicalDisplayUrl("https://$display:8443/sso/client", "https://$host:8443/sso/client"))
        assertEquals("http://127.0.0.1:1234/sso/client", browserCanonicalDisplayUrl("http://127.0.0.1:1234/sso/client", "http://127.0.0.1:1234/sso/client"))
    }
}
