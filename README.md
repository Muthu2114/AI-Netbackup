# NetBackup v2 - Network Configuration Backup & Version Management

Spring Boot 3.2 (Java 17) + Spring Security (BCrypt + JWT) + MySQL 8 + plain HTML/CSS/JavaScript. Same stack as v1.

## What is new in v2
| Area | Details |
|---|---|
| Authentication | Spring Security, BCrypt password hashes, stateless JWT (HS256, 8 h), `app_user` table |
| Roles | **ADMIN** everything incl. users, audit log, scheduler, delete device. **OPERATOR** add/edit devices, backup, upload, restore. **VIEWER** read-only (dashboard, devices, versions, compare) |
| Audit log | `audit_log` table: user, action, device, version, status (SUCCESS/FAILED/DENIED), details, time. Logs logins, device changes, backups, uploads, restores, comparisons, user changes, scheduler runs and denied requests. Admin-only viewer with filters |
| Risk analyzer | Every new version is compared with the previous one and rated LOW / MEDIUM / HIGH with the exact findings (see rules below). Shown on dashboard, history, compare and config view |
| Scheduler | `@Scheduled` automatic backup of all devices with "auto backup" enabled; admin "Run now" button |
| Dashboard | 5 stat cards, backups-per-day bar chart, risk donut, device-type donut, success bar, latest risk per device, recent backups, recent user activity (admin), scheduler status. Charts are plain SVG, no external libraries |
| Unchanged | Versioning, upload, comparison, restore (creates a new version), failed backups for INACTIVE devices, all v1 REST endpoints and JSON fields |

## Setup
1. JDK 17+, Maven 3.8+, MySQL 8 running.
2. Edit `src/main/resources/application.properties`: MySQL username/password; for anything but a demo set a real secret:
   `export NETBACKUP_JWT_SECRET="at-least-32-random-characters..."`
3. `mvn spring-boot:run` then open http://localhost:8080
4. Demo logins: **admin / admin123**, **operator / operator123**, **viewer / viewer123**. Change them (sidebar > Change password, or Users page).

