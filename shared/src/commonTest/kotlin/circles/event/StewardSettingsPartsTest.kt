package circles.event

import circles.encoding.ByteString
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The strict readers for `circle.create` settings and `steward.policy` (ADR 0027). */
class StewardSettingsPartsTest {
    private val registration = DeviceRegistration(
        MemberId("11".repeat(16)), DeviceId("22".repeat(16)), ByteString.of(ByteArray(32) { 1 }), ByteString.of(ByteArray(32) { 2 }),
    )

    private fun created(settings: StewardSettings?) = PublicParts.circleCreatePublic(registration, settings)

    @Test
    fun circleCreateWithoutSettingsGetsTheDefaults() {
        val terms = assertIs<Parsed.Ok<CircleCreateTerms>>(PublicParts.circleCreate(created(null))).value
        assertEquals(registration, terms.registration)
        assertEquals(StewardSettings(), terms.settings)
        assertEquals(registration.toPublic(), created(null), "without settings the public part is exactly the old one")
    }

    @Test
    fun circleCreateCarriesAllThreeSettingsOrJustSome() {
        val all = StewardSettings(quorum = 1, inactiveDays = 45, fallbackDelayDays = 10)
        assertEquals(all, assertIs<Parsed.Ok<CircleCreateTerms>>(PublicParts.circleCreate(created(all))).value.settings)
        val onlyQuorum = CborMap(registration.toPublic().entries + ("stewardQuorum" to CborInt(1)))
        assertEquals(StewardSettings(quorum = 1), assertIs<Parsed.Ok<CircleCreateTerms>>(PublicParts.circleCreate(onlyQuorum)).value.settings)
    }

    @Test
    fun circleCreateRefusesUnknownKeysMissingKeysWrongTypesAndOutOfRangeValues() {
        val good = created(StewardSettings(quorum = 3))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries + ("extra" to CborInt(1)))))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries - "boxPub")))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries + ("stewardQuorum" to CborText("3")))))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries + ("stewardQuorum" to CborInt(0)))))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries + ("stewardInactiveDays" to CborInt(6)))))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries + ("stewardFallbackDelayDays" to CborInt(91)))))
        assertIs<Parsed.Invalid>(PublicParts.circleCreate(CborMap(good.entries + ("stewardQuorum" to CborInt(-1)))))
    }

    @Test
    fun stewardPolicyRoundTripsAndIsStrict() {
        val settings = StewardSettings(quorum = 4, inactiveDays = 120, fallbackDelayDays = 30)
        val part = PublicParts.stewardSettingsPublic(settings)
        assertEquals(settings, assertIs<Parsed.Ok<StewardSettings>>(PublicParts.stewardSettings(part)).value)
        assertIs<Parsed.Invalid>(PublicParts.stewardSettings(CborMap(part.entries - "quorum")))
        assertIs<Parsed.Invalid>(PublicParts.stewardSettings(CborMap(part.entries + ("extra" to CborInt(1)))))
        assertIs<Parsed.Invalid>(PublicParts.stewardSettings(CborMap(part.entries + ("quorum" to CborText("4")))))
        assertIs<Parsed.Invalid>(PublicParts.stewardSettings(CborMap(part.entries + ("inactiveDays" to CborInt(1)))))
        assertIs<Parsed.Invalid>(PublicParts.stewardSettings(CborMap(part.entries + ("fallbackDelayDays" to CborInt(1000)))))
        assertIs<Parsed.Invalid>(PublicParts.stewardSettings(CborMap.EMPTY))
    }

    @Test
    fun theSubjectNamesExactlyTheValues() {
        assertEquals("2:60:14", StewardSettings().subject())
        assertEquals(false, StewardSettings(quorum = 3).subject() == StewardSettings(quorum = 2).subject())
    }
}
