"""Records the Lendger demo screen take: the people (Alice, Ben, plus Carol and a fourth member) in separate browser contexts against a throwaway server.
Each context records its own video; segments.json says whose screen is on show when, and captions are logged with cap().
Usage: python record_lendger.py <out-dir>   (SHOTS=1 also saves a PNG at every caption, for probing)
Environment: LENDGER_JAR (default <repo>/server/build/libs/lendger-server.jar), CHROME (Chromium binary; default: newest in ~/.cache/ms-playwright), PORT (default 18097)."""
import os, glob, json, time, sys, subprocess, datetime, urllib.request
from playwright.sync_api import sync_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
if len(sys.argv) != 2 or sys.argv[1] in ('-h', '--help'): sys.exit(__doc__)
REPO = os.path.abspath(os.path.join(HERE, '..', '..'))
OUT = os.path.abspath(sys.argv[1]); os.makedirs(OUT, exist_ok=True)
JAR = os.environ.get('LENDGER_JAR', os.path.join(REPO, 'server', 'build', 'libs', 'lendger-server.jar'))
if not os.path.exists(JAR): sys.exit(f'No server jar at {JAR}: run ./gradlew :server:shadowJar')
PORT = int(os.environ.get('PORT', '18097'))
URL = f'http://localhost:{PORT}'
SHOTS = os.environ.get('SHOTS') == '1'
PASS_A = 'maple street toolshed'
PASS_B = 'blue door by the park'
PASS_C = 'lemon tree on the corner'
PASS_D = 'green gate past the shops'
_found = sorted(glob.glob(os.path.expanduser('~/.cache/ms-playwright/chromium-*/chrome-linux*/chrome')))
exe = os.environ.get('CHROME') or (_found[-1] if _found else sys.exit('No Chromium found: run "python -m playwright install chromium" or set CHROME'))
PHOTO = os.path.join(OUT, 'ladder.jpg')
subprocess.run([sys.executable, os.path.join(HERE, 'make_photo.py'), PHOTO], check=True)

# Throwaway server: its own new data folder, no rate limit (two browsers share one address).
data = os.path.join(OUT, 'server-data'); os.makedirs(data)
env = dict(os.environ, LENDGER_DATA_DIR=data, LENDGER_BIND='127.0.0.1', LENDGER_RATE_LIMIT='false', LENDGER_PORT=str(PORT))
srv = subprocess.Popen(['java', '-jar', JAR, 'serve'], env=env, stdout=open(os.path.join(OUT, 'server.log'), 'w'), stderr=subprocess.STDOUT)
for _ in range(120):
    try:
        if urllib.request.urlopen(f'http://127.0.0.1:{PORT}/healthz').status == 200: break
    except Exception: pass
    time.sleep(0.25)

INIT = """
localStorage.setItem('circles.textSize', 'large');
window.addEventListener('DOMContentLoaded',()=>{
  const c=document.createElement('div'); c.id='demo-cursor';
  c.style.cssText='position:fixed;left:-50px;top:-50px;width:22px;height:22px;border-radius:50%;background:rgba(11,87,208,.30);border:2px solid #0b57d0;z-index:99999;pointer-events:none;transform:translate(-50%,-50%);transition:transform .08s';
  document.body.appendChild(c);
  document.addEventListener('mousemove',e=>{c.style.left=e.clientX+'px';c.style.top=e.clientY+'px'});
  document.addEventListener('mousedown',()=>c.style.transform='translate(-50%,-50%) scale(.6)');
  document.addEventListener('mouseup',()=>c.style.transform='translate(-50%,-50%) scale(1)');
});
"""
SYNC_JS = 'document.querySelector("[data-action=sync]")?.click()'
caps, segs = [], []
flashes = {'alice': [], 'bea': [], 'carol': [], 'dan': []}
T0 = time.time()
def now(): return time.time() - T0
day = lambda n: (datetime.datetime.now(datetime.timezone.utc).date() + datetime.timedelta(days=n)).isoformat()

