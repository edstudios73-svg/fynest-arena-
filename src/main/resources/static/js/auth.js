/* Sign in, register and admin sign-in. Minimal screens; every step is enforced by the server. */
(function () {
  "use strict";
  const { esc, $, api, busy } = SA;
  const flow = () => $("#flow");
  const screen = (html) => { flow().innerHTML = `<div class="panel">${html}</div>`; return flow(); };
  const nextTarget = () => {
    const p = new URLSearchParams(location.search).get("next");
    return p && /^\/student\.html(#[\w/?=&.-]*)?$/.test(p) ? p : "/student.html";
  };

  /* ---------- face step (shared) ---------- */
  function faceStep({ title, challengePath, uploadPath, onPass, error }) {
    screen(`<h1 class="h2">${esc(title)}</h1><p class="muted">Good light, face the camera, follow the prompts.</p>
      ${error ? SA.alert("error", error) : ""}<div class="err"></div>
      <button class="btn block lg" id="startFace" type="button" data-busy="Starting…">Scan face</button>`);
    $("#startFace").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      let ch = await api(challengePath, { method: "POST", body: {} });
      if (ch.enrolRequired) ch = await api(challengePath, { method: "POST", body: { accepted: true, consentVersion: ch.consentVersion } });
      const stage = document.createElement("div");
      flow().firstElementChild.replaceChildren(stage);
      try {
        const res = await SA.faceCapture(stage, ch, (fd) => api(uploadPath, { method: "POST", form: fd }));
        if (res.passed) return onPass(res);
        faceStep({ title, challengePath, uploadPath, onPass, error: res.message || "Face not recognised. Try again." });
      } catch (ex) {
        faceStep({ title, challengePath, uploadPath, onPass, error: ex.message });
      }
    }, $(".err", flow())));
  }

  function indexForm({ title, button, sub, onSubmit }) {
    screen(`<h1 class="h2">${esc(title)}</h1>
      <form id="f" novalidate><div class="field"><label for="idx">Index number</label>
      <input id="idx" class="index-input" type="text" inputmode="numeric" autocomplete="username" autocapitalize="characters" spellcheck="false" required></div>
      <div class="err"></div><button class="btn block lg" data-busy="Checking…">${esc(button)}</button></form>${sub || ""}`);
    const saved = localStorage.getItem("sa_index");
    if (saved) $("#idx").value = saved;
    $("#idx").focus();
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const idx = $("#idx").value.trim();
      const err = $(".err", flow());
      if (!idx) { err.innerHTML = SA.alert("error", "Enter your index number."); return; }
      busy($("button", e.target), () => onSubmit(idx), err);
    });
  }

  /* ---------- student sign in ---------- */
  function studentLogin() {
    indexForm({
      title: "Sign in", button: "Continue",
      sub: `<p class="small muted center">New here? <a href="/register.html">Register</a></p>`,
      onSubmit: async (idx) => {
        const r = await api("/api/auth/student/login/start", { method: "POST", body: { indexNumber: idx } });
        localStorage.setItem("sa_index", idx.toUpperCase());
        if (r.next === "PASSKEY") {
          const cred = await SA.usePasskey(r.publicKey);
          const p = await api("/api/auth/student/login/passkey", { method: "POST", body: cred });
          if (p.next !== "FACE") { location.href = nextTarget(); return; }
        }
        faceStep({ title: "Scan your face", challengePath: "/api/auth/student/login/face/challenge",
          uploadPath: "/api/auth/student/login/face", onPass: () => (location.href = nextTarget()) });
      },
    });
  }

  /* ---------- registration ---------- */
  function register() {
    indexForm({
      title: "Register", button: "Continue",
      sub: `<p class="small muted center">Already registered? <a href="/login.html">Sign in</a></p>`,
      onSubmit: async (idx) => {
        const s = await api("/api/auth/student/register/start", { method: "POST", body: { indexNumber: idx } });
        localStorage.setItem("sa_index", idx.toUpperCase());
        confirmName(s);
      },
    });
  }

  function confirmName(s) {
    screen(`<h1 class="h2">Is this you?</h1>
      <div class="name-confirm"><b>${esc(s.fullName)}</b><span class="muted">${esc(s.indexNumber)}</span></div>
      <label class="check"><input type="checkbox" id="agree"> <span>I agree to my face being saved for attendance. <a href="/privacy.html" target="_blank" rel="noopener">Privacy</a></span></label>
      <div class="err"></div>
      <button class="btn block lg" id="yes" type="button" disabled>Yes, continue</button>
      <button class="btn ghost block" id="no" type="button">This isn't me</button>`);
    const err = $(".err", flow());
    $("#agree").addEventListener("change", (e) => { $("#yes").disabled = !e.target.checked; });
    $("#yes").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      await api("/api/auth/student/register/confirm", { method: "POST", body: { confirmed: true } });
      await api("/api/auth/student/register/consent", { method: "POST", body: { accepted: true, consentVersion: "2026-11-v1" } });
      enrolFace();
    }, err));
    $("#no").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      await api("/api/auth/student/register/confirm", { method: "POST", body: { confirmed: false, note: "Student says the register name is not theirs" } });
      screen(`<h1 class="h2">Reported</h1><p>The admin will check the class list.</p><a class="btn block" href="/">OK</a>`);
    }, err));
  }

  function enrolFace(error) {
    faceStep({ title: "Save your face", error, challengePath: "/api/auth/student/register/face/challenge",
      uploadPath: "/api/auth/student/register/face",
      onPass: async (r) => {
        if (r.registered) return done();
        // Passkey mode (if switched on by the admin)
        screen(`<h1 class="h2">Secure this phone</h1><div class="err"></div><button class="btn block lg" id="pk" type="button" data-busy="Waiting…">Continue</button>`);
        $("#pk").addEventListener("click", (e) => busy(e.currentTarget, async () => {
          const opts = await api("/api/auth/student/register/passkey/options", { method: "POST", body: {} });
          await api("/api/auth/student/register/passkey/verify", { method: "POST", body: await SA.createPasskey(opts) });
          done();
        }, $(".err", flow())));
      } });
  }

  function done() {
    screen(`<div class="confirm"><div class="stamp">${SA.icon.check}</div><h1 class="h2">You're registered</h1>
      <a class="btn block lg" href="/student.html">Continue</a></div>`);
  }

  /* ---------- admin (owner) sign in ---------- */
  function adminLogin() {
    screen(`<h1 class="h2">Admin</h1>
      <form id="f" novalidate><div class="field"><label for="em">Email</label><input id="em" type="email" autocomplete="username" required></div>
      <div class="field"><label for="pw">Password</label><input id="pw" type="password" autocomplete="current-password" required></div>
      <div class="err"></div><button class="btn block lg" data-busy="Signing in…">Sign in</button></form>`);
    $("#em").focus();
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      busy($("button", e.target), async () => {
        const r = await api("/api/auth/staff/login", { method: "POST", body: { email: $("#em").value, password: $("#pw").value } });
        if (r.mustChangePassword) return changePassword();
        location.href = r.role === "LECTURER" ? "/lecturer.html" : "/admin.html";
      }, $(".err", flow()));
    });
  }

  async function changePassword() {
    await SA.session();
    screen(`<h1 class="h2">New password</h1>
      <form id="f" novalidate><div class="field"><label for="cur">Current password</label><input id="cur" type="password" autocomplete="current-password"></div>
      <div class="field"><label for="np">New password</label><input id="np" type="password" autocomplete="new-password"><div class="hint">12+ characters, mix letters and numbers.</div></div>
      <div class="field"><label for="np2">Repeat</label><input id="np2" type="password" autocomplete="new-password"></div>
      <div class="err"></div><button class="btn block lg" data-busy="Saving…">Save</button></form>`);
    $("#f").addEventListener("submit", (e) => {
      e.preventDefault();
      const err = $(".err", flow());
      if ($("#np").value !== $("#np2").value) { err.innerHTML = SA.alert("error", "Passwords don't match."); return; }
      busy($("button", e.target), async () => {
        await api("/api/me/password", { method: "POST", body: { currentPassword: $("#cur").value, newPassword: $("#np").value } });
        location.href = "/admin.html";
      }, err);
    });
  }

  document.addEventListener("DOMContentLoaded", async () => {
    const page = document.body.dataset.page;
    if (page === "student-login" || page === "register") {
      const s = await SA.session().catch(() => null);
      if (s && s.role === "STUDENT") { location.href = nextTarget(); return; }
    }
    ({ "student-login": studentLogin, register, "admin-login": adminLogin, "lecturer-login": adminLogin,
       recover: studentLogin })[page]();
  });
})();
