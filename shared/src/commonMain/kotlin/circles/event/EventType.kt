package circles.event

/** Every event type in spec section 3.3. A server only accepts the ones its milestone supports. */
enum class EventType(val wire: String) {
    CIRCLE_CREATE("circle.create"),
    MEMBER_INVITE("member.invite"),
    MEMBER_JOIN("member.join"),
    MEMBER_PROFILE("member.profile"),
    DEVICE_ADD("device.add"),
    DEVICE_REVOKE("device.revoke"),
    KEY_WRAP("key.wrap"),
    KEY_ROTATE("key.rotate"),
    RECOVERY_SHARE("recovery.share"),
    STEWARD_ADD("steward.add"),
    STEWARD_REMOVE("steward.remove"),
    STEWARD_POLICY("steward.policy"),
    CONFIG_UPDATE("config.update"),
    VOUCH("vouch"),
    ITEM_UPSERT("item.upsert"),
    ITEM_RETIRE("item.retire"),
    ITEM_DETAIL("item.detail"),
    SKILL_UPSERT("skill.upsert"),
    SKILL_RETIRE("skill.retire"),
    LOAN_REQUEST("loan.request"),
    LOAN_RESPOND("loan.respond"),
    LOAN_HANDOVER("loan.handover"),
    LOAN_RETURN("loan.return"),
    LOAN_CANCEL("loan.cancel"),
    LOAN_DISPUTE("loan.dispute"),
    LOAN_RESOLVE("loan.resolve"),
    STANDING_NOTE("standing.note"),
    STANDING_CONFIRM("standing.confirm"),
    PAUSE_PROPOSE("pause.propose"),
    PAUSE_REVIEW("pause.review"),
    PAUSE_LIFT("pause.lift"),
    PAUSE_APPEAL("pause.appeal");

    companion object {
        fun fromWire(text: String): EventType? = entries.firstOrNull { it.wire == text }
    }
}
