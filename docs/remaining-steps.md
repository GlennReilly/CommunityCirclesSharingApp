# What is left: step-by-step (written 2026-11-05)

Everything in the code that can be built and tested without people and devices is done. What remains needs **you**: a real server, real phones, a screen reader and a few real people. This guide gives the steps in the order that works best.

**Why this order:** QR codes and passkeys need an address a phone can open over HTTPS (a passkey refuses an IP address, and a QR link must reach the server). So install the host first (Part 1) and run every later test against it.

Time: Part 1 about half a day; Parts 2 to 4 about two hours each; Part 5 an evening with friends.

| Part | What | You need |
|---|---|---|
| 1 | Install on a real machine with HTTPS | A Raspberry Pi or small VPS, a domain name |
| 2 | Try every screen on a real phone, plus QR codes | An Android or iPhone, a second phone or laptop |
| 3 | Passkey unlock on real devices | A phone or laptop with a fingerprint, face or PIN |
| 4 | Screen-reader pass of the borrow flow | A screen reader (steps below) |
| 5 | A stranger follows the operator guides; a small group tries it | 3 to 5 people |

Keep a notes file open as you go (a plain text file is fine). For anything odd, write: **which screen, what you did, what you expected, what happened, which phone and browser.** Part 6 says what to send back.

---

## Part 1. Install a host over HTTPS

Full reference: `docs/operator/install.md`. These are the steps for a Docker install with automatic certificates.

**Before you start**
1. Pick a machine: a Raspberry Pi 4 or a small VPS (1 core, 512 MB of memory is enough for 300 members; see `docs/operator/requirements.md`).
2. Pick a domain name (for example `lendger.example.org`). Add a DNS "A" record pointing it at the machine's public address.
3. Make sure ports 80 and 443 are open to the internet (router port forwarding at home; the firewall page on a VPS).

**On your development computer** (in `/mnt/2TB_SSD/Dev/ClaudeCodeExperiments/CommunityCirclesSharingApp`)
4. Build the jar: `./gradlew :server:shadowJar`. It appears at `server/build/libs/lendger-server.jar`.
5. Copy the `deploy/` folder and that jar to the machine (for example with `scp`), keeping the same folder layout.

**On the machine** (Docker with the Compose plugin installed)
6. Make a folder for backups, ideally on another disk:
   `sudo mkdir -p /srv/lendger-backups && sudo chown 10001:10001 /srv/lendger-backups`
7. In the folder that holds `deploy/`, create a file called `.env` with these lines (use your own domain):
   ```
   BACKUP_DIR_ON_HOST=/srv/lendger-backups
   LENDGER_DOMAIN=lendger.example.org
   LENDGER_TRUST_PROXY=true
   ```
8. Start it with the certificate proxy: `docker compose -f deploy/compose.yaml --profile tls up -d --build`
9. Wait about 30 seconds, then check: `docker compose -f deploy/compose.yaml ps` (the circles service says "healthy").
10. From any computer, open `https://lendger.example.org/healthz`. It should say `ok`, and the browser should show a padlock with no warning.

**Prove the basics**
11. Open `https://lendger.example.org` and choose **Start a circle**. Pick a passphrase of at least 12 characters. You should land on **Circle home**.
12. Backups: run `docker compose -f deploy/compose.yaml exec lendger java -Xmx256m -jar /app/lendger-server.jar backup /backups` if you want to force one now (the folder inside the container is the one you mapped). Then look in `/srv/lendger-backups` for a new file.
13. Restore test (do this once; it is the thing you will be glad of later): follow `docs/operator/backup-restore.md`, section "Practice once", on a spare folder or machine. Write down how long it took.
14. Reboot the machine. After it is back, step 10 should work again with no action from you.

**Done when:** the padlock is green, a circle can be started, a backup file exists, a restore has been proven and the server survives a reboot. Note anything in `docs/operator/` that was wrong or unclear.

---

## Part 2. Real phone, every screen, and QR codes

