// End-to-end browser test against a running SmartAttend (default http://localhost:8080).
// Run against a FRESH database (it imports a synthetic register and bootstraps the super admin).
// Usage: node tests-e2e/e2e.mjs   (needs: running app, psql access via $PSQL, Playwright Chromium)
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
const NEWPW = "E2e-Super-Pass-2026!";
fs.mkdirSync(SHOTS, { recursive: true });
const sql = (q) => execFileSync(PSQL[0], [...PSQL.slice(1), "-Atc", q]).toString().trim();
let failures = 0;
const ok = (c, m) => { console.log((c ? "  ✓ " : "  ✗ ") + m); if (!c) failures++; };

function watch(page, name) {
  const errs = [];
  page.on("console", (m) => { if (m.type() === "error" && !m.text().startsWith("Failed to load resource")) errs.push(m.text()); });
  page.on("pageerror", (e) => { errs.push(e.message); console.log("    [pageerror " + name + "] " + e.message); });
  page.on("response", (r) => { if (r.status() >= 400 && r.url().includes("/api/")) console.log("    [" + name + "] " + r.status() + " " + r.url()); });
  return () => { ok(errs.length === 0, `${name}: no console/CSP errors${errs.length ? " — " + errs.join(" | ") : ""}`); };
}

const browser = await chromium.launch({ args: ["--use-fake-device-for-media-stream", "--use-fake-ui-for-media-stream"] });

// ---------------------------------------------------------------- public pages
console.log("Public pages");
for (const [label, ctxOpts] of [["mobile", devices["Pixel 7"]], ["desktop", { viewport: { width: 1360, height: 900 } }]]) {
  const ctx = await browser.newContext(ctxOpts);
  for (const p of ["index", "how-it-works", "privacy", "about", "login", "register", "recover", "lecturer-login", "admin-login"]) {
    const page = await ctx.newPage();
    const done = watch(page, `${p} (${label})`);
    await page.goto(`${BASE}/${p}.html`);
    await page.waitForLoadState("networkidle");
    if (["index", "login", "register"].includes(p)) await page.screenshot({ path: `${SHOTS}/${p}-${label}.png`, fullPage: p === "index" });
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 1);
    ok(!overflow, `${p} (${label}): no horizontal overflow`);
    done();
    await page.close();
  }
  await ctx.close();
}

// ---------------------------------------------------------------- admin
console.log("Administrator");
const adminCtx = await browser.newContext({ viewport: { width: 1360, height: 900 } });
const admin = await adminCtx.newPage();
const adminDone = watch(admin, "admin console");
await admin.goto(`${BASE}/admin-login.html`);
await admin.fill("#em", SUPER.email);
await admin.fill("#pw", SUPER.password);
await admin.click("button:has-text('Sign in')");
await admin.waitForSelector("text=Choose a new password");
await admin.fill("#cur", SUPER.password);
await admin.fill("#np", NEWPW);
await admin.fill("#np2", NEWPW);
await admin.click("button:has-text('Save password')");
await admin.waitForSelector("#pageTitle:has-text('Overview')");
ok(await admin.isVisible("text=is not verified"), "overview warns that the register is not verified");

// Import a synthetic register of 134 students (no real student data).
const rows = ["Student ID,Title,First Name,Last Name,Other Names"];
for (let i = 1; i <= 134; i++) rows.push(`E2E${String(i).padStart(6, "0")},${i % 2 ? "Mr." : "Ms."},Given${i},Family${i},${i % 5 ? "" : "Other" + i}`);
fs.writeFileSync(`${SHOTS}/register.csv`, rows.join("\n"));
await admin.goto(`${BASE}/admin.html#/register`);
await admin.setInputFiles("#file", `${SHOTS}/register.csv`);
await admin.click("button:has-text('Upload and preview')");
await admin.waitForSelector("text=Preview:");
ok(await admin.isVisible("text=timetable states 129 students but the register will contain 134"), "preview flags 129 vs 134 discrepancy");
await admin.screenshot({ path: `${SHOTS}/admin-import-preview.png`, fullPage: true });
await admin.click("button:has-text('Import 134 students')");
await admin.waitForSelector("text=Discrepancy: the timetable states 129");
await admin.click("[data-verify]");
await admin.fill("textarea[name=note]", "E2E: reconciled with department list — 5 late admissions");
await admin.click("dialog button:has-text('Verify register')");
await admin.waitForSelector(".toast.error:has-text(\"acknowledge\")"); // must acknowledge discrepancy
ok(true, "verification refused without acknowledgement");
await admin.click("[data-verify]");
await admin.fill("textarea[name=note]", "E2E: reconciled with department list — 5 late admissions");
await admin.check("dialog input[name=ack]");
await admin.click("dialog button:has-text('Verify register')");
await admin.waitForSelector(".badge.VERIFIED");
ok(true, "register verified after acknowledging the discrepancy");

