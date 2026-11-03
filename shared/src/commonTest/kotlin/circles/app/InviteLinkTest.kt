package circles.app

import circles.encoding.ByteString
import circles.event.EventId
import circles.policy.CircleId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InviteLinkTest {
    private val circle = CircleId("0123456789abcdef0123456789abcdef")
    private val invite = EventId("ab".repeat(32))
    private val seed = ByteString.of(ByteArray(32) { it.toByte() })
    private val secret = seed.toBase64Url()
    private val link = InviteLink(circle, invite, seed)

    @Test fun formatMatchesTheDocumentedShape() {
        assertEquals("https://circles.example/#/join/${circle.v}/${invite.v}/$secret", link.format("https://circles.example"))
        assertEquals(43, secret.length)
    }

    @Test fun aTrailingSlashOnTheOriginIsIgnored() {
        assertEquals(link.format("https://circles.example"), link.format("https://circles.example/"))
    }

    @Test fun aFormattedLinkParsesBack() {
        val parsed = assertNotNull(InviteLink.parse(link.format("https://circles.example:8443")))
        assertEquals(circle, parsed.circle)
        assertEquals(invite, parsed.invite)
        assertEquals(seed, parsed.secretSeed)
    }

    @Test fun justTheFragmentParsesToo() {
        assertNotNull(InviteLink.parse("#/join/${circle.v}/${invite.v}/$secret"))
        assertNotNull(InviteLink.parse("  #/join/${circle.v}/${invite.v}/$secret \n"))
    }

    @Test fun badLinksAreRefused() {
        val ok = "#/join/${circle.v}/${invite.v}/$secret"
        val bad = listOf(
            "", "hello", "https://circles.example/",
            ok + "/extra", ok.removeSuffix(secret),
            "#/join/${circle.v.dropLast(1)}/${invite.v}/$secret",
            "#/join/${circle.v.uppercase()}/${invite.v}/$secret",
            "#/join/${circle.v}/${invite.v.dropLast(2)}/$secret",
            "#/join/${circle.v}/${"g".repeat(64)}/$secret",
            "#/join/${circle.v}/${invite.v}/${secret.dropLast(1)}",
            "#/join/${circle.v}/${invite.v}/${secret}A",
            "#/join/${circle.v}/${invite.v}/${"!".repeat(43)}",
            "#/join/${circle.v}/${invite.v}/${secret.dropLast(1)}B", // not the canonical base64url spelling
            "x#/join/${circle.v}/${invite.v}/$secret",
            "#/joinx/${circle.v}/${invite.v}/$secret",
        )
        for (text in bad) assertNull(InviteLink.parse(text), text)
    }
}
