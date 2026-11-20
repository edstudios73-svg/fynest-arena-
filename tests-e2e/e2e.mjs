// End-to-end browser test of the owner flow against a running SmartAttend on a FRESH database.
// Usage: scripts/e2e-fresh.sh   (needs a running app, psql access via $PSQL, Playwright Chromium)
//
// A fake camera can't pass a real face check, so this browser run switches the face steps off in settings
// and signs the student in with a server-side session; face checks are covered by the JUnit flow tests.
import { createRequire } from "module";
import { execFileSync } from "child_process";
import crypto from "crypto";
import fs from "fs";
const require = createRequire(import.meta.url);
const { chromium, devices } = require("playwright");

const BASE = process.env.BASE_URL || "http://localhost:8080";
const SHOTS = process.env.SHOTS || "tests-e2e/screens";
const PSQL = (process.env.PSQL || "psql postgresql://smartattend:devpass@localhost/smartattend").split(" ");
const SUPER = { email: process.env.SA_EMAIL || "super@smartattend.test", password: process.env.SA_PASSWORD || "Initial-Pass-2026!" };
const NEWPW = "E2e-Owner-Pass-2026!";
const CLASS = { latitude: 5.6037, longitude: -0.187 };
fs.mkdirSync(SHOTS, { recursive: true });
const sql = (q) => execFileSync(PSQL[0], [...PSQL.slice(1), "-Atc", q]).toString().trim();
let failures = 0;
const ok = (c, m) => { console.log((c ? "  ✓ " : "  ✗ ") + m); if (!c) failures++; };
function watch(page, name) {
  const errs = [];
  page.on("console", (m) => { if (m.type() === "error" && !m.text().startsWith("Failed to load resource")) errs.push(m.text()); });
  page.on("pageerror", (e) => errs.push(e.message));
  return () => ok(errs.length === 0, `${name}: no console/CSP errors${errs.length ? " — " + errs.join(" | ") : ""}`);
}

const browser = await chromium.launch({ args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] });

console.log("Public pages");
for (const [label, opts] of [["mobile", devices["Pixel 7"]], ["desktop", { viewport: { width: 1360, height: 900 } }]]) {
  const ctx = await browser.newContext(opts);
  for (const p of ["index", "login", "register", "admin-login", "privacy", "how-it-works"]) {
    const page = await ctx.newPage();
    const done = watch(page, `${p} (${label})`);
    await page.goto(`${BASE}/${p}.html`);
    await page.waitForLoadState("networkidle");
    if (["index", "login", "register"].includes(p)) await page.screenshot({ path: `${SHOTS}/${p}-${label}.png` });
    ok(!(await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1)), `${p} (${label}): no horizontal overflow`);
    ok(!(await page.isVisible("text=Lecturer")), `${p} (${label}): no lecturer links`);
    done();
    await page.close();
  }
  await ctx.close();
}

console.log("Admin (owner)");
const adminCtx = await browser.newContext({ viewport: { width: 1360, height: 900 }, geolocation: { ...CLASS, accuracy: 10 }, permissions: ["geolocation"] });
const admin = await adminCtx.newPage();
const adminDone = watch(admin, "admin");
await admin.goto(`${BASE}/admin-login.html`);
await admin.fill("#em", SUPER.email);
await admin.fill("#pw", SUPER.password);
await admin.click("button:has-text('Sign in')");
await admin.waitForSelector("text=New password");
await admin.fill("#cur", SUPER.password);
await admin.fill("#np", NEWPW);
await admin.fill("#np2", NEWPW);
await admin.click("button:has-text('Save')");
await admin.waitForSelector("#pageTitle:has-text('Courses')");
ok(true, "admin signs in and stays signed in");

