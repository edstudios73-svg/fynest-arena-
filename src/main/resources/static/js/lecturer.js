/* Lecturer app. The server only returns courses assigned to the signed-in lecturer. */
(function () {
  "use strict";
  SA.onUnauthenticated = () => { location.href = "/lecturer-login.html"; };
  document.addEventListener("DOMContentLoaded", async () => {
    const me = await SA.session().catch(() => null);
    if (!me) return SA.onUnauthenticated();
    if (me.role === "STUDENT") { location.href = "/student.html"; return; }
    if (me.mustChangePassword) { location.href = "/lecturer-login.html"; return; }
    const ST = SA.staff;
    SA.shell({
      me, home: "#/", cls: "staff",
      nav: [
        { key: "home", href: "#/", label: "Overview", icon: "home" },
        { key: "courses", href: "#/courses", label: "My courses", icon: "book" },
        { key: "corrections", href: "#/corrections", label: "Correction requests", icon: "file" },
        { key: "alerts", href: "#/alerts", label: "Suspicious activity", icon: "alert" },
        { key: "notifications", href: "#/notifications", label: "Notifications", icon: "bell" },
        { key: "account", href: "#/account", label: "My account", icon: "user" },
      ].concat(me.role !== "LECTURER" ? [{ key: "admin", href: "/admin.html", label: "Administration", icon: "settings" }] : []),
      tabs: [
        { key: "home", href: "#/", label: "Overview", icon: "home" },
        { key: "courses", href: "#/courses", label: "Courses", icon: "book" },
        { key: "corrections", href: "#/corrections", label: "Requests", icon: "file" },
        { key: "account", href: "#/account", label: "Account", icon: "user" },
      ],
    });
    SA.router([
      SA.route("/", ST.overview, { key: "home" }),
      SA.route("/courses", ST.overview, { key: "courses" }),
      SA.route("/courses/:id", ST.course, { key: "courses" }),
      SA.route("/live/:id", ST.live, { key: "courses" }),
      SA.route("/sessions/:id", ST.session, { key: "courses" }),
      SA.route("/corrections", ST.corrections, { key: "corrections" }),
      SA.route("/alerts", ST.lecturerAlerts, { key: "alerts" }),
      SA.route("/notifications", SA.notificationsView, { key: "notifications" }),
      SA.route("/account", ST.account, { key: "account" }),
    ], (r) => { SA.markNav(r.key); ST.cleanup(); });
  });
})();
