/* Registration, student sign-in, device recovery and staff sign-in flows. Each step is enforced by the server. */
(function () {
  "use strict";
  const { esc, $, api, busy } = SA;
  const flow = () => $("#flow");
  const CONSENT_TEXT = `<p>SmartAttend uses a live face check to confirm that the person marking attendance is the registered student.</p>
    <ul class="small">
      <li>We store an encrypted numeric face template — not your photo. Camera frames are deleted after analysis.</li>
      <li>It is used only for GCTU attendance and account recovery, never to identify you elsewhere.</li>
      <li>It is kept for your programme (about five years) and you can ask for it to be deleted.</li>
    </ul>
    <p class="small">Read the full <a href="/privacy.html" target="_blank" rel="noopener">privacy notice</a>.</p>`;

  function progress(n, of) {
    return `<div class="progress" aria-label="Step ${n} of ${of}">${Array.from({ length: of }, (_, i) => `<i class="${i < n ? "done" : ""}"></i>`).join("")}</div>`;
  }
  function screen(html) { flow().innerHTML = `<div class="panel">${html}</div>`; return flow(); }
  function nextTarget() {
    const p = new URLSearchParams(location.search).get("next");
    return p && /^\/student\.html(#[\w/?=&.-]*)?$/.test(p) ? p : "/student.html";
  }

  /* ------------------------------------------------ face step (shared) */
  async function faceStep({ title, intro, challengePath, uploadPath, body, onPass, n, of }) {
    screen(`${progress(n, of)}<h2>${esc(title)}</h2><p class="muted">${esc(intro)}</p>
      <ul class="small muted"><li>Find good, even light — avoid a bright window behind you.</li><li>Remove sunglasses or face coverings.</li><li>Follow each instruction until the oval turns green.</li></ul>
      <div class="err"></div><button class="btn block lg" id="startFace" type="button" data-busy="Preparing…">Start face check</button>`);
    const err = $(".err", flow());
    $("#startFace").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      let ch = await api(challengePath, { method: "POST", body: body || {} });
      if (ch.enrolRequired) {
        const ok = await SA.formDialog({ title: "Re-enrol your face", intro: "Your face template was removed. Agree to enrol again to continue.", fields: [{ name: "agree", type: "checkbox", label: "I consent to SmartAttend storing an encrypted face template for attendance verification." }], submit: "Continue" });
        if (!ok || !ok.agree) throw new SA.ApiError(0, "CONSENT", "Consent is required to continue. Ask an administrator about supervised verification.");
        ch = await api(challengePath, { method: "POST", body: { accepted: true, consentVersion: ch.consentVersion } });
      }
      const stage = document.createElement("div");
      flow().firstElementChild.replaceChildren(stage);
      try {
        const res = await SA.faceCapture(stage, ch, (fd) => api(uploadPath, { method: "POST", form: fd }));
        if (res.passed) return onPass(res);
        faceStep({ title, intro, challengePath, uploadPath, body, onPass, n, of }).then(() => { $(".err", flow()).innerHTML = SA.alert("error", res.message || "Face check failed. Try again."); });
      } catch (ex) {
        faceStep({ title, intro, challengePath, uploadPath, body, onPass, n, of }).then(() => { $(".err", flow()).innerHTML = SA.alert("error", ex.message); });
      }
    }, err));
  }

  /* ------------------------------------------------ registration */
  function register() {
    screen(`${progress(1, 5)}<h1 class="h2">Register for SmartAttend</h1><p class="muted">Use the index number on your GCTU student ID.</p>
      <form id="f" novalidate><div class="field"><label for="idx">Index number</label>
      <input id="idx" class="index-input" type="text" autocomplete="username" autocapitalize="characters" spellcheck="false" required></div>
      <div class="err"></div><button class="btn block lg" data-busy="Checking…">Continue</button></form>
      <p class="small muted">Already registered? <a href="/login.html">Sign in</a>. New phone? <a href="/recover.html">Recover your account</a>.</p>`);
    $("#idx").focus();
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const idx = $("#idx").value.trim();
      const err = $(".err", flow());
      if (!idx) { err.innerHTML = SA.alert("error", "Enter your index number."); return; }
      busy($("button", e.target), async () => confirmName(await api("/api/auth/student/register/start", { method: "POST", body: { indexNumber: idx } })), err);
    });
  }

  function confirmName(s) {
    screen(`${progress(2, 5)}<h2>Is this you?</h2><p class="muted">This is the name on the official class register.</p>
      <div class="name-confirm"><b>${esc(s.fullName)}</b><span class="muted">${esc(s.indexNumber)} · ${esc(s.programme)}, Level ${esc(s.level)} · ${esc(s.group)}</span></div>
      <div class="err"></div><button class="btn block lg" id="yes" type="button">Yes, this is me</button>
      <button class="btn ghost block" id="no" type="button">No, the name is wrong</button>`);
    const err = $(".err", flow());
    $("#yes").addEventListener("click", (e) => busy(e.currentTarget, async () => { await api("/api/auth/student/register/confirm", { method: "POST", body: { confirmed: true } }); consent(); }, err));
    $("#no").addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Report a name problem", intro: "The department will check the register. Don't register with an incorrect name.", fields: [{ name: "note", label: "What is wrong?", type: "textarea", required: true }], submit: "Send report" });
      if (!r) return;
      await busy(null, async () => {
        await api("/api/auth/student/register/confirm", { method: "POST", body: { confirmed: false, note: r.note } });
        screen(`<h2>Report sent</h2><p>An administrator will review the register. You'll be able to register once it's corrected.</p><a class="btn" href="/">Back to home</a>`);
      }, err);
    });
  }

  function consent() {
    screen(`${progress(3, 5)}<h2>Face check consent</h2>${CONSENT_TEXT}
      <label class="check"><input type="checkbox" id="agree"> <span>I agree to the face check and the storage of an encrypted face template for attendance.</span></label>
      <div class="err"></div><button class="btn block lg" id="go" type="button" disabled>Continue</button>
      <button class="btn ghost block" id="alt" type="button">I can't or don't want to use the face check</button>`);
    const err = $(".err", flow());
    $("#agree").addEventListener("change", (e) => { $("#go").disabled = !e.target.checked; });
    $("#go").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      await api("/api/auth/student/register/consent", { method: "POST", body: { accepted: true, consentVersion: "2026-11-v1" } });
      enrolFace();
    }, err));
    $("#alt").addEventListener("click", () => alternative(err));
  }

  async function alternative(err) {
    const r = await SA.formDialog({ title: "Supervised verification", intro: "An administrator will contact you to arrange in-person verification. Your lecturer can then record your attendance.", fields: [{ name: "reason", label: "Tell us why (e.g. no camera, phone without screen lock, don't consent)", type: "textarea", required: true }], submit: "Request supervised verification" });
    if (!r) return;
    busy(null, async () => {
      await api("/api/auth/student/register/alternative", { method: "POST", body: { reason: r.reason } });
      screen(`<h2>Request received</h2><p>The department will arrange supervised verification with you. Until then, your lecturer can mark you present in person.</p><a class="btn" href="/">Back to home</a>`);
    }, err);
  }

  function enrolFace() {
    faceStep({ n: 4, of: 5, title: "Enrol your face", intro: "We'll ask you to look straight ahead and turn your head left and right.",
      challengePath: "/api/auth/student/register/face/challenge", uploadPath: "/api/auth/student/register/face", onPass: passkeyStep });
  }

  async function passkeyStep() {
    const supported = await SA.passkeySupported();
    screen(`${progress(5, 5)}<h2>Secure this phone</h2><p>Create a passkey so only this phone can sign in as you. You'll confirm with your fingerprint, Face ID or screen lock.</p>
      ${supported ? "" : SA.alert("warn", "This browser reports no built-in fingerprint/face unlock. Make sure your phone has a screen lock set, or use Chrome / Safari.")}
      <div class="err"></div><button class="btn block lg" id="pk" type="button" data-busy="Waiting for your device…">Create passkey</button>
      <button class="btn ghost block" id="alt" type="button">My phone doesn't support passkeys</button>`);
    const err = $(".err", flow());
    $("#pk").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      const opts = await api("/api/auth/student/register/passkey/options", { method: "POST", body: {} });
      const cred = await SA.createPasskey(opts);
      const r = await api("/api/auth/student/register/passkey/verify", { method: "POST", body: cred });
      screen(`<div class="confirm"><div class="stamp ${r.status === "ACTIVE" ? "" : "warn"}">${SA.icon.check}</div><h2>You're registered</h2>
        <p>${r.status === "ACTIVE" ? "Your account is active. You can mark attendance from your home screen." : "Your registration is complete. The department will verify your account before you can mark attendance."}</p>
        <a class="btn block lg" href="/student.html">Go to my dashboard</a></div>`);
    }, err));
    $("#alt").addEventListener("click", () => alternative(err));
  }

  /* ------------------------------------------------ student sign-in */
  function studentLogin() {
    screen(`<h1 class="h2">Student sign in</h1><p class="muted">Enter your index number. Your phone will ask for your fingerprint, face or screen lock.</p>
      <form id="f" novalidate><div class="field"><label for="idx">Index number</label>
      <input id="idx" class="index-input" type="text" autocomplete="username webauthn" autocapitalize="characters" spellcheck="false" required></div>
      <div class="err"></div><button class="btn block lg" data-busy="Waiting for your device…">Sign in</button></form>
      <p class="small muted">First time? <a href="/register.html">Register</a> · Changed phone? <a href="/recover.html">Recover your account</a></p>`);
    const saved = localStorage.getItem("sa_index");
    if (saved) $("#idx").value = saved;
    $("#idx").focus();
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const idx = $("#idx").value.trim();
      const err = $(".err", flow());
      if (!idx) { err.innerHTML = SA.alert("error", "Enter your index number."); return; }
      busy($("button", e.target), async () => {
        const start = await api("/api/auth/student/login/start", { method: "POST", body: { indexNumber: idx } });
        const cred = await SA.usePasskey(start.publicKey);
        const r = await api("/api/auth/student/login/passkey", { method: "POST", body: cred });
        localStorage.setItem("sa_index", idx.toUpperCase());
        if (r.next === "FACE") {
          faceStep({ n: 2, of: 2, title: "Confirm it's you", intro: "A quick live face check completes sign-in.",
            challengePath: "/api/auth/student/login/face/challenge", uploadPath: "/api/auth/student/login/face", onPass: () => (location.href = nextTarget()) });
        } else location.href = nextTarget();
      }, err);
    });
  }

  /* ------------------------------------------------ recovery */
  function recover() {
    screen(`<h1 class="h2">Recover your account</h1><p class="muted">Use this on your new phone if you lost, replaced or reset your old one. An administrator must approve the new phone.</p>
      <form id="f" novalidate><div class="field"><label for="idx">Index number</label><input id="idx" class="index-input" type="text" autocapitalize="characters" required></div>
      <div class="field"><label for="why">What happened?</label><textarea id="why" placeholder="e.g. My phone was stolen on 12 November"></textarea></div>
      <div class="err"></div><button class="btn block lg" data-busy="Checking…">Continue</button></form>`);
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const err = $(".err", flow());
      const idx = $("#idx").value.trim(), why = $("#why").value.trim();
      if (!idx) { err.innerHTML = SA.alert("error", "Enter your index number."); return; }
      busy($("button", e.target), async () => {
        const st = await api("/api/auth/student/recover/start", { method: "POST", body: { indexNumber: idx, reason: why } });
        if (st.requestStatus === "NEW" && !why) throw new SA.ApiError(0, "REASON", "Tell us what happened to your old phone.");
        if (!st.faceEnrolled) { await submitRecovery(why, false); return; }
        faceStep({ n: 1, of: 2, title: "Confirm it's you", intro: "Your face is checked against your enrolment before the request goes to an administrator.",
          challengePath: "/api/auth/student/recover/face/challenge", uploadPath: "/api/auth/student/recover/face",
          onPass: async (r) => {
            if (r.requestStatus === "APPROVED") return newPasskey();
            if (r.requestStatus === "PENDING") return pending();
            await submitRecovery(why, true);
          } });
      }, err);
    });
  }

  async function submitRecovery(reason, faceOk) {
    await api("/api/auth/student/recover/submit", { method: "POST", body: { reason: reason || "Device change" } });
    pending(faceOk);
  }

  function pending() {
    screen(`<div class="confirm"><div class="stamp warn">${SA.icon.clock}</div><h2>Waiting for approval</h2>
      <p>Your request is with the department. Bring your student ID if asked. When it's approved, come back to this page <b>on this same phone</b> to finish.</p>
      <a class="btn secondary block" href="/recover.html">Check again</a></div>`);
  }

  async function newPasskey() {
    screen(`<h2>Approved — secure this phone</h2><p>Create a passkey on this phone. Your old phone will be signed out.</p>
      <div class="err"></div><button class="btn block lg" id="pk" type="button" data-busy="Waiting for your device…">Create passkey</button>`);
    $("#pk").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      const o = await api("/api/auth/student/recover/passkey/options", { method: "POST", body: {} });
      const cred = await SA.createPasskey(o.publicKey);
      await api("/api/auth/student/recover/passkey/verify", { method: "POST", body: cred });
      location.href = "/student.html";
    }, $(".err", flow())));
  }

  /* ------------------------------------------------ staff */
  function staffLogin(portal) {
    const isAdmin = portal === "admin";
    screen(`<h1 class="h2">${isAdmin ? "Administrator" : "Lecturer"} sign in</h1>
      <form id="f" novalidate><div class="field"><label for="em">Email</label><input id="em" type="email" autocomplete="username" required></div>
      <div class="field"><label for="pw">Password</label><input id="pw" type="password" autocomplete="current-password" required></div>
      <div class="err"></div><button class="btn block lg" data-busy="Signing in…">Sign in</button></form>
      <p class="small muted">Accounts are issued by the SmartAttend administrator. Forgotten your password? Ask an administrator to issue a new temporary password.</p>`);
    $("#em").focus();
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const err = $(".err", flow());
      busy($("button", e.target), async () => {
        const r = await api("/api/auth/staff/login", { method: "POST", body: { email: $("#em").value, password: $("#pw").value, portal } });
        if (r.mustChangePassword) return changePassword(r.role);
        location.href = r.role === "LECTURER" ? "/lecturer.html" : "/admin.html";
      }, err);
    });
  }

  async function changePassword(role) {
    await SA.session();
    screen(`<h2>Choose a new password</h2><p class="muted">You signed in with a temporary password. Use at least 12 characters with a mix of upper and lower case letters, numbers or symbols.</p>
      <form id="f" novalidate><div class="field"><label for="cur">Temporary password</label><input id="cur" type="password" autocomplete="current-password"></div>
      <div class="field"><label for="np">New password</label><input id="np" type="password" autocomplete="new-password" minlength="12"></div>
      <div class="field"><label for="np2">Repeat new password</label><input id="np2" type="password" autocomplete="new-password"></div>
      <div class="err"></div><button class="btn block lg" data-busy="Saving…">Save password</button></form>`);
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const err = $(".err", flow());
      if ($("#np").value !== $("#np2").value) { err.innerHTML = SA.alert("error", "The new passwords don't match."); return; }
      busy($("button", e.target), async () => {
        await api("/api/me/password", { method: "POST", body: { currentPassword: $("#cur").value, newPassword: $("#np").value } });
        location.href = role === "LECTURER" ? "/lecturer.html" : "/admin.html";
      }, err);
    });
  }

  document.addEventListener("DOMContentLoaded", async () => {
    const page = document.body.dataset.page;
    if (page === "student-login" || page === "register") {
      const s = await SA.session().catch(() => null);
      if (s && s.role === "STUDENT") { location.href = nextTarget(); return; }
    }
    ({ "student-login": studentLogin, register, recover, "lecturer-login": () => staffLogin("lecturer"), "admin-login": () => staffLogin("admin") })[page]();
  });
})();