const rows = ["Student ID,Title,First Name,Last Name,Other Names"];
for (let i = 1; i <= 134; i++) rows.push(`E2E${String(i).padStart(6, "0")},MR,Given${i},Family${i},`);
fs.writeFileSync(`${SHOTS}/register.csv`, rows.join("\n"));
await admin.goto(`${BASE}/admin.html#/register`);
await admin.setInputFiles("#file", `${SHOTS}/register.csv`);
await admin.click("button:has-text('Upload and preview')");
await admin.waitForSelector("text=Import 134 students");
await admin.click("button:has-text('Import 134 students')");
await admin.waitForSelector(".toast:has-text('Imported 134 students')");
ok(true, "class list imported (active immediately)");
for (const [k, v] of [["security.faceOnAttendance", "false"], ["security.faceOnCheckout", "false"]]) {
  await admin.evaluate(async ([k, v]) => SA.api("/api/admin/settings", { method: "PUT", body: { key: k, value: v } }), [k, v]);
}

await admin.goto(`${BASE}/admin.html#/`);
await admin.waitForSelector(".ccard:has-text('134 students')");
await admin.check("#locSwitch");
await admin.waitForSelector("#locText:has-text('On')");
await admin.screenshot({ path: `${SHOTS}/admin-courses.png`, fullPage: true });
const card = admin.locator(".ccard", { hasText: "Digital Electronics" });
await card.locator("[data-edit]").click();
await admin.fill("dialog input[name=classMinutes]", "60");
await admin.fill("dialog input[name=creditHours]", "3");
await admin.click("dialog button:has-text('Save')");
await admin.waitForSelector(".ccard:has-text('60 min')");
ok(true, "course edited (duration, credit hours)");
await admin.locator(".ccard", { hasText: "Digital Electronics" }).locator("[data-toggle]").check();
await admin.waitForSelector("#qr svg");
await admin.waitForFunction(() => /^\d{3} \d{3}$/.test(document.querySelector("#otp").textContent));
await admin.waitForSelector("#loc:has-text('Location on')");
ok(true, "class started with QR, code and admin location on");
ok(await admin.isVisible("#proj.fullscreen"), "turning attendance on opens the board view");
await admin.screenshot({ path: `${SHOTS}/admin-live.png` });
const code = async () => (await admin.textContent("#otp")).replace(/\s/g, "");
const firstCode = await code();
const firstQr = await admin.innerHTML("#qr");
await admin.waitForFunction((c) => document.querySelector("#otp").textContent.replace(/\s/g, "") !== c, firstCode, { timeout: 80000 });
ok((await admin.innerHTML("#qr")) !== firstQr, `code and QR change after 60 s (${firstCode} → ${await code()})`);

console.log("Student");
const idx = "E2E000007";
const studentId = sql(`SELECT id FROM students WHERE index_number='${idx}'`);
const userId = sql(`INSERT INTO app_users(role,status) VALUES ('STUDENT','ACTIVE') RETURNING id`).split("\n")[0];
sql(`UPDATE students SET user_id='${userId}' WHERE id='${studentId}'`);
const token = crypto.randomBytes(32).toString("base64url");
sql(`INSERT INTO auth_sessions(token_hash,user_id,csrf_token,expires_at) VALUES ('\\x${crypto.createHash("sha256").update(token).digest("hex")}','${userId}','${crypto.randomBytes(18).toString("base64url")}', now()+interval '2 hours')`);
const host = new URL(BASE).hostname;

// 1) too far away
const farCtx = await browser.newContext({ ...devices["Pixel 7"], geolocation: { latitude: CLASS.latitude + 0.0018, longitude: CLASS.longitude, accuracy: 10 }, permissions: ["geolocation"] });
await farCtx.addCookies([{ name: "sa_session", value: token, domain: host, path: "/" }]);
const far = await farCtx.newPage();
await far.goto(`${BASE}/student.html`);
await far.waitForSelector("text=Class open");
await far.screenshot({ path: `${SHOTS}/student-home-mobile.png` });
await far.click(".live-callout a:has-text('Mark attendance')");
await far.fill("#idx", "E2E000008");
await far.click("button:has-text('Next')");
await far.waitForSelector(".alert.error");
ok(await far.isVisible("text=doesn't match your account"), "someone else's index number is refused");
await far.fill("#idx", idx);
await far.click("button:has-text('Next')");
await far.waitForSelector("#otp");
await far.fill("#otp", await code());
await far.click("form button:has-text('Next')");
await far.waitForSelector(".alert.error", { timeout: 20000 });
ok(/m away/.test(await far.textContent(".alert.error")), "200 m away → refused: " + (await far.textContent(".alert.error")).trim());
await far.screenshot({ path: `${SHOTS}/student-too-far-mobile.png` });
await farCtx.close();