You need two devices that can both open your host: **Phone A** (the one you are testing) and **Device B** (a second phone or a laptop) so there are two members.

**Set up two members**
1. On Device B, open `https://lendger.example.org`, **Start a circle**, and sign in. This is the founder.
2. On Device B go to **Members**, tap **Invite**, then **Copy** the link. Send it to Phone A (a message to yourself works; the link holds a secret, so delete it afterwards).
3. On Phone A open the link, choose a passphrase, and **Join**. Phone A now says it is waiting.
4. On Device B, **Members** now shows Phone A's person under "waiting". Tap **Let in**. Sync both (the circular arrows at the top right).

**Argon2 time (important)**
5. On Phone A, lock the app (padlock icon), then sign in again and time how long between pressing the button and the screen changing. Write the seconds down. If it is longer than about 3 seconds on your slowest phone, tell me: the key-stretching cost needs re-tuning.

**Walk through every screen on Phone A** (use the navigation bar). For each, check: text is readable without zooming, buttons are easy to hit with a thumb, nothing is cut off at the sides, the keyboard does not hide the field you are typing in.

| Screen | Try |
|---|---|
| Circle home | Summary links open the right pages |
| Items | Add an item with a name; add a photo from the camera or gallery; open "Larger photos" |
| Skills | Offer a skill; use the search box |
| Borrow | Request an item that Device B owns (see below) |
| My standing | Read the page; does it make sense to someone new? |
| Members | Invite, vouch, "Show my QR code" |
| Stewards | Read the rules; try proposing a rule change |
| Settings | Change text size to Largest and colours to Dark; confirm everything still fits; set it back |

**Borrow flow with two people** (both devices signed in)
6. Device B: **Items**, add "Ladder" (class Everyday). Sync both.
7. Phone A: **Items**, ask to borrow it, then **Borrow**: check the request shows under "waiting".
8. Device B: **Borrow**, approve it. Then mark it handed over. Phone A confirms. Then return it and confirm the return. Every step should appear on the other device within a couple of seconds without pressing Sync (live updates); if it takes longer, note it.