class Person:
    def __init__(self, browser, name):
        self.name = name
        self.ctx = browser.new_context(viewport={'width': 1280, 'height': 720}, record_video_dir=os.path.join(OUT, name), record_video_size={'width': 1280, 'height': 720})
        self.ctx.add_init_script(INIT)
        self.pg = self.ctx.new_page(); self.t0 = now()
        self.pg.on('console', lambda m: m.type == 'error' and print(f'[{name} console] {m.text}'))
    def wait(self, s): self.pg.wait_for_timeout(int(s * 1000))
    def text(self): return self.pg.inner_text('#app')
    def has(self, s): return s in self.text()
    def until(self, cond, what, secs=25):
        t = time.time()
        while time.time() - t < secs:
            try:
                if cond(): return
            except Exception: pass
            self.wait(.25)
        raise RuntimeError(f'{self.name}: timeout waiting for {what}\n' + self.text()[:1500])
    def until_text(self, s, secs=25): self.until(lambda: self.has(s), repr(s), secs)
    def sync_until(self, cond, what, secs=30, visible=False):
        t = time.time()
        while time.time() - t < secs:
            if cond(): return
            if visible: self.click('[data-action="sync"]')
            else: self.pg.evaluate('document.querySelector("[data-action=sync]")?.click()')
            self.wait(1.5)
        raise RuntimeError(f'{self.name}: timeout (sync) waiting for {what}\n' + self.text()[:1500])
    def mv(self, sel, steps=22):
        loc = self.pg.locator(sel).first; loc.scroll_into_view_if_needed()
        bb = loc.bounding_box(); self.pg.mouse.move(bb['x'] + bb['width'] / 2, bb['y'] + bb['height'] / 2, steps=steps)
    def click(self, sel, pause=.3):
        self.mv(sel); self.wait(pause); self.pg.locator(sel).first.click()
    def type_in(self, sel, text, delay=60):
        self.click(sel); self.pg.keyboard.type(text, delay=delay)
    def nav(self, path, visible=True):
        sel = f'nav a[href="#/{path}"]'
        if visible: self.click(sel)
        else: self.pg.evaluate(f'location.hash = "#/{path}"')
    def scroll_to(self, sel, block='center'):
        self.pg.evaluate(f'document.querySelector({json.dumps(sel)})?.scrollIntoView({{behavior:"smooth", block:"{block}"}})'); self.wait(.9)
    def top(self): self.pg.evaluate('window.scrollTo({top:0, behavior:"smooth"})'); self.wait(.6)

def flash(p, colour):
    """Covers the page in one colour for a moment and logs the wall time, so compose.py can line the video clock up with ours."""
    p.pg.evaluate("c => { const d = document.createElement('div'); d.id = 'demo-flash'; d.style.cssText = 'position:fixed;inset:0;z-index:100000;background:' + c; document.documentElement.appendChild(d); }", colour)
    p.wait(.05); flashes[p.name].append(now()); p.wait(.6)
    p.pg.evaluate("document.getElementById('demo-flash')?.remove()"); p.wait(.4)

def mark(p, action, member_text, exclude=False):
    """Tags the [action] button in the card that does (or, with exclude, does not) mention member_text, as #demo-target."""
    return p.pg.evaluate("""([a, t, ex]) => { document.getElementById('demo-target')?.removeAttribute('id');
      const b = [...document.querySelectorAll('[data-action="' + a + '"]')].find(b => { const c = b.closest('li, section > div'); return c && (c.innerText.includes(t) !== ex); });
      if (b) b.id = 'demo-target'; return !!b; }""", [action, member_text, exclude])

def mark_card(p, action, name):
    """Tags the [action] button in the card whose heading is exactly this person's name, as #demo-target."""
    return p.pg.evaluate("""([a, n]) => { document.getElementById('demo-target')?.removeAttribute('id');
      const b = [...document.querySelectorAll('[data-action="' + a + '"]')].find(b => { const c = b.closest('li, section > div'); if (!c) return false;
        const first = c.innerText.trim().split('\\n')[0].trim(); return first === n || first.startsWith(n + ' ('); });
      if (b) b.id = 'demo-target'; return !!b; }""", [action, name])

