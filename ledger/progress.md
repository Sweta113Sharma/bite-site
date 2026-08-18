# BiteSite Development Ledger

> Tracks every meaningful session — which model, which tool, what changed, and why.

---

## Session 001

| Field | Value |
|---|---|
| **Date/Time** | 2026-08-14 ~10:44 IST |
| **Model** | Claude Opus 4.6 (Thinking) via Gemini Antigravity IDE |
| **Tool** | Antigravity IDE (Gemini Code Agent) |
| **Domain** | `bitesite.in` (acquired) |
| **Operator** | Yash |

### Context

Starting a major architectural refactor of BiteSite — the existing monolith serves all roles (SUPER_ADMIN, TECH_MANAGER, CANTEEN_STAFF, STUDENT) on a single deployment at `localhost:8080`. The goal is to evolve toward a **three-portal** architecture mirroring Yash's SpeedoExpress RBAC pattern:

| Portal | Subdomain | Roles allowed |
|---|---|---|
| **App** (Student Ordering) | `app.bitesite.in` | `STUDENT` |
| **Outlet** (Canteen Ops) | `outlet.bitesite.in` | `CANTEEN_STAFF` |
| **Admin** (Back-office) | `admin.bitesite.in` | `SUPER_ADMIN`, `TECH_MANAGER`, `SALES`, `SUPPORT` (feature-gated by role) |

### Key design decisions discussed

1. **Multi-role users**: A single email/account can hold multiple roles (today: one `role` VARCHAR column → refactor to a `user_roles` join table). Users log in once, see a role-switcher for their eligible roles, and are only allowed into portals that match their current active role.

2. **SpeedoExpress RBAC principles to adopt**:
   - *Entitlement vs. view-mode*: `roles[]` = what you ARE, `active_role` = what you're CURRENTLY acting as.
   - *Authorize on the GRANT, not the view-mode*: Staff privileges key off the durable grant, not the transient active_role.
   - *Fail closed*: Unknown/missing role → deny.

3. **New staff sub-roles for admin portal**: `SALES`, `SUPPORT` (in addition to existing `SUPER_ADMIN`, `TECH_MANAGER`) — feature-gated within `/admin/**` by scoped tiers.

4. **Split deployment**: via `APP_TARGET` env var + middleware/interceptor allowlist — one Spring Boot build, three deployments, each only serves routes for its portal's roles.

### What was done this session

- [x] Full codebase audit: schema, models, security config, controllers, DAOs, services, templates
- [x] Created `/ledger/progress.md` (this file)
- [ ] Implementation plan created → awaiting user approval

### Files touched

| File | Action |
|---|---|
| `ledger/progress.md` | Created (this file) |

---

## Session 002

| Field | Value |
|---|---|
| **Date/Time** | 2026-08-14 ~12:30–15:00 IST |
| **Model** | Claude Sonnet 5 |
| **Tool** | Claude Code CLI |
| **Domain** | `bitesite.in` (acquired) |
| **Operator** | Yash |

### Context

Picked up mid-flight: the three-portal + multi-role RBAC design from Session 001 was
already substantially *implemented* in the working tree by the time this session started
(Role/PortalTarget/StaffScope/PortalResolver/PortalGateFilter/PortalGuard, `user_roles` +
`active_role` + `role_audit` via V7/V8 migrations, RoleSwitchController, updated
SecurityConfig/AppUserPrincipal/RoleBasedAuthenticationSuccessHandler) — apparently
continued by the Antigravity session past what Session 001's log captured. This session's
job was: verify that implementation was actually sound, reconcile it against Yash's direct
answers (below), fill the gaps, and fix what didn't work.

**Correction to Session 001's plan**: Yash's direct answer on `SALES`/`SUPPORT` was *"it
was an example, only keep the roles which are needed for our app to function"* — i.e. no
new roles beyond the original four (`SUPER_ADMIN`, `TECH_MANAGER`, `CANTEEN_STAFF`, `USER`).
Both were removed from the enum, `PortalTarget`, `StaffScope`, `SecurityConfig`,
`RoleLandingPages`, and the V7/V8 migrations (safe to edit in place — uncommitted, never
applied outside this machine). `OnboardingController`'s `SALES_SCOPE` calls moved to
`FULL_ADMIN`, matching the original decision that the sales/onboarding pipeline is
SUPER_ADMIN-only.

### Real bugs found and fixed this session

1. **Login didn't reconcile `active_role` against the portal being logged into.**
   `active_role` was whatever was last persisted — possibly from a session on a *different*
   portal — so a multi-role user logging into a portal their persisted role didn't fit
   would either land on a page that immediately 403'd, or (if no held role fit) succeed
   into a session that was going to get bounced on the next request. Fixed in
   `RoleBasedAuthenticationSuccessHandler`: resolves the portal from the request, picks a
   role from the user's actual entitlements that fits (preferring SUPER_ADMIN on admin),
   persists it if it changed, and rejects the login outright (`?error=noaccess`) if the
   user holds no role valid for this portal at all.

