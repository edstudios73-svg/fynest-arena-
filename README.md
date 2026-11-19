# SmartAttend — GCTU Smart Biometric Attendance

Attendance system for Ghana Communication Technology University, piloted with the **Diploma in
Computer Science, Level 100 (First Semester 2026/2027)**. Students mark attendance by scanning a
rotating QR code or typing a 60-second code, then passing a live face check and a passkey check
(fingerprint / Face ID / screen lock) on their registered phone. Every step is verified on the server.

**Stack (as requested: HTML, CSS and Java)**

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.5 (REST API, SSE live updates, scheduled jobs) |
| Database | PostgreSQL 16, Flyway migrations |
| Frontend | Plain HTML + CSS, with small vanilla JavaScript modules (no framework) |
| Passkeys | WebAuthn via webauthn4j (server-side verification) |
| Face verification | OpenCV 4.9 — YuNet detector + SFace recogniser, run on the server |
| Reports | Apache POI (Excel), OpenPDF (PDF), CSV |

> **Why there is some JavaScript:** browsers only expose the camera, passkeys (WebAuthn) and
> live updates through JavaScript APIs, so HTML/CSS alone cannot do them. The JavaScript is kept to
> plain, framework-free files in `src/main/resources/static/js/`; all logic and security live in Java.

---

## Quick start (local)

Requirements: Java 21, Maven 3.9, PostgreSQL 16, `curl`.

```bash
# 1. Database
sudo -u postgres psql -c "CREATE ROLE smartattend LOGIN PASSWORD 'devpass';" \
                      -c "CREATE DATABASE smartattend OWNER smartattend;"

# 2. Face models (≈39 MB, checksum-verified, not committed to git)
./scripts/download-models.sh

# 3. Run (migrations and GCTU seed data apply automatically)
mvn package -DskipTests
DATABASE_PASSWORD=devpass \
SMARTATTEND_SUPERADMIN_EMAIL=you@example.com \
SMARTATTEND_SUPERADMIN_PASSWORD='Choose-A-Strong-Pass-1' \
java -jar target/smartattend-1.0.0.jar
```

Open <http://localhost:8080>. Passkeys and the camera work on `localhost` without HTTPS; on any
other host they **require HTTPS**.

In development, if `SMARTATTEND_BIOMETRIC_KEY` / `SMARTATTEND_HMAC_KEY` are not set, keys are generated
into `./data/dev-keys.properties` (git-ignored) with a warning. Production refuses to start without them
when `SMARTATTEND_ALLOW_DEV_KEYS=false`.

## Pilot setup checklist (administrator)

1. **Sign in** at `/admin-login.html` with the bootstrap Super Administrator and change the password.
2. **Import the register** — *Class register & import*: upload `DIPLOMA IN COMPUTER SCIENCE GROUPS.xlsx`
   (sheet `GROUP A` is detected automatically). Review the preview: invalid rows, duplicate IDs and
   existing records are listed and excluded; nothing is saved until you confirm.
3. **Resolve the 129 vs 134 discrepancy.** The timetable says 129 students; the workbook has 134. The
   system flags this and **will not let students register or lecturers start attendance until the
   register is verified**. Either mark records that should not be active as *Withdrawn* / *Under review*,
   or verify with an explanation acknowledging the difference. Nothing is deleted automatically.
4. **Set the semester start date** — *Timetable & calendar*. Needed for week-specific changes
   (Linear Algebra in D4B in weeks 2, 6, 10; Communication Skills on campus in weeks 4 and 8).
5. **Confirm the course code** for *General Communication Skills I* (not on the timetable; marked "Code pending").
6. **Issue lecturer credentials** — *Lecturers*: set each lecturer's email, then *Issue password*
   (temporary, shown once, must be changed at first sign-in).
7. **Configure venues** (optional) — GPS coordinates and/or classroom network ranges for D3A, D3B, D4A, D4B.
8. **Review policies** — *Policies & settings* (late cut-off, percentage rules, approval of new registrations, face threshold).