// Lecturer credentials for Digital Electronics (CSNS 141)
await admin.goto(`${BASE}/admin.html#/lecturers`);
await admin.waitForSelector("text=Gabriel Gidisu");
const row = admin.locator("tr", { hasText: "Gabriel Gidisu" });
await row.locator("[data-edit]").click();
await admin.fill("dialog input[name=email]", "g.gidisu@e2e.test");
await admin.click("dialog button:has-text('Save')");
await admin.waitForSelector("text=g.gidisu@e2e.test");
await admin.locator("tr", { hasText: "Gabriel Gidisu" }).locator("[data-cred]").click();
await admin.click("dialog button:has-text('Issue password')");
await admin.waitForSelector("code.secret");
const tempPw = (await admin.textContent("code.secret")).trim();
ok(tempPw.length >= 12, "temporary lecturer password issued");
await admin.click("dialog button:has-text('Done')");
// Pilot policies for this browser test: face checks off (no real face on a fake camera), passkeys stay on.
for (const [k, v] of [["security.faceOnLogin", "false"], ["security.faceOnAttendance", "false"], ["security.registrationRequiresApproval", "false"]]) {
  await admin.evaluate(async ([k, v]) => SA.api("/api/admin/settings", { method: "PUT", body: { key: k, value: v } }), [k, v]);
}
await admin.goto(`${BASE}/admin.html#/settings`);
await admin.waitForSelector("text=Face verification engine");
ok(await admin.isVisible("text=yunet"), "settings shows the real face engine (OpenCV YuNet + SFace)");
await admin.goto(`${BASE}/admin.html#/`);
await admin.waitForSelector(".stats");
await admin.screenshot({ path: `${SHOTS}/admin-overview.png`, fullPage: true });

