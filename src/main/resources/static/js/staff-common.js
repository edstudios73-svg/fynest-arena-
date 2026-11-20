/* Views shared by lecturers and administrators: courses, start attendance, live session, corrections. */
(function () {
  "use strict";
  const { esc, $, $$, api, busy } = SA;
  const ST = (SA.staff = {});
  let liveCleanup = null;

  ST.cleanup = () => { if (liveCleanup) { liveCleanup(); liveCleanup = null; } };

  ST.reportButtons = (base, label = "Export") => `<div class="row">${["pdf", "xlsx", "csv"].map((f) =>
    `<button class="btn secondary sm" type="button" data-dl="${esc(base)}?format=${f}">${esc(label)} ${f.toUpperCase()}</button>`).join("")}</div>`;
  ST.bindDownloads = (root) => $$("[data-dl]", root).forEach((b) => b.addEventListener("click", () => busy(b, () => SA.download(b.dataset.dl))));

  /* ---------------------------------------------------------------- overview */
  ST.overview = async function () {
    SA.loading();
    const o = await api("/api/lecturer/overview");
    const open = o.courses.filter((c) => c.openSessionId);
    const v = SA.view("Overview", `
      ${open.map((c) => `<div class="live-callout"><span class="pulse">Attendance in progress</span><h2>${esc(c.title)}</h2><a class="btn" href="#/live/${esc(c.openSessionId)}">Open live screen</a></div>`).join("")}
      <div class="stats">
        <div class="stat"><b>${o.courses.length}</b><span>Assigned courses</span></div>
        <div class="stat"><b>${o.totalStudents}</b><span>Enrolled students</span></div>
        <div class="stat"><b>${open.length}</b><span>Active sessions</span></div>
        <div class="stat"><b>${esc(SA.pct(o.summary.percentage))}</b><span>Overall attendance</span></div>
        <div class="stat"><b>${o.pendingCorrections || 0}</b><span>Correction requests</span></div>
      </div>
      <h2>Courses</h2>
      <div class="cards">${o.courses.map((c) => {
        const slots = (c.slots || []).map((s) => `${SA.days[s.day]} ${SA.hm(s.start)}–${SA.hm(s.end)} · ${s.mode === "ONLINE" ? "Online" : s.venue || "TBC"}`).join("<br>");
        return `<div class="panel course-card"><div class="top"><div><div class="muted small">${esc(c.code || "Code pending")}</div><div class="title">${esc(c.title)}</div></div>
          <div class="pct">${esc(SA.pct(c.summary.percentage))}</div></div>${SA.meter(c.summary.percentage)}
          <div class="small muted">${c.enrolled} students · ${c.sessionsHeld} sessions held<br>${slots}</div>
          <div class="row">${c.openSessionId ? `<a class="btn sm" href="#/live/${esc(c.openSessionId)}">Live screen</a>` : `<button class="btn sm" type="button" data-start="${esc(c.id)}">Start attendance</button>`}
          <a class="btn secondary sm" href="#/courses/${esc(c.id)}">Details</a></div></div>`;
      }).join("") || `<div class="panel empty">No courses are assigned to you yet. Contact the administrator.</div>`}</div>
      <div class="grid2"><div class="panel"><h3>Weekly attendance</h3>${SA.trendChart(o.trend)}</div>
      <div class="panel"><h3>Recent classes</h3>${SA.table([{ label: "Date", get: (s) => SA.date(s.sessionDate) }, { label: "Course", get: (s) => s.code || s.title },
        { label: "Attended", num: true, get: (s) => s.attended + " / " + s.records }, { label: "", html: (s) => `<a href="#/sessions/${esc(s.id)}">${s.status === "OPEN" ? "Live" : "View"}</a>` }], o.recentSessions, "No classes yet.")}</div></div>`);
    $$("[data-start]", v).forEach((b) => b.addEventListener("click", () => ST.startDialog(b.dataset.start)));
  };

  /* ---------------------------------------------------------------- course */
  ST.course = async function ([id]) {
    SA.loading();
    const c = await api("/api/lecturer/courses/" + encodeURIComponent(id));
    const today = c.today;
    const open = c.sessions.find((s) => s.status === "OPEN");
    const v = SA.view(c.course.title, `<p class="muted">${esc(c.course.code || "Code pending")}${c.course.altCode ? " (" + esc(c.course.altCode) + ")" : ""}</p>
      <div class="panel"><div class="panel-head"><div><h3>Attendance</h3>
        <div class="small muted">${today ? `Today: ${esc(SA.hm(today.startTime))}–${esc(SA.hm(today.endTime))} · ${esc(today.venue || "TBC")}${today.week ? " · week " + today.week : ""}` : "Not timetabled today — you can still take attendance."}</div></div>
        ${open ? `<a class="btn" href="#/live/${esc(open.id)}">Open live screen</a>` : `<button class="btn" id="startBtn" type="button">Start attendance</button>`}</div>
        <div class="stats"><div class="stat"><b>${esc(SA.pct(c.summary.percentage))}</b><span>Overall</span></div><div class="stat"><b>${c.students.length}</b><span>Students</span></div>
        <div class="stat"><b>${c.sessions.filter((s) => s.status === "CLOSED").length}</b><span>Sessions held</span></div><div class="stat"><b>${c.summary.pending}</b><span>Pending review</span></div></div></div>
      <div class="panel"><div class="panel-head"><h3>Reports</h3>${ST.reportButtons("/api/lecturer/reports/course/" + id, "Course")}</div>${SA.trendChart(c.trend)}</div>
      <div class="panel"><h3>Sessions</h3>${SA.table([{ label: "Date", get: (s) => SA.date(s.sessionDate) }, { label: "Opened", get: (s) => SA.time(s.openedAt) },
        { label: "Mode", get: (s) => SA.label(s.mode) + (s.venue ? " · " + s.venue : "") }, { label: "Status", html: (s) => SA.badge(s.status) },
        { label: "Present", num: true, key: "present" }, { label: "Late", num: true, key: "late" }, { label: "Absent", num: true, key: "absent" }, { label: "Review", num: true, key: "pending" },
        { label: "", html: (s) => `<a href="#/${s.status === "OPEN" ? "live" : "sessions"}/${esc(s.id)}">${s.status === "OPEN" ? "Live" : "View"}</a>` }], c.sessions, "No sessions yet. Start attendance at the beginning of your next lecture.")}</div>
      <div class="panel"><div class="panel-head"><h3>Registered students</h3><input type="search" id="q" placeholder="Search name or index" aria-label="Search students"></div>
      <div id="stuTable"></div></div>`);
    const render = (q) => {
      const rows = c.students.filter((s) => !q || (s.name + " " + s.indexNumber).toLowerCase().includes(q.toLowerCase()));
      $("#stuTable", v).innerHTML = SA.table([{ label: "Index", key: "indexNumber" }, { label: "Name", key: "name" }, { label: "Account", html: (s) => SA.badge(s.accountStatus && s.accountStatus !== "DISABLED" ? s.accountStatus : "NOT_REGISTERED") },
        { label: "Present", num: true, key: "present" }, { label: "Late", num: true, key: "late" }, { label: "Absent", num: true, key: "absent" }, { label: "Excused", num: true, key: "excused" },
        { label: "Attendance", html: (s) => `<div class="row">${SA.meter(s.percentage)}<b>${esc(SA.pct(s.percentage))}</b></div>` }], rows, "No students enrolled.");
      SA.applyWidths(v);
    };
    render("");
    $("#q", v).addEventListener("input", (e) => render(e.target.value));
    ST.bindDownloads(v);
    if ($("#startBtn", v)) $("#startBtn", v).addEventListener("click", () => ST.startDialog(id, c));
  };

  ST.position = () => new Promise((resolve, reject) => {
    if (!navigator.geolocation) return reject(new SA.ApiError(0, "NO_GPS", "This device can't share its location."));
    navigator.geolocation.getCurrentPosition(
      (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy }),
      (e) => reject(new SA.ApiError(0, "GPS", e.code === 1 ? "Allow location for this site to start a class." : "Couldn't get this device's location.")),
      { enableHighAccuracy: true, timeout: 15000, maximumAge: 0 });
  });

  ST.startDialog = async function (courseId, info) {
    const c = info || (await api("/api/lecturer/courses/" + courseId));
    const today = c.today;
    let mins = 120;
    if (today && today.startTime && today.endTime) {
      const [h1, m1] = today.startTime.split(":").map(Number), [h2, m2] = today.endTime.split(":").map(Number);
      mins = (h2 * 60 + m2) - (h1 * 60 + m1);
    }
    const r = await SA.formDialog({ title: "Start class", intro: (c.course.code ? c.course.code + " · " : "") + c.course.title, fields: [
      { name: "classMinutes", label: "Class length (minutes)", type: "number", value: mins, required: true },
      { name: "geofenceMeters", label: "Students must be within (metres)", type: "number", value: 50, required: true },
      { name: "checkoutMinutes", label: "End-of-class confirm window (minutes)", type: "number", value: 10, required: true },
      { name: "online", type: "checkbox", label: "Online class (no location check)", value: today && today.mode === "ONLINE" },
    ], submit: "Start" });
    if (!r) return;
    await busy(null, async () => {
      const body = { courseId, classMinutes: Number(r.classMinutes), geofenceMeters: Number(r.geofenceMeters), checkoutMinutes: Number(r.checkoutMinutes) };
      if (r.online) Object.assign(body, { mode: "ONLINE", proximityPolicy: "NONE" });
      else Object.assign(body, { mode: "IN_PERSON", proximityPolicy: "GEOFENCE" }, await ST.position());
      const s = await api("/api/lecturer/sessions", { method: "POST", body });
      location.hash = "#/live/" + s.sessionId;
    });
  };

  /* ---------------------------------------------------------------- live session */
  /** Board view after attendance is turned off: the codes are gone. */
  ST.boardOff = function (id, title) {
    ST.cleanup();
    SA.view("Attendance off", `<section class="projector off-board" id="proj">
        <div class="course">${esc(title || "")}</div>
        <div class="off-mark">Attendance off</div>
        <p class="sub">The QR code and code no longer work.</p>
        <div class="row center-row"><a class="btn secondary" href="#/">Courses</a><a class="btn ghost light" href="#/sessions/${esc(id)}">View record</a></div>
      </section>`);
  };

  ST.live = async function ([id], params) {
    ST.cleanup();
    SA.loading();
    let snap = await api("/api/lecturer/sessions/" + encodeURIComponent(id));
    if (snap.session.status !== "OPEN") { ST.boardOff(id, snap.session.courseTitle); return; }
    const s = snap.session;
    const v = SA.view("Live attendance", `<div class="live-grid">
      <section class="projector" id="proj" aria-label="Attendance code for students">
        <div class="row"><div class="grow"><div class="course">${esc(s.courseTitle)}</div><div class="sub">${esc([s.courseCode, s.mode === "ONLINE" ? "Online" : s.venue, s.groupName].filter(Boolean).join(" · "))}</div></div>
          <button class="btn secondary sm" id="offBtn" type="button">Turn off</button>
          <button class="btn secondary sm" id="fs" type="button">Board</button></div>
        <div class="qr" id="qr" aria-label="QR code"></div>
        <div class="otp" id="otp" aria-live="polite">······</div>
        <div class="timer"><i id="bar"></i></div>
        <div class="count" id="count">Scan the QR code or enter the code · changes every 60 s</div>
        <div class="loc" id="loc">Location: starting…</div>
      </section>
      <section class="stack">
        <div class="stats" id="liveStats"></div>
        <div class="panel tight live-actions"><span class="small muted" id="closes"></span>
          <span class="btns">${SA.courseRep ? "" : `<button class="btn ghost sm" id="cancelBtn" type="button">Cancel session</button>`}<button class="btn danger sm" id="closeBtn" type="button">Turn off attendance</button></span></div>
        <div class="panel"><h3>Signed in</h3><ul class="feed" id="feed"></ul></div>
        <div class="panel"><h3>Failed attempts</h3><ul class="feed" id="fails"></ul></div>
      </section></div>`);
    let ch = null, offset = 0, timer = null, stopped = false;
    const qrBox = $("#qr", v), otpEl = $("#otp", v), bar = $("#bar", v), countEl = $("#count", v);

    async function loadChallenge() {
      try {
        ch = await api(`/api/lecturer/sessions/${id}/challenge`);
        offset = new Date(ch.serverNow).getTime() - Date.now(); // align countdown with server time
        qrBox.innerHTML = ch.qrSvg; // server-generated SVG (no user content)
        otpEl.textContent = ch.otp.slice(0, 3) + " " + ch.otp.slice(3);
      } catch (e) { if (e.code === "SESSION_CLOSED") { stopped = true; ST.boardOff(id, s.courseTitle); } else SA.toast(e.message, "error"); }
    }
    function tick() {
      if (!ch || stopped) return;
      const now = Date.now() + offset;
      const left = new Date(ch.expiresAt).getTime() - now;
      const frac = Math.max(0, Math.min(1, left / (ch.rotationSeconds * 1000)));
      bar.style.width = (frac * 100).toFixed(1) + "%";
      countEl.textContent = `New code in ${Math.max(0, Math.ceil(left / 1000))} s`;
      if (left <= 600) { ch = null; loadChallenge(); }
      const ends = new Date(s.endsAt || s.closesAt).getTime();
      const confirmFrom = ends - (s.checkoutMinutes || 0) * 60000;
      $("#closes", v).textContent = now < confirmFrom ? `Class ends ${SA.time(s.endsAt || s.closesAt)} · confirm opens ${SA.time(new Date(confirmFrom).toISOString())}`
        : now < ends ? `Students confirming now · class ends ${SA.time(s.endsAt)}` : `Closing ${SA.time(s.closesAt)}`;
    }
    function renderSnap() {
      const c = snap.counts;
      $("#liveStats", v).innerHTML = `<div class="stat"><b>${c.CHECKED_IN}</b><span>Checked in</span></div>
        <div class="stat"><b>${c.PRESENT + c.LATE}</b><span>Confirmed</span></div><div class="stat"><b>${snap.enrolled}</b><span>Students</span></div>
        <div class="stat"><b>${snap.failedAttempts}</b><span>Failed</span></div>`;
      $("#feed", v).innerHTML = snap.records.filter((r) => r.status !== "ABSENT").map((r) => `<li><div><b>${esc(r.name)}</b><div class="small muted">${esc(r.indexNumber)} · ${esc(SA.time(r.submittedAt))}</div></div>
        <div class="row">${SA.badge(r.status)}${r.status === "PENDING_VERIFICATION" ? `<button class="btn sm success" type="button" data-approve="${esc(r.studentId)}">Approve</button>` : ""}</div></li>`).join("") || `<li class="muted">Nobody yet.</li>`;
      $("#fails", v).innerHTML = snap.failures.slice(0, 15).map((f) => `<li><div><b>${esc(f.name)}</b><div class="small muted">${esc(SA.label(f.eventType))} · ${esc((f.detail && f.detail.reason) ? SA.label(f.detail.reason) : "")}</div></div><span class="small muted">${esc(SA.time(f.createdAt))}</span></li>`).join("") || `<li class="muted">None.</li>`;
      $$("[data-approve]", v).forEach((b) => b.addEventListener("click", () => busy(b, async () => {
        await api(`/api/lecturer/sessions/${id}/records/${b.dataset.approve}`, { method: "POST", body: { status: "PRESENT", reason: "Verified in class by lecturer" } });
        refresh();
      })));
    }
    async function refresh() {
      try { snap = await api("/api/lecturer/sessions/" + id); } catch (e) { return; }
      if (snap.session.status !== "OPEN") { stopped = true; ST.boardOff(id, s.courseTitle); return; }
      renderSnap();
    }
    renderSnap();
    await loadChallenge();
    timer = setInterval(tick, 250);
    // Real-time updates via Server-Sent Events, with polling as a fallback.
    const es = new EventSource(`/api/lecturer/sessions/${id}/stream`);
    let pending = false;
    es.addEventListener("update", () => { if (pending) return; pending = true; setTimeout(() => { pending = false; refresh(); }, 400); });
    const poll = setInterval(refresh, 15000);
    // Keep the geofence centred on this device while the screen is open.
    let watch = null, lastSent = 0, unlisten = null;
    const locEl = $("#loc", v);
    const label = (p) => {
      if (!p) { locEl.textContent = "Location off · students can't check in"; locEl.classList.add("off"); return; }
      locEl.textContent = `Location on · ±${Math.round(p.accuracy)} m · ${s.geofenceM} m range`;
      locEl.classList.remove("off");
    };
    if (s.mode === "ONLINE") locEl.textContent = "Online class · no location check";
    else if (SA.ownerLocation) {
      // Admin app: one shared location reader for every open class.
      const L = SA.ownerLocation;
      if (!L.openIds.includes(id)) L.openIds.push(id);
      L.start();
      L.listeners.add(label);
      unlisten = () => L.listeners.delete(label);
      if (L.last) { label(L.last); L.push(); }
    } else if (navigator.geolocation) {
      watch = navigator.geolocation.watchPosition((pos) => {
        const p = { latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracy: pos.coords.accuracy };
        label(p);
        if (Date.now() - lastSent < 30000) return;
        lastSent = Date.now();
        api(`/api/lecturer/sessions/${id}/anchor`, { method: "POST", body: p }).catch(() => {});
      }, () => label(null), { enableHighAccuracy: true, maximumAge: 10000 });
    }
    liveCleanup = () => { stopped = true; clearInterval(timer); clearInterval(poll); es.close(); if (watch !== null) navigator.geolocation.clearWatch(watch); if (unlisten) unlisten(); };

    const setBoard = (on) => {
      const p = $("#proj", v);
      p.classList.toggle("fullscreen", on);
      $("#fs", v).textContent = on ? "Exit board" : "Board";
      if (on && p.requestFullscreen) p.requestFullscreen().catch(() => {});
      else if (!on && document.fullscreenElement) document.exitFullscreen().catch(() => {});
    };
    $("#fs", v).addEventListener("click", () => setBoard(!$("#proj", v).classList.contains("fullscreen")));
    if (params && params.board) setBoard(true);
    const turnOff = async () => {
      if (!(await SA.confirm("Turn off attendance?", "The QR code and code stop working now. Students who haven't confirmed are marked absent.", "Turn off", true))) return;
      await busy(null, async () => {
        const r = await api(`/api/lecturer/sessions/${id}/close`, { method: "POST", body: {} });
        SA.toast(`Attendance off · ${r.markedAbsent + (r.leftEarly || 0)} absent`, "success");
        ST.boardOff(id, s.courseTitle);
      });
    };
    $("#offBtn", v).addEventListener("click", turnOff);
    $("#closeBtn", v).addEventListener("click", async () => {
      if (!(await SA.confirm("Close attendance?", "The code and QR stop working immediately. Enrolled students without a record are marked absent.", "Close attendance", true))) return;
      busy($("#closeBtn", v), async () => { const r = await api(`/api/lecturer/sessions/${id}/close`, { method: "POST", body: {} }); SA.toast(`Attendance off · ${r.markedAbsent + (r.leftEarly || 0)} absent`, "success"); ST.boardOff(id, s.courseTitle); });
    });
    if ($("#cancelBtn", v)) $("#cancelBtn", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Cancel this session?", intro: "Cancelled sessions don't count towards anyone's attendance percentage.", fields: [{ name: "reason", label: "Reason", type: "text", required: true }], submit: "Cancel session", danger: true });
      if (!r) return;
      busy(null, async () => { await api(`/api/lecturer/sessions/${id}/cancel`, { method: "POST", body: r }); ST.boardOff(id, s.courseTitle); });
    });
  };

  /* ---------------------------------------------------------------- session record (closed) */
  ST.session = async function ([id]) {
    ST.cleanup();
    SA.loading();
    const snap = await api("/api/lecturer/sessions/" + encodeURIComponent(id));
    if (snap.session.status === "OPEN") { location.hash = "#/live/" + id; return; }
    const s = snap.session;
    const byStudent = Object.fromEntries(snap.records.map((r) => [r.studentId, r]));
    const v = SA.view("Session record", `<p class="muted">${esc(s.courseCode || "")} ${esc(s.courseTitle)} · ${esc(SA.date(s.sessionDate))} · ${esc(SA.label(s.mode))}${s.venue ? " · " + esc(s.venue) : ""} · ${SA.badge(s.status)}</p>
      <div class="stats">${Object.entries(snap.counts).map(([k, n]) => `<div class="stat"><b>${n}</b><span>${esc(SA.label(k))}</span></div>`).join("")}</div>
      ${SA.isSuper && s.status === "CLOSED" ? `<div class="row"><button class="btn sm success" type="button" data-all="PRESENT">Everyone present</button><button class="btn sm secondary" type="button" data-all="ABSENT">Clear all marks</button></div><br>` : ""}
      <div class="panel"><div class="panel-head"><h3>Students</h3><div class="row"><button class="btn sm" type="button" data-dl="/api/lecturer/reports/session/${esc(id)}?format=pdf">Attendance sheet (PDF)</button><button class="btn secondary sm" type="button" data-dl="/api/lecturer/reports/session/${esc(id)}?format=xlsx">Excel</button></div></div>
      ${SA.table([{ label: "Index", key: "indexNumber" }, { label: "Name", key: "name" },
        { label: "Status", html: (r) => byStudent[r.studentId] ? SA.badge(byStudent[r.studentId].status) : SA.badge("ABSENT") },
        { label: "Time", get: (r) => byStudent[r.studentId] && byStudent[r.studentId].status !== "ABSENT" ? SA.time(byStudent[r.studentId].submittedAt) : "—" },
        { label: "Note", get: (r) => (byStudent[r.studentId] && byStudent[r.studentId].reason) || "" },
        { label: "", html: (r) => s.status === "CANCELLED" || SA.courseRep ? "" : `<button class="btn ghost sm" data-mark="${esc(r.studentId)}" data-name="${esc(r.name)}">Change</button>` }], snap.roster)}</div>`);
    ST.bindDownloads(v);
    $$("[data-all]", v).forEach((b) => b.addEventListener("click", async () => {
      const present = b.dataset.all === "PRESENT";
      if (!(await SA.confirm(present ? "Mark everyone present?" : "Clear all marks?", present ? "Every student in this class is marked present." : "Every student in this class is set to absent.", present ? "Mark present" : "Clear", !present))) return;
      busy(null, async () => { const r = await api(`/api/admin/sessions/${id}/mark-all`, { method: "POST", body: { status: b.dataset.all } }); SA.toast(`${r.updated} students updated`, "success"); ST.session([id]); });
    }));
    $$("[data-mark]", v).forEach((b) => b.addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Change attendance", intro: b.dataset.name + ". Manual changes are recorded in the audit log.",
        fields: [{ name: "status", label: "New status", type: "select", value: "EXCUSED", options: ["PRESENT", "LATE", "EXCUSED", "ABSENT", "REJECTED"].map((x) => ({ value: x, label: SA.label(x) })) },
          { name: "reason", label: "Reason", type: "text", required: true, hint: "e.g. Medical certificate seen, verified in person" }], submit: "Save change" });
      if (!r) return;
      busy(null, async () => { await api(`/api/lecturer/sessions/${id}/records/${b.dataset.mark}`, { method: "POST", body: r }); SA.toast("Attendance updated", "success"); ST.session([id]); });
    }));
  };

  /* ---------------------------------------------------------------- corrections & alerts */
  ST.corrections = async function (_, params) {
    SA.loading();
    const status = params.status || "PENDING";
    const rows = await api("/api/lecturer/corrections?status=" + status);
    const v = SA.view("Correction requests", `<div class="filters"><select id="st" aria-label="Status">${["PENDING", "APPROVED", "REJECTED", "ALL"].map((s) => `<option ${s === status ? "selected" : ""} value="${s}">${SA.label(s)}</option>`).join("")}</select></div>
      ${SA.table([{ label: "Student", html: (r) => `${esc(r.name)}<div class="small muted">${esc(r.indexNumber)}</div>` }, { label: "Session", get: (r) => (r.courseCode || r.courseTitle) + " · " + SA.date(r.sessionDate) },
        { label: "Current", html: (r) => SA.badge(r.currentStatus) }, { label: "Requested", html: (r) => SA.badge(r.requestedStatus) }, { label: "Reason", get: (r) => r.reason },
        { label: "", html: (r) => r.status === "PENDING" ? `<div class="row"><button class="btn sm success" data-res="${esc(r.id)}" data-ok="1">Approve</button><button class="btn sm secondary" data-res="${esc(r.id)}">Reject</button></div>` : SA.badge(r.status) }], rows, "No correction requests.")}`);
    $("#st", v).addEventListener("change", (e) => (location.hash = "#/corrections?status=" + e.target.value));
    $$("[data-res]", v).forEach((b) => b.addEventListener("click", async () => {
      const ok = !!b.dataset.ok;
      const r = await SA.formDialog({ title: ok ? "Approve correction" : "Reject correction", fields: [{ name: "note", label: ok ? "Note (optional)" : "Reason for rejecting", type: "text", required: !ok }], submit: ok ? "Approve" : "Reject", danger: !ok });
      if (!r) return;
      busy(null, async () => { await api(`/api/lecturer/corrections/${b.dataset.res}/resolve`, { method: "POST", body: { approve: ok, note: r.note } }); ST.corrections(_, params); });
    }));
  };

  ST.lecturerAlerts = async function () {
    SA.loading();
    const rows = await api("/api/lecturer/alerts");
    SA.view("Suspicious activity", `<p class="muted">Signals for your courses. These are prompts to check in person — not proof of wrongdoing. Administrators review and close alerts.</p>
      ${SA.table([{ label: "When", get: (a) => SA.dateTime(a.createdAt) }, { label: "Signal", get: (a) => SA.label(a.kind) }, { label: "Severity", html: (a) => SA.badge(a.severity) },
        { label: "Student", get: (a) => a.indexNumber || "—" }, { label: "Course", get: (a) => (a.courseCode || a.courseTitle) + " · " + SA.date(a.sessionDate) }, { label: "Status", html: (a) => SA.badge(a.status) }], rows, "No suspicious activity recorded.")}`);
  };

  ST.account = async function () {
    const s = await SA.session();
    const v = SA.view("My account", `<div class="panel"><h2>${esc(s.name)}</h2><p class="muted">${esc(SA.label(s.role))}</p></div>
      <div class="panel"><h3>Change password</h3><form id="pw" novalidate><div class="field"><label for="c">Current password</label><input id="c" type="password" autocomplete="current-password"></div>
      <div class="field"><label for="n">New password</label><input id="n" type="password" autocomplete="new-password"><div class="hint">At least 12 characters, mixing upper and lower case, numbers or symbols.</div></div>
      <div class="err"></div><button class="btn">Change password</button></form></div>`);
    $("#pw", v).addEventListener("submit", (e) => { e.preventDefault(); busy($("button", e.target), async () => {
      await api("/api/me/password", { method: "POST", body: { currentPassword: $("#c", v).value, newPassword: $("#n", v).value } }); SA.toast("Password changed", "success"); e.target.reset(); }, $(".err", v)); });
  };
})();