Seeded from the supplied timetable (no student data is seeded):

| Day | Course | Code | Time | Venue | Lecturer |
|---|---|---|---|---|---|
| Mon | Digital Electronics | CSNS 141 | 13:00–15:00 | D4A | Gabriel Gidisu |
| Tue | Introduction to Computer Systems | CIIS 115 (CSSD 111) | 11:00–13:00 | D3B | Dr. Amanda Quist-Okronipa |
| Wed | Programming & Problem-Solving | CSSD 101 | 09:00–11:00 | D4A | Rebecca Adwoa Amponsah |
| Wed | Linear Algebra | MATH 105 | 13:00–15:00 | D3A (D4B wk 2, 6, 10) | Dr. Stephen Kwaku Okrah |
| Thu | General Communication Skills I | *pending* | 13:00–15:00 | Online (D4B wk 4, 8) | Mr. Isaac Darko Danquah |
| Fri | Discrete Mathematics | MATH 103 | 09:00–11:00 | D3A | Dr. Adusei-Poku Afful |

## How the flows work

- **Registration:** index number → name from register shown for confirmation (wrong name → correction
  request) → biometric consent → live face enrolment with random head-turn liveness → passkey creation →
  account active (or pending administrator approval, configurable). One account per index number.
- **Sign in:** index number → passkey → live face check (policy) → session.
- **Attendance:** lecturer starts a session → projector screen shows a QR code and 6-digit code that rotate
  every 60 s → student scans/enters it → face check → passkey → presence evidence → the server records
  PRESENT / LATE / PENDING_VERIFICATION in one transaction → receipt with reference number.
- **Close:** codes stop working instantly; enrolled students without a record become ABSENT.
- **New phone:** `/recover.html` → face check → administrator approves after checking identity → the
  student finishes on the *same* phone within 48 h → old passkeys/devices are revoked.
- **No camera / no passkey / no consent:** supervised verification request; lecturers record attendance manually
  (reason required, audited). A biometric failure never locks a student out permanently.

## Tests

```bash
mvn test                      # 50 JUnit tests against a real PostgreSQL (database: smartattend_test)
./scripts/e2e-fresh.sh        # browser end-to-end test (Playwright/Chromium) on a throwaway database
```

- **Integration tests** drive the HTTP API with a cookie/CSRF-aware client and a **real software WebAuthn
  authenticator** (P-256 keys, signed assertions verified by the production code). They cover all 20 scenarios
  in the brief: valid/invalid/duplicate registration, sign-in, wrong-face rejection, liveness spoofs (static photo,
  replayed challenge, wrong sequence), expired/valid OTP, cross-course QR, double and *concurrent* submission,
  closed sessions, lecturer scoping, students unable to edit records, register import and the 129/134 discrepancy,
  report correctness, device recovery approval, CSRF/origin/role enforcement, lockouts.
