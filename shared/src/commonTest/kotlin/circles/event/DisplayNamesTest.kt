package circles.event

import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.MemberId
import circles.text.nfkc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The display-name rules of ADR 0068, which must give the same answer on the JVM and in the browser. */
class DisplayNamesTest {
    @Test
    fun ordinaryNamesInAnyScriptAreAccepted() {
        for (name in listOf("Bea", "Ada", "Dee", "Bea Lee", "José", "Zoë O'Neil", "李小龍", "Анна", "محمد", "Jo-Ann 2", "A", "x".repeat(MAX_DISPLAY_NAME_CODE_POINTS), "🙂 Sam")) {
            assertNull(DisplayNames.problem(name), name)
        }
    }

    @Test
    fun lengthCountsCodePointsNotUtf16Units() {
        assertNull(DisplayNames.problem("🙂".repeat(MAX_DISPLAY_NAME_CODE_POINTS)), "40 emoji are 80 UTF-16 units but 40 characters")
        assertEquals("a name is at most 40 characters", DisplayNames.problem("🙂".repeat(MAX_DISPLAY_NAME_CODE_POINTS + 1)))
        assertEquals("a name must be valid text", DisplayNames.problem("Bea\uD83D"))
    }

    @Test
    fun controlCharactersInvisibleMarksOddSpacesAndIdLikeNamesAreRefused() {
        val refused = mapOf(
            "" to "a name cannot be empty",
            "Bea\n" to "a name cannot hold control characters, invisible marks or unusual spaces",
            "Bea\u0085" to "a name cannot hold control characters, invisible marks or unusual spaces",
            "Bea‍" to "a name cannot hold control characters, invisible marks or unusual spaces",
            "‮aeB" to "a name cannot hold control characters, invisible marks or unusual spaces",
            "Bea Lee" to "a name cannot hold control characters, invisible marks or unusual spaces",
            " Bea" to "a name cannot start or end with a space",
            "Bea " to "a name cannot start or end with a space",
            "Bea  Lee" to "a name cannot hold two spaces in a row",
            "42" to "a name cannot be just a number or look like a member code",
            "2b69a182" to "a name cannot be just a number or look like a member code",
            "2b69 a182 F00D" to "a name cannot be just a number or look like a member code",
        )
        for ((name, reason) in refused) assertEquals(reason, DisplayNames.problem(name), name)
        assertNull(DisplayNames.problem("Bedazzled"), "hex letters mixed with others make a word")
        assertNull(DisplayNames.problem("Deadbee"), "seven hex letters is still a name")
    }

    @Test
    fun typingIsTidiedToNfkcWithSingleSpaces() {
        val tidy = { typed: String -> DisplayNames.tidy(typed) { nfkc(it) } }
        assertEquals("Bea Lee", tidy("  Bea \t  Lee \n"))
        assertEquals("Bea", tidy("Ｂｅａ"), "full-width letters become plain ones")
        assertEquals("fi", tidy("ﬁ"), "a ligature is split")
        assertEquals("", tidy("   "))
        assertNull(DisplayNames.problem(tidy("  Zoë　O'Neil ")))
    }

    @Test
    fun payloadsRoundTripAndTheReadersAreStrict() {
        val member = MemberId("a".repeat(RANDOM_ID_HEX_LENGTH))
        val profile = EventId("b".repeat(EVENT_ID_HEX_LENGTH))
        assertEquals(Parsed.Ok(MemberProfile("Bea")), NamePayloads.parseProfile(NamePayloads.profile(MemberProfile("Bea"))))
        val confirmation = NameConfirmation(member, profile, "Bea", NameCheck.FINGERPRINT_IN_PERSON)
        assertEquals(Parsed.Ok(confirmation), NamePayloads.parseConfirmation(NamePayloads.confirmation(confirmation)))
        assertIs<Parsed.Invalid>(NamePayloads.parseProfile(CborMap.of("name" to CborText("Bea"), "area" to CborText("north"))))
        assertIs<Parsed.Invalid>(NamePayloads.parseProfile(CborMap.of("name" to CborText("x".repeat(MAX_DISPLAY_NAME_CODE_POINTS * 3)))))
        val wire = NamePayloads.confirmation(confirmation).entries
        assertIs<Parsed.Invalid>(NamePayloads.parseConfirmation(CborMap(wire + ("how" to CborText("BY_PHONE")))))
        assertIs<Parsed.Invalid>(NamePayloads.parseConfirmation(CborMap(wire + ("profile" to CborText("short")))))
        assertIs<Parsed.Invalid>(NamePayloads.parseConfirmation(CborMap(wire + ("member" to CborText("NOT-HEX")))))
        assertIs<Parsed.Invalid>(NamePayloads.parseConfirmation(CborMap(wire - "how")))
        assertTrue(NamePayloads.profile(MemberProfile("Bea")).entries.keys == setOf("name"))
    }
}

/** The case-folded comparison key of ADR 0069: one fixed table, so the JVM and the browser agree (this runs on both). */
class NameKeyTest {
    @Test
    fun caseDoesNotMakeANameDifferent() {
        assertEquals(NameKey.of("Bea"), NameKey.of("bea"))
        assertEquals(NameKey.of("Bea"), NameKey.of("BEA"))
        assertEquals("bea lee", NameKey.of("BEA Lee"))
    }

    @Test
    fun accentedGreekAndCyrillicLettersFold() {
        assertEquals(NameKey.of("josé"), NameKey.of("JOSÉ"))
        assertEquals(NameKey.of("Łukasz"), NameKey.of("łUKASZ"))
        assertEquals(NameKey.of("Řehák"), NameKey.of("řEHÁK"))
        assertEquals(NameKey.of("Αλέξης"), NameKey.of("ΑΛΈΞΗΣ")) // final sigma folds to sigma
        assertEquals(NameKey.of("Анна"), NameKey.of("АННА"))
        assertEquals(NameKey.of("Ёлка"), NameKey.of("ёЛКА"))
        assertEquals(NameKey.of("Ҙ"), NameKey.of("ҙ"))
    }

    @Test
    fun anAccentMakesADifferentNameAndTurkishLettersStayApart() {
        assertNotEquals(NameKey.of("Bea"), NameKey.of("Béa"))
        assertNotEquals(NameKey.of("I"), NameKey.of("ı"), "dotless i is its own letter")
        assertNotEquals(NameKey.of("i"), NameKey.of("İ"), "dotted capital I is not folded to i")
        assertNotEquals(NameKey.of("İsmail"), NameKey.of("ismail"))
        assertEquals(NameKey.of("ISMAIL"), NameKey.of("ismail"))
        assertEquals("ß", NameKey.of("ß"), "sharp s is left alone")
        assertEquals("×", NameKey.of("×"), "the multiplication sign is not a letter")
    }

    @Test
    fun lookAlikesFromOtherScriptsAreNotDetected() {
        assertNotEquals(NameKey.of("Anna"), NameKey.of("Аnna"), "Cyrillic A is not Latin A: a known limit of ADR 0069")
    }

    @Test
    fun theKeysOfFixedVectorsAreTheSameOnBothPlatforms() {
        val vectors = mapOf("Bea" to "bea", "ÉMILE" to "émile", "ЖЕНЯ" to "женя", "ΣΩΦΙΑ" to "σωφια", "İIıi" to "İiıi", "中文" to "中文")
        for ((text, key) in vectors) assertEquals(key, NameKey.of(text), text)
    }
}
