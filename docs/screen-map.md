# BiteSite Screen / Feature / Control Map

Root: `/Users/yash113gadia/java project` · Spring Boot + Thymeleaf, multi-tenant (college → outlets), Razorpay payments.

## Global / shared

| Item | Path | Notes |
|---|---|---|
| Head fragment | `templates/fragments/head.html` | Title, favicon, PWA manifest, Inter font, self-hosted Material Symbols, Phosphor icons (outlet/admin only), Bootstrap 5.3.3, `01-tokens.css` + `app.css`, `app.js` (defer), CSRF meta `meta[name="_csrf"]` / `_csrf_parameter` (used by app.js fetch calls) |
| Navbar | `templates/fragments/navbar.html` | Fragments `navbar` + `bottomNav`. IDs: `navbar-toggle-btn`, `navbar-links-collapse`, `bottom-nav`, `cart-badge`. Role-switch `<select>` with `onchange="this.form.submit()"`. Bottom nav items: `data-page` = `menu, cart, orders, support, account` (USER role only) |
| State fragment | `templates/fragments/state.html` | `medallion(icon, tone)` empty-state helper |
| Order status | `templates/fragments/order-status.html` | Shared status labels/badges |
| Auth brand | `templates/fragments/auth-brand.html` | Branding on login/register |
| JS | `static/js/app.js` | See "JS hooks to preserve" below |

## Public

| Screen | Template | Route (controller) | Features |
|---|---|---|---|
| Home | (via `LoginController` `GET /`) | `/` | Landing/login redirect |
| Login | `auth/login.html` | `/login` (GET; POST handled by Spring Security) | Fields `username` (`#login-email`), `password` (`#login-password`), show-password button `data-target="login-password"` |
| Tenant unavailable | `error/tenant-unavailable.html` | `/tenant-unavailable` (`SiteController`) | Public error for disabled college |
| Access denied | `error/access-denied.html` | `/access-denied` (`SiteController`) | Configured in SecurityConfig |
| Generic error | `error/generic.html` | `/error` | Public per SecurityConfig |

## Legal (public, `LegalController`)

| Screen | Template | Route |
|---|---|---|
| Terms | `legal/terms.html` | `/terms` |
| Privacy policy | `legal/privacy-policy.html` | `/privacy-policy` |
| Refund policy | `legal/refund-policy.html` | `/refund-policy` |
| Shipping policy | `legal/shipping-policy.html` | `/shipping-policy` |
| Grievance policy | `legal/grievance-policy.html` | `/grievance-policy` |

All cross-link each other plus `/student/account`, `/student/grievances`, `/student/privacy`. Each carries a "draft, not legal advice" warning.

## Auth

| Screen | Template | Route(s) | Forms/actions |
|---|---|---|---|
| Register | `auth/register.html` | `/register/student` (GET/POST, `RegistrationController`) | Fields: `tenantId` (`#reg-college`), `name`, `email` (`#reg-email`), `password` (`#register-password` + show toggle `data-target`), `phone`, `rollNo`. All have `reg-*` IDs |
| Verify | `auth/verify.html` | `/verify` (GET, `VerificationController`); POSTs: `/verify/email`, `/verify/phone`, `/verify/resend` | Email/phone code verification |
| Resend verification | `auth/resend-verification.html` | `/resend-verification` (GET/POST) | Resend form |

Role switch: `POST /api/role/switch` (`RoleSwitchController`) — navbar select.

## Student portal — gate: `hasRole('USER')` on `/student/**`