2. **`AppUserPrincipal` exposed ALL held roles as Spring Security authorities, not just the
   active one** — a cross-portal route leak: a USER+CANTEEN_STAFF account on
   `outlet.localhost` could still reach `/student/**` if Spring Security's `hasRole()`
   happened to pass, since the portal gate only checked "is my active role valid for this
   *portal*", not "is this *route* valid for this portal". Fixed by scoping
   `getAuthorities()` to `activeRole` only — `PortalGuard.requireSuperAdmin()` and
   `user.hasRole()` still correctly read the full grant set for the cases that genuinely
   need to (the "authorize on the grant" principle), independent of what Spring Security
   sees.

3. **The role switch (and the login reconciliation above) updated
   `SecurityContextHolder` but never persisted it to the session** — Spring Security 6's
   default `SecurityContextHolderFilter` loads the context at the start of a request but,
   unlike the older `SecurityContextPersistenceFilter`, does not auto-save further changes.
   Net effect: switching roles updated the DB correctly but the *session* kept serving the
   old authorities until logout/login. Fixed by explicitly declaring a
   `SecurityContextRepository` bean (`SecurityConfig`, has to be a `static @Bean` to avoid
   a circular dependency back through `RoleBasedAuthenticationSuccessHandler`) and calling
   `saveContext(...)` explicitly in both the login handler and `RoleSwitchController`.
   Caught by a new test that reuses the session cookie across requests
   (`LoginPortalReconciliationTest`) — every prior test only checked the immediate
   response, never a follow-up request on the same session, so this had no coverage before.

4. **Seed `superuser@bitesite.local` held `CANTEEN_STAFF` and `USER` with no
   `tenant_id`/`outlet_id`** — switching into either would have shown an empty screen.
   V8 now scopes it to Demo College / Main Canteen.

### What was built

- **Outlet-selection screen** (`/student/menu/select`, `MenuBrowseController`): the flow
  Yash asked for directly — log in, see every outlet at your college, pick one, see its
  menu. Auto-skips straight to the menu when there's only one outlet (no needless click).
  The chosen outlet is remembered on the session `Cart` for the rest of the session.
- **Role-switcher** in the navbar — a plain `<select>` (no Bootstrap JS bundle is loaded in
  this app) shown only when `GlobalModelAttributes.switchableRoles` is non-empty, i.e. the
  account holds more than one role valid on the *current* portal.
- **`/admin/users`** (`PlatformUserController`, SUPER_ADMIN/FULL_ADMIN only): create
  platform accounts, grant/revoke SUPER_ADMIN and TECH_MANAGER on any existing one. Revoke
  refuses to remove a user's last remaining role, and re-points `active_role` to a
  surviving role if the revoked one was active.

### Verified

- `mvn clean verify`: 87/87 tests, clean build.
- Live, real HTTP (not just MockMvc) across all three `*.localhost` subdomains: correct
  login routing per role, cross-portal login rejection, role switch persisting across a
  fresh request, outlet picker end-to-end, admin grant/revoke end-to-end including the
  last-role guard.
- `RolePermissionSecurityTest` (pre-existing, now Host-header-aware) and the new
  `LoginPortalReconciliationTest` both green.

### Files touched (session 002, in addition to session 001's)

| File | Action |
|---|---|
| `Role.java`, `PortalTarget.java`, `StaffScope.java`, `SecurityConfig.java`, `RoleLandingPages.java`, `OnboardingController.java`, `PortalGuard.java` | Removed SALES/SUPPORT |
| `V7__multi_role_rbac.sql`, `V8__multi_role_seed_update.sql` | Edited in place (SALES/SUPPORT removal, superuser tenant/outlet scoping) |
| `RoleBasedAuthenticationSuccessHandler.java` | Portal/active-role reconciliation + explicit session save |
| `AppUserPrincipal.java` | Authorities scoped to `activeRole` only |
| `RoleSwitchController.java` | Explicit session save |
| `SecurityConfig.java` | `SecurityContextRepository` bean |
| `MenuBrowseController.java`, `student/select-outlet.html` (new) | Outlet-selection screen |
| `GlobalModelAttributes.java`, `fragments/navbar.html` | Role switcher |
| `PlatformUserController.java` (new), `PlatformUserForm.java` (new), `admin/users.html` (new), `UserService.java` | Platform user + role management |
| `LoginPortalReconciliationTest.java` (new), `UserServiceTest.java` | Test coverage for all of the above |

### Open items for the next session

- Tenant-scoped multi-role (e.g. a canteen staff member who also wants a USER account to
  order for themselves) has no grant UI yet — only platform roles (SUPER_ADMIN/TECH_MANAGER)
  are manageable via `/admin/users` today.
- Real DNS for `app.bitesite.in` / `outlet.bitesite.in` / `admin.bitesite.in` is still
  unconfigured — `app.portal.domains.*` in `application.yml` currently defaults to
  `*.localhost` for dev; `app.portal.target` (`APP`/`OUTLET`/`ADMIN`) is how a real
  single-portal deployment would pin itself in production.
- 90+ files are still uncommitted on `spring-boot-multitenant-saas`, never pushed.

---

*Next session should begin by reading this file for context.*
