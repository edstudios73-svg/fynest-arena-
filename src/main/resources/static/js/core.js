/* SmartAttend shared client utilities. All dynamic text is escaped with esc() before insertion. */
(function () {
  "use strict";
  const SA = (window.SA = {});
  const TZ = "Africa/Accra";
  let csrf = null;

  SA.esc = (v) => String(v == null ? "" : v).replace(/[&<>"'`]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;", "`": "&#96;" }[c]));
  SA.$ = (sel, root = document) => root.querySelector(sel);
  SA.$$ = (sel, root = document) => Array.from(root.querySelectorAll(sel));
  SA.setCsrf = (t) => { csrf = t; };

  class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
  }
  SA.ApiError = ApiError;

  /** JSON / multipart API call. Throws ApiError with a user-presentable message. */
  SA.api = async function (path, { method = "GET", body, form, raw } = {}) {
    const headers = { Accept: "application/json" };
    if (method !== "GET" && csrf) headers["X-CSRF-Token"] = csrf;
    let payload;
    if (form) payload = form;
    else if (body !== undefined) { headers["Content-Type"] = "application/json"; payload = typeof body === "string" ? body : JSON.stringify(body); }
    let res;
    try {
      res = await fetch(path, { method, headers, body: payload, credentials: "same-origin" });
    } catch (e) {
      throw new ApiError(0, "NETWORK", "No connection. Check your internet and try again.");
    }
    if (raw) {
      if (!res.ok) throw new ApiError(res.status, "DOWNLOAD", "Download failed.");
      return res;
    }
    let data = null;
    const ct = res.headers.get("content-type") || "";
    if (ct.includes("json")) data = await res.json().catch(() => null);
    if (!res.ok) {
      const err = (data && data.error) || {};
      if (res.status === 401 && SA.onUnauthenticated && !path.startsWith("/api/auth/")) SA.onUnauthenticated();
      throw new ApiError(res.status, err.code || "HTTP_" + res.status, err.message || "Request failed (" + res.status + ").");
    }
    return data;
  };

  /** Load the current session; sets CSRF. Returns null if not signed in. */
  SA.session = async function () {
    const s = await SA.api("/api/auth/session");
    if (s && s.authenticated) { csrf = s.csrfToken; return s; }
    return null;
  };

  SA.logout = async function (to) {
    try { await SA.api("/api/auth/logout", { method: "POST", body: {} }); } catch (e) { /* ignore */ }
    location.href = to || "/";
  };

  /* ---------- formatting (always Africa/Accra) ---------- */
  const fmt = (opts) => new Intl.DateTimeFormat("en-GB", Object.assign({ timeZone: TZ }, opts));
  SA.date = (v) => (v ? fmt({ weekday: "short", day: "numeric", month: "short", year: "numeric" }).format(new Date(v.length === 10 ? v + "T12:00:00Z" : v)) : "—");
  SA.time = (v) => (v ? fmt({ hour: "2-digit", minute: "2-digit" }).format(new Date(v)) : "—");
  SA.dateTime = (v) => (v ? fmt({ day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }).format(new Date(v)) : "—");
  SA.hm = (t) => (t ? String(t).slice(0, 5) : "");
  SA.pct = (p) => (p == null ? "—" : Number(p).toFixed(p % 1 === 0 ? 0 : 1) + "%");
  SA.days = ["", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];
  SA.label = (s) => (s === "ADMIN" ? "Course rep" : s ? String(s).replace(/_/g, " ").toLowerCase().replace(/^./, (c) => c.toUpperCase()) : "");
  SA.badge = (s) => `<span class="badge ${SA.esc(s)}">${SA.esc(SA.label(s))}</span>`;
  SA.courseName = (c) => (c.code || c.courseCode ? SA.esc(c.code || c.courseCode) + " · " : "") + SA.esc(c.title || c.courseTitle);
  SA.initials = (name) => String(name || "?").split(/\s+/).filter(Boolean).slice(0, 2).map((x) => x[0]).join("").toUpperCase();

  /* ---------- feedback ---------- */
  SA.toast = function (msg, kind = "") {
    let box = SA.$(".toasts");
    if (!box) { box = document.createElement("div"); box.className = "toasts"; box.setAttribute("role", "status"); box.setAttribute("aria-live", "polite"); document.body.appendChild(box); }
    const t = document.createElement("div");
    t.className = "toast " + kind;
    t.textContent = msg;
    box.appendChild(t);
    setTimeout(() => t.remove(), kind === "error" ? 6000 : 3500);
  };
  SA.alert = (kind, msg) => `<div class="alert ${kind}" role="${kind === "error" ? "alert" : "status"}">${SA.esc(msg)}</div>`;

  /** Run an async action with a busy button; shows errors as toasts (or in `errorBox`). */
  SA.busy = async function (btn, fn, errorBox) {
    const label = btn ? btn.innerHTML : null;
    if (btn) { btn.disabled = true; btn.innerHTML = `<span class="spin" aria-hidden="true"></span><span>${btn.dataset.busy || "Working…"}</span>`; }
    if (errorBox) errorBox.innerHTML = "";
    try { return await fn(); }
    catch (e) {
      if (errorBox) errorBox.innerHTML = SA.alert("error", e.message);
      else SA.toast(e.message, "error");
      return undefined;
    } finally {
      if (btn && btn.isConnected) { btn.disabled = false; btn.innerHTML = label; }
    }
  };

  /** Modal dialog. `html` is trusted template (callers escape data). Returns {dlg, close}. */
  SA.modal = function (html) {
    const dlg = document.createElement("dialog");
    dlg.innerHTML = `<div class="dlg">${html}</div>`;
    document.body.appendChild(dlg);
    dlg.addEventListener("close", () => dlg.remove());
    dlg.addEventListener("click", (e) => { if (e.target.matches("[data-close]")) dlg.close(); });
    dlg.showModal();
    return { dlg, close: () => dlg.close() };
  };

  /** Promise-based form dialog. fields: [{name,label,type,options,value,required,hint}] */
  SA.formDialog = function ({ title, intro, fields, submit = "Save", danger }) {
    return new Promise((resolve) => {
      const inputs = fields.map((f) => {
        const id = "f_" + f.name;
        const req = f.required ? "required" : "";
        let ctl;
        if (f.type === "select") ctl = `<select id="${id}" name="${f.name}" ${req}>${f.options.map((o) => `<option value="${SA.esc(o.value)}" ${String(o.value) === String(f.value ?? "") ? "selected" : ""}>${SA.esc(o.label)}</option>`).join("")}</select>`;
        else if (f.type === "textarea") ctl = `<textarea id="${id}" name="${f.name}" ${req}>${SA.esc(f.value || "")}</textarea>`;
        else if (f.type === "checkbox") return `<div class="field"><label class="check"><input type="checkbox" name="${f.name}" ${f.value ? "checked" : ""}> <span>${SA.esc(f.label)}</span></label></div>`;
        else ctl = `<input id="${id}" name="${f.name}" type="${f.type || "text"}" value="${SA.esc(f.value ?? "")}" ${req} ${f.step ? `step="${f.step}"` : ""}>`;
        return `<div class="field"><label for="${id}">${SA.esc(f.label)}</label>${ctl}${f.hint ? `<div class="hint">${SA.esc(f.hint)}</div>` : ""}</div>`;
      }).join("");
      const m = SA.modal(`<h2>${SA.esc(title)}</h2>${intro ? `<p class="muted">${SA.esc(intro)}</p>` : ""}<form method="dialog" novalidate>${inputs}<div class="err"></div>
        <div class="dlg-actions"><button type="button" class="btn secondary" data-close>Cancel</button><button class="btn ${danger ? "danger" : ""}" type="submit">${SA.esc(submit)}</button></div></form>`);
      let done = false;
      const form = SA.$("form", m.dlg);
      form.addEventListener("submit", (e) => {
        e.preventDefault();
        const out = {};
        fields.forEach((f) => { const el = form.elements[f.name]; out[f.name] = f.type === "checkbox" ? el.checked : el.value.trim(); });
        const missing = fields.find((f) => f.required && !out[f.name]);
        if (missing) { SA.$(".err", form).innerHTML = SA.alert("error", missing.label + " is required."); return; }
        done = true; m.close(); resolve(out);
      });
      m.dlg.addEventListener("close", () => { if (!done) resolve(null); });
    });
  };

  SA.confirm = async (title, text, submit = "Confirm", danger = false) =>
    (await SA.formDialog({ title, intro: text, fields: [], submit, danger })) !== null;

  /** Apply data-w="NN" widths to meter bars (CSP forbids inline style attributes). */
  SA.applyWidths = function (root = document) {
    SA.$$("[data-w]", root).forEach((el) => { el.style.width = Math.max(0, Math.min(100, Number(el.dataset.w) || 0)) + "%"; });
  };
  SA.meter = (p, low = 75, good = 90) => `<div class="meter ${p == null ? "" : p < low ? "low" : p >= good ? "good" : ""}"><i data-w="${p == null ? 0 : p}"></i></div>`;

  SA.ring = function (p, label = "attendance") {
    const r = 50, c = 2 * Math.PI * r, v = p == null ? 0 : Math.max(0, Math.min(100, p));
    const color = p == null ? "#CBD3E1" : p < 75 ? "#C2362B" : p >= 90 ? "#14804A" : "#0F2A55";
    return `<div class="ring" role="img" aria-label="${SA.esc(SA.pct(p) + " " + label)}"><svg viewBox="0 0 118 118"><circle cx="59" cy="59" r="${r}" fill="none" stroke="#E2E7EF" stroke-width="12"/>
      <circle cx="59" cy="59" r="${r}" fill="none" stroke="${color}" stroke-width="12" stroke-linecap="round" stroke-dasharray="${(c * v) / 100} ${c}"/></svg>
      <div class="pct"><div>${SA.esc(SA.pct(p))}<small>${SA.esc(label)}</small></div></div></div>`;
  };

  /** Weekly trend line chart (SVG). rows: [{weekStart, percentage}] */
  SA.trendChart = function (rows) {
    const pts = (rows || []).filter((r) => r.percentage != null);
    if (!pts.length) return `<div class="empty">No closed sessions yet. Trends appear after the first attendance session is closed.</div>`;
    const W = 600, H = 200, L = 34, B = 26, T = 10, R = 10;
    const x = (i) => L + (pts.length === 1 ? (W - L - R) / 2 : (i * (W - L - R)) / (pts.length - 1));
    const y = (v) => T + ((100 - v) * (H - T - B)) / 100;
    const grid = [0, 25, 50, 75, 100].map((g) => `<line x1="${L}" x2="${W - R}" y1="${y(g)}" y2="${y(g)}" stroke="#E2E7EF"/><text x="${L - 6}" y="${y(g) + 4}" text-anchor="end">${g}</text>`).join("");
    const path = pts.map((p, i) => `${i ? "L" : "M"}${x(i).toFixed(1)},${y(p.percentage).toFixed(1)}`).join(" ");
    const dots = pts.map((p, i) => `<circle cx="${x(i)}" cy="${y(p.percentage)}" r="4" fill="#0F2A55"><title>${SA.esc(p.weekStart)}: ${SA.esc(p.percentage)}%</title></circle>
      <text x="${x(i)}" y="${H - 6}" text-anchor="middle">${SA.esc(new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short" }).format(new Date(p.weekStart + "T12:00:00Z")))}</text>`).join("");
    return `<svg class="chart" viewBox="0 0 ${W} ${H}" role="img" aria-label="Weekly attendance trend">${grid}<path d="${path}" fill="none" stroke="#2F6BFF" stroke-width="2.5"/>${dots}</svg>`;
  };

  /** Horizontal bars. items: [{label, value, pct}] */
  SA.bars = (items) => `<div class="bars">${items.map((i) => `<div class="bar-row"><span>${SA.esc(i.label)}</span>${SA.meter(i.pct)}<b class="num">${SA.esc(i.value)}</b></div>`).join("")}</div>`;

  SA.table = function (cols, rows, emptyText) {
    if (!rows.length) return `<div class="table-wrap"><div class="empty">${SA.esc(emptyText || "Nothing here yet.")}</div></div>`;
    return `<div class="table-wrap"><table><thead><tr>${cols.map((c) => `<th class="${c.num ? "num" : ""}">${SA.esc(c.label)}</th>`).join("")}</tr></thead>
      <tbody>${rows.map((r) => `<tr>${cols.map((c) => `<td class="${c.num ? "num" : ""}">${c.html ? c.html(r) : SA.esc(c.get ? c.get(r) : r[c.key])}</td>`).join("")}</tr>`).join("")}</tbody></table></div>`;
  };

  /** Download a server-generated file (keeps cookies / auth). */
  SA.download = async function (url) {
    const res = await SA.api(url, { raw: true });
    const blob = await res.blob();
    const cd = res.headers.get("content-disposition") || "";
    const m = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(cd);
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = m ? decodeURIComponent(m[1]) : "report";
    document.body.appendChild(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 5000);
  };

  /* ---------- tiny hash router ---------- */
  SA.router = function (routes, onNav) {
    async function go() {
      const hash = location.hash.replace(/^#/, "") || "/";
      const [path, qs] = hash.split("?");
      const params = Object.fromEntries(new URLSearchParams(qs || ""));
      for (const r of routes) {
        const m = r.re.exec(path);
        if (m) { if (onNav) onNav(r, path); await r.fn(m.slice(1), params); return; }
      }
      location.hash = "#/";
    }
    window.addEventListener("hashchange", go);
    go();
    return go;
  };
  SA.route = (pattern, fn, extra = {}) => Object.assign({ re: new RegExp("^" + pattern.replace(/:(\w+)/g, "([^/]+)") + "$"), fn }, extra);

  /* ---------- icons (inline SVG, stroke-based) ---------- */
  const I = (d) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${d}</svg>`;
  SA.icon = {
    home: I('<path d="M3 11l9-7 9 7"/><path d="M5 10v10h14V10"/>'),
    calendar: I('<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>'),
    scan: I('<path d="M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M7 12h10"/>'),
    list: I('<path d="M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01"/>'),
    user: I('<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>'),
    bell: I('<path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9"/><path d="M10.3 21a1.94 1.94 0 0 0 3.4 0"/>'),
    book: I('<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5z"/><path d="M4 19.5V21h16"/>'),
    play: I('<circle cx="12" cy="12" r="9"/><path d="M10 8l6 4-6 4z"/>'),
    chart: I('<path d="M3 3v18h18"/><path d="M7 15l4-4 3 3 5-6"/>'),
    shield: I('<path d="M12 3l8 3v6c0 5-3.5 8.5-8 9-4.5-.5-8-4-8-9V6z"/><path d="M9 12l2 2 4-4"/>'),
    users: I('<circle cx="9" cy="8" r="4"/><path d="M2 21c1-3.5 3.8-5.5 7-5.5s6 2 7 5.5"/><path d="M16 4a4 4 0 0 1 0 8M22 21c-.6-2.4-2-4-4-5"/>'),
    upload: I('<path d="M12 16V4M7 9l5-5 5 5"/><path d="M4 16v4h16v-4"/>'),
    settings: I('<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>'),
    alert: I('<path d="M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9v4M12 17h.01"/>'),
    phone: I('<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M11 18h2"/>'),
    file: I('<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>'),
    clock: I('<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>'),
    logout: I('<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9"/>'),
    check: I('<path d="M5 12l5 5L20 7"/>'),
    building: I('<rect x="4" y="3" width="16" height="18" rx="1"/><path d="M9 7h1M14 7h1M9 11h1M14 11h1M9 15h1M14 15h1M10 21v-3h4v3"/>'),
    log: I('<path d="M4 4h16v16H4z"/><path d="M8 8h8M8 12h8M8 16h5"/>'),
    key: I('<circle cx="8" cy="15" r="4"/><path d="M10.8 12.2L20 3M17 6l3 3"/>'),
  };

  /** Render the authenticated app shell (sidebar + topbar + mobile tab bar). */
  SA.shell = function ({ me, nav, tabs, home, cls }) {
    document.body.className = cls || "";
    const navHtml = nav.map((n) => n.group ? `<div class="group">${SA.esc(n.group)}</div>` : `<a href="${n.href}" data-key="${n.key}">${SA.icon[n.icon] || ""}<span>${SA.esc(n.label)}</span></a>`).join("");
    const tabHtml = (tabs || []).map((t) => `<a href="${t.href}" data-key="${t.key}" class="${t.primary ? "primary" : ""}"><span class="ic">${SA.icon[t.icon]}</span><span>${SA.esc(t.label)}</span></a>`).join("");
    document.body.innerHTML = `<div class="app">
      <aside class="sidebar"><a class="brand" href="${home}"><img src="/img/logo.svg" alt=""><span>SmartAttend<span class="inst">Ghana Communication Technology University</span></span></a>
        <nav aria-label="Main">${navHtml}</nav>
        <div class="me"><div class="row"><div class="avatar" aria-hidden="true">${SA.esc(SA.initials(me.name))}</div><div><b>${SA.esc(me.name || "")}</b><div class="small muted">${SA.esc(SA.label(me.role))}</div></div></div>
        <button class="btn ghost sm block" id="logoutBtn" type="button">${SA.icon.logout} Sign out</button></div></aside>
      <div class="content"><header class="topbar"><h1 id="pageTitle">SmartAttend</h1><div class="actions">
        <a class="icon-btn" href="#/notifications" aria-label="Notifications">${SA.icon.bell}<span class="dot hidden" id="notifDot"></span></a>
        <button class="icon-btn" id="logoutTop" type="button" aria-label="Sign out">${SA.icon.logout}</button></div></header>
        <main class="view" id="view" tabindex="-1"></main></div>
      ${tabHtml ? `<nav class="tabbar" aria-label="Main">${tabHtml}</nav>` : ""}</div>`;
    SA.$("#logoutBtn").addEventListener("click", () => SA.logout());
    SA.$("#logoutTop").addEventListener("click", () => SA.logout());
    SA.setUnread(me.unreadNotifications);
  };

  SA.setUnread = function (n) {
    const d = SA.$("#notifDot");
    if (!d) return;
    d.textContent = n > 9 ? "9+" : String(n || "");
    d.classList.toggle("hidden", !n);
  };

  SA.markNav = function (key) {
    SA.$$(".sidebar nav a, .tabbar a").forEach((a) => a.classList.toggle("active", a.dataset.key === key));
  };

  SA.view = function (title, html) {
    const v = SA.$("#view");
    SA.$("#pageTitle").textContent = title;
    document.title = title + " · SmartAttend";
    v.innerHTML = html;
    SA.applyWidths(v);
    return v;
  };

  SA.loading = () => { const v = SA.$("#view"); if (v) v.innerHTML = `<div class="loading">Loading…</div>`; };

  SA.notificationsView = async function () {
    SA.loading();
    const list = await SA.api("/api/notifications");
    SA.view("Notifications", `<div class="panel-head"><h2>Notifications</h2><button class="btn secondary sm" id="markRead">Mark all as read</button></div>
      ${list.length ? `<div class="panel"><ul class="feed">${list.map((n) => `<li><div><b>${SA.esc(n.title)}</b><div class="small muted">${SA.esc(n.body)}</div></div>
        <div class="small muted">${SA.esc(SA.dateTime(n.createdAt))}${n.readAt ? "" : ' <span class="badge OPEN">New</span>'}</div></li>`).join("")}</ul></div>`
        : `<div class="panel empty">You're all caught up. Attendance updates and account messages will appear here.</div>`}`);
    SA.$("#markRead").addEventListener("click", async () => { await SA.api("/api/notifications/read", { method: "POST", body: {} }); SA.setUnread(0); SA.notificationsView(); });
  };

  /** Preview-mode banner: tells everyone data is temporary when no permanent database is attached. */
  SA.previewBanner = async function () {
    try {
      const st = await fetch("/api/public/status", { credentials: "same-origin" }).then((r) => r.json());
      if (!st.previewMode || document.getElementById("previewBanner")) return;
      const b = document.createElement("div");
      b.id = "previewBanner";
      b.className = "preview-banner";
      b.setAttribute("role", "note");
      b.textContent = "Preview mode: this demo runs on a temporary built-in database. Registrations and attendance are reset whenever the server restarts.";
      document.body.prepend(b);
    } catch (e) { /* offline */ }
  };
  const _shell = SA.shell;
  SA.shell = function (o) { _shell(o); SA.previewBanner(); };
  document.addEventListener("DOMContentLoaded", () => SA.previewBanner());

  /** Mobile menu toggle on public pages + service worker registration. */
  document.addEventListener("DOMContentLoaded", () => {
    const t = SA.$(".menu-toggle"), n = SA.$(".site-nav");
    if (t && n) t.addEventListener("click", () => { const open = n.classList.toggle("open"); t.setAttribute("aria-expanded", String(open)); });
  });
  if ("serviceWorker" in navigator && location.protocol !== "file:") {
    window.addEventListener("load", () => navigator.serviceWorker.register("/sw.js").catch(() => {}));
  }
})();