| Screen | Template | Route (controller) | Forms / actions |
|---|---|---|---|
| Select outlet | `student/select-outlet.html` | `GET /student/menu/select` (`MenuBrowseController`) | Outlet chooser |
| Menu | `student/menu.html` | `GET /student/menu` | Outlet select `onchange=this.form.submit()`; search `#menu-search`; chips `#category-chips` / `.category-chip[data-category]`; `#menu-no-results`; cards `.menu-card[data-name]`; add-to-cart forms `.add-to-cart-form[data-item-id][data-outlet-id]`; sticky bar `#sticky-cart-bar`, `#sticky-cart-count` |
| Item detail | `student/item-detail.html` | `GET /student/menu/item/{itemId}` | Add to cart form |
| Cart | `student/cart.html` | `GET /student/cart` (`CartController`) | POSTs: `/student/cart/add`, `/update`, `/remove`. `.cart-control[data-item-id]`, `.cart-qty-minus/plus/value`, `form[data-cart-update]`, `form[data-cart-remove]`, `input[name=menuItemId]`, `.cart-item-subtotal`, `.sticky-pay-total` |
| Checkout | `student/checkout.html` | `POST /student/checkout`, `GET /student/checkout/{orderId}`, `POST .../confirm` (`CheckoutController`) | `#pay-btn` (Razorpay), hidden `#confirm-form` with `#f-order-id`, `#f-payment-id`, `#f-signature`; `#pay-notice` result div |
| Orders list | `student/orders.html` | `GET /student/orders` (`OrderHistoryController`) | History; `POST /{orderId}/reorder` |
| Order detail | `student/order-detail.html` | `GET /student/orders/{orderId}` | Status, items, pickup code (shown when READY), payment refs, support link |
| Account | `student/account.html` | `GET /student/account` (`StudentAccountController`); `POST /student/account/delete` | Push toggle `#push-toggle`; delete form with `onsubmit=confirm(...)` |
| Your data (privacy) | `student/privacy.html` | `GET /student/privacy` (`PrivacyController`); `GET /export`; `POST /notifications`; `POST /requests` | Toggles `#orderUpdates`, `#marketing`; data export button; DPDP request form (`kind` select: ACCESS/CORRECTION/ERASURE + `note`); consent history |
| Grievances | `student/grievances.html` | `GET/POST /student/grievances` (`StudentGrievanceController`) | Form with `#grievance-order` select |

## Canteen portal — gate: `CANTEEN_MANAGER` or `CANTEEN_OPERATOR` on `/canteen/**` (per-method `PortalGuard`/`StaffScope`)

| Screen | Template | Route(s) | Forms / actions |
|---|---|---|---|
| Queue (live) | `canteen/queue.html` | `GET /canteen/queue` (`OrderQueueController`); polls `GET /api/orders/queue` (`OrderQueueApiController`) | IDs `#queue-empty`, `#queue-body`; cards `data-order-id`; POSTs `/{orderId}/status`, `/cancel`, `/collect`, `/accepting`; cancel has confirm onsubmit |
| Orders | `canteen/orders.html` | `GET /canteen/orders` (`OutletAdminController`) | Status filter `onchange=this.form.submit()`; detail: `GET /canteen/orders/{orderId}` (`canteen/order-detail.html`) |
| Menu | `canteen/menu.html` | `GET /canteen/menu` (`MenuController`) | Restock-all (`POST /restock-all`, confirm), delete (`POST /{id}/delete`, confirm), toggle stock (`POST /{id}/toggle`) — stock toggles deliberately shared manager/operator |
| Menu form | `canteen/menu-form.html` | `GET /canteen/menu/new`, `GET /{id}/edit`; `POST /canteen/menu`, `POST /{id}` | Fields incl. `removePhoto` checkbox |
| Categories | `canteen/categories.html` | `GET/POST /canteen/categories` (`CategoryController`); `POST /{id}/rename`, `/{id}/order`, `/{id}/delete` (delete confirm onsubmit) | Ordering of categories |
| Staff | `canteen/staff.html` | `GET/POST /canteen/staff`; `POST /staff/{userId}/deactivate` (confirm onsubmit) | Manager-only |
| Settings | `canteen/settings.html` | `GET/POST /canteen/settings` | IDs `#opensAt`, `#closesAt`, `#contactPhone`, `#notice` |
| Reports | `canteen/reports.html` | `GET /canteen/reports` | Sales/operational reporting |

## Admin portal — gate: `SUPER_ADMIN` or `TECH_MANAGER` on `/admin/**`