def let_in_hidden(newcomer, what):
    """Alice lets the one waiting person in, off camera: Check and let in, tick, Let them in."""
    A.pg.evaluate('location.hash = "#/members"')
    A.sync_until(lambda: A.pg.locator('[data-action="check-person"][data-device]').count() > 0, what + ' waiting')
    A.pg.evaluate('document.querySelector("[data-action=check-person][data-device]").click()')
    A.until(lambda: A.pg.locator('form[id^="let-in-"]').count() > 0, what + ' let-in form')
    A.pg.evaluate("""(() => { const f = document.querySelector('form[id^="let-in-"]'); const b = f.querySelector('input[type=checkbox]'); b.checked = true; b.dispatchEvent(new Event('input', {bubbles: true})); f.requestSubmit(); })()""")
    A.until_text('Let in.'); A.wait(1.0); A.pg.evaluate(SYNC_JS)
    A.sync_until(lambda: A.pg.locator('[data-action="check-person"][data-device]').count() == 0, what + ' admitted')
    newcomer.sync_until(lambda: newcomer.pg.locator('#name-form').count() > 0, what + ' name form')

def hide():
    """Cut: nothing is shown until the next show() (background work, e.g. the other person syncing)."""
    segs.append((now(), None))

def show(p):
    """The video shows this person's screen from now on."""
    segs.append((now(), p.name))

shot_n = [0]
def cap(text, who=None):
    caps.append((now(), text))
    if SHOTS and who is not None:
        shot_n[0] += 1; who.pg.screenshot(path=os.path.join(OUT, f'shot{shot_n[0]:02d}.png'))