// ---------------------------------------------------------------- student with passkey (virtual authenticator)
console.log("Student");
const idx = "E2E000007";
const studentId = sql(`SELECT id FROM students WHERE index_number='${idx}'`);
const userId = sql(`INSERT INTO app_users(role,status) VALUES ('STUDENT','ACTIVE') RETURNING id`).split("\n")[0];
sql(`UPDATE students SET user_id='${userId}' WHERE id='${studentId}'`);
const { publicKey, privateKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
const jwk = publicKey.export({ format: "jwk" });
const credId = crypto.randomBytes(32);
const x = Buffer.from(jwk.x, "base64url"), y = Buffer.from(jwk.y, "base64url");
const cose = Buffer.concat([Buffer.from([0xa5, 0x01, 0x02, 0x03, 0x26, 0x20, 0x01, 0x21, 0x58, 0x20]), x, Buffer.from([0x22, 0x58, 0x20]), y]);
const len = Buffer.alloc(2); len.writeUInt16BE(credId.length);
const acd = Buffer.concat([Buffer.alloc(16), len, credId, cose]);
sql(`INSERT INTO webauthn_credentials(user_id, credential_id, attested_credential_data, sign_count, uv_initialized, transports, label)
     VALUES ('${userId}', '\\x${credId.toString("hex")}', '\\x${acd.toString("hex")}', 0, true, 'internal', 'E2E virtual authenticator')`);

const stuCtx = await browser.newContext({ ...devices["Pixel 7"] });
const stu = await stuCtx.newPage();
const stuDone = watch(stu, "student app");
const cdp = await stuCtx.newCDPSession(stu);
await cdp.send("WebAuthn.enable");
const { authenticatorId } = await cdp.send("WebAuthn.addVirtualAuthenticator", { options: { protocol: "ctap2", transport: "internal", hasResidentKey: true, hasUserVerification: true, isUserVerified: true } });
await cdp.send("WebAuthn.addCredential", { authenticatorId, credential: { credentialId: credId.toString("base64"), isResidentCredential: false, rpId: "localhost", privateKey: privateKey.export({ format: "der", type: "pkcs8" }).toString("base64"), signCount: 0 } });

await stu.goto(`${BASE}/login.html`);
await stu.fill("#idx", idx.toLowerCase());
await stu.click("button:has-text('Sign in')");
await stu.waitForSelector("#pageTitle:has-text('Home')");
ok(await stu.isVisible("text=Hello, Given7"), "student signed in with passkey and sees dashboard");
await stu.screenshot({ path: `${SHOTS}/student-dashboard-mobile.png`, fullPage: true });

// ---------------------------------------------------------------- lecturer
console.log("Lecturer");
const lecCtx = await browser.newContext({ viewport: { width: 1360, height: 900 } });
const lec = await lecCtx.newPage();
const lecDone = watch(lec, "lecturer app");
await lec.goto(`${BASE}/lecturer-login.html`);
await lec.fill("#em", "g.gidisu@e2e.test");
await lec.fill("#pw", tempPw);
await lec.click("button:has-text('Sign in')");
await lec.waitForSelector("text=Choose a new password");
await lec.fill("#cur", tempPw);
await lec.fill("#np", "Lecturer-E2e-Pass-1!");
await lec.fill("#np2", "Lecturer-E2e-Pass-1!");
await lec.click("button:has-text('Save password')");
await lec.waitForSelector("text=Digital Electronics");
ok(!(await lec.isVisible("text=Discrete Mathematics")), "lecturer sees only assigned course");
await lec.click("[data-start]");
await lec.selectOption("dialog select[name=mode]", "IN_PERSON");
await lec.click("dialog button:has-text('Start attendance')");
await lec.waitForSelector("#qr svg");
await lec.waitForFunction(() => /^\d{3} \d{3}$/.test(document.querySelector("#otp").textContent));
const otp = (await lec.textContent("#otp")).replace(/\s/g, "");
ok(/^\d{6}$/.test(otp), "live screen shows rotating 6-digit code and QR");
await lec.screenshot({ path: `${SHOTS}/lecturer-live.png` });

// ---------------------------------------------------------------- student marks attendance
await stu.reload();
await stu.waitForSelector("text=Attendance open now");
await stu.screenshot({ path: `${SHOTS}/student-attendance-open-mobile.png` });
await stu.click(".live-callout a:has-text('Mark attendance')");
await stu.fill("#otp", "000000" === otp ? "111111" : "000000");
await stu.click("button:has-text('Continue')");
await stu.waitForSelector(".alert.error");
ok(await stu.isVisible("text=Invalid code"), "wrong code is rejected with a clear message");
await stu.fill("#otp", (await lec.textContent("#otp")).replace(/\s/g, ""));
await stu.click("button:has-text('Continue')");
await stu.click("button:has-text('Confirm on this phone')");
await stu.waitForSelector(".confirm h2");
const heading = await stu.textContent(".confirm h2");
ok(/marked present|marked late/.test(heading), "attendance confirmation shown: " + heading);
ok(await stu.isVisible(`dd:has-text('${idx}')`), "receipt shows index number");
await stu.screenshot({ path: `${SHOTS}/student-confirmation-mobile.png`, fullPage: true });

await lec.waitForSelector(`#feed :text('Given7')`, { timeout: 10000 });
ok(true, "lecturer live screen updated in real time");
await lec.click("#closeBtn");
await lec.click("dialog button:has-text('Close attendance')");
await lec.waitForSelector("#pageTitle:has-text('Session record')");
ok(await lec.isVisible("text=Absent"), "closing marks remaining students absent");
await lec.screenshot({ path: `${SHOTS}/lecturer-session-record.png`, fullPage: true });

await stu.goto(`${BASE}/student.html#/history`);
await stu.waitForSelector(".badge.PRESENT, .badge.LATE");
ok(true, "student history lists the record");
await stu.goto(`${BASE}/student.html#/timetable`);
await stu.waitForSelector("text=Linear Algebra");
await stu.screenshot({ path: `${SHOTS}/student-timetable-mobile.png`, fullPage: true });

// ---------------------------------------------------------------- registration with fake camera hits the real face pipeline
console.log("Registration with a fake camera");
const regCtx = await browser.newContext({ ...devices["Pixel 7"], permissions: ["camera"] });
const reg = await regCtx.newPage();
await reg.goto(`${BASE}/register.html`);
await reg.fill("#idx", "E2E000012");
await reg.click("button:has-text('Continue')");
await reg.waitForSelector("text=Given12 Family12");
await reg.click("#yes");
await reg.check("#agree");
await reg.click("#go");
await reg.click("#startFace");
await reg.waitForSelector(".face-stage video");
await reg.screenshot({ path: `${SHOTS}/register-face-mobile.png` });
await reg.waitForSelector(".alert.error", { timeout: 30000 });
const msg = await reg.textContent(".alert.error");
ok(/No face detected|face/i.test(msg), "synthetic camera feed (no real face) is rejected by the real face engine: " + msg.trim());

adminDone(); stuDone(); lecDone();
await browser.close();
console.log(failures ? `\n${failures} check(s) FAILED` : "\nAll end-to-end checks passed");
process.exit(failures ? 1 : 0);
