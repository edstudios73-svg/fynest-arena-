/* Administrator & Super Administrator console. Every action is authorised and audited on the server. */
(function () {
  "use strict";
  const { esc, $, $$, api, busy } = SA;
  let me = null;
  const ST = () => SA.staff;
  const opts = (rows, v, l) => rows.map((r) => ({ value: r[v], label: typeof l === "function" ? l(r) : r[l] }));

  SA.onUnauthenticated = () => { location.href = "/admin-login.html"; };

  document.addEventListener("DOMContentLoaded", async () => {
    me = await SA.session().catch(() => null);
    if (!me) return SA.onUnauthenticated();
    if (me.role === "STUDENT") { location.href = "/student.html"; return; }
    if (me.role === "LECTURER") { location.href = "/lecturer.html"; return; }
    if (me.mustChangePassword) { location.href = "/admin-login.html"; return; }
    const sup = me.role === "SUPER_ADMIN";
    SA.isSuper = sup;
    SA.courseRep = me.role === "ADMIN";
    const repNav = [
      { key: "home", href: "#/", label: "Courses", icon: "book" },
      { key: "sessions", href: "#/sessions", label: "Past classes", icon: "list" },
      { key: "analytics", href: "#/analytics", label: "Attendance", icon: "chart" },
      { key: "reports", href: "#/reports", label: "Reports", icon: "file" },
      { key: "account", href: "#/account", label: "My account", icon: "user" },
    ];
    const repTabs = repNav.filter((n) => n.key !== "account");
    SA.shell({
      me, home: "#/", cls: "staff",
      nav: SA.courseRep ? repNav : [
        { key: "home", href: "#/", label: "Courses", icon: "book" },
        { key: "students", href: "#/students", label: "Students", icon: "users" },
        { key: "register", href: "#/register", label: "Class list", icon: "upload" },
        { key: "reports", href: "#/reports", label: "Reports", icon: "file" },
        { key: "settings", href: "#/settings", label: "Settings", icon: "settings" },
        { group: "More" },
        { key: "overview", href: "#/overview", label: "Overview", icon: "home" },
        { key: "sessions", href: "#/sessions", label: "Past classes", icon: "list" },
        { key: "analytics", href: "#/analytics", label: "Analytics", icon: "chart" },
        { key: "security", href: "#/security", label: "Alerts", icon: "alert" },
        { key: "audit", href: "#/audit", label: "Audit log", icon: "log" },
        { key: "account", href: "#/account", label: "My account", icon: "user" },
      ],
      tabs: SA.courseRep ? repTabs : [
        { key: "home", href: "#/", label: "Courses", icon: "book" },
        { key: "students", href: "#/students", label: "Students", icon: "users" },
        { key: "register", href: "#/register", label: "Class list", icon: "upload" },
        { key: "settings", href: "#/menu", label: "More", icon: "list" },
      ],
    });
    OwnerLocation.init();
    SA.router([
      SA.route("/", coursesHome, { key: "home" }),
      SA.route("/overview", overview, { key: "overview" }),
      SA.route("/menu", menu, { key: "settings" }),
      SA.route("/register", register, { key: "register" }),
      SA.route("/students", students, { key: "students" }),
      SA.route("/students/:id", student, { key: "students" }),
      SA.route("/lecturers", lecturers, { key: "lecturers" }),
      SA.route("/courses", coursesHome, { key: "home" }),
      SA.route("/courses/manage", courses, { key: "courses" }),
      SA.route("/courses/:id", (a, p) => ST().course(a, p), { key: "courses" }),
      SA.route("/live/:id", (a, p) => ST().live(a, p), { key: "home" }),
      SA.route("/sessions/:id", (a) => ST().session(a), { key: "sessions" }),
      SA.route("/sessions", sessions, { key: "sessions" }),
      SA.route("/timetable", timetable, { key: "timetable" }),
      SA.route("/venues", venues, { key: "venues" }),
      SA.route("/analytics", analytics, { key: "analytics" }),
      SA.route("/reports", reports, { key: "reports" }),
      SA.route("/security", security, { key: "security" }),
      SA.route("/recovery", recovery, { key: "recovery" }),
      SA.route("/devices", devices, { key: "devices" }),
      SA.route("/audit", audit, { key: "audit" }),
      SA.route("/settings", settings, { key: "settings" }),
      SA.route("/structure", structure, { key: "structure" }),
      SA.route("/admins", admins, { key: "admins" }),
      SA.route("/notifications", SA.notificationsView, { key: "home" }),
      SA.route("/account", () => ST().account(), { key: "account" }),
    ], (r) => { SA.markNav(r.key); ST().cleanup(); });
  });

  async function classes() {
    SA.loading();
    const o = await api("/api/lecturer/overview");
    const v = SA.view("Classes", `<div class="course-list">${o.courses.map((c) => `<div class="course-row">
        <span><b>${esc(c.title)}</b><small>${esc(c.code || "")} · ${c.enrolled} students</small></span>
        ${c.openSessionId ? `<a class="btn sm" href="#/live/${esc(c.openSessionId)}">Open</a>` : `<button class="btn sm" type="button" data-start="${esc(c.id)}">Start</button>`}</div>`).join("")}</div>
      <p class="small muted center"><a href="#/courses/manage">Edit courses</a></p>`);
    $$("[data-start]", v).forEach((b) => b.addEventListener("click", () => ST().startDialog(b.dataset.start)));
  }

  /* ---------------------------------------------------------------- owner location (always-on option) */
  const OwnerLocation = SA.ownerLocation = {
    watch: null, last: null, lastSent: 0, openIds: [], listeners: new Set(),
    on() { return localStorage.getItem("sa_owner_loc") === "on"; },
    init() { if (this.on()) this.start(); },
    start() {
      if (!navigator.geolocation) { SA.toast("This device can't share its location.", "error"); return false; }
      if (!window.isSecureContext) { SA.toast("Location needs the https:// link.", "error"); return false; }
      localStorage.setItem("sa_owner_loc", "on");
      if (this.watch !== null) return true;
      this.problem = null;
      this.listen(true);
      this.paint();
      return true;
    },
    /** Watch the position. High accuracy (GPS) first; if it can't get a fix, fall back to Wi-Fi/network location instead of giving up. */
    listen(high) {
      if (this.watch !== null) navigator.geolocation.clearWatch(this.watch);
      this.high = high;
      this.watch = navigator.geolocation.watchPosition((p) => {
        this.problem = null;
        this.last = { latitude: p.coords.latitude, longitude: p.coords.longitude, accuracy: p.coords.accuracy };
        this.paint();
        this.listeners.forEach((fn) => fn(this.last));
        if (Date.now() - this.lastSent > 30000) this.push();
      }, (e) => {
        if (e.code === 1) {
          // Permission denied: only the user can fix this in the browser.
          this.stop();
          this.listeners.forEach((fn) => fn(null));
          SA.formDialog({ title: "Allow location", intro: "Your browser blocked location for this site. Tap the lock icon next to the address, set Location to Allow, then turn My location on again. On a phone, also turn on Location in Settings.", fields: [], submit: "OK" });
          return;
        }
        // Timeout or no fix yet (common indoors): keep trying, with network location if GPS can't see the sky.
        this.problem = this.last ? null : "Finding your location…";
        this.paint();
        if (this.high) this.listen(false);
        else setTimeout(() => { if (this.on() && !this.last) this.listen(true); }, 15000);
      }, { enableHighAccuracy: high, maximumAge: 15000, timeout: high ? 20000 : 30000 });
    },
    stop() {
      localStorage.setItem("sa_owner_loc", "off");
      if (this.watch !== null) navigator.geolocation.clearWatch(this.watch);
      this.watch = null; this.last = null;
      this.paint();
    },
    push() {
      if (!this.last) return;
      this.lastSent = Date.now();
      [...new Set(this.openIds)].forEach((id) => api(`/api/lecturer/sessions/${id}/anchor`, { method: "POST", body: this.last }).catch(() => {}));
    },
    current() {
      if (this.last) return Promise.resolve(this.last);
      if (!this.on()) this.start();
      return new Promise((resolve, reject) => {
        const done = (p) => { clearTimeout(t); this.listeners.delete(done); p ? resolve(p) : reject(new SA.ApiError(0, "GPS", "Location is blocked. Allow it for this site and try again.")); };
        const t = setTimeout(() => { this.listeners.delete(done); reject(new SA.ApiError(0, "GPS", "Still finding your location. Wait a few seconds and try again.")); }, 45000);
        this.listeners.add(done);
      });
    },
    paint() {
      const sw = $("#locSwitch");
      if (!sw) return;
      sw.checked = this.on();
      $("#locText").textContent = !this.on() ? "Off" : this.last ? `On · ±${Math.round(this.last.accuracy)} m` : this.problem || "Turning on…";
    },
  };

  /** Super admin: record a class where attendance wasn't turned on, then adjust students on the record. */
  async function manualClass(courseId, title) {
    const today = new Date().toISOString().slice(0, 10);
    const r = await SA.formDialog({ title: "Mark manually", intro: title + " · for a class where attendance wasn't turned on.",
      fields: [{ name: "date", label: "Class date", type: "date", value: today, required: true },
        { name: "everyone", label: "Start with", type: "select", value: "PRESENT", options: [{ value: "PRESENT", label: "Everyone present" }, { value: "ABSENT", label: "Everyone absent" }] }],
      submit: "Create" });
    if (!r) return;
    busy(null, async () => {
      const out = await api(`/api/admin/courses/${courseId}/manual-class`, { method: "POST", body: { date: r.date, everyonePresent: r.everyone === "PRESENT" } });
      SA.toast(`Class recorded · ${out.students} students`, "success");
      location.hash = "#/sessions/" + out.sessionId;
    });
  }

  /* ---------------------------------------------------------------- courses home */
  async function coursesHome() {
    SA.loading();
    const rows = await api("/api/admin/courses");
    OwnerLocation.openIds = rows.filter((c) => c.openSessionId).map((c) => c.openSessionId);
    const v = SA.view("Courses", `
      <div class="owner-bar"><label class="switch"><input type="checkbox" id="locSwitch"><span class="slider"></span></label>
        <div><b>My location</b><small id="locText">Off</small></div>
        ${SA.isSuper ? `<button class="btn sm secondary" type="button" id="addCourse">+ Course</button>` : ""}</div>
      <div class="ccards">${rows.map((c) => {
        const live = !!c.openSessionId;
        return `<article class="ccard ${live ? "live" : ""}">
          <header><div><h3>${esc(c.title)}</h3><small>${esc([c.code, c.lecturerName].filter(Boolean).join(" · ") || "No code")}</small></div>
            <button class="icon-btn" type="button" data-edit="${esc(c.id)}" aria-label="Edit ${esc(c.title)}">${SA.icon.settings}</button></header>
          <div class="chips"><span>${c.creditHours != null ? esc(c.creditHours) + " credit hrs" : "Credit hrs —"}</span><span>${esc(c.classMinutes)} min</span>
            <span>${esc(c.geofenceM)} m</span><span>${esc(c.enrolled)} students</span></div>
          ${live ? `<p class="live-line"><span class="dot-live"></span>Live · ${esc(c.checkedIn)} checked in · ${esc(c.confirmed)} confirmed · ends ${esc(SA.time(c.openEndsAt))}</p>` : ""}
          <footer><label class="switch"><input type="checkbox" data-toggle="${esc(c.id)}" ${live ? "checked" : ""}><span class="slider"></span></label>
            <span class="sw-label">${live ? "Attendance on" : "Attendance off"}</span>
            ${live ? `<a class="btn sm" href="#/live/${esc(c.openSessionId)}?board=1">Show on board</a>` : ""}
            ${!live && SA.isSuper ? `<button class="btn sm ghost" type="button" data-manual="${esc(c.id)}" data-title="${esc(c.title)}">Mark manually</button>` : ""}</footer>
        </article>`;
      }).join("")}</div>`);
    OwnerLocation.paint();
    if (OwnerLocation.on()) OwnerLocation.push();

    $("#locSwitch", v).addEventListener("change", (e) => {
      if (e.target.checked) { if (OwnerLocation.start()) OwnerLocation.push(); else e.target.checked = false; } else OwnerLocation.stop();
    });
    if ($("#addCourse", v)) $("#addCourse", v).addEventListener("click", () => editCourse(null));
    $$("[data-manual]", v).forEach((b) => b.addEventListener("click", () => manualClass(b.dataset.manual, b.dataset.title)));
    $$("[data-edit]", v).forEach((b) => b.addEventListener("click", () => editCourse(rows.find((c) => c.id === b.dataset.edit))));
    $$("[data-toggle]", v).forEach((t) => t.addEventListener("change", async () => {
      const c = rows.find((x) => x.id === t.dataset.toggle);
      t.disabled = true;
      try {
        if (t.checked) await activate(c);
        else {
          if (!(await SA.confirm("Turn off attendance?", `${c.title}: students who haven't confirmed will be marked absent.`, "Turn off", true))) { t.checked = true; return; }
          await api(`/api/lecturer/sessions/${c.openSessionId}/close`, { method: "POST", body: {} });
          SA.toast("Attendance off", "success");
        }
        coursesHome();
      } catch (e) {
        SA.toast(e.message, "error");
        t.checked = !t.checked;
      } finally { t.disabled = false; }
    }));
    clearInterval(window.__ownerPoll);
    window.__ownerPoll = setInterval(() => { if (location.hash === "" || location.hash === "#/" || location.hash === "#/courses") { if (!document.hidden && !document.querySelector("dialog")) coursesHome(); } else clearInterval(window.__ownerPoll); }, 20000);
  }

  async function activate(c) {
    const body = { courseId: c.id, classMinutes: c.classMinutes, geofenceMeters: c.geofenceM };
    if (!OwnerLocation.on()) {
      const r = await SA.formDialog({ title: "Location is off", intro: "Students must be near you to check in.",
        fields: [{ name: "online", type: "checkbox", label: "Online class instead (no location check)" }], submit: "Continue" });
      if (!r) throw new SA.ApiError(0, "CANCELLED", "Cancelled.");
      if (r.online) Object.assign(body, { mode: "ONLINE", proximityPolicy: "NONE" });
      else OwnerLocation.start();
    }
    if (!body.mode) Object.assign(body, { mode: "IN_PERSON", proximityPolicy: "GEOFENCE" }, await OwnerLocation.current());
    const s = await api("/api/lecturer/sessions", { method: "POST", body });
    SA.toast("Attendance on", "success");
    location.hash = "#/live/" + s.sessionId + "?board=1";
  }

  async function editCourse(c) {
    const r = await SA.formDialog({ title: c ? "Edit course" : "New course", fields: [
      { name: "title", label: "Course name", value: c && c.title, required: true },
      { name: "code", label: "Course code", value: c && c.code },
      { name: "lecturerName", label: "Lecturer", value: c && c.lecturerName },
      { name: "creditHours", label: "Credit hours", type: "number", value: c ? c.creditHours ?? "" : 3 },
      { name: "classMinutes", label: "Duration (minutes)", type: "number", value: c ? c.classMinutes : 120, required: true },
      { name: "geofenceMeters", label: "Students within (metres)", type: "number", value: c ? c.geofenceM : 100, required: true },
    ], submit: "Save" });
    if (!r) return;
    const body = { ...r, creditHours: r.creditHours === "" ? null : Number(r.creditHours), classMinutes: Number(r.classMinutes), geofenceMeters: Number(r.geofenceMeters), altCode: c ? c.altCode : null };
    await busy(null, async () => {
      if (c) await api("/api/admin/courses/" + c.id, { method: "PUT", body });
      else await api("/api/admin/courses", { method: "POST", body });
      SA.toast("Saved", "success");
      coursesHome();
    });
  }

  function menu() {
    SA.view("More", `<div class="choice">${SA.$$(".sidebar nav a").map((a) => `<a class="btn secondary block" href="${esc(a.getAttribute("href"))}">${esc(a.textContent.trim())}</a>`).join("")}</div>`);
  }

  /* ---------------------------------------------------------------- overview */
  async function overview() {
    SA.loading();
    const o = await api("/api/admin/overview");
    const c = o.counts;
    SA.view("Overview", `
      ${o.warnings.map((w) => `<div class="alert ${w.level === "HIGH" ? "error" : w.level === "MEDIUM" ? "warn" : "info"}">${esc(w.message)}${w.link ? ` <a href="${esc(w.link)}">Fix this</a>` : ""}</div>`).join("")}
      <div class="stats">
        <div class="stat"><b>${c.registerSize}</b><span>Students on register</span></div>
        <div class="stat"><b>${c.activeAccounts}</b><span>Active accounts</span></div>
        <div class="stat"><b>${c.pendingApprovals}</b><span>Awaiting approval</span></div>
        <div class="stat"><b>${c.biometricEnrolled}</b><span>Face enrolled</span></div>
        <div class="stat"><b>${c.openSessions}</b><span>Open sessions</span></div>
        <div class="stat"><b>${c.openAlerts}</b><span>Open alerts</span></div>
        <div class="stat"><b>${c.pendingRecovery}</b><span>Recovery requests</span></div>
        <div class="stat"><b>${esc(SA.pct(o.summary.percentage))}</b><span>Overall attendance</span></div>
      </div>
      <div class="grid2"><div class="panel"><h3>Weekly attendance</h3>${SA.trendChart(o.trend)}</div>
      <div class="panel"><h3>Class registers</h3>${SA.table([{ label: "Class", get: (g) => `${g.programme} L${g.level} ${g.name}` },
        { label: "Records", num: true, key: "actualSize" }, { label: "Expected", num: true, get: (g) => g.expectedSize ?? "—" }, { label: "Status", html: (g) => SA.badge(g.registerStatus) }], o.groups)}
        <p class="small"><a href="#/register">Manage registers</a></p></div></div>`);
  }

  /* ---------------------------------------------------------------- register & import */
  async function register() {
    SA.loading();
    const [groups, batches] = await Promise.all([api("/api/admin/groups"), api("/api/admin/imports")]);
    const v = SA.view("Class register & import", `
      <div class="panel"><h3>Import the official register</h3>
        <p class="muted">Upload the department's Excel workbook (e.g. <i>DIPLOMA IN COMPUTER SCIENCE GROUPS.xlsx</i>, sheet "GROUP A") or a CSV with the columns Student ID, Title, First Name, Last Name, Other Names. Nothing is saved until you confirm the preview.</p>
        <form id="imp" novalidate><div class="grid2"><div class="field"><label for="grp">Class</label><select id="grp" name="classGroupId">${groups.map((g) => `<option value="${esc(g.id)}">${esc(g.programme)} · Level ${esc(g.level)} · ${esc(g.name)}</option>`).join("")}</select></div>
        <div class="field"><label for="sheet">Sheet name (optional)</label><input id="sheet" name="sheetName" type="text" placeholder="GROUP A"></div></div>
        <div class="field"><label for="file">File</label><input id="file" name="file" type="file" accept=".xlsx,.xls,.csv" required></div>
        <label class="check"><input type="checkbox" name="enrolAllCourses" checked> <span>Enrol imported students in every course on this class's timetable</span></label>
        <div class="err"></div><button class="btn" data-busy="Validating…">Upload and preview</button></form></div>
      <div id="preview"></div>
      <div class="panel"><h3>Register verification</h3>
        ${groups.map((g) => {
          const mismatch = g.actualSize > 0 && g.expectedSize != null && g.expectedSize !== g.actualSize;
          return `<div class="panel tight"><div class="panel-head"><div><b>${esc(g.programme)} · Level ${esc(g.level)} · ${esc(g.name)}</b>
            <div class="small muted">${g.actualSize} active records (${g.totalRecords} total) · expected ${g.expectedSize ?? "not set"}${g.expectedSizeSource ? " — " + esc(g.expectedSizeSource) : ""}</div></div>${SA.badge(g.registerStatus)}</div>
            ${g.actualSize === 0 ? SA.alert("info", "No register imported yet. Import the official list above, then verify it here.") : ""}
            ${mismatch ? SA.alert("warn", `Discrepancy: the timetable states ${g.expectedSize} students but the register has ${g.actualSize}. Check with the department, mark any records that should not be active as "Withdrawn" or "Under review", then verify.`) : ""}
            ${g.registerStatus === "VERIFIED" ? `<p class="small muted">Verified ${esc(SA.dateTime(g.registerVerifiedAt))}: ${esc(g.registerVerificationNote || "")}</p>` : ""}
            <div class="row"><button class="btn sm" data-verify="${esc(g.id)}" data-mismatch="${mismatch ? 1 : ""}" type="button">Verify register</button>
            <button class="btn secondary sm" data-expected="${esc(g.id)}" data-val="${esc(g.expectedSize ?? "")}" type="button">Change expected size</button>
            <a class="btn ghost sm" href="#/students?groupId=${esc(g.id)}">Review records</a></div></div>`;
        }).join("")}</div>
      <div class="panel"><h3>Import history</h3>${SA.table([{ label: "Uploaded", get: (b) => SA.dateTime(b.createdAt) }, { label: "File", key: "filename" }, { label: "Sheet", key: "sheetName" },
        { label: "Rows", num: true, key: "rowCount" }, { label: "Valid", num: true, key: "validCount" }, { label: "Invalid", num: true, key: "invalidCount" }, { label: "Duplicates", num: true, key: "duplicateCount" },
        { label: "Status", html: (b) => SA.badge(b.status) }, { label: "By", key: "uploadedBy" }], batches, "No imports yet.")}</div>`);
    $("#imp", v).addEventListener("submit", (e) => {
      e.preventDefault();
      const fd = new FormData(e.target);
      fd.set("enrolAllCourses", e.target.enrolAllCourses.checked ? "true" : "false");
      if (!fd.get("sheetName")) fd.delete("sheetName");
      if (!e.target.file.files.length) { $(".err", v).innerHTML = SA.alert("error", "Choose a file."); return; }
      busy($("button", e.target), async () => showPreview(await api("/api/admin/imports", { method: "POST", form: fd })), $(".err", v));
    });
    $$("[data-verify]", v).forEach((b) => b.addEventListener("click", async () => {
      const mismatch = !!b.dataset.mismatch;
      const r = await SA.formDialog({ title: "Verify class register", intro: mismatch ? "The record count does not match the expected class size. Explain how this was reconciled with the department." : "Confirm the register matches the department's official class list. Students can register once verified.",
        fields: [{ name: "note", label: "Verification note", type: "textarea", required: true, hint: "e.g. Checked against Academic Affairs list on 20 Nov; 5 late admissions confirmed." }].concat(mismatch ? [{ name: "ack", type: "checkbox", label: "I acknowledge the discrepancy and confirm the register is correct." }] : []), submit: "Verify register" });
      if (!r) return;
      busy(null, async () => { await api(`/api/admin/groups/${b.dataset.verify}/verify`, { method: "POST", body: { note: r.note, acknowledgeDiscrepancy: !!r.ack } }); SA.toast("Register verified", "success"); register(); });
    }));
    $$("[data-expected]", v).forEach((b) => b.addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Expected class size", intro: "Changing this requires the register to be verified again.", fields: [{ name: "expectedSize", label: "Expected number of students", type: "number", value: b.dataset.val, required: true }, { name: "source", label: "Source", type: "text", value: "Confirmed by department" }], submit: "Save" });
      if (!r) return;
      busy(null, async () => { await api(`/api/admin/groups/${b.dataset.expected}`, { method: "PUT", body: { expectedSize: Number(r.expectedSize), source: r.source } }); register(); });
    }));
  }

  function showPreview(p) {
    const box = $("#preview");
    if (!p.valid) { box.innerHTML = `<div class="panel">${p.fileErrors.map((e) => SA.alert("error", e)).join("")}${p.sheets && p.sheets.length ? `<p class="small muted">Sheets found: ${esc(p.sheets.join(", "))}</p>` : ""}</div>`; return; }
    const problems = p.rows.filter((r) => r.status !== "VALID");
    box.innerHTML = `<div class="panel"><div class="panel-head"><h3>Preview: ${esc(p.filename)} · ${esc(p.sheet)}</h3></div>
      <div class="stats"><div class="stat"><b>${p.rowCount}</b><span>Rows read</span></div><div class="stat"><b>${p.validCount}</b><span>Ready to import</span></div>
      <div class="stat"><b>${p.invalidCount}</b><span>Missing / invalid</span></div><div class="stat"><b>${p.duplicateCount}</b><span>Duplicate IDs</span></div><div class="stat"><b>${p.existingCount}</b><span>Already on register</span></div></div>
      ${p.discrepancy ? SA.alert("warn", p.discrepancy.message) : ""}
      ${problems.length ? `<h3>Rows that will not be imported</h3>${SA.table([{ label: "Row", num: true, key: "row" }, { label: "Student ID", key: "indexNumber" }, { label: "Name", get: (r) => [r.firstName, r.otherNames, r.lastName].filter(Boolean).join(" ") }, { label: "Status", html: (r) => SA.badge(r.status) }, { label: "Problem", get: (r) => r.errors.join("; ") }], problems)}` : SA.alert("success", "All rows are valid.")}
      <h3>First rows to import</h3>${SA.table([{ label: "Row", num: true, key: "row" }, { label: "Student ID", key: "indexNumber" }, { label: "Title", key: "title" }, { label: "First name", key: "firstName" }, { label: "Last name", key: "lastName" }, { label: "Other names", key: "otherNames" }], p.rows.filter((r) => r.status === "VALID").slice(0, 15))}
      <div class="row"><button class="btn" id="commit" type="button" ${p.validCount ? "" : "disabled"}>Import ${p.validCount} students</button><button class="btn secondary" id="cancelImp" type="button">Discard</button></div></div>`;
    $("#commit").addEventListener("click", (e) => busy(e.currentTarget, async () => {
      const r = await api(`/api/admin/imports/${p.batchId}/commit`, { method: "POST", body: {} });
      SA.toast(`Imported ${r.inserted} students · ${r.enrolments} course enrolments`, "success");
      register();
    }));
    $("#cancelImp").addEventListener("click", (e) => busy(e.currentTarget, async () => { await api(`/api/admin/imports/${p.batchId}/cancel`, { method: "POST", body: {} }); box.innerHTML = ""; }));
    box.scrollIntoView({ behavior: "smooth" });
  }

  /* ---------------------------------------------------------------- students */
  async function students(_, params) {
    SA.loading();
    const q = new URLSearchParams();
    ["q", "status", "groupId", "page"].forEach((k) => params[k] && q.set(k, params[k]));
    const [res, groups] = await Promise.all([api("/api/admin/students?" + q), api("/api/admin/groups")]);
    const page = Number(params.page || 0);
    const v = SA.view("Students", `<form class="filters" id="flt">
        <input type="search" name="q" placeholder="Index number or name" aria-label="Search" value="${esc(params.q || "")}">
        <select name="status" aria-label="Status"><option value="">All</option>${["NOT_REGISTERED", "PENDING_APPROVAL", "ACTIVE", "SUSPENDED", "UNDER_REVIEW", "WITHDRAWN"].map((s) => `<option value="${s}" ${params.status === s ? "selected" : ""}>${SA.label(s)}</option>`).join("")}</select>
        <select name="groupId" aria-label="Class"><option value="">All classes</option>${groups.map((g) => `<option value="${esc(g.id)}" ${params.groupId === g.id ? "selected" : ""}>${esc(g.programme)} L${esc(g.level)} ${esc(g.name)}</option>`).join("")}</select>
        <button class="btn secondary">Search</button><button class="btn" type="button" id="add">Add student</button></form>
      <p class="small muted">${res.total} students</p>
      ${SA.table([{ label: "Index", html: (s) => `<a href="#/students/${esc(s.id)}">${esc(s.indexNumber)}</a>` }, { label: "Name", key: "name" }, { label: "Class", key: "groupName" },
        { label: "Register", html: (s) => SA.badge(s.registerStatus) }, { label: "Account", html: (s) => SA.badge(s.accountStatus) },
        { label: "Face", html: (s) => s.biometricEnrolled ? SA.badge("ok").replace("Ok", "Enrolled") : '<span class="muted">—</span>' }, { label: "Passkeys", num: true, key: "passkeys" },
        { label: "", html: (s) => s.accountStatus === "PENDING_APPROVAL" ? `<button class="btn sm success" data-approve="${esc(s.id)}">Approve</button>` : "" }], res.rows, "No students match.")}
      <div class="row">${page > 0 ? `<a class="btn secondary sm" href="#/students?${new URLSearchParams({ ...params, page: page - 1 })}">Previous</a>` : ""}
      ${(page + 1) * res.pageSize < res.total ? `<a class="btn secondary sm" href="#/students?${new URLSearchParams({ ...params, page: page + 1 })}">Next</a>` : ""}</div>`);
    $("#flt", v).addEventListener("submit", (e) => { e.preventDefault(); const p = new URLSearchParams(new FormData(e.target)); [...p.keys()].forEach((k) => !p.get(k) && p.delete(k)); location.hash = "#/students?" + p; });
    $$("[data-approve]", v).forEach((b) => b.addEventListener("click", () => approve(b.dataset.approve, () => students(_, params))));
    $("#add", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Add a student", intro: "Only for students missing from the official register. The register must be verified again afterwards.", fields: [
        { name: "classGroupId", label: "Class", type: "select", options: opts(groups, "id", (g) => `${g.programme} L${g.level} ${g.name}`) },
        { name: "indexNumber", label: "Index number", required: true }, { name: "title", label: "Title" }, { name: "firstName", label: "First name", required: true },
        { name: "lastName", label: "Last name", required: true }, { name: "otherNames", label: "Other names" }, { name: "reason", label: "Reason / source", required: true }], submit: "Add student" });
      if (!r) return;
      busy(null, async () => { const x = await api("/api/admin/students", { method: "POST", body: r }); location.hash = "#/students/" + x.id; });
    });
  }

  async function approve(id, after) {
    const r = await SA.formDialog({ title: "Approve account", intro: "Confirm you have verified this student's identity (e.g. student ID card in person).", fields: [{ name: "reason", label: "How was identity verified?", required: true }], submit: "Approve" });
    if (!r) return;
    busy(null, async () => { await api(`/api/admin/students/${id}/approve`, { method: "POST", body: r }); SA.toast("Account approved", "success"); after(); });
  }

  async function student([id]) {
    SA.loading();
    const d = await api("/api/admin/students/" + encodeURIComponent(id));
    const s = d.student;
    const acct = s.userId && s.accountStatus !== "DISABLED" ? s.accountStatus : "NOT_REGISTERED";
    const v = SA.view(s.name, `<p class="muted">${esc(s.indexNumber)} · ${esc(s.groupName)} · register ${SA.badge(s.registerStatus)} · account ${SA.badge(acct)}</p>
      <div class="panel"><div class="row">
        <button class="btn secondary sm" id="edit" type="button">Correct register details</button>
        ${acct === "PENDING_APPROVAL" ? `<button class="btn sm success" id="approve" type="button">Approve account</button>` : ""}
        ${acct === "ACTIVE" || acct === "PENDING_APPROVAL" ? `<button class="btn sm danger" id="suspend" type="button">Suspend</button>` : ""}
        ${acct === "SUSPENDED" ? `<button class="btn sm" id="reactivate" type="button">Reactivate</button>` : ""}
        ${d.biometric.some((b) => b.status === "ACTIVE") ? `<button class="btn ghost sm" id="bio" type="button">Reset locked face</button>` : ""}
        <button class="btn ghost sm" data-dl="/api/admin/reports/student/${esc(s.id)}?format=pdf" type="button">Attendance report PDF</button></div></div>
      <div class="grid2"><div class="panel"><h3>Face verification</h3>
        ${(() => { const a = d.biometric.find((b) => b.status === "ACTIVE"); return a
          ? SA.alert("success", `Face locked to ${s.indexNumber} since ${SA.date(a.createdAt)}. The student cannot change it; only an administrator can reset it. Kept until ${SA.date(a.retentionUntil)}.`)
          : SA.alert("info", "No face enrolled yet. The first face enrolled for this index number will be locked to it."); })()}
        <p class="small muted">Templates are encrypted and never displayed or downloadable.</p>
        ${SA.table([{ label: "Status", html: (b) => SA.badge(b.status) }, { label: "Provider", key: "provider" }, { label: "Consent", get: (b) => b.consentVersion + " · " + SA.date(b.consentGivenAt) }, { label: "Delete by", get: (b) => SA.date(b.retentionUntil) }], d.biometric, "Not enrolled.")}
        <h3>Recent face checks</h3>${SA.table([{ label: "When", get: (f) => SA.dateTime(f.usedAt) }, { label: "Purpose", get: (f) => SA.label(f.purpose) }, { label: "Result", html: (f) => SA.badge(f.passed ? "ok" : "REJECTED").replace("Ok", "Passed") }, { label: "Reason", get: (f) => SA.label(f.failure) }], d.faceAttempts, "None.")}</div>
      <div class="panel"><h3>Devices</h3>${SA.table([{ label: "Device", key: "label" }, { label: "Last used", get: (x) => SA.dateTime(x.lastSeenAt) }, { label: "Passkeys", num: true, key: "passkeys" }, { label: "Status", html: (x) => SA.badge(x.status) },
        { label: "", html: (x) => x.status === "TRUSTED" ? `<button class="btn ghost sm" data-revoke="${esc(x.id)}">Revoke</button>` : "" }], d.devices, "No devices.")}
        <h3>Recovery requests</h3>${SA.table([{ label: "When", get: (r) => SA.date(r.createdAt) }, { label: "Type", get: (r) => SA.label(r.kind) }, { label: "Status", html: (r) => SA.badge(r.status) }], d.recovery, "None.")}</div></div>
      <div class="panel"><h3>Attendance by course</h3>${SA.table([{ label: "Course", get: (c) => (c.courseCode || "") + " " + c.courseTitle }, { label: "Present", num: true, key: "present" }, { label: "Late", num: true, key: "late" },
        { label: "Absent", num: true, key: "absent" }, { label: "Excused", num: true, key: "excused" }, { label: "%", num: true, get: (c) => SA.pct(c.percentage) }], d.stats)}</div>
      <div class="panel"><h3>Security alerts</h3>${SA.table([{ label: "When", get: (a) => SA.dateTime(a.createdAt) }, { label: "Signal", get: (a) => SA.label(a.kind) }, { label: "Severity", html: (a) => SA.badge(a.severity) }, { label: "Status", html: (a) => SA.badge(a.status) }], d.alerts, "No alerts.")}</div>`);
    ST().bindDownloads(v);
    const reload = () => student([id]);
    $("#edit", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Correct register details", intro: "Spelling is kept exactly as you type it. Every change is audited with the previous values.", fields: [
        { name: "title", label: "Title", value: s.title }, { name: "firstName", label: "First name", value: s.firstName, required: true }, { name: "lastName", label: "Last name", value: s.lastName, required: true },
        { name: "otherNames", label: "Other names", value: s.otherNames }, { name: "registerStatus", label: "Register status", type: "select", value: s.registerStatus, options: ["ACTIVE", "UNDER_REVIEW", "WITHDRAWN"].map((x) => ({ value: x, label: SA.label(x) })) },
        { name: "reason", label: "Reason for correction", required: true }], submit: "Save correction" });
      if (r) busy(null, async () => { await api("/api/admin/students/" + id, { method: "PUT", body: r }); reload(); });
    });
    if ($("#approve", v)) $("#approve", v).addEventListener("click", () => approve(id, reload));
    if ($("#suspend", v)) $("#suspend", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Suspend account", intro: "The student is signed out and cannot mark attendance until reactivated.", fields: [{ name: "reason", label: "Justification", required: true }], submit: "Suspend", danger: true });
      if (r) busy(null, async () => { await api(`/api/admin/students/${id}/suspend`, { method: "POST", body: r }); reload(); });
    });
    if ($("#reactivate", v)) $("#reactivate", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Reactivate account", fields: [{ name: "reason", label: "Reason", required: true }], submit: "Reactivate" });
      if (r) busy(null, async () => { await api(`/api/admin/students/${id}/reactivate`, { method: "POST", body: r }); reload(); });
    });
    if ($("#bio", v)) $("#bio", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Reset locked face", intro: "The locked face template is erased. The student must consent and enrol again at their next sign-in, and the new face is then locked. Use only after verifying identity in person or on a data deletion request.", fields: [{ name: "reason", label: "Reason (e.g. data subject request)", required: true }], submit: "Delete template", danger: true });
      if (r) busy(null, async () => { await api(`/api/admin/students/${id}/biometric/delete`, { method: "POST", body: r }); reload(); });
    });
    $$("[data-revoke]", v).forEach((b) => b.addEventListener("click", () => revokeDevice(b.dataset.revoke, reload)));
  }

  async function revokeDevice(id, after) {
    const r = await SA.formDialog({ title: "Revoke device", intro: "Its passkeys stop working and its sessions are signed out.", fields: [{ name: "reason", label: "Reason", required: true }], submit: "Revoke device", danger: true });
    if (r) busy(null, async () => { await api(`/api/admin/devices/${id}/revoke`, { method: "POST", body: r }); after(); });
  }

  /* ---------------------------------------------------------------- lecturers */
  async function lecturers() {
    SA.loading();
    const rows = await api("/api/admin/lecturers");
    const v = SA.view("Lecturers", `<div class="row"><button class="btn" id="add" type="button">Add lecturer</button></div><br>
      ${SA.table([{ label: "Name", get: (l) => [l.title, l.fullName].filter(Boolean).join(" ") }, { label: "Email", get: (l) => l.email || "—" },
        { label: "Courses", get: (l) => (l.courses || []).map((c) => c.code || c.title).join(", ") }, { label: "Sign-in", html: (l) => l.hasCredentials ? (l.mustChangePassword ? SA.badge("PENDING") : SA.badge("ACTIVE")) : '<span class="muted">No credentials</span>' },
        { label: "Status", html: (l) => SA.badge(l.status) }, { label: "Last sign-in", get: (l) => SA.dateTime(l.lastLoginAt) },
        { label: "", html: (l) => `<div class="row"><button class="btn ghost sm" data-edit="${esc(l.id)}">Edit</button><button class="btn ghost sm" data-cred="${esc(l.id)}">Issue password</button>
          <button class="btn ghost sm" data-status="${esc(l.id)}" data-to="${l.status === "ACTIVE" ? "DISABLED" : "ACTIVE"}">${l.status === "ACTIVE" ? "Disable" : "Enable"}</button></div>` }], rows)}`);
    const form = (l = {}) => [{ name: "title", label: "Title", value: l.title }, { name: "fullName", label: "Full name", value: l.fullName, required: true }, { name: "email", label: "Email (sign-in)", type: "email", value: l.email }, { name: "staffId", label: "Staff ID", value: l.staffId }];
    $("#add", v).addEventListener("click", async () => { const r = await SA.formDialog({ title: "Add lecturer", fields: form(), submit: "Add lecturer" }); if (r) busy(null, async () => { await api("/api/admin/lecturers", { method: "POST", body: r }); lecturers(); }); });
    $$("[data-edit]", v).forEach((b) => b.addEventListener("click", async () => { const l = rows.find((x) => x.id === b.dataset.edit); const r = await SA.formDialog({ title: "Edit lecturer", fields: form(l), submit: "Save" }); if (r) busy(null, async () => { await api("/api/admin/lecturers/" + l.id, { method: "PUT", body: r }); lecturers(); }); }));
    $$("[data-cred]", v).forEach((b) => b.addEventListener("click", async () => {
      if (!(await SA.confirm("Issue a temporary password?", "Any existing password stops working and the lecturer's sessions are signed out.", "Issue password"))) return;
      busy(null, async () => { const r = await api(`/api/admin/lecturers/${b.dataset.cred}/credentials`, { method: "POST", body: {} });
        SA.modal(`<h2>Temporary password</h2><p>For <b>${esc(r.email)}</b>. ${esc(r.note)}</p><code class="secret">${esc(r.temporaryPassword)}</code><div class="dlg-actions"><button class="btn" data-close type="button">Done</button></div>`); lecturers(); });
    }));
    $$("[data-status]", v).forEach((b) => b.addEventListener("click", () => busy(b, async () => { await api(`/api/admin/lecturers/${b.dataset.status}/status?status=${b.dataset.to}`, { method: "POST", body: {} }); lecturers(); })));
  }

  /* ---------------------------------------------------------------- courses */
  async function courses() {
    SA.loading();
    const [rows, lecs, groups] = await Promise.all([api("/api/admin/courses"), api("/api/admin/lecturers"), api("/api/admin/groups")]);
    const v = SA.view("Courses", `<div class="row"><button class="btn" id="add" type="button">Add course</button></div><br>
      ${SA.table([{ label: "Code", html: (c) => c.code ? esc(c.code) + (c.altCode ? ` <span class="muted">(${esc(c.altCode)})</span>` : "") : '<span class="badge PENDING">Code pending</span>' },
        { label: "Title", html: (c) => `<a href="#/courses/${esc(c.id)}">${esc(c.title)}</a>` }, { label: "Lecturers", html: (c) => (c.lecturers || []).map((l) => `${esc(l.name)} <button class="btn ghost sm" data-unassign="${esc(c.id)}" data-lec="${esc(l.lecturerId)}" aria-label="Remove ${esc(l.name)}">×</button>`).join("<br>") || '<span class="muted">None</span>' },
        { label: "Enrolled", num: true, key: "enrolled" },
        { label: "", html: (c) => `<div class="row"><button class="btn ghost sm" data-edit="${esc(c.id)}">Edit</button><button class="btn ghost sm" data-assign="${esc(c.id)}">Assign lecturer</button><button class="btn ghost sm" data-enrol="${esc(c.id)}">Enrol class</button></div>` }], rows)}`);
    const form = (c = {}) => [{ name: "code", label: "Course code", value: c.code }, { name: "altCode", label: "Alternative code", value: c.altCode }, { name: "title", label: "Title", value: c.title, required: true }];
    $("#add", v).addEventListener("click", async () => { const r = await SA.formDialog({ title: "Add course", fields: form(), submit: "Add course" }); if (r) busy(null, async () => { await api("/api/admin/courses", { method: "POST", body: r }); courses(); }); });
    $$("[data-edit]", v).forEach((b) => b.addEventListener("click", async () => { const c = rows.find((x) => x.id === b.dataset.edit); const r = await SA.formDialog({ title: "Edit course", fields: form(c), submit: "Save" }); if (r) busy(null, async () => { await api("/api/admin/courses/" + c.id, { method: "PUT", body: r }); courses(); }); }));
    $$("[data-assign]", v).forEach((b) => b.addEventListener("click", async () => { const r = await SA.formDialog({ title: "Assign lecturer", fields: [{ name: "lecturerId", label: "Lecturer", type: "select", options: opts(lecs, "id", (l) => [l.title, l.fullName].filter(Boolean).join(" ")) }], submit: "Assign" }); if (r) busy(null, async () => { await api(`/api/admin/courses/${b.dataset.assign}/assign`, { method: "POST", body: r }); courses(); }); }));
    $$("[data-unassign]", v).forEach((b) => b.addEventListener("click", async () => { if (await SA.confirm("Remove lecturer from course?", "They will lose access to this course.", "Remove", true)) busy(null, async () => { await api(`/api/admin/courses/${b.dataset.unassign}/assign/${b.dataset.lec}`, { method: "DELETE" }); courses(); }); }));
    $$("[data-enrol]", v).forEach((b) => b.addEventListener("click", async () => { const r = await SA.formDialog({ title: "Enrol a whole class", fields: [{ name: "classGroupId", label: "Class", type: "select", options: opts(groups, "id", (g) => `${g.programme} L${g.level} ${g.name}`) }], submit: "Enrol" }); if (r) busy(null, async () => { const x = await api(`/api/admin/courses/${b.dataset.enrol}/enrol-group`, { method: "POST", body: r }); SA.toast(`${x.added} enrolments added`, "success"); courses(); }); }));
  }

  /* ---------------------------------------------------------------- timetable */
  async function timetable() {
    SA.loading();
    const [t, courseRows] = await Promise.all([api("/api/admin/timetable"), api("/api/admin/courses")]);
    const sem = t.semester;
    const v = SA.view("Timetable & calendar", `<div class="panel"><div class="panel-head"><h3>${esc(sem.academicYear)} · ${esc(sem.name)}</h3><button class="btn secondary sm" id="semEdit" type="button">Edit dates</button></div>
        <dl class="kv"><dt>Teaching week 1 starts</dt><dd>${sem.startDate ? esc(SA.date(sem.startDate)) : '<span class="badge PENDING">Not set</span> — venue changes by week can\'t be applied until this is set'}</dd>
        <dt>Semester ends</dt><dd>${esc(sem.endDate ? SA.date(sem.endDate) : "—")}</dd><dt>Teaching weeks</dt><dd>${esc(sem.teachingWeeks)}</dd><dt>Current week</dt><dd>${esc(t.currentWeek ?? "—")}</dd><dt>Timezone</dt><dd>Africa/Accra</dd></dl>
        ${t.weeks.length ? `<p class="small">${t.weeks.map((w) => `Week ${w.weekNumber}: ${SA.label(w.kind)}${w.note ? " (" + esc(w.note) + ")" : ""}`).join(" · ")}</p>` : ""}
        <button class="btn ghost sm" id="weekEdit" type="button">Mark a break / exam week</button></div>
      <div class="panel"><div class="panel-head"><h3>Weekly lectures</h3><button class="btn sm" id="addSlot" type="button">Add lecture</button></div>
      ${SA.table([{ label: "Day", get: (s) => SA.days[s.dayOfWeek] }, { label: "Time", get: (s) => SA.hm(s.startTime) + "–" + SA.hm(s.endTime) }, { label: "Course", get: (s) => (s.courseCode || "") + " " + s.courseTitle },
        { label: "Venue", get: (s) => s.mode === "ONLINE" ? "Online" : s.venue || "TBC" }, { label: "Week changes", get: (s) => t.exceptions.filter((e) => e.timetableId === s.id).map((e) => `W${e.weekNumber}: ${e.cancelled ? "cancelled" : [e.mode && SA.label(e.mode), e.venue].filter(Boolean).join(" ")}`).join("; ") },
        { label: "", html: (s) => `<div class="row"><button class="btn ghost sm" data-edit="${esc(s.id)}">Edit</button><button class="btn ghost sm" data-ex="${esc(s.id)}">Week change</button><button class="btn ghost sm" data-del="${esc(s.id)}">Delete</button></div>` }], t.slots)}</div>
      <div class="panel"><h3>Week changes</h3>${SA.table([{ label: "Week", num: true, key: "weekNumber" }, { label: "Lecture", get: (e) => { const s = t.slots.find((x) => x.id === e.timetableId); return s ? (s.courseCode || s.courseTitle) : ""; } },
        { label: "Change", get: (e) => e.cancelled ? "Cancelled" : [e.mode && SA.label(e.mode), e.venue].filter(Boolean).join(" · ") }, { label: "Note", key: "note" }, { label: "", html: (e) => `<button class="btn ghost sm" data-rmex="${esc(e.id)}">Remove</button>` }], t.exceptions, "No week-specific changes.")}</div>`);
    const venueOpts = [{ value: "", label: "— none —" }].concat(opts(t.venues, "id", "code"));
    const slotForm = (s = {}) => [
      { name: "courseId", label: "Course", type: "select", value: s.courseId, options: opts(courseRows, "id", (c) => (c.code || "") + " " + c.title) },
      { name: "classGroupId", label: "Class", type: "select", value: s.classGroupId, options: opts(t.groups, "id", (g) => `${g.programme} L${g.level} ${g.name}`) },
      { name: "dayOfWeek", label: "Day", type: "select", value: s.dayOfWeek || 1, options: [1, 2, 3, 4, 5, 6, 7].map((d) => ({ value: d, label: SA.days[d] })) },
      { name: "startTime", label: "Start", type: "time", value: SA.hm(s.startTime), required: true }, { name: "endTime", label: "End", type: "time", value: SA.hm(s.endTime), required: true },
      { name: "mode", label: "Mode", type: "select", value: s.mode || "IN_PERSON", options: [{ value: "IN_PERSON", label: "In person" }, { value: "ONLINE", label: "Online" }] },
      { name: "venueId", label: "Venue", type: "select", value: s.venueId || "", options: venueOpts }, { name: "notes", label: "Notes", value: s.notes }];
    const clean = (r) => Object.assign(r, { dayOfWeek: Number(r.dayOfWeek), venueId: r.venueId || null });
    $("#addSlot", v).addEventListener("click", async () => { const r = await SA.formDialog({ title: "Add lecture", fields: slotForm(), submit: "Add" }); if (r) busy(null, async () => { await api("/api/admin/timetable", { method: "POST", body: clean(r) }); timetable(); }); });
    $$("[data-edit]", v).forEach((b) => b.addEventListener("click", async () => { const s = t.slots.find((x) => x.id === b.dataset.edit); const r = await SA.formDialog({ title: "Edit lecture", fields: slotForm(s), submit: "Save" }); if (r) busy(null, async () => { await api("/api/admin/timetable/" + s.id, { method: "PUT", body: clean(r) }); timetable(); }); }));
    $$("[data-del]", v).forEach((b) => b.addEventListener("click", async () => { if (await SA.confirm("Delete this lecture slot?", "Slots with attendance history can't be deleted.", "Delete", true)) busy(null, async () => { await api("/api/admin/timetable/" + b.dataset.del, { method: "DELETE" }); timetable(); }); }));
    $$("[data-ex]", v).forEach((b) => b.addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Change for one teaching week", fields: [{ name: "weekNumber", label: "Teaching week", type: "number", required: true },
        { name: "mode", label: "Mode", type: "select", options: [{ value: "", label: "Unchanged" }, { value: "IN_PERSON", label: "In person" }, { value: "ONLINE", label: "Online" }] },
        { name: "venueId", label: "Venue", type: "select", options: venueOpts }, { name: "cancelled", type: "checkbox", label: "Lecture cancelled this week" }, { name: "note", label: "Note" }], submit: "Save" });
      if (r) busy(null, async () => { await api("/api/admin/timetable/exceptions", { method: "POST", body: { timetableId: b.dataset.ex, weekNumber: Number(r.weekNumber), mode: r.mode || null, venueId: r.venueId || null, cancelled: r.cancelled, note: r.note } }); timetable(); });
    }));
    $$("[data-rmex]", v).forEach((b) => b.addEventListener("click", () => busy(b, async () => { await api("/api/admin/timetable/exceptions/" + b.dataset.rmex, { method: "DELETE" }); timetable(); })));
    $("#semEdit", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Semester dates", intro: "Week 1 start date drives week-specific venue changes and online/campus weeks.", fields: [{ name: "startDate", label: "Teaching week 1 starts (Monday)", type: "date", value: sem.startDate || "" }, { name: "endDate", label: "Semester ends", type: "date", value: sem.endDate || "" }, { name: "teachingWeeks", label: "Teaching weeks", type: "number", value: sem.teachingWeeks }], submit: "Save dates" });
      if (r) busy(null, async () => { await api("/api/admin/semesters/current", { method: "PUT", body: { ...r, teachingWeeks: Number(r.teachingWeeks) } }); timetable(); });
    });
    $("#weekEdit", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Academic week", fields: [{ name: "weekNumber", label: "Week number", type: "number", required: true }, { name: "kind", label: "Type", type: "select", options: ["TEACHING", "BREAK", "EXAM", "HOLIDAY"].map((k) => ({ value: k, label: SA.label(k) })) }, { name: "note", label: "Note" }], submit: "Save" });
      if (r) busy(null, async () => { await api("/api/admin/semesters/current/weeks", { method: "POST", body: { ...r, weekNumber: Number(r.weekNumber) } }); timetable(); });
    });
  }

  /* ---------------------------------------------------------------- venues */
  async function venues() {
    SA.loading();
    const rows = await api("/api/admin/venues");
    const v = SA.view("Venues & proximity", `${SA.alert("info", "Proximity evidence supports, but never proves, presence. Classroom network ranges only work if the server sees campus-internal addresses (on-premise or a known campus NAT address). GPS is approximate indoors. Browsers cannot scan Bluetooth beacons; beacon IDs are stored for a future native companion app.")}
      <div class="row"><button class="btn" id="add" type="button">Add venue</button></div><br>
      ${SA.table([{ label: "Venue", key: "code" }, { label: "Description", key: "description" }, { label: "GPS", get: (x) => x.latitude != null ? `${x.latitude.toFixed(5)}, ${x.longitude.toFixed(5)} (±${x.radiusM} m)` : "Not set" },
        { label: "Classroom network", get: (x) => x.networkCidrs || "Not set" }, { label: "Beacons", num: true, key: "beacons" },
        { label: "", html: (x) => `<div class="row"><button class="btn ghost sm" data-edit="${esc(x.id)}">Edit</button><button class="btn ghost sm" data-beacon="${esc(x.id)}">Add beacon</button></div>` }], rows)}`);
    const form = (x = {}) => [{ name: "code", label: "Code", value: x.code, required: true }, { name: "description", label: "Description", value: x.description },
      { name: "latitude", label: "Latitude", type: "number", step: "any", value: x.latitude ?? "" }, { name: "longitude", label: "Longitude", type: "number", step: "any", value: x.longitude ?? "" },
      { name: "radiusM", label: "Radius (metres)", type: "number", value: x.radiusM || 60 }, { name: "networkCidrs", label: "Classroom network ranges", value: x.networkCidrs || "", hint: "Comma separated, e.g. 10.20.0.0/16" }];
    const clean = (r) => ({ ...r, latitude: r.latitude === "" ? null : Number(r.latitude), longitude: r.longitude === "" ? null : Number(r.longitude), radiusM: Number(r.radiusM) || 60 });
    $("#add", v).addEventListener("click", async () => { const r = await SA.formDialog({ title: "Add venue", fields: form(), submit: "Add" }); if (r) busy(null, async () => { await api("/api/admin/venues", { method: "POST", body: clean(r) }); venues(); }); });
    $$("[data-edit]", v).forEach((b) => b.addEventListener("click", async () => { const x = rows.find((y) => y.id === b.dataset.edit); const r = await SA.formDialog({ title: "Edit " + x.code, fields: form(x).slice(1), submit: "Save" }); if (r) busy(null, async () => { await api("/api/admin/venues/" + x.id, { method: "PUT", body: clean(r) }); venues(); }); }));
    $$("[data-beacon]", v).forEach((b) => b.addEventListener("click", async () => { const r = await SA.formDialog({ title: "Register proximity device", fields: [{ name: "kind", label: "Type", type: "select", options: [{ value: "BLE_BEACON", label: "Bluetooth beacon" }, { value: "WIFI_AP", label: "Wi-Fi access point" }, { value: "LOCAL_NETWORK_NODE", label: "Local network node" }] }, { name: "identifier", label: "Identifier (UUID / BSSID / host)", required: true }, { name: "note", label: "Note" }], submit: "Add" }); if (r) busy(null, async () => { await api(`/api/admin/venues/${b.dataset.beacon}/proximity-devices`, { method: "POST", body: r }); venues(); }); }));
  }

  /* ---------------------------------------------------------------- sessions, analytics, reports */
  async function sessions(_, params) {
    SA.loading();
    const rows = await api("/api/admin/sessions" + (params.status ? "?status=" + encodeURIComponent(params.status) : ""));
    const v = SA.view("Past classes", `<div class="filters"><select id="st" aria-label="Status"><option value="">All sessions</option>${["OPEN", "CLOSED", "CANCELLED"].map((s) => `<option ${params.status === s ? "selected" : ""} value="${s}">${SA.label(s)}</option>`).join("")}</select></div>
      ${SA.table([{ label: "Date", get: (s) => SA.date(s.sessionDate) }, { label: "Opened", get: (s) => SA.time(s.openedAt) }, { label: "Course", get: (s) => (s.code || "") + " " + s.title },
        { label: "Venue", get: (s) => s.mode === "ONLINE" ? "Online" : s.venue || "—" }, { label: "Started by", key: "startedBy" }, { label: "Status", html: (s) => SA.badge(s.status) },
        { label: "Attended", num: true, key: "attended" }, { label: "Review", num: true, key: "pending" }, { label: "", html: (s) => `<div class="row"><a class="btn ghost sm" href="#/${s.status === "OPEN" ? "live" : "sessions"}/${esc(s.id)}">${s.status === "OPEN" ? "Live" : "View"}</a>${s.status === "CLOSED" ? `<button class="btn secondary sm" type="button" data-dl="/api/lecturer/reports/session/${esc(s.id)}?format=pdf">PDF</button>` : ""}</div>` }], rows, "No classes yet.")}`);
    ST().bindDownloads(v);
    $("#st", v).addEventListener("change", (e) => (location.hash = "#/sessions" + (e.target.value ? "?status=" + e.target.value : "")));
  }

  async function analytics(_, params) {
    SA.loading();
    const [a, courseRows] = await Promise.all([api("/api/admin/analytics" + (params.courseId ? "?courseId=" + params.courseId : "")), api("/api/admin/courses")]);
    const total = a.summary.distribution.reduce((x, d) => x + d.count, 0) || 1;
    const p = a.policy;
    const v = SA.view("Analytics", `<div class="filters"><select id="c" aria-label="Course"><option value="">All courses</option>${courseRows.map((c) => `<option value="${esc(c.id)}" ${params.courseId === c.id ? "selected" : ""}>${esc(c.code || c.title)}</option>`).join("")}</select></div>
      <div class="stats"><div class="stat"><b>${esc(SA.pct(a.summary.percentage))}</b><span>Overall attendance</span></div><div class="stat"><b>${a.summary.present}</b><span>Present</span></div>
        <div class="stat"><b>${a.summary.late}</b><span>Late</span></div><div class="stat"><b>${a.summary.absent}</b><span>Absent</span></div><div class="stat"><b>${a.summary.excused}</b><span>Excused</span></div><div class="stat"><b>${a.low.length}</b><span>Below ${p.lowThreshold}%</span></div></div>
      <div class="grid2"><div class="panel"><h3>Weekly trend</h3>${SA.trendChart(a.trend)}</div>
      <div class="panel"><h3>Distribution of students</h3>${SA.bars(a.summary.distribution.map((d) => ({ label: d.label, value: d.count, pct: (d.count * 100) / total })))}</div></div>
      <div class="panel"><h3>By course</h3>${SA.bars(a.byCourse.map((c) => ({ label: c.code || c.title, value: SA.pct(c.percentage), pct: c.percentage })))}</div>
      <div class="grid2"><div class="panel"><h3>Low attendance (below ${p.lowThreshold}%)</h3>${SA.table([{ label: "Student", html: (s) => `${SA.courseRep ? esc(s.name) : `<a href="#/students/${esc(s.studentId)}">${esc(s.name)}</a>`}<div class="small muted">${esc(s.indexNumber)}</div>` }, { label: "Attended", num: true, get: (s) => s.attended + "/" + s.eligible }, { label: "%", num: true, get: (s) => SA.pct(s.percentage) }], a.low, "No students below the threshold.")}</div>
      <div class="panel"><h3>Excellent attendance (${p.excellentThreshold}%+)</h3>${SA.table([{ label: "Student", get: (s) => s.name }, { label: "%", num: true, get: (s) => SA.pct(s.percentage) }], a.excellent.slice(0, 20), "None yet.")}</div></div>
      <p class="small muted">Only closed sessions count. Late ${p.lateCountsAsAttended ? "counts" : "does not count"} as attended; excused sessions are ${p.excusedExcludedFromDenominator ? "excluded from" : "included in"} the denominator; pending reviews count as not attended until approved. ${SA.courseRep ? "" : "Change these in Policies & settings."}</p>`);
    $("#c", v).addEventListener("change", (e) => (location.hash = "#/analytics" + (e.target.value ? "?courseId=" + e.target.value : "")));
  }

  async function reports() {
    SA.loading();
    const [courseRows, lecs, groups] = await Promise.all([api("/api/admin/courses"), SA.courseRep ? [] : api("/api/admin/lecturers"), api("/api/admin/groups")]);
    const v = SA.view("Reports", `<div class="panel"><h3>Attendance records</h3><p class="muted">Every record matching the filters (by course, lecturer, class and date range).</p>
      <form id="rf" class="grid2"><div class="field"><label for="rc">Course</label><select id="rc" name="courseId"><option value="">All</option>${courseRows.map((c) => `<option value="${esc(c.id)}">${esc((c.code || "") + " " + c.title)}</option>`).join("")}</select></div>
      ${SA.courseRep ? "" : `<div class="field"><label for="rl">Lecturer</label><select id="rl" name="lecturerId"><option value="">All</option>${lecs.map((l) => `<option value="${esc(l.id)}">${esc(l.fullName)}</option>`).join("")}</select></div>`}
      <div class="field"><label for="rg">Class</label><select id="rg" name="classGroupId"><option value="">All</option>${groups.map((g) => `<option value="${esc(g.id)}">${esc(g.programme)} L${esc(g.level)} ${esc(g.name)}</option>`).join("")}</select></div>
      <div class="field"><label for="rfrom">From</label><input id="rfrom" type="date" name="from"></div><div class="field"><label for="rto">To</label><input id="rto" type="date" name="to"></div>
      <div class="field"><label for="rfmt">Format</label><select id="rfmt" name="format"><option value="pdf">PDF</option><option value="xlsx">Excel (.xlsx)</option><option value="csv">CSV</option></select></div>
      <div><button class="btn" data-busy="Preparing…">Download</button></div></form></div>
      <div class="panel"><h3>Course summaries (current semester)</h3>${SA.table([{ label: "Course", get: (c) => (c.code || "") + " " + c.title }, { label: "", html: (c) => ST().reportButtons("/api/admin/reports/course/" + c.id, "") }], courseRows)}</div>
      ${SA.courseRep ? `<p class="small muted">For one class, open it in Past classes and download its attendance sheet.</p>` : `<p class="small muted">Individual student reports are on each student's page.</p>`}`);
    $("#rf", v).addEventListener("submit", (e) => { e.preventDefault(); const p = new URLSearchParams(new FormData(e.target)); [...p.keys()].forEach((k) => !p.get(k) && p.delete(k)); busy($("button", e.target), () => SA.download("/api/admin/reports/records?" + p)); });
    ST().bindDownloads(v);
  }

  /* ---------------------------------------------------------------- security */
  async function security(_, params) {
    SA.loading();
    const status = params.status || "OPEN";
    const rows = await api("/api/admin/alerts?status=" + status);
    const v = SA.view("Fraud alerts", `<p class="muted">Signals are evidence for review, not verdicts. A single unreliable signal should never lead to a penalty — confirm in person first.</p>
      <div class="filters"><select id="st" aria-label="Status">${["OPEN", "CONFIRMED", "DISMISSED", "ALL"].map((s) => `<option ${s === status ? "selected" : ""} value="${s}">${SA.label(s)}</option>`).join("")}</select></div>
      ${SA.table([{ label: "When", get: (a) => SA.dateTime(a.createdAt) }, { label: "Signal", get: (a) => SA.label(a.kind) }, { label: "Severity", html: (a) => SA.badge(a.severity) },
        { label: "Student", html: (a) => a.studentId ? `<a href="#/students/${esc(a.studentId)}">${esc(a.indexNumber)}</a>` : "—" }, { label: "Session", get: (a) => a.courseCode ? a.courseCode + " · " + SA.date(a.sessionDate) : "—" },
        { label: "Details", get: (a) => Object.entries(a.detail || {}).map(([k, x]) => `${k}: ${typeof x === "object" ? JSON.stringify(x) : x}`).join("; ").slice(0, 160) },
        { label: "", html: (a) => a.status === "OPEN" ? `<div class="row"><button class="btn ghost sm" data-rev="${esc(a.id)}" data-to="DISMISSED">Dismiss</button><button class="btn ghost sm" data-rev="${esc(a.id)}" data-to="CONFIRMED">Confirm</button></div>` : `${SA.badge(a.status)}<div class="small muted">${esc(a.reviewNote || "")}</div>` }], rows, "No alerts.")}`);
    $("#st", v).addEventListener("change", (e) => (location.hash = "#/security?status=" + e.target.value));
    $$("[data-rev]", v).forEach((b) => b.addEventListener("click", async () => {
      const r = await SA.formDialog({ title: (b.dataset.to === "CONFIRMED" ? "Confirm" : "Dismiss") + " alert", fields: [{ name: "note", label: "Review note", required: true }], submit: SA.label(b.dataset.to) });
      if (r) busy(null, async () => { await api(`/api/admin/alerts/${b.dataset.rev}/review`, { method: "POST", body: { status: b.dataset.to, note: r.note } }); security(_, params); });
    }));
  }

  async function recovery(_, params) {
    SA.loading();
    const status = params.status || "PENDING";
    const rows = await api("/api/admin/recovery?status=" + status);
    const v = SA.view("Recovery requests", `<p class="muted">Approve a new phone only after confirming the student's identity — ideally their student ID card in person. Approval is valid for 48 hours and only for the phone that made the request.</p>
      <div class="filters"><select id="st" aria-label="Status">${["PENDING", "APPROVED", "COMPLETED", "REJECTED", "ALL"].map((s) => `<option ${s === status ? "selected" : ""} value="${s}">${SA.label(s)}</option>`).join("")}</select></div>
      ${SA.table([{ label: "When", get: (r) => SA.dateTime(r.createdAt) }, { label: "Student", html: (r) => `<a href="#/students/${esc(r.studentId)}">${esc(r.name)}</a><div class="small muted">${esc(r.indexNumber)}</div>` },
        { label: "Type", get: (r) => SA.label(r.kind) }, { label: "Reason", get: (r) => r.reason }, { label: "Face check", html: (r) => r.kind === "DEVICE_CHANGE" ? (r.faceVerified ? SA.badge("ok").replace("Ok", "Passed") : SA.badge("REJECTED").replace("Rejected", "Not passed")) : "—" },
        { label: "", html: (r) => r.status === "PENDING" ? `<div class="row"><button class="btn sm success" data-dec="${esc(r.id)}" data-ok="1">Approve</button><button class="btn sm secondary" data-dec="${esc(r.id)}">Reject</button></div>` : `${SA.badge(r.status)}<div class="small muted">${esc(r.reviewNote || "")}</div>` }], rows, "No requests.")}`);
    $("#st", v).addEventListener("change", (e) => (location.hash = "#/recovery?status=" + e.target.value));
    $$("[data-dec]", v).forEach((b) => b.addEventListener("click", async () => {
      const ok = !!b.dataset.ok;
      const r = await SA.formDialog({ title: ok ? "Approve request" : "Reject request", fields: [{ name: "note", label: ok ? "How did you confirm identity?" : "Reason", required: true }], submit: ok ? "Approve" : "Reject", danger: !ok });
      if (r) busy(null, async () => { await api(`/api/admin/recovery/${b.dataset.dec}/decide`, { method: "POST", body: { approve: ok, note: r.note } }); recovery(_, params); });
    }));
  }

  async function devices(_, params) {
    SA.loading();
    const rows = await api("/api/admin/devices" + (params.q ? "?q=" + encodeURIComponent(params.q) : ""));
    const v = SA.view("Devices", `<form class="filters" id="f"><input type="search" name="q" placeholder="Index number or name" value="${esc(params.q || "")}" aria-label="Search"><button class="btn secondary">Search</button></form>
      ${SA.table([{ label: "Owner", html: (d) => d.studentId ? `<a href="#/students/${esc(d.studentId)}">${esc(d.owner)}</a> <span class="muted small">${esc(d.indexNumber)}</span>` : esc(d.owner) },
        { label: "Device", key: "label" }, { label: "Accounts on this browser", num: true, html: (d) => d.accountsOnDevice > 1 ? `<span class="badge HIGH">${d.accountsOnDevice}</span>` : "1" },
        { label: "Passkeys", num: true, key: "passkeys" }, { label: "Last used", get: (d) => SA.dateTime(d.lastSeenAt) }, { label: "Status", html: (d) => SA.badge(d.status) },
        { label: "", html: (d) => d.status === "TRUSTED" ? `<button class="btn ghost sm" data-revoke="${esc(d.id)}">Revoke</button>` : "" }], rows, "No devices.")}`);
    $("#f", v).addEventListener("submit", (e) => { e.preventDefault(); location.hash = "#/devices?q=" + encodeURIComponent(e.target.q.value); });
    $$("[data-revoke]", v).forEach((b) => b.addEventListener("click", () => revokeDevice(b.dataset.revoke, () => devices(_, params))));
  }

  async function audit(_, params) {
    SA.loading();
    const rows = await api("/api/admin/audit" + (params.action ? "?action=" + encodeURIComponent(params.action) : ""));
    const v = SA.view("Audit log", `<p class="muted">Append-only: entries cannot be edited or deleted, even by administrators.</p>
      <form class="filters" id="f"><input type="search" name="action" placeholder="Filter by action, e.g. REGISTER" value="${esc(params.action || "")}" aria-label="Action"><button class="btn secondary">Filter</button></form>
      ${SA.table([{ label: "When", get: (a) => SA.dateTime(a.createdAt) }, { label: "Actor", get: (a) => (a.actor || "System") + (a.actorRole ? " (" + SA.label(a.actorRole) + ")" : "") },
        { label: "Action", get: (a) => SA.label(a.action) }, { label: "Target", get: (a) => (a.entityType || "") + (a.entityId ? " " + String(a.entityId).slice(0, 8) : "") },
        { label: "Details", get: (a) => JSON.stringify(a.detail).slice(0, 180) }, { label: "IP", key: "ip" }], rows, "No entries.")}`);
    $("#f", v).addEventListener("submit", (e) => { e.preventDefault(); location.hash = "#/audit?action=" + encodeURIComponent(e.target.action.value); });
  }

  /* ---------------------------------------------------------------- settings */
  const SETTING_TEXT = {
    "attendance.lateAfterMinutes": "Minutes after attendance opens before a student is marked late",
    "attendance.defaultDurationMinutes": "Default minutes attendance stays open",
    "attendance.otpRotationSeconds": "Seconds each code and QR stays valid",
    "attendance.lateCountsAsAttended": "Late counts as attended in percentages",
    "attendance.excusedExcludedFromDenominator": "Excused sessions are left out of percentages",
    "attendance.lowAttendanceThreshold": "Low attendance threshold (%)",
    "attendance.excellentAttendanceThreshold": "Excellent attendance threshold (%)",
    "attendance.maxOtpFailuresPerSession": "Wrong codes allowed per student per session",
    "security.faceOnLogin": "Require face check at sign-in",
    "security.faceOnAttendance": "Require face check when marking attendance",
    "security.passkeyOnAttendance": "Require device passkey when marking attendance",
    "security.registrationRequiresApproval": "New registrations need administrator approval",
    "security.maxFaceFailuresPerHour": "Failed face checks allowed per hour",
    "face.matchThreshold": "Face match threshold (cosine similarity, higher is stricter)",
    "face.duplicateCheckOnEnrol": "Flag when a new face matches another student",
    "biometric.retentionDays": "Days to keep face templates",
    "proximity.defaultPolicy": "Default classroom check for new sessions",
  };
  async function settings() {
    SA.loading();
    const s = await api("/api/admin/settings");
    const sup = me.role === "SUPER_ADMIN";
    const fe = s.faceEngine;
    const v = SA.view("Policies & settings", `<div class="panel"><h3>Face verification engine</h3><dl class="kv"><dt>Provider</dt><dd>${esc(fe.provider)}</dd><dt>Model</dt><dd>${esc(fe.model)}</dd>
        <dt>Status</dt><dd>${fe.available ? SA.badge("ACTIVE") : SA.badge("DISABLED") + " " + esc(fe.reason || "")}</dd></dl></div>
      <div class="panel"><h3>Policies</h3>${sup ? "" : `<p class="small muted">Security, face and biometric policies can only be changed by a Super Administrator.</p>`}
      ${SA.table([{ label: "Policy", get: ([k]) => SETTING_TEXT[k] || k }, { label: "Value", html: ([k, x]) => `<b>${esc(x.value)}</b>` }, { label: "Changed", get: ([, x]) => SA.dateTime(x.updatedAt) },
        { label: "", html: ([k]) => !sup && /^(security|face|biometric)\./.test(k) ? "" : `<button class="btn ghost sm" data-set="${esc(k)}">Change</button>` }], Object.entries(s.settings))}</div>`);
    $$("[data-set]", v).forEach((b) => b.addEventListener("click", async () => {
      const k = b.dataset.set, cur = s.settings[k], type = (cur.type || "").split(":");
      const field = type[0] === "bool" ? { name: "value", label: SETTING_TEXT[k] || k, type: "select", value: cur.value, options: [{ value: "true", label: "Yes" }, { value: "false", label: "No" }] }
        : type[0] === "enum" ? { name: "value", label: SETTING_TEXT[k] || k, type: "select", value: cur.value, options: type[1].split(",").map((o) => ({ value: o, label: SA.label(o) })) }
        : { name: "value", label: SETTING_TEXT[k] || k, type: "number", step: "any", value: cur.value, required: true, hint: `Allowed ${type[1]} – ${type[2]}` };
      const r = await SA.formDialog({ title: "Change policy", intro: "The change is audited and applies to new activity immediately.", fields: [field], submit: "Save" });
      if (r) busy(null, async () => { await api("/api/admin/settings", { method: "PUT", body: { key: k, value: r.value } }); settings(); });
    }));
  }

  /* ---------------------------------------------------------------- super admin */
  async function structure() {
    SA.loading();
    const s = await api("/api/super/structure");
    const v = SA.view("Institutions & structure", `
      <div class="panel"><div class="panel-head"><h3>Institutions</h3><button class="btn sm" data-new="institutions" type="button">Add</button></div>${SA.table([{ label: "Name", key: "name" }, { label: "Short", key: "shortName" }, { label: "Timezone", key: "timezone" }], s.institutions)}</div>
      <div class="panel"><div class="panel-head"><h3>Departments</h3><button class="btn sm" data-new="departments" type="button">Add</button></div>${SA.table([{ label: "Department", key: "name" }, { label: "Institution", key: "institution" }], s.departments)}</div>
      <div class="panel"><div class="panel-head"><h3>Programmes</h3><button class="btn sm" data-new="programmes" type="button">Add</button></div>${SA.table([{ label: "Programme", key: "name" }, { label: "Award", key: "award" }, { label: "Department", key: "department" }], s.programmes)}</div>
      <div class="panel"><div class="panel-head"><h3>Academic years & semesters</h3><div class="row"><button class="btn sm" data-new="academic-years" type="button">Add year</button><button class="btn sm" data-new="semesters" type="button">Add semester</button></div></div>
        ${SA.table([{ label: "Year", key: "academicYear" }, { label: "Semester", key: "name" }, { label: "Starts", get: (x) => x.startDate ? SA.date(x.startDate) : "Not set" }, { label: "Current", html: (x) => x.isCurrent ? SA.badge("ACTIVE") : `<button class="btn ghost sm" data-current="${esc(x.id)}">Make current</button>` }], s.semesters)}</div>
      <div class="panel"><div class="panel-head"><h3>Classes</h3><button class="btn sm" data-new="groups" type="button">Add class</button></div>${SA.table([{ label: "Programme", key: "programme" }, { label: "Level", num: true, key: "level" }, { label: "Class", key: "name" }, { label: "Semester", key: "semester" }, { label: "Expected", num: true, get: (g) => g.expectedSize ?? "—" }, { label: "Register", html: (g) => SA.badge(g.registerStatus) }], s.groups)}</div>`);
    const forms = {
      institutions: [{ name: "name", label: "Name", required: true }, { name: "shortName", label: "Short name", required: true }, { name: "timezone", label: "Timezone", value: "Africa/Accra" }],
      departments: [{ name: "parentId", label: "Institution", type: "select", options: opts(s.institutions, "id", "name") }, { name: "name", label: "Department", required: true }],
      programmes: [{ name: "parentId", label: "Department", type: "select", options: opts(s.departments, "id", (d) => d.institution + " · " + d.name) }, { name: "name", label: "Programme", required: true }, { name: "award", label: "Award (e.g. Diploma, BSc)", required: true }],
      "academic-years": [{ name: "parentId", label: "Institution", type: "select", options: opts(s.institutions, "id", "name") }, { name: "label", label: "Label (e.g. 2027/2028)", required: true }],
      semesters: [{ name: "academicYearId", label: "Academic year", type: "select", options: opts(s.academicYears, "id", "label") }, { name: "name", label: "Name", required: true }, { name: "startDate", label: "Week 1 starts", type: "date" }, { name: "teachingWeeks", label: "Teaching weeks", type: "number", value: 13 }],
      groups: [{ name: "programmeId", label: "Programme", type: "select", options: opts(s.programmes, "id", "name") }, { name: "semesterId", label: "Semester", type: "select", options: opts(s.semesters, "id", (x) => x.academicYear + " " + x.name) }, { name: "level", label: "Level", type: "number", required: true }, { name: "name", label: "Class name", required: true }, { name: "expectedSize", label: "Expected size", type: "number" }],
    };
    $$("[data-new]", v).forEach((b) => b.addEventListener("click", async () => {
      const k = b.dataset.new;
      const r = await SA.formDialog({ title: "Add", fields: forms[k], submit: "Add" });
      if (!r) return;
      ["teachingWeeks", "level", "expectedSize"].forEach((n) => { if (n in r) r[n] = r[n] === "" ? null : Number(r[n]); });
      busy(null, async () => { await api("/api/super/" + k, { method: "POST", body: r }); structure(); });
    }));
    $$("[data-current]", v).forEach((b) => b.addEventListener("click", async () => { if (await SA.confirm("Make this the current semester?", "Dashboards, timetables and new sessions switch to it. All open sessions must be closed first.", "Make current", true)) busy(null, async () => { await api(`/api/super/semesters/${b.dataset.current}/make-current`, { method: "POST", body: {} }); structure(); }); }));
  }

  async function admins() {
    SA.loading();
    const rows = await api("/api/super/administrators");
    const v = SA.view("Administrators", `<div class="row"><button class="btn" id="add" type="button">Add administrator</button></div><br>
      ${SA.table([{ label: "Name", key: "fullName" }, { label: "Email", key: "email" }, { label: "Role", get: (a) => SA.label(a.role) }, { label: "Status", html: (a) => SA.badge(a.status) }, { label: "Last sign-in", get: (a) => SA.dateTime(a.lastLoginAt) },
        { label: "", html: (a) => a.userId === me.userId ? '<span class="muted small">You</span>' : `<button class="btn ghost sm" data-st="${esc(a.id)}" data-to="${a.status === "ACTIVE" ? "DISABLED" : "ACTIVE"}">${a.status === "ACTIVE" ? "Disable" : "Enable"}</button>` }], rows)}`);
    $("#add", v).addEventListener("click", async () => {
      const r = await SA.formDialog({ title: "Add administrator", fields: [{ name: "fullName", label: "Full name", required: true }, { name: "email", label: "Email", type: "email", required: true }, { name: "role", label: "Role", type: "select", options: [{ value: "ADMIN", label: "Administrator" }, { value: "SUPER_ADMIN", label: "Super Administrator" }] }], submit: "Create account" });
      if (!r) return;
      busy(null, async () => { const x = await api("/api/super/administrators", { method: "POST", body: r });
        SA.modal(`<h2>Account created</h2><p>Temporary password for <b>${esc(x.email)}</b>. ${esc(x.note)}</p><code class="secret">${esc(x.temporaryPassword)}</code><div class="dlg-actions"><button class="btn" data-close type="button">Done</button></div>`); admins(); });
    });
    $$("[data-st]", v).forEach((b) => b.addEventListener("click", () => busy(b, async () => { await api(`/api/super/administrators/${b.dataset.st}/status?status=${b.dataset.to}`, { method: "POST", body: {} }); admins(); })));
  }
})();