with sync_playwright() as pw:
    b = pw.chromium.launch(executable_path=exe)
    A = Person(b, 'alice'); B = Person(b, 'bea'); C = Person(b, 'carol'); D = Person(b, 'dan')
    A.pg.goto(URL + '/'); B.pg.goto('about:blank'); C.pg.goto('about:blank'); D.pg.goto('about:blank')
    A.until_text('Welcome to Lendger'); A.wait(.8)
    flash(A, '#ff0000'); flash(B, '#ff0000'); flash(C, '#ff0000'); flash(D, '#ff0000'); A.wait(1.2)
    START = now()
    try:
        SYNC = 'document.querySelector("[data-action=sync]")?.click()'
        # 3. Start a circle, and pick a name
        NAMEF = lambda p: p.pg.locator('#name-form').count() > 0
        show(A); cap('Start a circle. Only your group can read it', A)
        A.wait(1.2); A.click('a[href="#/start"]'); A.until(lambda: A.pg.locator('#start-form').count() > 0, 'start form'); A.wait(.6)
        A.type_in('#passphrase', PASS_A, 60); A.wait(.5)
        A.click('#start-form #submit'); A.until(lambda: NAMEF(A), 'alice name form', 30); A.wait(.6)
        cap('Pick the name others will see', A)
        A.type_in('#display-name', 'Alice', 90); A.wait(.5)
        A.click('#name-form #submit'); A.until_text('Circle home', 30); A.until_text('Up to date', 30); A.wait(1.4)

        # 4. Invite Ben; he joins and Alice checks who he is before letting him in
        cap('Invite someone with a one-time link', A)
        A.nav('members'); A.until(lambda: A.pg.locator('[data-action="invite"]').count() > 0, 'invite button'); A.wait(.6)
        A.click('[data-action="invite"]'); A.until(lambda: A.pg.locator('#invite-output').count() > 0, 'invite link'); A.wait(.3)
        A.scroll_to('#invite-output'); A.mv('#invite-output'); A.wait(1.6)
        hide()
        link = A.pg.input_value('#invite-output')
        A.pg.evaluate(SYNC); A.wait(1.2)
        B.pg.goto(link); B.until(lambda: B.pg.locator('#join-form').count() > 0, 'join form'); B.wait(.5)
        show(B); cap('Ben joins from his own device', B)
        B.wait(.6); B.type_in('#passphrase', PASS_B, 55); B.wait(.4)
        B.click('#join-form #submit'); B.until_text('A steward needs to let you in', 30); B.wait(.8)
        B.pg.evaluate('document.querySelector("svg.qr")?.scrollIntoView({behavior: "smooth", block: "center"})'); B.wait(2.0)
        hide()
        A.nav('members', visible=False); A.sync_until(lambda: A.pg.locator('[data-action="check-person"][data-device]').count() > 0, 'ben waiting')
        A.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()')
        A.scroll_to('[data-action="check-person"][data-device]')
        show(A); cap("Alice checks Ben's identity, then lets him in", A)
        A.wait(1.0); A.click('[data-action="check-person"][data-device]')
        A.until(lambda: A.pg.locator('form[id^="let-in-"]').count() > 0, 'let-in form'); A.wait(.4)
        A.mv('text=With a camera'); A.wait(1.4); A.mv('p.fingerprint'); A.wait(1.4)
        A.scroll_to('form[id^="let-in-"]'); A.click('form[id^="let-in-"] input[type="checkbox"]'); A.wait(.7)
        A.click('form[id^="let-in-"] button[type="submit"]'); A.until_text('Let in.'); A.wait(1.4)
        hide()
        A.pg.evaluate(SYNC)
        A.sync_until(lambda: A.pg.locator('[data-action="check-person"][data-device]').count() == 0, 'ben admitted')
        B.sync_until(lambda: NAMEF(B), 'ben name form')
        B.pg.fill('#display-name', 'Ben'); B.pg.evaluate('document.getElementById("name-form").requestSubmit()'); B.until_text('Circle home', 30)
        B.pg.evaluate(SYNC); B.wait(1.2)
        BEA = 'Ben'
        # Carol joins, names herself and becomes a second steward, off camera.
        A.pg.evaluate('location.hash = "#/members"'); A.wait(.8)
        def new_invite(prev):
            A.pg.evaluate('location.hash = "#/members"'); A.wait(.6)
            A.pg.evaluate('document.querySelector("[data-action=invite]").click()')
            A.until(lambda: A.pg.locator('#invite-output').count() > 0 and A.pg.input_value('#invite-output') != prev, 'new invite')
            l = A.pg.input_value('#invite-output'); A.pg.evaluate(SYNC); A.wait(1.5); return l
        link2 = new_invite(link)
        C.pg.goto(link2); C.until(lambda: C.pg.locator('#join-form').count() > 0, 'carol join form')
        C.pg.fill('#passphrase', PASS_C); C.pg.evaluate('document.getElementById("join-form").requestSubmit()')
        C.until_text('A steward needs to let you in', 30)
        let_in_hidden(C, 'carol')
        C.pg.fill('#display-name', 'Carol'); C.pg.evaluate('document.getElementById("name-form").requestSubmit()'); C.until_text('Circle home', 30)
        C.pg.evaluate(SYNC); C.wait(1.5)
        A.pg.evaluate('location.hash = "#/stewards"'); A.sync_until(lambda: A.has('Carol') and mark_card(A, 'steward-add', 'Carol'), 'carol propose button')
        A.pg.evaluate('document.getElementById("demo-target").click()')
        A.until_text('Done. It takes effect now.'); A.pg.evaluate(SYNC); A.wait(1.5)
        C.sync_until(lambda: True, 'carol sync'); print('carol is set up')
        # A fourth person joins off camera and tries the name Ben, which is taken.
        link3 = new_invite(link2)
        D.pg.goto(link3); D.until(lambda: D.pg.locator('#join-form').count() > 0, 'dan join form')
        D.pg.fill('#passphrase', PASS_D); D.pg.evaluate('document.getElementById("join-form").requestSubmit()')
        D.until_text('A steward needs to let you in', 30)
        let_in_hidden(D, 'dan'); D.wait(.8)
        show(D); cap('Names are unique in a circle', D)
        D.wait(.6); D.type_in('#display-name', 'Ben', 120); D.wait(.4)
        D.click('#name-form #submit'); D.until_text('Ben is already used in this circle'); D.wait(2.6)
        D.pg.fill('#display-name', ''); D.type_in('#display-name', 'Ben T.', 110); D.wait(.4)
        D.click('#name-form #submit'); D.until_text('Circle home', 30); D.wait(1.0)
        hide()
        D.pg.evaluate(SYNC)
        # Alice confirms Ben's identity in person.
        A.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()'); A.pg.evaluate('location.hash = "#/members"')
        A.sync_until(lambda: mark_card(A, 'check-person', 'Ben') and A.has('Ben T.'), 'check button for ben')
        A.scroll_to('#demo-target')
        show(A); cap('Identities are confirmed in person', A)
        A.wait(.8); A.click('#demo-target'); A.until(lambda: A.pg.locator('form[id^="confirm-name-"]').count() > 0, 'confirm form'); A.wait(.4)
        A.pg.evaluate('window.scrollTo(0, 0)'); A.mv('text=With a camera'); A.wait(.8)
        cap('Scan a QR code, or read the characters out', A)
        A.wait(1.0); A.mv('p.fingerprint'); A.wait(1.6)
        A.scroll_to('form[id^="confirm-name-"]'); A.click('form[id^="confirm-name-"] input[type="checkbox"]'); A.wait(.7)
        A.click('form[id^="confirm-name-"] button[type="submit"]'); A.until_text('You confirmed their identity.'); A.wait(.6)
        A.pg.evaluate('window.scrollTo({top: 0, behavior: "smooth"})'); A.wait(1.8)
        hide()
        A.pg.evaluate(SYNC); A.wait(.8)
        A.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()'); A.pg.evaluate('location.hash = "#/home"'); A.wait(1.2); A.pg.evaluate('window.scrollTo(0, 0)'); A.wait(.5)

        # 5. Add an item with a photo and private details
        show(A); cap('Add an item, a photo and private details', A)
        A.nav('items'); A.until(lambda: A.pg.locator('a[href="#/items/add"]').count() > 0, 'add link'); A.wait(.5)
        A.click('a[href="#/items/add"]'); A.until(lambda: A.pg.locator('#item-add-form').count() > 0, 'add form'); A.wait(.4)
        A.type_in('#item-add-form [name="name"]', 'Extension ladder', 50); A.wait(.3)
        A.type_in('#item-add-form [name="details"]', 'In the garage. Side gate code 4821.', 38); A.wait(.6)
        A.click('#item-add-form #submit'); A.until_text('Extension ladder'); A.wait(.8)
        A.click('[data-action="edit-item"]'); A.until(lambda: A.pg.locator('#photo-add-form').count() > 0, 'photo form')
        A.scroll_to('#photo-add-form'); A.mv('#photo-file'); A.wait(.5)
        A.pg.set_input_files('#photo-file', PHOTO)
        A.until(lambda: A.pg.evaluate('!document.querySelector("#photo-add-form #submit").hidden'), 'add-photo button'); A.wait(.4)
        A.click('#photo-add-form #submit'); A.until(lambda: A.pg.locator('img.photo[src^="blob:"]').count() > 0, 'photo shown', 40)
        A.scroll_to('img.photo'); A.wait(1.6)
        hide()
        A.pg.evaluate('location.hash = "#/items"'); A.until(lambda: A.pg.locator('img.thumb[src^="blob:"]').count() > 0, 'thumb', 30)
        A.pg.evaluate(SYNC); A.wait(1)
        B.nav('items', visible=False); B.sync_until(lambda: B.has('Extension ladder') and B.pg.locator('img.thumb[src^="blob:"]').count() > 0, 'bea sees ladder')
        show(B); cap('Ben sees the ladder, but not the details yet', B)
        B.wait(.4); B.mv('img.thumb'); B.wait(2.6)

        # 6. Borrow, approve, hand over, return, confirm
        cap('He asks to borrow it, with dates', B)
        B.click('[data-action="borrow-item"]'); B.until(lambda: B.pg.locator('#loan-request-form').count() > 0, 'request form'); B.wait(.4)
        B.mv('#loan-request-form [name="from"]'); B.pg.fill('#loan-request-form [name="from"]', day(1)); B.wait(.3)
        B.mv('#loan-request-form [name="to"]'); B.pg.fill('#loan-request-form [name="to"]', day(3)); B.wait(.3)
        B.type_in('#loan-request-form [name="note"]', 'Clearing the gutters on Saturday', 40); B.wait(.4)
        B.click('#loan-request-form #submit'); B.until_text('Waiting for an answer'); B.wait(1.2)
        hide()
        B.pg.evaluate(SYNC)
        A.nav('borrow', visible=False); A.sync_until(lambda: A.has('Requests to answer'), 'request')
        show(A); cap('Alice approves; the details unlock for Ben', A)
        A.wait(.8); A.click('[data-action="approve-loan"]'); A.until_text('Ready to hand over'); A.wait(.8)
        hide()
        A.pg.evaluate(SYNC)
        B.nav('borrow', visible=False); B.sync_until(lambda: B.has('Ready to hand over') and B.has('Side gate code'), 'bea approved')
        B.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()')
        show(B); B.wait(.4); B.mv('text=Details: In the garage'); B.wait(2.0)
        cap('Hand over, return, confirm: all on record', B)
        B.click('[data-action="handover-loan"]'); B.until_text('You have confirmed'); B.wait(.8)
        hide()
        B.pg.evaluate(SYNC)
        A.sync_until(lambda: A.has('has confirmed'), 'alice sees bea confirm')
        A.pg.evaluate('document.querySelector("[data-action=handover-loan]").click()'); A.until_text('Out now'); A.wait(1.0)
        A.pg.evaluate(SYNC)
        B.sync_until(lambda: B.has('How did it go:'), 'bea out now'); B.wait(.6)
        B.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()')
        B.scroll_to('form[id^="return-form-"]')
        show(B)
        B.mv('form[id^="return-form-"] [name="outcome"]'); B.wait(.4)
        B.pg.select_option('form[id^="return-form-"] [name="outcome"]', 'OK'); B.wait(.4)
        B.click('form[id^="return-form-"] button[type="submit"]'); B.until_text('Reported back'); B.wait(1.0)
        hide()
        B.pg.evaluate(SYNC)
        A.sync_until(lambda: A.has('It came back fine'), 'alice sees return')
        A.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()')
        A.scroll_to('[data-action="confirm-return"]')
        show(A); A.wait(.5); A.click('[data-action="confirm-return"]'); A.until_text('Confirmed. Thank you.'); A.wait(1.4)

        # 7. Trust and standing
        hide()
        A.pg.evaluate('location.hash = "#/items/add"'); A.until(lambda: A.pg.locator('#item-add-form').count() > 0, 'add form 2')
        A.pg.fill('#item-add-form [name="name"]', 'Cordless drill'); A.pg.select_option('#item-add-form [name="kind"]', 'VALUABLE')
        A.pg.fill('#item-add-form [name="details"]', 'Two batteries, charger in the case.')
        A.pg.evaluate('document.getElementById("item-add-form").requestSubmit()'); A.until_text('Cordless drill')
        A.pg.evaluate(SYNC)
        B.nav('items', visible=False); B.sync_until(lambda: B.has('Cordless drill') and B.has('Not yet.'), 'drill not yet')
        B.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()')
        B.pg.evaluate('(() => { const li = [...document.querySelectorAll("main li")].find(l => l.innerText.includes("Cordless drill")); li?.querySelector("details")?.setAttribute("open", ""); li?.scrollIntoView({block: "center"}); })()'); B.wait(.4)
        show(B); cap('Valuable things open up as trust grows', B)
        B.wait(.5); B.mv('main li:has-text("Cordless drill") details'); B.wait(3.2)
        hide()
        A.nav('members', visible=False); A.until(lambda: mark_card(A, 'check-person', 'Ben'), 'check ben')
        A.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()'); A.wait(.4); assert mark_card(A, 'check-person', 'Ben')
        A.pg.evaluate('document.getElementById("demo-target").click()'); A.until(lambda: A.pg.locator('[data-action="vouch"]').count() > 0, 'vouch on check page')
        A.scroll_to('[data-action="vouch"]')
        show(A); cap('Two members, Alice and Carol, vouch for Ben', A)
        A.wait(.7); A.click('[data-action="vouch"]'); A.wait(1.8)
        hide()
        A.pg.evaluate(SYNC)
        C.nav('members', visible=False); C.sync_until(lambda: mark_card(C, 'check-person', 'Ben'), 'carol check ben')
        C.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()'); C.wait(.4); assert mark_card(C, 'check-person', 'Ben')
        C.pg.evaluate('document.getElementById("demo-target").click()'); C.until(lambda: C.pg.locator('[data-action="vouch"]').count() > 0, 'carol vouch on check page')
        C.scroll_to('[data-action="vouch"]')
        show(C); C.wait(.6); C.click('[data-action="vouch"]'); C.wait(1.8)
        hide()
        C.pg.evaluate(SYNC); C.wait(1)
        B.nav('items', visible=False)
        B.sync_until(lambda: B.pg.evaluate("""[...document.querySelectorAll('main li')].some(l => l.innerText.includes('Cordless drill') && l.querySelector('[data-action=borrow-item]'))"""), 'drill open for bea', 40)
        B.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()')
        B.pg.evaluate("""(() => { const li = [...document.querySelectorAll('main li')].find(l => l.innerText.includes('Cordless drill')); li.querySelector('[data-action=borrow-item]').id = 'demo-target'; li.scrollIntoView({block: 'center'}); })()"""); B.wait(.4)
        show(B); cap('Now the drill opens up for Ben', B)
        B.wait(.6); B.mv('#demo-target'); B.wait(2.8)
        hide()
        B.nav('standing', visible=False); B.until_text('Your level'); B.wait(.5); B.pg.evaluate('window.scrollTo(0, 0)')
        show(B); cap('Everyone sees their own standing, and why', B)
        B.wait(1.6); B.pg.mouse.wheel(0, 240); B.wait(2.0)
        hide()
        # 8. QR tags and dark theme
        A.pg.evaluate('location.hash = "#/items"'); A.until(lambda: A.pg.locator('[data-action="tag-item"]').count() > 0, 'tag button')
        A.pg.evaluate('document.querySelector("[data-action=dismiss]")?.click()'); A.pg.evaluate('window.scrollTo(0, 0)'); A.wait(.6)
        show(A); cap('Print a QR tag to stick on each item', A); A.wait(.8)
        A.click('[data-action="tag-item"]'); A.until(lambda: A.pg.locator('svg.qr').count() > 0, 'qr'); A.wait(3.0)
        hide()
        A.pg.evaluate('location.hash = "#/items"'); A.wait(.6); A.pg.evaluate('window.scrollTo(0, 0)')
        A.pg.emulate_media(color_scheme='dark'); A.wait(.6)
        show(A); cap('Only members can read it, not even the server', A)
        A.wait(1.2); A.mv('img.thumb', 30); A.wait(3.4)
        cap(None); END = now(); A.wait(.5)
        flash(A, '#00ff00'); flash(B, '#00ff00'); flash(C, '#00ff00'); flash(D, '#00ff00')
        print('BEA FINAL:', B.text()[:300].replace('\n', ' | '))
    except Exception as e:
        print('FAILED:', e)
        A.pg.screenshot(path=os.path.join(OUT, 'fail-alice.png')); B.pg.screenshot(path=os.path.join(OUT, 'fail-bea.png'))
        END = now()
    vids = {}
    for p in (A, B, C, D):
        v = p.pg.video; p.ctx.close(); vids[p.name] = {"file": str(v.path()), 't0': p.t0}
    b.close()
srv.terminate()
json.dump({'start': START, 'end': END, 'caps': caps, 'segs': segs, 'videos': vids, 'flashes': flashes}, open(os.path.join(OUT, 'marks.json'), 'w'), indent=1)
print('start', START, 'end', END, 'caps', len(caps), 'segs', len(segs))
