/*
 * Copyright 2023-2025 RWPP contributors
 * 此源代码的使用受 GNU AGPLv3 许可证的约束。
 * https://github.com/Minxyzgo/RWPP/blob/main/LICENSE
 */

import io.github.rwpp.net.account.*
import io.github.rwpp.ui.forumSsoErrorText
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlTable
import java.io.File
import java.io.IOException
import kotlin.test.*

class ForumSsoErrorsTest {
    @Test fun diagnosticsDistinguishStepsAndNeverDisplayRemoteMessages() {
        val denied = forumSsoErrorText(ForumSsoStepException(ForumSsoStep.TICKET, AccountApiException("secret-code", "Bearer secret-token appkey=secret-key", 401))) { it }
        assertTrue(denied.contains("ssoStage_ticket (HTTP 401)"))
        assertTrue(denied.contains("ssoReason_ticketDenied"))
        assertFalse(denied.contains("secret"))
        val missing = forumSsoErrorText(ForumSsoStepException(ForumSsoStep.CONFIG, ForumSsoHttpException(404))) { it }
        assertTrue(missing.contains("ssoStage_config (HTTP 404)"))
        assertTrue(missing.contains("ssoReason_unavailable"))
        val network = forumSsoErrorText(ForumSsoStepException(ForumSsoStep.PREPARE, IOException("https://secret-token@example.test"))) { it }
        assertTrue(network.contains("ssoReason_network"))
        assertFalse(network.contains("secret"))
    }

    @Test fun everyDiagnosticKeyExistsInBothBundles() {
        for (language in listOf("en", "zh")) {
            val bundle = Toml.parseToTomlTable(File("src/commonMain/composeResources/files/bundle_$language.toml").readText())["browser"] as TomlTable
            val text: (String) -> String = { key -> assertTrue(bundle.containsKey(key.removePrefix("browser.")), "Missing $key in $language"); key }
            for (step in ForumSsoStep.entries) {
                for (status in listOf(302, 400, 401, 403, 404, 419, 429, 503)) {
                    forumSsoErrorText(ForumSsoStepException(step, ForumSsoHttpException(status)), text)
                }
                forumSsoErrorText(ForumSsoStepException(step, IOException("offline")), text)
            }
            forumSsoErrorText(ForumSsoRequiresHttpsException(), text)
            forumSsoErrorText(IllegalStateException("secret"), text)
        }
    }
}
