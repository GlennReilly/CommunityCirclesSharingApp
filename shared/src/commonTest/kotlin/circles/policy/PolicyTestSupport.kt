package circles.policy

/** Shared builders for the policy tests. Days are plain integers; TODAY is arbitrary. */
internal const val TODAY: Day = 20_000
internal const val POINTS_TOLERANCE = 0.006

internal val CIRCLE = CircleId("street")
internal val CFG = CircleConfig(CIRCLE, "Our Street")

internal val ALICE = MemberId("alice")
internal val ANN = MemberId("ann")
internal val BEA = MemberId("bea")
internal val CAL = MemberId("cal")
internal val DEE = MemberId("dee")
internal val BOB = MemberId("bob")

internal fun event(
    member: MemberId,
    kind: EventKind,
    daysAgo: Int = 0,
    counterpart: MemberId? = null,
    sourceId: String = ""
): StandingEvent = StandingEvent(member, CIRCLE, kind, TODAY - daysAgo, counterpart, sourceId)

internal fun vouch(voucher: MemberId, vouchee: MemberId, daysAgo: Int = 10, expiresInDays: Int = 170): Vouch =
    Vouch(voucher, vouchee, CIRCLE, TODAY - daysAgo, TODAY + expiresInDays)

internal fun item(
    owner: MemberId,
    itemClass: ItemClass,
    ownerMinTier: Tier? = null
): Item = Item(ItemId("i-${itemClass.name}"), owner, itemClass.name.lowercase(), itemClass, ownerMinTier, setOf(CIRCLE))

internal fun standingWith(
    member: MemberId,
    events: List<StandingEvent> = emptyList(),
    vouches: List<Vouch> = emptyList(),
    cfg: CircleConfig = CFG,
    today: Day = TODAY,
    tierOf: (MemberId) -> Tier = { Tier.REGULAR }
): Standing = StandingCalculator.compute(member, cfg, events, vouches, tierOf, today)

/** A member with plenty of recent activity: TRUSTED by points alone. */
internal fun trustedHistory(member: MemberId, count: Int = 30): List<StandingEvent> =
    List(count) { event(member, EventKind.LENT_ITEM_RETURNED_OK, daysAgo = 7 * it + 5) }
