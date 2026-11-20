/* Student app (minimal). Data comes from /api/student/*, scoped server-side to the signed-in student. */
(function () {
  "use strict";
  const { esc, $, $$, api, busy } = SA;
  let me = null;
  let poll = null;

  SA.onUnauthenticated = () => { location.href = "/login.html?next=" + encodeURIComponent("/student.html" + location.hash); };

  document.addEventListener("DOMContentLoaded", async () => {
    me = await SA.session().catch(() => null);
    if (!me) return SA.onUnauthenticated();
    if (me.role !== "STUDENT") { location.href = "/admin.html"; return; }
    SA.shell({
      me, home: "#/", cls: "student",
      nav: [
        { key: "home", href: "#/", label: "Courses", icon: "book" },
        { key: "history", href: "#/history", label: "History", icon: "list" },
        { key: "me", href: "#/me", label: "Me", icon: "user" },
      ],
      tabs: [
        { key: "home", href: "#/", label: "Courses", icon: "book" },
        { key: "history", href: "#/history", label: "History", icon: "list" },
        { key: "me", href: "#/me", label: "Me", icon: "user" },
      ],
    });
    SA.router([
      SA.route("/", home, { key: "home" }),
      SA.route("/c/:id", course, { key: "home" }),
      SA.route("/attend", attendFromQr, { key: "home" }),
      SA.route("/attend/:sid", attend, { key: "home" }),
      SA.route("/confirm/:sid", confirmEnd, { key: "home" }),
      SA.route("/history", history, { key: "history" }),
      SA.route("/notifications", SA.notificationsView, { key: "home" }),
      SA.route("/me", profile, { key: "me" }),
    ], (r) => { SA.markNav(r.key); if (poll) { clearInterval(poll); poll = null; } });
  });

  const time = (t) => SA.time(t);
  const sessionLine = (a) => a.myStatus === "CHECKED_IN"
    ? (a.checkoutOpen ? `<a class="btn lg block" href="#/confirm/${esc(a.sessionId)}">Confirm I'm still here</a>`
                      : `<p class="ok-line">${SA.icon.check} Checked in · confirm from ${esc(time(a.checkoutFrom))}</p>`)
    : a.myStatus ? `<p class="ok-line">${SA.icon.check} ${esc(SA.label(a.myStatus))}</p>`
    : a.checkinOpen ? `<a class="btn lg block" href="#/attend/${esc(a.sessionId)}">Mark attendance</a>`
    : `<p class="muted">Check-in closed</p>`;

  /* ---------------------------------------------------------------- home: courses */
  async function home() {
    SA.loading();
    const d = await api("/api/student/dashboard");
    const open = Object.fromEntries(d.active.map((a) => [a.courseId, a]));
    SA.view("Courses", `
      ${d.active.map((a) => `<div class="live-callout"><span class="pulse">Class open</span><h2>${esc(a.courseTitle)}</h2>${sessionLine(a)}</div>`).join("")}
      <div class="course-list">${d.courses.map((c) => `<a class="course-row" href="#/c/${esc(c.courseId)}">
        <span><b>${esc(c.title)}</b><small>${esc(c.code || "")}</small></span>
        ${open[c.courseId] ? '<span class="badge OPEN">Open</span>' : `<span class="pct">${esc(SA.pct(c.percentage))}</span>`}</a>`).join("")}</div>`);
    poll = setInterval(async () => {
      if (document.hidden) return;
      const a = await api("/api/student/attendance/active").catch(() => null);
      if (a && JSON.stringify(a.map((x) => [x.sessionId, x.myStatus, x.checkoutOpen])) !== JSON.stringify(d.active.map((x) => [x.sessionId, x.myStatus, x.checkoutOpen]))) home();
    }, 15000);
  }

  /* ---------------------------------------------------------------- course */
  async function course([id]) {
    SA.loading();
    const [c, active] = await Promise.all([api("/api/student/courses/" + encodeURIComponent(id)), api("/api/student/attendance/active")]);
    const a = active.find((x) => x.courseId === id);
    const s = c.stats || {};
    SA.view(c.course.title, `<div class="panel center-panel">
        <p class="muted">${esc(c.course.code || "")}</p>
        ${a ? sessionLine(a) : `<p class="muted">No class open right now.</p>`}
      </div>
      <div class="stats"><div class="stat"><b>${esc(SA.pct(s.percentage))}</b><span>Attendance</span></div>
        <div class="stat"><b>${esc(s.attended || 0)}/${esc(s.denominator || 0)}</b><span>Classes</span></div></div>
      ${historyList(c.history.slice(0, 10))}`);
    if (a) poll = setInterval(() => { if (!document.hidden) course([id]); }, 20000);
  }

  /* ---------------------------------------------------------------- check in */
  function steps(n) {
    const names = ["Index", "Face", "Code", "Location"];
    return `<ol class="stepper">${names.map((x, i) => `<li class="${i < n ? "done" : i === n ? "now" : ""}">${x}</li>`).join("")}</ol>`;
  }

  function attendFromQr(_, params) {
    if (!params.t) { location.hash = "#/"; return; }
    attend([null], params);
  }

  async function attend([sid], params) {
    const qr = params && params.t;
    window.history.replaceState(null, "", "#/attend/" + (sid || "qr"));
    let course = null;
    if (sid) {
      const a = (await api("/api/student/attendance/active")).find((x) => x.sessionId === sid);
      if (!a) return SA.view("Mark attendance", `<div class="panel center-panel"><p>This class isn't open.</p><a class="btn" href="#/">Back</a></div>`);
      course = a.courseTitle;
    }
    // Step 1: index number
    const v = SA.view(course || "Mark attendance", `<div class="panel">${steps(0)}
      <form id="f" novalidate><div class="field"><label for="idx">Index number</label>
      <input id="idx" class="index-input" type="text" inputmode="numeric" autocomplete="off" required></div>
      <div class="err"></div><button class="btn block lg" data-busy="Checking…">Next</button></form></div>`);
    $("#idx", v).focus();
    $("#f", v).addEventListener("submit", (e) => {
      e.preventDefault();
      const idx = $("#idx", v).value.trim();
      if (!idx) { $(".err", v).innerHTML = SA.alert("error", "Enter your index number."); return; }
      busy($("button", e.target), async () => {
        const b = await api("/api/student/attendance/begin", { method: "POST", body: { sessionId: sid || null, indexNumber: idx, qrToken: qr || null } });
        b.requiresFace ? faceStep(b) : b.codeDone ? locationStep(b) : codeStep(b);
      }, $(".err", v));
    });
  }

  function faceStep(b, error) {
    const v = SA.view(b.course.title, `<div class="panel">${steps(1)}${error ? SA.alert("error", error) : ""}
      <button class="btn block lg" id="go" type="button">Scan face</button></div>`);
    $("#go", v).addEventListener("click", async () => {
      const panel = $(".panel", v);
      panel.innerHTML = steps(1) + `<div id="cam"></div>`;
      try {
        const r = await SA.faceCapture($("#cam", v), b.faceChallenge, (fd) => api(`/api/student/attendance/${b.attemptId}/face`, { method: "POST", form: fd }));
        if (r.passed) return b.codeDone ? locationStep(b) : codeStep(b);
        b.faceChallenge = r.faceChallenge;
        faceStep(b, r.message);
      } catch (e) {
        if (["ATTEMPT_EXPIRED", "SESSION_CLOSED", "RATE_LIMITED", "FACE_CHALLENGE_INVALID"].includes(e.code)) return fail(e.message);
        faceStep(b, e.message);
      }
    });
  }

  function codeStep(b) {
    const v = SA.view(b.course.title, `<div class="panel">${steps(2)}
      <div class="seg" role="tablist" aria-label="How to enter the code">
        <button type="button" role="tab" id="tabQr" aria-selected="true">${SA.icon.scan} Scan QR</button>
        <button type="button" role="tab" id="tabCode" aria-selected="false">6-digit code</button></div>
      <div id="paneQr"><div id="scanner" class="scanner"></div>
        <p class="muted center small" id="scanMsg">Point your camera at the QR code on the board</p></div>
      <form id="f" class="hidden" novalidate><div class="field"><label for="otp">Code on the board</label>
        <input id="otp" class="otp-input" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code"></div>
        <button class="btn block lg" data-busy="Checking…">Next</button></form>
      <div class="err"></div></div>`);
    const otp = $("#otp", v);
    let scanner = null;
    otp.addEventListener("input", () => { otp.value = otp.value.replace(/\D/g, "").slice(0, 6); });
    const send = async (body, btn) => {
      let ok = false;
      await busy(btn, async () => {
        if (scanner) scanner.stop();
        await api(`/api/student/attendance/${b.attemptId}/code`, { method: "POST", body });
        ok = true;
        locationStep(b);
      }, $(".err", v));
      // A QR that didn't work (e.g. it just changed on the board): keep scanning.
      if (!ok && body.qrToken && v.isConnected) setTimeout(() => { if (v.isConnected && !$("#paneQr", v).classList.contains("hidden")) show("qr", true); }, 1500);
    };
    $("#f", v).addEventListener("submit", (e) => {
      e.preventDefault();
      if (!/^\d{6}$/.test(otp.value)) { $(".err", v).innerHTML = SA.alert("error", "Enter the 6 digits."); return; }
      send({ otp: otp.value }, $("button", e.target));
    });
    function show(which, keepError) {
      const qr = which === "qr";
      $("#tabQr", v).setAttribute("aria-selected", String(qr));
      $("#tabCode", v).setAttribute("aria-selected", String(!qr));
      $("#paneQr", v).classList.toggle("hidden", !qr);
      $("#f", v).classList.toggle("hidden", qr);
      if (scanner) { scanner.stop(); scanner = null; }
      if (qr) {
        if (!keepError) $(".err", v).innerHTML = "";
        $("#scanner", v).classList.remove("found");
        scanner = scanQr($("#scanner", v), $("#scanMsg", v), (token) => send({ qrToken: token }, null), () => show("code"));
      } else otp.focus();
    }
    $("#tabQr", v).addEventListener("click", () => show("qr"));
    $("#tabCode", v).addEventListener("click", () => show("code"));
    show(navigator.mediaDevices && navigator.mediaDevices.getUserMedia ? "qr" : "code");
  }

  /** Load the jsQR decoder (for browsers without a built-in QR reader, e.g. iPhone Safari). */
  let jsQrLoading = null;
  function loadJsQr() {
    if (window.jsQR) return Promise.resolve(window.jsQR);
    jsQrLoading = jsQrLoading || new Promise((resolve, reject) => {
      const el = document.createElement("script");
      el.src = "/js/vendor/jsQR.js";
      el.onload = () => resolve(window.jsQR);
      el.onerror = () => { jsQrLoading = null; reject(new Error("QR reader failed to load")); };
      document.head.appendChild(el);
    });
    return jsQrLoading;
  }

  /** In-page QR scanner using the back camera. Returns { stop }. Calls onToken with the code from the board's QR. */
  function scanQr(box, msg, onToken, fallback) {
    let stream = null, stopped = false, timer = null;
    const stop = () => { stopped = true; clearTimeout(timer); if (stream) stream.getTracks().forEach((t) => t.stop()); stream = null; };
    box.innerHTML = `<video playsinline muted autoplay></video><div class="scan-frame"><i></i><i></i><i></i><i></i><span class="scan-line"></span></div>`;
    const video = $("video", box);
    (async () => {
      try {
        stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: "environment" }, width: { ideal: 1280 }, height: { ideal: 1280 } }, audio: false });
      } catch (e) {
        msg.textContent = "Camera blocked. Use the 6-digit code instead.";
        SA.toast("Camera blocked. Type the 6-digit code.", "error");
        fallback();
        return;
      }
      if (stopped) { stop(); return; }
      video.srcObject = stream;
      await new Promise((r) => (video.readyState >= 2 ? r() : video.addEventListener("loadeddata", r, { once: true })));
      let detect;
      if ("BarcodeDetector" in window) {
        const det = new BarcodeDetector({ formats: ["qr_code"] });
        detect = async () => (await det.detect(video)).map((c) => c.rawValue);
      } else {
        const jsQR = await loadJsQr().catch(() => null);
        if (!jsQR) { msg.textContent = "Scanner unavailable. Use the 6-digit code."; fallback(); return; }
        const canvas = document.createElement("canvas"), ctx = canvas.getContext("2d", { willReadFrequently: true });
        detect = async () => {
          const w = video.videoWidth, h = video.videoHeight, scale = Math.min(1, 640 / Math.max(w, h));
          canvas.width = Math.round(w * scale); canvas.height = Math.round(h * scale);
          ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
          const r = jsQR(ctx.getImageData(0, 0, canvas.width, canvas.height).data, canvas.width, canvas.height, { inversionAttempts: "dontInvert" });
          return r ? [r.data] : [];
        };
      }
      const started = Date.now();
      const tick = async () => {
        if (stopped || !box.isConnected) { stop(); return; }
        if (Date.now() - started > 120000) { stop(); msg.textContent = "Scanner paused. Tap Scan QR to try again."; return; }
        try {
          const found = await detect();
          const hit = found.find((x) => /[#&?]t=/.test(x));
          if (hit) {
            stop();
            box.classList.add("found");
            msg.textContent = "QR code found…";
            return onToken(decodeURIComponent(hit.split("t=")[1].split("&")[0]));
          }
          if (found.length) msg.textContent = "That isn't the attendance QR code.";
        } catch (e) { /* frame not ready */ }
        timer = setTimeout(tick, 150);
      };
      tick();
    })();
    return { stop };
  }

  function getLocation() {
    return new Promise((resolve, reject) => {
      if (!navigator.geolocation) return reject(new SA.ApiError(0, "NO_GPS", "This phone can't share its location."));
      const ok = (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy });
      const fail = (e) => reject(new SA.ApiError(0, "GPS", e.code === 1 ? "Allow location for this site (tap the lock icon by the address), then try again." : "Couldn't get your location. Turn on Location and try again."));
      // GPS first; inside a big hall it may not get a fix, so fall back to Wi-Fi/network location.
      navigator.geolocation.getCurrentPosition(ok,
        (e) => e.code === 1 ? fail(e) : navigator.geolocation.getCurrentPosition(ok, fail, { enableHighAccuracy: false, timeout: 30000, maximumAge: 30000 }),
        { enableHighAccuracy: true, timeout: 20000, maximumAge: 10000 });
    });
  }

  function locationStep(b, error) {
    const v = SA.view(b.course.title, `<div class="panel">${steps(3)}${error ? SA.alert("error", error) : ""}
      <p class="muted center">Checking you're in class…</p><div class="err"></div>
      <button class="btn block lg" id="go" type="button" data-busy="Checking…">${error ? "Try again" : "Check location"}</button></div>`);
    const run = () => busy($("#go", v), async () => {
      const gps = b.requiresLocation || b.wantsLocation ? await getLocation().catch((e) => { if (b.requiresLocation) throw e; return {}; }) : {};
      try {
        const r = await api(`/api/student/attendance/${b.attemptId}/submit`, { method: "POST", body: gps });
        checkedIn(r);
      } catch (e) {
        if (e.code === "OUT_OF_RANGE" || e.code === "PRESENCE_FAILED") return locationStep(b, e.message);
        if (["ATTEMPT_EXPIRED", "SESSION_CLOSED", "ALREADY_RECORDED"].includes(e.code)) return fail(e.message, e.code);
        throw e;
      }
    }, $(".err", v));
    $("#go", v).addEventListener("click", run);
    if (!error) run();
  }

  function checkedIn(r) {
    const waiting = r.status === "CHECKED_IN";
    SA.view(r.courseTitle, `<div class="panel confirm">
      <div class="stamp ${waiting ? "" : r.status === "PENDING_VERIFICATION" ? "warn" : ""}">${SA.icon.check}</div>
      <h2>${waiting ? "Checked in" : SA.label(r.status)}</h2>
      ${waiting ? `<p class="muted">Confirm from ${esc(time(r.checkoutFrom))} before class ends. Stay in class.</p>` : ""}
      <dl class="receipt"><dt>Name</dt><dd>${esc(r.studentName)}</dd><dt>Index</dt><dd>${esc(r.indexNumber)}</dd>
      <dt>Time</dt><dd>${esc(time(r.submittedAt))}</dd><dt>Ref</dt><dd>${esc(r.referenceNo)}</dd></dl>
      <a class="btn block lg" href="#/">Done</a></div>`);
  }

  /* ---------------------------------------------------------------- end-of-class confirmation */
  async function confirmEnd([sid], _, error) {
    SA.loading();
    let start;
    try { start = await api(`/api/student/attendance/checkout/${sid}/start`, { method: "POST", body: {} }); }
    catch (e) { return fail(e.message, e.code); }
    const v = SA.view("Confirm", `<div class="panel">${error ? SA.alert("error", error) : ""}
      <p class="center">Still in class? Scan your face to confirm.</p>
      <button class="btn block lg" id="go" type="button">${start.requiresFace ? "Scan face" : "Confirm"}</button></div>`);
    $("#go", v).addEventListener("click", async () => {
      try {
        if (start.requiresFace) {
          const panel = $(".panel", v);
          panel.innerHTML = `<div id="cam"></div>`;
          const r = await SA.faceCapture($("#cam", v), start.faceChallenge, (fd) => api(`/api/student/attendance/checkout/${sid}/face`, { method: "POST", form: fd }));
          if (!r.passed) return confirmEnd([sid], null, r.message);
        }
        SA.view("Confirm", `<div class="panel"><p class="muted center">Checking you're still in class…</p></div>`);
        const gps = await getLocation();
        const done = await api(`/api/student/attendance/checkout/${sid}/confirm`, { method: "POST", body: gps });
        SA.view(done.courseTitle, `<div class="panel confirm"><div class="stamp">${SA.icon.check}</div>
          <h2>Attendance marked</h2><p>${SA.badge(done.status)}</p><a class="btn block lg" href="#/">Done</a></div>`);
      } catch (e) {
        if (e.code === "SESSION_CLOSED" || e.code === "ALREADY_RECORDED") return fail(e.message, e.code);
        confirmEnd([sid], null, e.message);
      }
    });
  }

  function fail(message, code) {
    const ok = code === "ALREADY_RECORDED";
    SA.view("Attendance", `<div class="panel confirm"><div class="stamp ${ok ? "" : "warn"}">${ok ? SA.icon.check : SA.icon.alert}</div>
      <p>${esc(message)}</p><a class="btn block lg" href="#/">Back</a></div>`);
  }

  /* ---------------------------------------------------------------- history & me */
  const historyList = (rows) => rows.length
    ? `<ul class="hist">${rows.map((r) => `<li><span><b>${esc(r.courseCode || r.courseTitle)}</b><small>${esc(SA.date(r.sessionDate))}</small></span>${SA.badge(r.status)}</li>`).join("")}</ul>`
    : `<p class="muted center">No attendance yet.</p>`;

  async function history() {
    SA.loading();
    SA.view("History", historyList(await api("/api/student/history?limit=200")));
  }

  async function profile() {
    SA.loading();
    const s = await SA.session();
    const st = s.student;
    SA.view("Me", `<div class="panel center-panel"><div class="avatar big">${esc(SA.initials(s.name))}</div>
      <h2>${esc([st.firstName, st.otherNames, st.lastName].filter(Boolean).join(" "))}</h2>
      <p class="muted">${esc(st.indexNumber)}</p><p class="small muted">Face locked to your index number.</p>
      <button class="btn secondary" id="out" type="button">Sign out</button></div>`);
    $("#out").addEventListener("click", () => SA.logout("/login.html"));
  }
})();
