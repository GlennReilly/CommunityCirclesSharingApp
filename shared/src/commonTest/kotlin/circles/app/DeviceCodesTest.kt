package circles.app

import circles.encoding.ByteString
import circles.event.DeviceId
import circles.policy.CircleId
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DeviceCodesTest {
    private val device = DeviceId("0123456789abcdef0123456789abcdef")
    private val sign = ByteString.of(ByteArray(32) { it.toByte() })
    private val box = ByteString.of(ByteArray(32) { (it + 100).toByte() })
    private val code = DeviceCode(device, sign, box)

    @Test fun aDeviceCodeRoundTripsAndHasTheDocumentedShape() {
        val text = code.format()
        assertEquals("${device.v}.${sign.toBase64Url()}.${box.toBase64Url()}", text)
        val parsed = assertNotNull(DeviceCode.parse(text))
        assertEquals(device, parsed.device); assertEquals(sign, parsed.signPub); assertEquals(box, parsed.boxPub)
        assertNotNull(DeviceCode.parse("  $text\n"))
    }

    @Test fun badDeviceCodesAreRefused() {
        val s = sign.toBase64Url(); val b = box.toBase64Url(); val d = device.v
        for (bad in listOf("", "x", "$d.$s", "$d.$s.$b.extra", "${d.dropLast(1)}.$s.$b", "${d.uppercase()}.$s.$b", "$d.${s.dropLast(1)}.$b", "$d.$s.${b}A", "$d.!!.$b",
            "$d.${s.dropLast(1)}B.$b", "$d..$b", "$d.$s.")) assertNull(DeviceCode.parse(bad), bad)
    }

    @Test fun aDeviceLinkRoundTripsAndOnlyAcceptsTwoIds() {
        val link = DeviceLink(CircleId("a".repeat(32)), MemberId("b".repeat(32)))
        assertEquals("https://circles.example/#/device/${"a".repeat(32)}/${"b".repeat(32)}", link.format("https://circles.example/"))
        val parsed = assertNotNull(DeviceLink.parse(link.format("https://circles.example:8443")))
        assertEquals(link.circle, parsed.circle); assertEquals(link.member, parsed.member)
        assertNotNull(DeviceLink.parse("#/device/${"a".repeat(32)}/${"b".repeat(32)}"))
        for (bad in listOf("", "#/device/", "#/device/${"a".repeat(32)}", "#/device/${"a".repeat(32)}/${"b".repeat(32)}/x", "#/device/${"a".repeat(31)}/${"b".repeat(32)}",
            "#/device/${"A".repeat(32)}/${"b".repeat(32)}", "x#/device/${"a".repeat(32)}/${"b".repeat(32)}", "#/devices/${"a".repeat(32)}/${"b".repeat(32)}")) {
            assertNull(DeviceLink.parse(bad), bad)
        }
    }

    @Test
    fun verifyAndItemLinksRoundTripAndRefuseAnythingElse() {
        val circle = circles.policy.CircleId("a".repeat(32)); val member = circles.policy.MemberId("b".repeat(32)); val fp = "0123456789abcdef0123"
        val link = VerifyLink(circle, member, fp).format("https://circles.example/")
        assertEquals("https://circles.example/#/verify/${circle.v}/${member.v}/$fp", link)
        val parsed = assertNotNull(VerifyLink.parse(link))
        assertEquals(Triple(circle, member, fp), Triple(parsed.circle, parsed.member, parsed.fingerprint))
        assertNotNull(VerifyLink.parse("#/verify/${circle.v}/${member.v}/$fp"))
        for (bad in listOf("", "#/verify/${circle.v}/${member.v}", "#/verify/${circle.v}/${member.v}/${fp}0", "#/verify/${circle.v}/${member.v}/${fp.uppercase()}", "#/verify/${circle.v}/short/$fp", "x#/verify/${circle.v}/${member.v}/$fp", "#/verify/${circle.v}/${member.v}/$fp/extra")) {
            assertNull(VerifyLink.parse(bad), bad)
        }
        val item = circles.policy.ItemId("c".repeat(32))
        assertEquals("https://x.example/#/item/${item.v}", ItemLink(item).format("https://x.example"))
        assertEquals(item, ItemLink.parse("https://x.example/#/item/${item.v}")!!.item)
        assertNull(ItemLink.parse("#/item/${item.v}/more")); assertNull(ItemLink.parse("#/item/nope")); assertNull(ItemLink.parse("#/items"))
        assertEquals("ab12cd34", Fingerprint.compact("ab12 cd34"))
    }
}