### Upgrading an existing v1 database
Just start v2 on the same database. Hibernate adds the new tables/columns, `DataSeeder` imports your old `admin` accounts as ADMIN users (the old SHA-256 hash is replaced by BCrypt at their first login) and risk-rates your existing versions. Your devices and backups are untouched. The demo `operator` and `viewer` accounts are created at startup if they do not exist yet (existing users are never changed); delete or disable them on the Users page for real deployments.
Prefer SQL? See `database/migrations/V2__security_audit_risk.sql` (use it instead of, not in addition to, Hibernate's automatic update).
Fresh start: `DROP DATABASE netbackup;` and restart.

## Risk rules (RiskAnalyzer.java)
Only changed lines are examined; the overall level is the highest severity found. Score: HIGH 40, MEDIUM 20, LOW 5 per finding (max 100).
| Severity | Examples |
|---|---|
| HIGH | interface `shutdown` added; interface IP address changed; default route (0.0.0.0/0) added/changed; routing protocol (`router`, `network`, `neighbor`, `redistribute`) changed; `enable secret` changed; `no service password-encryption`, `transport input telnet`, `ip http server`; default SNMP community (public/private); ACL `deny` rule removed |
| MEDIUM | static route; `permit ip any any`; other ACL entries; SNMP community change; local user change; hostname change |
| LOW | VLAN, NTP, logging changes; interface re-enabled; other minor edits; first version of a device is an "Initial version" baseline |

A restore is rated against the version that was current at the time, so you see how risky the rollback itself is.

## Scheduled backups
Configured in `application.properties`:
```
netbackup.schedule.enabled=true
netbackup.schedule.cron=0 0 2 * * *      # daily 02:00; use "0 * * * * *" for every minute in a demo
```
For each ACTIVE device with auto backup on, the simulator "fetches" the configuration. About 40% of the time it has drifted (an interface shut, an IP or route changed ...): a new version with source SCHEDULED is stored and risk-rated. Unchanged devices get no new version (logged as "No changes detected"). INACTIVE devices produce a FAILED backup, like manual backups. Admins can trigger a run from the dashboard (**Run now**) or `POST /api/scheduler/run`.

## Project structure
```
src/main/java/com/netbackup
├── NetworkBackupApplication.java          (@EnableScheduling)
├── config/      SecurityConfig (filter chain, JWT filter, role rules), DataSeeder
├── model/       AppUser, AuditLog, Device, ConfigBackup
├── repo/        AppUserRepository, AuditLogRepository, DeviceRepository, ConfigBackupRepository
├── service/     AuthService, JwtService, AuditService, BackupService, ScheduledBackupService,
│                RiskAnalyzer*, DiffUtil*, ConfigSimulator*     (* plain Java, no Spring)
└── controller/  Auth, User, Audit, Scheduler, Device, Backup, Dashboard
src/main/resources/static/  index.html, style.css, app.js
database/        schema.sql, migrations/V2__security_audit_risk.sql
sample-configs/  Core-Router-01-custom.cfg, risky-change.cfg
```

## REST API (JWT: `Authorization: Bearer <token>`)
| Method & URL | Roles |
|---|---|
| POST /api/auth/login | public. Returns `token, username, role, expiresInMinutes` |
| GET /api/auth/me, POST /api/auth/change-password | any logged-in user |
| GET /api/devices, /api/devices/{id}, /api/devices/{id}/backups, /api/backups, /api/backups/{id}, /api/backups/compare?a=&b=, /api/dashboard | ADMIN, OPERATOR, VIEWER |
| POST/PUT /api/devices, POST /api/devices/{id}/backup, POST /api/devices/{id}/upload, POST /api/backups/{id}/restore | ADMIN, OPERATOR |
| DELETE /api/devices/{id} | ADMIN |
| GET/POST/PUT /api/users, GET /api/audit, GET + POST /api/scheduler[/run] | ADMIN |

New response fields: backups have `riskLevel`, `riskScore`, `riskDetails` (lines `SEVERITY\|rule\|config line`); compare adds `riskLevel`, `riskScore`, `findings`; dashboard adds `riskDistribution`, `deviceRisk`, `backupsByDay`, `devicesByType`, `scheduler`, `recentActivity`. Devices have `autoBackup`.

## Demo script (8 minutes)
1. Sign in as **admin**. Dashboard: cards, 7-day chart, risk donut (one HIGH from the sample emergency change), latest risk per device, scheduler status.
2. **Versions & Compare** > Core-Router-01: newest version is HIGH. Click **View** to see the findings (interface shutdown, IP change, default route). Compare v4 and v5 to see the diff with the risk panel.
3. **Restore** v4: a new version appears; its risk shows how risky the rollback is.
4. **Devices**: add a device, **Backup now** (baseline version, LOW). Upload `sample-configs/risky-change.cfg` on Core-Router-01 and watch it rate HIGH.
5. Dashboard > **Run now** a few times: new SCHEDULED versions appear, some devices "unchanged", Access-Switch-05 fails.
6. **Users**: create a viewer, sign in as `viewer`: no buttons for changes. Try `curl -X POST` with a viewer token and get 403.
7. **Audit log** (admin): filter by status DENIED / FAILED, see every action above with user and time.

## Quick RBAC check with curl
```
login() { curl -s -X POST localhost:8080/api/auth/login -H "Content-Type: application/json" -d "{\"username\":\"$1\",\"password\":\"$2\"}" | sed 's/.*"token":"\([^"]*\)".*/\1/'; }
V=$(login viewer viewer123); O=$(login operator operator123)
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/devices                                          # 401 no token
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $V" localhost:8080/api/devices            # 200
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $V" localhost:8080/api/devices/1/backup   # 403
curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $O" localhost:8080/api/devices/1/backup   # 200
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $O" localhost:8080/api/audit              # 403
```

## Security notes
- Passwords: BCrypt. Tokens: HS256 JWT signed with `netbackup.jwt.secret`, checked on every request against the DB (a disabled user or changed role takes effect immediately).
- The token is kept in the browser's localStorage, so XSS would expose it; all user data is HTML-escaped in the UI. For internet exposure add HTTPS, rate limiting/lockout on login and a refresh-token flow.
- Server-side role checks are in `SecurityConfig`; hiding buttons in the UI is only convenience.