- Workflow tests use a test-only face engine (a camera can't be scripted). **`FaceEngineRealModelTest` runs the real
  OpenCV models** on public-domain portraits: same person across two photos scores ≈0.81, different people ≈0.10
  (threshold 0.42), and a tilted flat photo fails liveness.
- **E2E** checks every page on phone and desktop sizes for console/CSP errors and horizontal overflow, then: admin
  imports a synthetic 134-row register, sees the discrepancy, verifies it; issues lecturer credentials; lecturer starts a
  live session; a student signs in with a passkey (Chrome virtual authenticator) and marks attendance; the lecturer
  screen updates live; closing marks absentees. Screenshots go to `tests-e2e/screens/`.

## Recommended hosting: Render (one server + permanent disk)

`render.yaml` defines one always-on server with a 1 GB permanent disk. The built-in PostgreSQL stores its data on
that disk, so nothing is lost on restarts or redeploys and every request sees the same data. No external database
service is needed.

1. Sign in at https://dashboard.render.com and connect GitHub (allow access to `edstudios73-svg/fynest-arena-`).
2. **New → Blueprint** → choose the repo → branch `claude/peaceful-johnson-wi1vxl` → **Apply**. Render reads
   `render.yaml`, generates the encryption keys and a one-time admin password, and builds the Docker image (~5 min).
3. Open the service → **Environment** → copy `SMARTATTEND_SUPERADMIN_PASSWORD` (and keep a backup of
   `SMARTATTEND_BIOMETRIC_KEY`).
4. Visit `https://<service>.onrender.com/admin-login.html`, sign in as `admin@smartattend.gctu`, set a new password,
   then **Class register & import** → upload `DIPLOMA IN COMPUTER SCIENCE GROUPS.xlsx` → **Verify register**.

A permanent disk requires a paid Render instance (Starter). Run exactly one instance.

## Live deployment (Vercel)

Live at **https://smartattend-gctu.vercel.app** — Vercel project `smartattend-gctu` (team Mcdan), built from the root
`Dockerfile` with Vercel's Container runtime.

- **Preview mode (current):** no external database is attached, so `SMARTATTEND_EMBEDDED_DB=true` starts a real
  PostgreSQL 16 *inside* the app. Vercel containers have no permanent disk, so **data resets when Vercel restarts the
  app**; a banner says so on every page. The official class list is re-imported automatically after each restart from
  the encrypted `SMARTATTEND_SEED_REGISTER` environment variable (never stored in git) and left unverified.
- **Permanent data:** attach any PostgreSQL (e.g. Vercel Storage → Neon) so the project gets `DATABASE_URL`
  (`postgres://…` URLs are accepted directly), then redeploy. Preview mode switches off automatically.
- Keep a copy of `SMARTATTEND_BIOMETRIC_KEY` from the Vercel environment variables: without it, stored faces can't be read.

## Deployment

The app is a single Java service plus PostgreSQL. **Vercel is not a good fit** (it does not run long-lived Java
processes with native OpenCV libraries and SSE). Recommended:

- **App:** any container host — Render, Railway, Fly.io, Google Cloud Run (min instances 1), Azure App Service,
  or a university VM — using the included `Dockerfile` (downloads face models at build time).
- **Database:** managed PostgreSQL. **Supabase works as the Postgres database** (use its connection string with
  `sslmode=require`); Supabase Auth/Realtime are not used — auth and live updates are handled by the Java service.
- **TLS:** terminate HTTPS at the platform/proxy and set `FORWARD_HEADERS_STRATEGY=framework`.

```bash
docker build -t smartattend .
docker run --env-file .env -p 8080:8080 smartattend
```

Required variables are listed in [`.env.example`](.env.example). Generate keys with `openssl rand -base64 32`.
**Back up `SMARTATTEND_BIOMETRIC_KEY`**: without it, stored face templates cannot be decrypted.

Run a **single instance** for the pilot: the in-memory rate limiter, SSE subscribers and the plaintext of the
current OTP (shown on the lecturer screen; only its HMAC is stored) are per-instance. For multiple instances,
add sticky sessions or move these to Redis.

More detail: [docs/SECURITY.md](docs/SECURITY.md) (security model, encryption boundaries, biometric and proximity
limitations) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Not supplied / still to do before the pilot

- The **Excel register** and **timetable image** were not in the repository. The timetable was configured from the
  details in the brief; the register must be imported by an administrator (the import is tested with synthetic data).
- The **GCTU logo** was not supplied, so the university is named in text only. Do not substitute an unofficial logo.
- The **face engine** uses evaluated open models with active (challenge–response) liveness. It is not a certified
  presentation-attack-detection product; for higher assurance, plug a certified provider (e.g. AWS Rekognition Face
  Liveness, Azure AI Face, iProov, FaceTec) into the `FaceEngine` interface — this needs a paid account.
- **Bluetooth beacons** can be registered per venue but cannot be scanned from a web browser; that needs a native
  companion app.
- Have the university's data protection officer review the privacy notice against Act 843 and institutional policy.
