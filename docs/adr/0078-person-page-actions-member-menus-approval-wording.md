# 0078. Person page actions, links beside member menus, and the approval notice wording

- Status: Accepted
- Date: 2026-11-13
- Supersedes: nothing. It extends ADR 0076 (person pages, name links) and ADR 0071 (checking identity in person); ADR 0077 (messages) is not edited, and the notice wording in point 4 refines one of its examples.

## Context

ADR 0076 put a "Check identity in person" button on the person page and left Confirm identity and Vouch on the check page. The owner wants those two reachable from the person page too, without a second place that confirms. ADR 0076 also left two menus of members (propose a pause, thank someone) as plain text, because a menu option cannot hold a link. And the owner changed the wording of the notice a steward sees after letting someone in.

## Decision

1. **Launch buttons on the person page.** For another member the page shows, in this order: **Check identity in person** (the primary route), then **Confirm identity** only when the viewer can confirm now (`MemberSummary.canConfirmName`), then **Vouch** only when the viewer can vouch now (`HomeData.canVouch`) and has not already. All three do the same thing: they open the check page (`#/members/check`) for that member. The in-person tick, the QR route and the characters stay on that page (ADR 0071). The person page never confirms or vouches by itself, shows no tick box and no confirm form, and does not repeat the QR or characters.
2. **No dead buttons.** A button that would not work is not shown; one plain sentence says why instead: no name yet ("This member has not chosen a name yet, so there is nothing to confirm."), identity already confirmed ("This member's identity is already confirmed."), not enough standing ("Only members at Level 2 or above, or a steward, can confirm a member's identity." and the same for vouching, wording through `TierText`), or, when already vouched, "You vouch for this member until <date>."
3. **A link beside every menu of members.** The menus that list members (propose a pause, thank someone) are built by one helper, `memberMenu`. Next to the menu it writes a "View <name>" link per member with `personName`; only the chosen member's link shows, the rest are hidden, and `Main.onInput` shows the right one when the menu changes. The links are ordinary links in the tab order, the group is a polite live region, and the first member (selected by default) shows at once. The menu options stay plain text, the only plain-text names left in a view besides notices and search text. `PersonNameSourceScanTest` now fails unless there is exactly one such option line, inside `memberMenu`, and `memberMenu` writes the view links with `personName`.
4. **Approval notice.** After a steward lets someone in the notice reads "<name>'s access has now been approved. The circle opens for their device once it syncs." and with no name "The new member's access has now been approved. ...". The failure reads "Could not approve <name>'s access just now. Try again after the next sync." (no name: "the new member's"). The 's is always added after the name, including a name ending in s ("James's"). Button labels and headings that say "let in" are not changed here.

## Consequences

- Three buttons on the person page share one action, so a member with all three offered still lands on the same check page; this is the owner's choice.
- Vouch is only offered when the existing rule allows it, so the person page needs no new rule.
- A menu of members costs one extra line of links under it; with one member there is one link.
- The word "approve" now appears in the notice while the buttons and page titles still say "let in" (ADR 0071); aligning them is left to the owner.
