/* Student app. All data comes from /api/student/* which is scoped server-side to the signed-in student. */
(function () {
  "use strict";
  const { esc, $, $$, api, busy } = SA;
  let me = null;
  let activePoll = null;

  SA.onUnauthenticated = () => { location.href = "/login.html?next=" + encodeURIComponent("/student.html" + location.hash); };

  document.addEventListener("DOMContentLoaded", async () => {
    me = await SA.session().catch(() => null);
    if (!me) return SA.onUnauthenticated();
    if (me.role !== "STUDENT") { location.href = me.role === "LECTURER" ? "/lecturer.html" : "/admin.html"; return; }
    SA.shell({
      me, home: "#/", cls: "student",
      nav: [
        { key: "home", href: "#/", label: "Home", icon: "home" },
        { key: "attend", href: "#/attend", label: "Mark attendance", icon: "scan" },
        { key: "timetable", href: "#/timetable", label: "Timetable", icon: "calendar" },
        { key: "history", href: "#/history", label: "Attendance history", icon: "list" },
        { key: "reports", href: "#/reports", label: "My reports", icon: "chart" },
        { key: "notifications", href: "#/notifications", label: "Notifications", icon: "bell" },
        { key: "profile", href: "#/profile", label: "Profile & devices", icon: "user" },
      ],
      tabs: [
        { key: "home", href: "#/", label: "Home", icon: "home" },
        { key: "timetable", href: "#/timetable", label: "Timetable", icon: "calendar" },
        { key: "attend", href: "#/attend", label: "Attend", icon: "scan", primary: true },
        { key: "history", href: "#/history", label: "History", icon: "list" },
        { key: "profile", href: "#/profile", label: "Profile", icon: "user" },
      ],
    });
    SA.router([
      SA.route("/", dashboard, { key: "home" }),
      SA.route("/attend", attend, { key: "attend" }),
      SA.route("/timetable", timetable, { key: "timetable" }),
      SA.route("/courses/:id", course, { key: "home" }),
      SA.route("/history", historyView, { key: "history" }),
      SA.route("/reports", reports, { key: "reports" }),
      SA.route("/notifications", SA.notificationsView, { key: "notifications" }),
      SA.route("/profile", profile, { key: "profile" }),
    ], (r) => { SA.markNav(r.key); if (activePoll) { clearInterval(activePoll); activePoll = null; } });
  });

  const pendingBanner = (status) => status === "PENDING_APPROVAL"
    ? SA.alert("warn", "Your account is waiting for verification by the department. You can view your timetable, but you can't mark attendance until it's approved.") : "";

  function liveCallouts(active) {
    return active.filter((a) => !a.myStatus).map((a) => `<div class="live-callout">
      <span class="pulse">Attendance open now</span>
      <h2>${esc(a.courseTitle)}</h2>
      <div>${esc(a.courseCode || "")}${a.venue ? " · " + esc(a.venue) : a.mode === "ONLINE" ? " · Online" : ""} · closes ${esc(SA.time(a.closesAt))}</div>
      <a class="btn lg" href="#/attend?s=${esc(a.sessionId)}">Mark attendance</a></div>`).join("");
  }

  /* ---------------------------------------------------------------- dashboard */
  async function dashboard() {
    SA.loading();
    const d = await api("/api/student/dashboard");
    const s = d.summary, n = d.nextLecture;
    const first = (me.student && me.student.firstName) || me.name;
    const recorded = d.active.filter((a) => a.myStatus);
    SA.view("Home", `${pendingBanner(d.accountStatus)}
      <div class="stack">${liveCallouts(d.active)}</div>
      ${recorded.map((a) => SA.alert("success", `${a.courseTitle}: recorded as ${SA.label(a.myStatus)} (ref ${a.referenceNo}).`)).join("")}
      <div class="panel" ${""}>
        <div class="row">${SA.ring(s.percentage)}<div class="grow"><h2>Hello, ${esc(first)}</h2>
        <p class="muted">${esc(d.semester.academicYear)} · ${esc(d.semester.name)}</p>
        ${n ? `<p><b>Next lecture:</b> ${esc(n.courseTitle)}<br><span class="muted">${esc(SA.date(n.date))}, ${esc(SA.hm(n.startTime))}–${esc(SA.hm(n.endTime))} · ${esc(n.venue || "Venue to be confirmed")}</span></p>` : `<p class="muted">No lectures in the next two weeks.</p>`}</div></div>
      </div>
      <div class="stats" ${""}>
        <div class="stat"><b>${d.courses.length}</b><span>Registered courses</span></div>
        <div class="stat"><b>${s.attended}</b><span>Classes attended</span></div>
        <div class="stat"><b>${s.absent}</b><span>Classes missed</span></div>
        <div class="stat"><b>${s.late}</b><span>Late arrivals</span></div>
      </div>
      <h2>My courses</h2>
      <div class="cards">${d.courses.map(courseCard).join("")}</div>`);
    // Keep the open-attendance call-out fresh while on the home screen.
    activePoll = setInterval(async () => {
      if (document.hidden) return;
      const a = await api("/api/student/attendance/active").catch(() => null);
      if (a && a.filter((x) => !x.myStatus).length !== d.active.filter((x) => !x.myStatus).length) dashboard();
    }, 15000);
  }

  function courseCard(c) {
    const slot = c.slot ? `${SA.days[c.slot.dayOfWeek]}s, ${SA.hm(c.slot.startTime)}–${SA.hm(c.slot.endTime)}` : "Not timetabled";
    return `<a class="panel course-card" href="#/courses/${esc(c.courseId)}">
      <div class="top"><div><div class="muted small">${esc(c.code || "Code pending")}</div><div class="title">${esc(c.title)}</div></div>
      <div class="pct">${esc(SA.pct(c.percentage))}</div></div>
      ${SA.meter(c.percentage)}
      <div class="small muted">${esc(c.lecturer || "Lecturer to be assigned")}<br>${esc(slot)}</div>
      <div class="small">${c.next ? `Next: ${esc(SA.date(c.next.date))} · ${esc(c.next.venue || "")}` : "No upcoming lecture"}</div>
      <div class="small muted">${esc(c.attended)} of ${esc(c.eligible)} sessions attended</div></a>`;
  }

  /* ---------------------------------------------------------------- mark attendance */
  async function attend(_, params) {
    SA.loading();
    if (me.status === "PENDING_APPROVAL" || (await SA.session()).status === "PENDING_APPROVAL") {
      SA.view("Mark attendance", pendingBanner("PENDING_APPROVAL"));
      return;
    }
    if (params.t) return begin({ qrToken: params.t });
    const active = (await api("/api/student/attendance/active")).filter((a) => !a.myStatus);
    if (!active.length) {
      SA.view("Mark attendance", `<div class="panel empty"><h2>No attendance is open right now</h2>
        <p>When your lecturer starts attendance, it appears here and on your home screen. Keep this page open — it checks automatically.</p>
        <a class="btn secondary" href="#/timetable">View timetable</a></div>`);
      activePoll = setInterval(async () => { const a = await api("/api/student/attendance/active").catch(() => []); if (a.some((x) => !x.myStatus)) attend(_, params); }, 10000);
      return;
    }
    const chosen = active.find((a) => a.sessionId === params.s) || (active.length === 1 ? active[0] : null);
    if (!chosen) {
      SA.view("Mark attendance", `<div class="panel"><h2>Which class are you in?</h2><div class="choice">${active.map((a) =>
        `<a class="btn secondary block lg" href="#/attend?s=${esc(a.sessionId)}">${esc(a.courseCode || "")} ${esc(a.courseTitle)}</a>`).join("")}</div></div>`);
      return;
    }
    const canScan = "BarcodeDetector" in window;
    const v = SA.view("Mark attendance", `<div class="panel">
      <div class="muted small">${esc(chosen.courseCode || "")}${chosen.venue ? " · " + esc(chosen.venue) : ""}</div>
      <h2>${esc(chosen.courseTitle)}</h2>
      <form id="otpForm" novalidate><div class="field"><label for="otp">Enter the 6-digit code on the screen</label>
        <input id="otp" class="otp-input" type="text" inputmode="numeric" pattern="[0-9]*" maxlength="6" autocomplete="one-time-code" required></div>
        <div class="err"></div><button class="btn block lg" data-busy="Checking code…">Continue</button></form>
      <p class="small muted">Or scan the QR code with your phone's camera — it opens SmartAttend directly.</p>
      ${canScan ? `<button class="btn secondary block" id="scanBtn" type="button">${SA.icon.scan} Scan QR code here</button><div id="scanner" class="scanner"></div>` : ""}
    </div>`);
    const otp = $("#otp", v);
    otp.focus();
    otp.addEventListener("input", () => { otp.value = otp.value.replace(/\D/g, "").slice(0, 6); });
    $("#otpForm", v).addEventListener("submit", (e) => {
      e.preventDefault();
      if (!/^\d{6}$/.test(otp.value)) { $(".err", v).innerHTML = SA.alert("error", "Enter all 6 digits."); return; }
      begin({ sessionId: chosen.sessionId, otp: otp.value }, $("button", e.target), $(".err", v));
    });
    if (canScan) $("#scanBtn", v).addEventListener("click", () => scanQr($("#scanner", v), chosen.sessionId));
  }

  async function scanQr(box, sessionId) {
    let stream;
    try { stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" } }); }
    catch (e) { SA.toast("Camera permission denied. Type the code instead.", "error"); return; }
    box.innerHTML = `<video playsinline muted autoplay></video>`;
    const video = $("video", box);
    video.srcObject = stream;
    const det = new BarcodeDetector({ formats: ["qr_code"] });
    const stop = () => stream.getTracks().forEach((t) => t.stop());
    const started = Date.now();
    const tick = async () => {
      if (!box.isConnected || Date.now() - started > 60000) { stop(); return; }
      try {
        const codes = await det.detect(video);
        const hit = codes.map((c) => c.rawValue).find((v) => /[#&?]t=/.test(v));
        if (hit) { stop(); return begin({ sessionId, qrToken: hit.split("t=")[1].split("&")[0] }); }
      } catch (e) { /* frame not ready */ }
      requestAnimationFrame(tick);
    };
    video.addEventListener("loadeddata", tick, { once: true });
  }

  /** Server-driven attendance steps: challenge → face → passkey → presence → submit. */
  async function begin(body, btn, errBox) {
    let b;
    if (btn) { b = await busy(btn, () => api("/api/student/attendance/begin", { method: "POST", body }), errBox); if (!b) return; }
    else {
      SA.loading();
      try { b = await api("/api/student/attendance/begin", { method: "POST", body }); }
      catch (e) { return failure(e.message, e.code); }
    }
    window.history.replaceState(null, "", "#/attend");
    let faceChallenge = b.faceChallenge;
    const steps = [b.requiresFace && "face", b.requiresPasskey && "passkey", "submit"].filter(Boolean);
    const header = (i) => `<div class="muted small">${esc(b.course.code || "")}</div><h2>${esc(b.course.title)}</h2>
      <div class="progress">${steps.map((_, k) => `<i class="${k <= i ? "done" : ""}"></i>`).join("")}</div>`;

    async function faceStage(errMsg) {
      const v = SA.view("Mark attendance", `<div class="panel">${header(0)}<p><b>Code accepted.</b> Now a quick face check to confirm it's you.</p>
        ${errMsg ? SA.alert("error", errMsg) : ""}<button class="btn block lg" id="go" type="button">Start face check</button></div>`);
      $("#go", v).addEventListener("click", async () => {
        const panel = $(".panel", v);
        panel.innerHTML = header(0) + `<div id="cam"></div>`;
        try {
          const r = await SA.faceCapture($("#cam", v), faceChallenge, (fd) => api(`/api/student/attendance/${b.attemptId}/face`, { method: "POST", form: fd }));
          if (r.passed) return b.requiresPasskey ? passkeyStage() : submitStage();
          faceChallenge = r.faceChallenge;
          faceStage(r.message);
        } catch (e) {
          if (e.code === "ATTEMPT_EXPIRED" || e.code === "SESSION_CLOSED") return failure(e.message, e.code);
          if (e.code === "FACE_CHALLENGE_INVALID" || e.code === "RATE_LIMITED") return failure(e.message, e.code);
          faceStage(e.message);
        }
      });
    }

    async function passkeyStage(errMsg) {
      const v = SA.view("Mark attendance", `<div class="panel">${header(b.requiresFace ? 1 : 0)}<p>Confirm with your phone's fingerprint, Face ID or screen lock.</p>
        ${errMsg ? SA.alert("error", errMsg) : ""}<div class="err"></div><button class="btn block lg" id="pk" type="button" data-busy="Waiting for your device…">Confirm on this phone</button></div>`);
      $("#pk", v).addEventListener("click", (e) => busy(e.currentTarget, async () => {
        const cred = await SA.usePasskey(b.passkey);
        try { await api(`/api/student/attendance/${b.attemptId}/passkey`, { method: "POST", body: cred }); }
        catch (ex) { if (["PASSKEY_USED", "ATTEMPT_EXPIRED", "SESSION_CLOSED", "DEVICE_NOT_RECOGNISED"].includes(ex.code)) return failure(ex.message, ex.code); throw ex; }
        submitStage();
      }, $(".err", v)));
    }

    async function submitStage() {
      SA.view("Mark attendance", `<div class="panel">${header(steps.length - 1)}<div class="loading">Recording your attendance…</div></div>`);
      let gps = {};
      if (b.wantsLocation && navigator.geolocation) {
        gps = await new Promise((resolve) => navigator.geolocation.getCurrentPosition(
          (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy }),
          () => resolve({}), { enableHighAccuracy: true, timeout: 8000, maximumAge: 0 }));
      }
      try {
        const r = await api(`/api/student/attendance/${b.attemptId}/submit`, { method: "POST", body: gps });
        confirmation(r);
      } catch (e) { failure(e.message, e.code); }
    }

    if (b.requiresFace) faceStage(); else if (b.requiresPasskey) passkeyStage(); else submitStage();
  }

  function confirmation(r) {
    const pending = r.status === "PENDING_VERIFICATION";
    SA.view("Attendance recorded", `<div class="panel confirm">
      <div class="stamp ${pending ? "warn" : ""}">${pending ? SA.icon.clock : SA.icon.check}</div>
      <h2>${pending ? "Recorded — awaiting lecturer review" : r.status === "LATE" ? "You're marked late" : "You're marked present"}</h2>
      ${pending ? `<p class="muted">Some supporting evidence didn't match, so your lecturer will review this record. You don't need to do anything else.</p>` : ""}
      <dl class="receipt">
        <dt>Name</dt><dd>${esc(r.studentName)}</dd>
        <dt>Index number</dt><dd>${esc(r.indexNumber)}</dd>
        <dt>Course</dt><dd>${esc(r.courseTitle)}</dd>
        <dt>Course code</dt><dd>${esc(r.courseCode || "—")}</dd>
        <dt>Date</dt><dd>${esc(SA.date(r.sessionDate))}</dd>
        <dt>Time</dt><dd>${esc(SA.time(r.submittedAt))}</dd>
        <dt>Status</dt><dd>${SA.badge(r.status)}</dd>
        <dt>Verification</dt><dd>${SA.badge(r.verificationStatus)}</dd>
        <dt>Reference</dt><dd>${esc(r.referenceNo)}</dd>
      </dl>
      <a class="btn block lg" href="#/">Done</a></div>`);
  }

  function failure(message, code) {
    const done = code === "ALREADY_RECORDED";
    SA.view("Mark attendance", `<div class="panel confirm"><div class="stamp ${done ? "" : "warn"}">${done ? SA.icon.check : SA.icon.alert}</div>
      <h2>${done ? "Already recorded" : "Attendance not recorded"}</h2>${SA.alert(done ? "success" : "error", message)}
      <div class="row"><a class="btn" href="#/attend">Try again</a><a class="btn secondary" href="#/">Home</a></div>
      ${done ? "" : `<p class="small muted">Still stuck? Ask your lecturer to verify you in person.</p>`}</div>`);
  }

  /* ---------------------------------------------------------------- timetable */
  async function timetable() {
    SA.loading();
    const t = await api("/api/student/timetable");
    const ex = t.exceptions;
    const days = [1, 2, 3, 4, 5].map((d) => `<div class="day"><h3>${SA.days[d]}</h3>${t.slots.filter((s) => s.dayOfWeek === d).map((s) => {
      const notes = ex.filter((e) => e.timetableId === s.id).map((e) => `Week ${e.weekNumber}: ${e.cancelled ? "cancelled" : (e.mode === "IN_PERSON" ? "on campus " : e.mode === "ONLINE" ? "online " : "") + (e.venue || "")}`);
      return `<div class="slot ${s.mode === "ONLINE" ? "online" : ""}"><b>${esc(s.courseTitle)}</b><div class="t">${esc(s.courseCode || "")}</div>
        <div class="t">${esc(SA.hm(s.startTime))}–${esc(SA.hm(s.endTime))} · ${esc(s.mode === "ONLINE" ? "Online" : s.venueCode || "TBC")}</div>
        <div class="t">${esc(s.lecturer || "")}</div>${notes.length ? `<div class="t">${esc(notes.join("; "))}</div>` : ""}</div>`;
    }).join("") || `<p class="small muted">No lectures</p>`}</div>`).join("");
    SA.view("Timetable", `<p class="muted">${esc(t.semester.academicYear)} · ${esc(t.semester.name)}${t.week ? ` · Teaching week ${t.week}` : ""} · times in Accra time (GMT)</p>
      ${t.week ? "" : SA.alert("info", "The semester start date hasn't been confirmed yet, so week-specific venue changes are listed under each lecture rather than applied automatically.")}
      <div class="week">${days}</div>
      <h2>Coming up this week</h2>
      ${SA.table([{ label: "Date", get: (u) => SA.date(u.date) }, { label: "Time", get: (u) => SA.hm(u.startTime) + "–" + SA.hm(u.endTime) },
        { label: "Course", html: (u) => esc(u.courseTitle) }, { label: "Venue", get: (u) => u.venue || "TBC" }], t.upcoming, "No lectures in the next 7 days.")}`);
  }

  /* ---------------------------------------------------------------- course details */
  async function course([id]) {
    SA.loading();
    const c = await api("/api/student/courses/" + encodeURIComponent(id));
    const s = c.stats || {};
    SA.view(c.course.title, `<p class="muted">${esc(c.course.code || "Code pending")}${c.course.altCode ? " (" + esc(c.course.altCode) + ")" : ""}</p>
      <div class="panel"><div class="row">${SA.ring(s.percentage)}<div class="stats grow">
        <div class="stat"><b>${esc(s.present || 0)}</b><span>Present</span></div><div class="stat"><b>${esc(s.late || 0)}</b><span>Late</span></div>
        <div class="stat"><b>${esc(s.absent || 0)}</b><span>Absent</span></div><div class="stat"><b>${esc(s.excused || 0)}</b><span>Excused</span></div></div></div></div>
      <div class="panel"><h3>Weekly trend</h3>${SA.trendChart(c.trend)}</div>
      <div class="panel"><h3>Upcoming lectures</h3>${SA.table([{ label: "Date", get: (u) => SA.date(u.date) }, { label: "Time", get: (u) => SA.hm(u.startTime) + "–" + SA.hm(u.endTime) }, { label: "Venue", get: (u) => u.venue || "TBC" }], c.upcoming.slice(0, 5), "No upcoming lectures.")}</div>
      <h3>Sessions</h3>${historyTable(c.history)}`);
    bindCorrections();
  }

  /* ---------------------------------------------------------------- history */
  function historyTable(rows) {
    return SA.table([
      { label: "Course", html: (r) => `${esc(r.courseCode || "")} <span class="muted">${esc(r.courseTitle)}</span>` },
      { label: "Date", get: (r) => SA.date(r.sessionDate) },
      { label: "Time", get: (r) => (r.status === "ABSENT" ? "—" : SA.time(r.submittedAt)) },
      { label: "Status", html: (r) => SA.badge(r.status) },
      { label: "Verification", html: (r) => SA.badge(r.verificationStatus) },
      { label: "", html: (r) => r.correctionStatus ? SA.badge(r.correctionStatus) : ["ABSENT", "REJECTED", "LATE", "PENDING_VERIFICATION"].includes(r.status)
          ? `<button class="btn ghost sm" data-correct="${esc(r.sessionId)}" data-label="${esc((r.courseCode || "") + " " + SA.date(r.sessionDate))}">Request correction</button>` : "" },
    ], rows, "No attendance records yet.");
  }

  function bindCorrections() {
    $$("[data-correct]").forEach((b) => b.addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Request a correction", intro: b.dataset.label + ". Your lecturer reviews every request.",
        fields: [{ name: "requestedStatus", label: "Correct status", type: "select", value: "PRESENT", options: [{ value: "PRESENT", label: "Present" }, { value: "LATE", label: "Late" }, { value: "EXCUSED", label: "Excused (e.g. medical)" }] },
          { name: "reason", label: "Explain what happened", type: "textarea", required: true, hint: "At least 10 characters. Mention any evidence you can show." }], submit: "Send request" });
      if (!r) return;
      await busy(null, async () => { await api("/api/student/corrections", { method: "POST", body: { sessionId: b.dataset.correct, ...r } }); SA.toast("Correction request sent", "success"); b.replaceWith(Object.assign(document.createElement("span"), { className: "badge PENDING", textContent: "Pending" })); });
    }));
  }

  async function historyView(_, params) {
    SA.loading();
    const dash = await api("/api/student/dashboard");
    const q = new URLSearchParams();
    ["courseId", "status", "from", "to"].forEach((k) => params[k] && q.set(k, params[k]));
    const rows = await api("/api/student/history?" + q);
    const v = SA.view("Attendance history", `<form class="filters" id="flt">
        <select name="courseId" aria-label="Course"><option value="">All courses</option>${dash.courses.map((c) => `<option value="${esc(c.courseId)}" ${params.courseId === c.courseId ? "selected" : ""}>${esc(c.code || c.title)}</option>`).join("")}</select>
        <select name="status" aria-label="Status"><option value="">All statuses</option>${["PRESENT", "LATE", "ABSENT", "EXCUSED", "PENDING_VERIFICATION", "REJECTED"].map((s) => `<option value="${s}" ${params.status === s ? "selected" : ""}>${SA.label(s)}</option>`).join("")}</select>
        <input type="date" name="from" aria-label="From date" value="${esc(params.from || "")}"><input type="date" name="to" aria-label="To date" value="${esc(params.to || "")}">
        <button class="btn secondary">Filter</button></form>${historyTable(rows)}`);
    $("#flt", v).addEventListener("submit", (e) => { e.preventDefault(); const p = new URLSearchParams(new FormData(e.target)); [...p.keys()].forEach((k) => !p.get(k) && p.delete(k)); location.hash = "#/history?" + p; });
    bindCorrections();
  }

  /* ---------------------------------------------------------------- reports */
  async function reports() {
    SA.loading();
    const [d, corr] = await Promise.all([api("/api/student/dashboard"), api("/api/student/corrections")]);
    const p = d.policy;
    SA.view("My reports", `<div class="panel"><h3>Attendance by course</h3>
      ${SA.table([{ label: "Course", html: (c) => `${esc(c.code || "")} <span class="muted">${esc(c.title)}</span>` },
        { label: "Attended", num: true, get: (c) => c.attended + " / " + c.eligible }, { label: "Late", num: true, key: "late" }, { label: "Absent", num: true, key: "absent" },
        { label: "Attendance", html: (c) => `<div class="row">${SA.meter(c.percentage, p.lowThreshold, p.excellentThreshold)}<b>${esc(SA.pct(c.percentage))}</b></div>` }], d.courses)}
      <p class="small muted">Percentage = attended ÷ eligible closed sessions. Late ${p.lateCountsAsAttended ? "counts" : "does not count"} as attended; excused sessions are ${p.excusedExcludedFromDenominator ? "left out" : "counted as missed"}. Below ${p.lowThreshold}% is flagged as low.</p></div>
      <div class="panel"><h3>Correction requests</h3>${SA.table([{ label: "Course", get: (c) => c.courseCode || c.courseTitle }, { label: "Session", get: (c) => SA.date(c.sessionDate) },
        { label: "Requested", html: (c) => SA.badge(c.requestedStatus) }, { label: "Status", html: (c) => SA.badge(c.status) }, { label: "Response", get: (c) => c.reviewNote || "—" }], corr, "You haven't requested any corrections.")}</div>`);
  }

  /* ---------------------------------------------------------------- profile & devices */
  async function profile() {
    SA.loading();
    const [s, dv] = await Promise.all([SA.session(), api("/api/student/devices")]);
    const st = s.student;
    const v = SA.view("Profile & devices", `<div class="panel"><div class="row"><div class="avatar">${esc(SA.initials(s.name))}</div><div><h2>${esc([st.title, st.firstName, st.otherNames, st.lastName].filter(Boolean).join(" "))}</h2>
        <div class="muted">${esc(st.indexNumber)} · ${esc(st.programme)}, Level ${esc(st.level)} · ${esc(st.groupName)}</div></div></div>
        <p class="small muted">Names come from the official register. If something is wrong, ask the department to correct it.</p></div>
      <div class="panel"><h3>Face verification</h3>${dv.biometric ? `<dl class="kv"><dt>Status</dt><dd>${SA.badge("ACTIVE")}</dd><dt>Enrolled</dt><dd>${esc(SA.date(dv.biometric.createdAt))}</dd>
        <dt>Kept until</dt><dd>${esc(SA.date(dv.biometric.retentionUntil))}</dd></dl>` : `<p>Not enrolled. You'll be asked to enrol at your next sign-in.</p>`}</div>
      <div class="panel"><h3>Trusted devices</h3>${SA.table([{ label: "Device", html: (d) => `${esc(d.label || "Device")}${d.id === dv.currentDeviceId ? ' <span class="badge ok">This device</span>' : ""}` },
        { label: "Last used", get: (d) => SA.dateTime(d.lastSeenAt) }, { label: "Passkeys", num: true, key: "passkeys" }, { label: "Status", html: (d) => SA.badge(d.status) },
        { label: "", html: (d) => d.status === "TRUSTED" && d.id !== dv.currentDeviceId ? `<button class="btn ghost sm" data-revoke="${esc(d.id)}">Remove</button>` : "" }], dv.devices)}
        <p class="small muted">Got a new phone? Use <a href="/recover.html">account recovery</a> on the new phone — the department approves the change.</p></div>`);
    $$("[data-revoke]", v).forEach((b) => b.addEventListener("click", async () => {
      if (!(await SA.confirm("Remove this device?", "It will be signed out and its passkey removed.", "Remove device", true))) return;
      await busy(b, async () => { await api(`/api/student/devices/${b.dataset.revoke}/revoke`, { method: "POST", body: {} }); profile(); });
    }));
  }
})();