**QR codes** (uses the phone's normal camera app; there is no scanner inside Lendger)
9. Device B: **Members**, open "Show my QR code". Hold Phone A's camera at it. The phone should offer to open a link ending in `#/verify/...`. Open it: the page should say the fingerprint **matches**.
10. Make a doctored check: on Phone A change one character in that link in the address bar and open it. It should say it does **not** match.
11. Device B: **Items**, open an item, tap **Print a tag**, then **Print this tag** (print to paper or "Save as PDF"). Scan the printed tag with Phone A: it should open the Items page showing only that item.

**Slow network and offline**
12. On Phone A switch on airplane mode. Add an item. The header should say changes are waiting. Switch airplane mode off: it should sync by itself within about a minute (or press Sync now).
13. On Phone A, in the browser menu choose **Install app** or **Add to Home Screen**. Open it from the home screen: it should open with a spinner and then the sign-in page.
14. If you can, set the phone to a poor connection (mobile data with one bar) and load the site fresh: it should show the spinner and "Opening Lendger", then work. Note how many seconds it took.

**Done when:** every row above was tried and you wrote down anything that was hard to read, hard to tap, slow or confusing.

---

## Part 3. Passkey ("Unlock with this device")

Needs HTTPS on your real domain (Part 1). Tested so far only with Chrome's pretend authenticator.

1. On Phone A, **Settings**, find **Unlock with this device**. If the section is missing, the browser or device does not support it: note the browser name and version, then try another (Chrome on Android, Safari on a recent iPhone, Chrome or Edge on a laptop with Windows Hello or Touch ID).
2. Tap **Turn this on**. The device should ask for your fingerprint, face or PIN. Confirm.
3. Lock the app (padlock icon). On the sign-in page tap **Unlock with this device** and confirm. You should be back in without typing the passphrase.
4. Refuse on purpose: lock again, tap the button and cancel the fingerprint prompt. The page should say so kindly and the passphrase should still work.
5. Settings, **Turn this off**; lock; the passkey button should be gone.
6. Repeat steps 1 to 3 on a second kind of device (a laptop if you used a phone first).

**Done when:** it works, fails kindly and turns off on at least two real devices. Note exactly which ones.

---

## Part 4. Screen-reader pass of the borrow flow

The goal: a person using only a screen reader finishes borrowing, unaided. An automatic checker (axe) passes already; this checks what it cannot.

**Pick a screen reader**
- Android phone: **TalkBack** (Settings, Accessibility). Swipe right/left to move, double-tap to activate.
- iPhone: **VoiceOver** (Settings, Accessibility). Same swipes.
- Windows: **NVDA** (free, nvaccess.org). Move with Tab and arrow keys; `Insert+Space` toggles modes.
- Linux: **Orca** (`Super+Alt+S` to start on many desktops).

Switch it on, close your eyes or turn the screen off (TalkBack "screen curtain"; VoiceOver "screen curtain" gesture), and do the tasks below using only what you hear.

**Tasks** (Device B owns "Ladder" as in Part 2; you are the borrower)
1. Sign in to the app. *Check:* you hear the page title "Sign in" and the field label "Passphrase: ".
2. Go to **Items** and find "Ladder". *Check:* you can tell its name, who it is open to, and the button to ask to borrow.
3. Ask to borrow it with two dates. *Check:* the two date fields are named, and you hear a confirmation ("Request sent").
4. Open **Borrow**. *Check:* you can tell which loans need you to do something, and which are waiting.
5. After the owner approves, confirm the hand-over and later return it. *Check:* each button says what it does; nothing is only an icon you cannot name; after each action you hear the result.
6. Lock and unlock with the padlock icon. *Check:* the icon buttons are announced as "Lock now" and "Sync now".

**Also check**: you never get stuck in a part of the page you cannot leave; the on-screen message ("Saved.", "Request sent.") is read aloud without you hunting for it; the spinner on a busy button is announced as busy.

**Reading level:** hand the Borrow page and the My standing page to someone who has not seen the app. Ask them to say in their own words what each is for. Anything they stumble on, write down verbatim.

**Done when:** you completed tasks 1 to 6 without looking, or you have a list of exactly where you could not.

---

## Part 5. A stranger and a small group

**A. The operator guides (someone who has not seen the code)**
1. Find one person who is comfortable with a command line but has not worked on this project.
2. Give them only the `docs/operator/` folder and a blank machine (Part 1 again, on a second machine).
3. Do not help. Watch (or have them narrate) and note every place they get stuck or have to ask.
4. Fix or ask me to fix the guide where they stumbled.

**B. A small group (3 to 5 people, an evening)**
1. You start a circle and invite everyone (Members, Invite, one link each; let each person in).
2. Everyone adds one real item or skill they would genuinely lend or offer, with a photo if possible.
3. Do three real borrows between different pairs, including one with a "valuable" item so people meet the tier rules.
4. Two people vouch for the newest member. Check **My standing** changes in a way they understand.
5. Make one steward change: propose a second steward, agree on it from two phones. Then each steward makes a recovery code (Settings, **Make a recovery code**) and writes it on paper.
6. Remove one person who agrees to play the removed member (Members, **Remove this person**), then **Change the key**. Check they are signed out, and the others still work.
7. Ask each person afterwards: what was confusing, what felt slow, what they would change first, and whether they would use it again.

**Done when:** everyone completed at least one borrow and nobody needed you to explain how to use a screen (installing and inviting excepted).

---

## Part 6. What to send back

Send me (or paste into the chat):
- The Argon2 sign-in time on each phone (Part 2, step 5), and the first-load time on a poor connection.
- A list of anything that was hard to read, tap, hear or understand, with the screen name.
- Which devices and browsers passed or failed the passkey test.
- What went wrong in the operator guides (Parts 1 and 5A).
- Anything in the group evening that confused people (Part 5B, step 7).

I will turn each item into a fix, and write an ADR if one needs a decision from you. If something blocks you part-way, stop there and tell me the step number.