| Screen | Template | Route(s) | Forms / actions |
|---|---|---|---|
| Home | `admin/tenants.html` | `GET /admin` (`AdminHomeController`), `GET /admin/tenants` (`TenantController`) | Tenant list |
| Tenant form | `admin/tenant-form.html` | `GET /admin/tenants/new`; `POST /admin/tenants` | Create tenant |
| Tenant detail | `admin/tenant-detail.html` | `GET /admin/tenants/{id}` | POSTs: `/{id}/status`, `/{id}/logo`, `/{id}/outlets`, `/{id}/staff`, `/{id}/outlets/{outletId}/rename|status|delete` |
| Onboarding | `admin/onboarding.html` | `GET /admin/onboarding`, `/new` (`OnboardingController`) | POSTs `/admin/onboarding`, `/{id}/stage`, `/{id}/convert` (convert has onsubmit confirm) |
| Onboarding form | `admin/onboarding-form.html` | `GET /admin/onboarding/new` | Lead form |
| Users | `admin/users.html` | `GET/POST /admin/users` (`PlatformUserController`) | Create form `th:action=@{/admin/users}` (name, email, password, roles); `POST /{id}/roles/grant`, `/{id}/roles/revoke` (confirm onsubmit); revoke select `onchange=this.form.submit()` |
| Grievances | `admin/grievances.html` | `GET /admin/grievances`; `POST /{id}/resolve` (`AdminGrievanceController`) | Resolve action |
| Support desk | `admin/support.html` | `GET /admin/support` (`SupportDeskController`) | `POST /orders/{orderId}/refund` with confirm onsubmit |
| Audit log | `admin/audit-log.html` | `GET /admin/audit-log` (`AuditLogController`) | `tenantId` select `onchange=this.form.submit()`; table When/Entity/Action/Before/After |
| Data requests (DPDP) | `admin/data-requests.html` | `GET /admin/dpdp`; `POST /{id}/status` (`DataRequestController`) | Status filter + per-row status select + "Set" button |
| Outlets oversight | (admin template) | `GET /admin/outlets` (`PlatformOversightController`) | Cross-tenant outlet list |
| Orders oversight | `admin/orders.html` | `GET /admin/orders` | `tenantId` + `status` selects, both `onchange=this.form.submit()` |
| Payments | `admin/payments.html` | `GET /admin/payments` | `status` select `onchange=this.form.submit()` |

## Tech manager portal — gate: same admin roles on `/techmgr/**`

| Screen | Template | Route(s) | Forms / actions |
|---|---|---|---|
| Dashboard | `techmgr/dashboard.html` | `GET /techmgr` (`TechManagerHomeController`) | Overview |
| Health | `techmgr/health.html` | `GET /techmgr/health` (`HealthController`) | Actuator-backed status (`up`, `status`), payments readiness (`paymentsUp`, `paymentsDetails`) |
| Tenant config | `techmgr/tenant-config.html` | `GET /techmgr/tenants/{id}`; `POST /{id}/config` (`TechConfigController`) | Per-tenant config form |

## JS hooks that must survive a redesign (`static/js/app.js`)

- CSRF: `meta[name="_csrf"]`, `meta[name="_csrf_parameter"]` (head fragment)
- Push notifications: `#push-toggle`; endpoints `GET/POST /api/push/public-key|subscribe|unsubscribe`
- Navbar: `#navbar-toggle-btn`, `#navbar-links-collapse`
- Bottom nav: `#bottom-nav`, `.bottom-nav-item[data-page]`, `#cart-badge`
- Menu: `#menu-search`, `#menu-no-results`, `.menu-card` (`data-name`), `.menu-category-section[id]`, `#category-chips` / `.category-chip[data-category]` (note: app.js reads `data-category`, templates emit lowercase-hyphenated via `th:data-category`), `.add-to-cart-form` (`data-item-id`, `data-outlet-id`), `.qty-stepper` (`.qty-value`, `.qty-minus`, `.qty-plus`)
- Cart: `.cart-control` (`data-item-id`), `.cart-qty-minus/plus`, `.cart-item-subtotal`, `.sticky-pay-total`, `form[data-cart-update]`, `form[data-cart-remove]`, `input[name=menuItemId]`, `[data-cart-line]`; URLs from `body[data-cart-update-url]` / `data-cart-remove-url` (defaults `/student/cart/update|remove`)
- Sticky bar: `#sticky-cart-bar`, `#sticky-cart-count`
- Checkout (Razorpay): `#pay-btn`, `#confirm-form`, `#f-order-id`, `#f-payment-id`, `#f-signature`, `#pay-notice`
- Queue: `#queue-body` cards with `data-order-id`, `#queue-empty`
- Toasts: `.toast-container` created dynamically
- Inline handlers elsewhere: filter selects `onchange="this.form.submit()"` (menu outlet, canteen orders, admin orders/payments/audit-log/data-requests, navbar role switch); destructive confirms `onsubmit="return confirm(...)"` (account delete, menu delete/restock, category delete, staff deactivate, user role revoke, onboarding convert, support refund, queue cancel)