// 2) in class
const stuCtx = await browser.newContext({ ...devices["Pixel 7"], geolocation: { latitude: CLASS.latitude + 0.0002, longitude: CLASS.longitude, accuracy: 12 }, permissions: ["geolocation"] });
await stuCtx.addCookies([{ name: "sa_session", value: token, domain: host, path: "/" }]);
const stu = await stuCtx.newPage();
const stuDone = watch(stu, "student");
await stu.goto(`${BASE}/student.html`);
await stu.click(".course-row:has-text('Digital Electronics')");
await stu.waitForSelector("a:has-text('Mark attendance')");
await stu.click("a:has-text('Mark attendance')");
await stu.fill("#idx", idx);
await stu.click("button:has-text('Next')");
await stu.waitForSelector("#otp");
await stu.screenshot({ path: `${SHOTS}/student-code-mobile.png` });
await stu.fill("#otp", await code());
await stu.click("form button:has-text('Next')");
await stu.waitForSelector(".confirm h2:has-text('Checked in')", { timeout: 20000 });
ok(true, "in range (≈22 m) → checked in");
await stu.screenshot({ path: `${SHOTS}/student-checked-in-mobile.png` });
await admin.waitForFunction(() => document.querySelector("#liveStats .stat b").textContent === "1", null, { timeout: 20000 });
ok(true, "admin screen shows 1 checked in");

// 3) end of class
sql(`UPDATE attendance_sessions SET ends_at = now() + interval '5 minutes', closes_at = now() + interval '15 minutes' WHERE status='OPEN'`);
await stu.goto(`${BASE}/student.html#/`);
await stu.waitForSelector("a:has-text(\"Confirm I'm still here\")", { timeout: 30000 });
await stu.click("a:has-text(\"Confirm I'm still here\")");
await stu.click("button:has-text('Confirm')");
await stu.waitForSelector("text=Attendance marked", { timeout: 20000 });
ok(await stu.isVisible(".badge.PRESENT"), "end-of-class confirmation → PRESENT");
await stu.screenshot({ path: `${SHOTS}/student-marked-mobile.png` });

await admin.click("#offBtn");
await admin.click("dialog button:has-text('Turn off')");
await admin.waitForSelector(".off-mark:has-text('Attendance off')");
ok((await admin.locator("#qr, #otp").count()) === 0, "turning attendance off removes the QR code and code from the board");
await admin.screenshot({ path: `${SHOTS}/admin-board-off.png` });
ok(sql(`SELECT count(*) FROM attendance_records WHERE status='ABSENT'`) === "133", "everyone else marked absent on close");

console.log("Registration face screen");
const regCtx = await browser.newContext({ ...devices["Pixel 7"], permissions: ["camera"] });
const reg = await regCtx.newPage();
await reg.goto(`${BASE}/register.html`);
await reg.fill("#idx", "E2E000012");
await reg.click("button:has-text('Continue')");
await reg.waitForSelector("text=Given12 Family12");
ok(!(await reg.isVisible("text=verified")), "no waiting-for-verification message");
await reg.check("#agree");
await reg.click("#yes");
await reg.click("#startFace");
await reg.waitForSelector(".face-stage video");
const box = await reg.locator(".face-stage").boundingBox();
const vp = reg.viewportSize();
ok(box.height > vp.height * 0.6 && box.width > vp.width * 0.85, `face camera fills the screen (${Math.round(box.width)}×${Math.round(box.height)} of ${vp.width}×${vp.height})`);
await reg.screenshot({ path: `${SHOTS}/register-face-mobile.png` });
await reg.waitForSelector(".alert.error", { timeout: 30000 });
ok(/No face detected|face/i.test(await reg.textContent(".alert.error")), "fake camera (no real face) is refused by the real face engine");

adminDone(); stuDone();
await browser.close();
console.log(failures ? `\n${failures} check(s) FAILED` : "\nAll end-to-end checks passed");
process.exit(failures ? 1 : 0);
