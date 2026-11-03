package circles.event

/** Why a server refuses an event. The wire names are part of the API (spec 4.3). */
enum class RejectionCode(val wire: String) {
    MALFORMED("malformed"),
    UNSUPPORTED_VERSION("unsupported_version"),
    UNSUPPORTED_TYPE("unsupported_type"),
    TOO_LARGE("too_large"),
    FUTURE_DAY("future_day"),
    UNKNOWN_CIRCLE("unknown_circle"),
    CIRCLE_EXISTS("circle_exists"),
    CREATION_DISABLED("creation_disabled"),
    WRONG_CIRCLE("wrong_circle"),
    UNKNOWN_DEVICE("unknown_device"),
    DEVICE_REVOKED("device_revoked"),
    DEVICE_EXISTS("device_exists"),
    MEMBER_EXISTS("member_exists"),
    AUTHOR_MISMATCH("author_mismatch"),
    BAD_SIGNATURE("bad_signature"),
    NOT_STEWARD("not_steward"),
    INVALID_PUBLIC("invalid_public"),
    INVALID_PAYLOAD("invalid_payload"),
    UNKNOWN_INVITE("unknown_invite"),
    INVITE_EXPIRED("invite_expired"),
    INVITE_EXHAUSTED("invite_exhausted"),
    BAD_INVITE_PROOF("bad_invite_proof"),
    STEWARD_PROTECTED("steward_protected"),
}

data class Rejection(val code: RejectionCode, val detail: String)
