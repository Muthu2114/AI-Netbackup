let token = localStorage.getItem('token'), role = localStorage.getItem('role'), me = localStorage.getItem('user');
let devices = [], versions = [], uploadDeviceId = null, selectedDevice = null;
const $ = id => document.getElementById(id);
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const fmt = d => d ? new Date(d).toLocaleString() : '-';
const isAdmin = () => role === 'ADMIN';
const canWrite = () => role === 'ADMIN' || role === 'OPERATOR';
const badge = (t, cls) => `<span class="badge ${cls}">${esc(t)}</span>`;
const statusBadge = s => badge(s, s === 'SUCCESS' || s === 'ACTIVE' ? 'ok' : s === 'DENIED' ? 'warn' : 'bad');
const sourceBadge = s => badge(s, s === 'RESTORE' ? 'warn' : 'info');
const riskBadge = l => l ? badge(l, 'risk-' + l.toLowerCase()) : '<span class="muted">-</span>';
const deviceName = id => (devices.find(d => d.id === id) || {}).name || ('#' + id);
const RISK_COLORS = { LOW: '#15803d', MEDIUM: '#d97706', HIGH: '#b91c1c' };

/* ---------- API helper ---------- */
async function api(path, opts = {}) {
  const isForm = opts.body instanceof FormData;
  const headers = {};
  if (token) headers.Authorization = 'Bearer ' + token;
  if (opts.body && !isForm) headers['Content-Type'] = 'application/json';
  const res = await fetch('/api' + path, { ...opts, headers, body: opts.body && !isForm ? JSON.stringify(opts.body) : opts.body });
  const text = await res.text();
  let data = null; try { data = text ? JSON.parse(text) : null; } catch (e) { /* non-JSON error page */ }
  if (res.status === 401 && path !== '/auth/login') { logout(); throw new Error('Session expired - please sign in again'); }
  if (!res.ok) throw new Error((data && data.message) || 'Request failed (' + res.status + ')');
  return data;
}

function toast(msg, err) {
  const t = $('toast');
  t.textContent = msg; t.className = 'show' + (err ? ' err' : '');
  setTimeout(() => t.className = '', 3000);
}
const run = async fn => { try { await fn(); } catch (e) { toast(e.message, true); } };

function openModal(html) { $('modal-body').innerHTML = html; $('modal').hidden = false; }
function closeModal() { $('modal').hidden = true; }
$('modal').addEventListener('click', e => { if (e.target.id === 'modal') closeModal(); });

/* ---------- Risk helpers ---------- */
function parseRisk(details) {
  return (details || '').split('\n').filter(Boolean).map(l => { const p = l.split('|'); return { severity: p[0], rule: p[1], line: p.slice(2).join('|') }; });
}
function findingsHtml(list) {
  if (!list.length) return '<span class="muted">No risky changes detected.</span>';
  return `<ul class="findings">${list.map(f => `<li>${f.severity === 'INFO' ? badge('INFO', 'info') : riskBadge(f.severity)}
    <b>${esc(f.rule)}</b><code>${esc(f.line)}</code></li>`).join('')}</ul>`;
}

/* ---------- Login / session ---------- */
$('login-form').addEventListener('submit', async e => {
  e.preventDefault();
  try {
    const r = await api('/auth/login', { method: 'POST', body: { username: $('username').value, password: $('password').value } });
    token = r.token; role = r.role; me = r.username;
    localStorage.setItem('token', token); localStorage.setItem('role', role); localStorage.setItem('user', me);
    $('login-error').textContent = '';
    startApp();
  } catch (err) { $('login-error').textContent = err.message; }
});
function logout() {
  ['token', 'role', 'user'].forEach(k => localStorage.removeItem(k));
  token = role = me = null; $('app').hidden = true; $('login-page').hidden = false;
}
$('logout').onclick = logout;
$('pw-btn').onclick = () => openModal(`<h3>Change password</h3><div class="form-grid">
  <label>Current password<input id="pw-cur" type="password"></label>
  <label>New password (8+ characters)<input id="pw-new" type="password"></label></div>
  <div class="modal-actions"><button class="btn" onclick="closeModal()">Cancel</button>
  <button class="btn primary" onclick="changePassword()">Save password</button></div>`);
function changePassword() {
  run(async () => {
    await api('/auth/change-password', { method: 'POST', body: { currentPassword: $('pw-cur').value, newPassword: $('pw-new').value } });
    closeModal(); toast('Password changed');
  });
}

async function startApp() {
  $('login-page').hidden = true; $('app').hidden = false;
  $('who-name').textContent = me; $('who-role').textContent = role;
  document.querySelectorAll('nav a[data-role]').forEach(a => a.hidden = role !== a.dataset.role);
  await run(async () => { devices = await api('/devices'); });
  if (!token) return;   // stale/expired token: api() already signed us out
  show('dashboard');
}
document.querySelectorAll('nav a').forEach(a => a.onclick = () => show(a.dataset.view));

function show(view) {
  document.querySelectorAll('nav a').forEach(a => a.classList.toggle('active', a.dataset.view === view));
  $('header-actions').innerHTML = '';
  run({ dashboard, devices: devicesView, backups: backupsView, users: usersView, audit: auditView }[view]);
}

/* ---------- Charts (plain SVG, no libraries) ---------- */
function barChart(days) {
  const W = 520, H = 220, L = 30, B = 26, T = 10, ch = H - B - T;
  const max = Math.max(1, ...days.map(d => d.success + d.failed)), bw = (W - L) / days.length;
  let s = `<svg viewBox="0 0 ${W} ${H}" class="chart" role="img" aria-label="Backups per day for the last 7 days">`;
  [0, .5, 1].forEach(f => {
    const y = T + ch - ch * f;
    s += `<line x1="${L}" x2="${W}" y1="${y}" y2="${y}" class="grid"/><text x="${L - 6}" y="${y + 3}" text-anchor="end" class="axis">${Math.round(max * f)}</text>`;
  });
  days.forEach((d, i) => {
    const x = L + i * bw + bw * .2, w = bw * .6, hs = ch * d.success / max, hf = ch * d.failed / max;
    s += `<g><title>${d.date}: ${d.success} successful, ${d.failed} failed</title>
      <rect x="${x}" y="${T + ch - hs}" width="${w}" height="${hs}" fill="var(--ok)"/>
      <rect x="${x}" y="${T + ch - hs - hf}" width="${w}" height="${hf}" fill="var(--bad)"/></g>
      <text x="${x + w / 2}" y="${H - 8}" text-anchor="middle" class="axis">${d.date.slice(5)}</text>`;
  });
  return s + '</svg>';
}
function donut(items) {
  const total = items.reduce((a, i) => a + i.value, 0), R = 44, C = 2 * Math.PI * R;
  let off = 0;
  let s = `<svg viewBox="0 0 120 120" class="donut" role="img"><circle cx="60" cy="60" r="${R}" fill="none" stroke="#e5e7eb" stroke-width="16"/>`;
  if (total) items.forEach(i => {
    if (!i.value) return;
    const len = C * i.value / total;
    s += `<circle cx="60" cy="60" r="${R}" fill="none" stroke="${i.color}" stroke-width="16" stroke-dasharray="${len} ${C - len}"
      stroke-dashoffset="${-off}" transform="rotate(-90 60 60)"><title>${i.label}: ${i.value}</title></circle>`;
    off += len;
  });
  s += `<text x="60" y="66" text-anchor="middle" class="donut-n">${total}</text></svg>`;
  return `<div class="donut-wrap">${s}<ul class="legend">${items.map(i => `<li><i style="background:${i.color}"></i>${esc(i.label)}: <b>${i.value}</b></li>`).join('')}</ul></div>`;
}

/* ---------- Dashboard ---------- */
async function dashboard() {
  $('title').textContent = 'Dashboard';
  const s = await api('/dashboard');
  devices = await api('/devices');
  const total = s.totalBackups || 1, okPct = Math.round(s.successBackups / total * 100);
  const rd = s.riskDistribution, sc = s.scheduler;
  $('content').innerHTML = `
    <div class="cards">
      <div class="card stat">Devices<b>${s.totalDevices}</b><span class="muted">${s.activeDevices} active</span></div>
      <div class="card stat">Backups<b>${s.totalBackups}</b><span class="muted">all attempts</span></div>
      <div class="card stat">Stored versions<b>${s.totalVersions}</b><span class="muted">restorable</span></div>
      <div class="card stat">Failed backups<b style="color:var(--bad)">${s.failedBackups}</b><span class="muted">need attention</span></div>
      <div class="card stat">High-risk changes<b style="color:var(--bad)">${rd.HIGH}</b><span class="muted">${rd.MEDIUM} medium, ${rd.LOW} low</span></div>
    </div>
    <div class="grid-2">
      <div class="panel"><h3>Backups in the last 7 days</h3>${barChart(s.backupsByDay)}
        <span class="muted small">Green = successful, red = failed</span></div>
      <div class="panel"><h3>Change risk (all versions)</h3>${donut(['LOW', 'MEDIUM', 'HIGH'].map(l => ({ label: l, value: rd[l], color: RISK_COLORS[l] })))}</div>
    </div>
    <div class="grid-2">
      <div class="panel"><h3>Backup status</h3>
        <div class="bar"><i style="width:${okPct}%;background:var(--ok)"></i><i style="width:${100 - okPct}%;background:var(--bad)"></i></div>
        <span class="muted">${s.successBackups} successful, ${s.failedBackups} failed (${okPct}% success rate)</span>
        <h3 style="margin-top:18px">Scheduled backups</h3>
        <div class="sched"><div>${sc.enabled ? badge('ENABLED', 'ok') : badge('DISABLED', 'bad')}
          <span class="muted">cron <code>${esc(sc.cron)}</code></span><br>
          <span class="muted">Last run: ${fmt(sc.lastRunAt)} - ${esc(sc.lastRunSummary)}</span></div>
          ${isAdmin() ? '<button class="btn sm primary" onclick="runScheduler()">Run now</button>' : ''}</div></div>
      <div class="panel"><h3>Devices by type</h3>${donut(Object.entries(s.devicesByType).map(([k, v], i) =>
        ({ label: k, value: v, color: ['#0f766e', '#1d4ed8', '#7c3aed', '#64748b'][i % 4] })))}</div>
    </div>
    <div class="panel"><h3>Latest risk by device</h3>
      <table><tr><th>Device</th><th>Latest version</th><th>Risk</th><th>Score</th><th></th></tr>
      ${s.deviceRisk.map(r => `<tr><td>${esc(r.name)}</td><td>${r.version ? 'v' + r.version : '-'}</td><td>${riskBadge(r.riskLevel)}</td>
        <td>${r.riskScore ?? '-'}</td><td><button class="btn sm" onclick="openVersions(${r.deviceId})">Review</button></td></tr>`).join('')}</table></div>
    <div class="panel"><h3>Recent backup activity</h3>${backupTable(s.recent, true)}</div>
    ${isAdmin() ? `<div class="panel"><h3>Recent user activity</h3>${auditTable(s.recentActivity)}</div>` : ''}
    <div class="panel"><h3>Device overview</h3>
      <table><tr><th>Device</th><th>IP</th><th>Status</th><th>Current version</th><th>Last backup</th></tr>
      ${devices.map(d => `<tr><td>${esc(d.name)}</td><td>${esc(d.ipAddress)}</td><td>${statusBadge(d.status)}</td>
        <td>${d.currentVersion ? 'v' + d.currentVersion : '-'}</td><td>${fmt(d.lastBackupAt)}</td></tr>`).join('')}</table></div>`;
}

function runScheduler() {
  run(async () => {
    const r = await api('/scheduler/run', { method: 'POST' });
    toast('Scheduled run finished: ' + r.lastRunSummary); dashboard();
  });
}

function backupTable(list, showDevice) {
  if (!list.length) return '<div class="empty">No backups yet.</div>';
  return `<table><tr>${showDevice ? '<th>Device</th>' : ''}<th>Version</th><th>File</th><th>Source</th><th>Status</th><th>Risk</th><th>Note</th><th>Time</th></tr>
    ${list.map(b => `<tr>${showDevice ? `<td>${esc(deviceName(b.deviceId))}</td>` : ''}<td>${b.version ? 'v' + b.version : '-'}</td>
      <td>${esc(b.fileName)}</td><td>${sourceBadge(b.source)}</td><td>${statusBadge(b.status)}</td><td>${riskBadge(b.riskLevel)}</td>
      <td>${esc(b.note)}</td><td>${fmt(b.createdAt)}</td></tr>`).join('')}</table>`;
}

function auditTable(list) {
  if (!list.length) return '<div class="empty">No audit entries.</div>';
  return `<table><tr><th>Time</th><th>User</th><th>Action</th><th>Device</th><th>Version</th><th>Status</th><th>Details</th></tr>
    ${list.map(a => `<tr><td>${fmt(a.createdAt)}</td><td>${esc(a.username)}</td><td>${esc(a.action)}</td><td>${esc(a.deviceName || '-')}</td>
      <td>${a.version ? 'v' + a.version : '-'}</td><td>${statusBadge(a.status)}</td><td>${esc(a.details)}</td></tr>`).join('')}</table>`;
}

/* ---------- Devices ---------- */
async function devicesView() {
  $('title').textContent = 'Network devices';
  if (canWrite()) $('header-actions').innerHTML = '<button class="btn primary" onclick="deviceForm()">Add device</button>';
  devices = await api('/devices');
  $('content').innerHTML = `<div class="panel">${!devices.length ? '<div class="empty">No devices yet. Add your first device.</div>' :
    `<table><tr><th>Name</th><th>IP</th><th>Type</th><th>Vendor</th><th>Location</th><th>Status</th><th>Auto backup</th><th>Version</th><th>Actions</th></tr>
    ${devices.map(d => `<tr><td><b>${esc(d.name)}</b></td><td>${esc(d.ipAddress)}</td><td>${esc(d.type)}</td><td>${esc(d.vendor)}</td>
      <td>${esc(d.location)}</td><td>${statusBadge(d.status)}</td><td>${d.autoBackup === false ? 'No' : 'Yes'}</td>
      <td>${d.currentVersion ? 'v' + d.currentVersion : '-'}</td>
      <td class="actions">${canWrite() ? `<button class="btn sm primary" onclick="backupNow(${d.id})">Backup now</button>
        <button class="btn sm" onclick="pickFile(${d.id})">Upload</button>` : ''}
        <button class="btn sm" onclick="openVersions(${d.id})">Versions</button>
        ${canWrite() ? `<button class="btn sm" onclick="deviceForm(${d.id})">Edit</button>` : ''}
        ${isAdmin() ? `<button class="btn sm danger" onclick="removeDevice(${d.id})">Delete</button>` : ''}</td></tr>`).join('')}</table>`}</div>`;
}

function deviceForm(id) {
  const d = devices.find(x => x.id === id) || { type: 'Router', status: 'ACTIVE', autoBackup: true };
  const opt = (list, v) => list.map(o => `<option ${o === v ? 'selected' : ''}>${o}</option>`).join('');
  openModal(`<h3>${id ? 'Edit device' : 'Add device'}</h3>
    <div class="form-grid">
      <label>Name<input id="f-name" value="${esc(d.name)}"></label>
      <label>IP address<input id="f-ip" value="${esc(d.ipAddress)}" placeholder="10.0.0.1"></label>
      <label>Type<select id="f-type">${opt(['Router', 'Switch', 'Firewall'], d.type)}</select></label>
      <label>Vendor<input id="f-vendor" value="${esc(d.vendor)}"></label>
      <label>Location<input id="f-loc" value="${esc(d.location)}"></label>
      <label>Status<select id="f-status">${opt(['ACTIVE', 'INACTIVE'], d.status)}</select></label>
      <label class="checkline"><input type="checkbox" id="f-auto" ${d.autoBackup === false ? '' : 'checked'}> Include in scheduled backups</label>
    </div>
    <div class="modal-actions"><button class="btn" onclick="closeModal()">Cancel</button>
      <button class="btn primary" onclick="saveDevice(${id || 'null'})">Save device</button></div>`);
}

function saveDevice(id) {
  run(async () => {
    const body = { name: $('f-name').value, ipAddress: $('f-ip').value, type: $('f-type').value, vendor: $('f-vendor').value,
                   location: $('f-loc').value, status: $('f-status').value, autoBackup: $('f-auto').checked };
    await api(id ? '/devices/' + id : '/devices', { method: id ? 'PUT' : 'POST', body });
    closeModal(); toast('Device saved'); devicesView();
  });
}

function removeDevice(id) {
  if (!confirm('Delete this device and ALL its backup versions?')) return;
  run(async () => { await api('/devices/' + id, { method: 'DELETE' }); toast('Device deleted'); devicesView(); });
}

function backupNow(id) {
  run(async () => {
    const b = await api(`/devices/${id}/backup`, { method: 'POST' });
    b.status === 'SUCCESS' ? toast(`Backup saved as version ${b.version} (risk ${b.riskLevel})`) : toast(b.note, true);
    devicesView();
  });
}

function pickFile(id) { uploadDeviceId = id; $('file-input').value = ''; $('file-input').click(); }
$('file-input').addEventListener('change', e => {
  const f = e.target.files[0]; if (!f) return;
  run(async () => {
    const fd = new FormData(); fd.append('file', f);
    const b = await api(`/devices/${uploadDeviceId}/upload`, { method: 'POST', body: fd });
    toast(`Uploaded as version ${b.version} (risk ${b.riskLevel})`); devicesView();
  });
});

/* ---------- Versions, compare, restore ---------- */
function openVersions(id) { selectedDevice = id; show('backups'); }

async function backupsView() {
  $('title').textContent = 'Versions & compare';
  devices = await api('/devices');
  if (!devices.length) { $('content').innerHTML = '<div class="panel empty">Add a device first.</div>'; return; }
  if (!devices.some(d => d.id === selectedDevice)) selectedDevice = devices[0].id;
  $('content').innerHTML = `
    <div class="toolbar"><label>Device<select id="dev-select">${devices.map(d =>
      `<option value="${d.id}" ${d.id === selectedDevice ? 'selected' : ''}>${esc(d.name)} (${esc(d.ipAddress)})</option>`).join('')}</select></label></div>
    <div class="panel"><h3>Version history</h3><div id="history"></div></div>
    <div class="panel"><h3>Compare versions</h3><div id="compare-box"></div><div id="diff-out" style="margin-top:14px"></div></div>`;
  $('dev-select').onchange = e => { selectedDevice = +e.target.value; loadHistory(); };
  await loadHistory();
}

async function loadHistory() {
  const all = await api(`/devices/${selectedDevice}/backups`);
  versions = all.filter(b => b.status === 'SUCCESS');
  $('diff-out').innerHTML = '';
  $('history').innerHTML = !all.length ? '<div class="empty">No backups for this device yet. Use "Backup now" on the Devices page.</div>' :
    `<table><tr><th>Version</th><th>File</th><th>Source</th><th>Status</th><th>Risk</th><th>Checksum</th><th>Note</th><th>By</th><th>Time</th><th>Actions</th></tr>
    ${all.map(b => `<tr><td><b>${b.version ? 'v' + b.version : '-'}</b></td><td>${esc(b.fileName)}</td><td>${sourceBadge(b.source)}</td>
      <td>${statusBadge(b.status)}</td><td>${riskBadge(b.riskLevel)}</td><td>${esc((b.checksum || '').slice(0, 10))}</td><td>${esc(b.note)}</td>
      <td>${esc(b.createdBy)}</td><td>${fmt(b.createdAt)}</td>
      <td class="actions">${b.status === 'SUCCESS' ? `<button class="btn sm" onclick="viewConfig(${b.id})">View</button>
        <button class="btn sm" onclick="download(${b.id})">Download</button>
        ${canWrite() ? `<button class="btn sm primary" onclick="restore(${b.id}, ${b.version})">Restore</button>` : ''}` : ''}</td></tr>`).join('')}</table>`;
  if (versions.length < 2) { $('compare-box').innerHTML = '<span class="muted">At least two successful versions are needed to compare.</span>'; return; }
  const opts = sel => versions.map((b, i) => `<option value="${b.id}" ${i === sel ? 'selected' : ''}>v${b.version} - ${fmt(b.createdAt)}</option>`).join('');
  $('compare-box').innerHTML = `<div class="toolbar"><label>Older version<select id="cmp-a">${opts(1)}</select></label>
    <label>Newer version<select id="cmp-b">${opts(0)}</select></label>
    <button class="btn primary" onclick="compare()">Compare</button></div>`;
}

function viewConfig(id) {
  run(async () => {
    const b = await api('/backups/' + id);
    openModal(`<h3>${esc(deviceName(b.deviceId))} - version ${b.version} ${riskBadge(b.riskLevel)}</h3>
      <p class="muted">${esc(b.fileName)} | SHA-256 ${esc((b.checksum || '').slice(0, 16))}...</p>
      <h4>Risk analysis (vs previous version)</h4>${findingsHtml(parseRisk(b.riskDetails))}
      <h4>Configuration</h4><pre class="code">${esc(b.content)}</pre>
      <div class="modal-actions"><button class="btn" onclick="closeModal()">Close</button></div>`);
  });
}

function download(id) {
  run(async () => {
    const b = await api('/backups/' + id);
    const a = document.createElement('a');
    a.href = URL.createObjectURL(new Blob([b.content], { type: 'text/plain' }));
    a.download = b.fileName; a.click(); URL.revokeObjectURL(a.href);
  });
}

function compare() {
  run(async () => {
    const r = await api(`/backups/compare?a=${$('cmp-a').value}&b=${$('cmp-b').value}`);
    $('diff-out').innerHTML = `<p>Comparing <b>v${r.fromVersion}</b> to <b>v${r.toVersion}</b>:
      ${badge('+' + r.added + ' added', 'ok')} ${badge('-' + r.removed + ' removed', 'bad')}
      Change risk: ${riskBadge(r.riskLevel)} <span class="muted">(score ${r.riskScore}/100)</span></p>
      <div style="margin-bottom:12px">${findingsHtml(r.findings)}</div>
      <div class="diff">${r.lines.map(l => `<div class="${l.type}">${l.type === 'added' ? '+ ' : l.type === 'removed' ? '- ' : '  '}${esc(l.text)}</div>`).join('')}</div>`;
  });
}

function restore(id, v) {
  if (!confirm(`Restore version ${v}? A new version with this configuration will be created.`)) return;
  run(async () => {
    const b = await api(`/backups/${id}/restore`, { method: 'POST' });
    toast(`Version ${v} restored as new version ${b.version} (risk ${b.riskLevel})`); loadHistory();
  });
}

/* ---------- Users (ADMIN) ---------- */
let userList = [];
async function usersView() {
  $('title').textContent = 'User management';
  $('header-actions').innerHTML = '<button class="btn primary" onclick="userForm()">Add user</button>';
  userList = await api('/users');
  $('content').innerHTML = `<div class="panel"><table><tr><th>Username</th><th>Role</th><th>Status</th><th>Created</th><th>Actions</th></tr>
    ${userList.map(u => `<tr><td><b>${esc(u.username)}</b></td><td>${badge(u.role, 'info')}</td>
      <td>${u.enabled ? badge('ENABLED', 'ok') : badge('DISABLED', 'bad')}</td><td>${fmt(u.createdAt)}</td>
      <td class="actions"><button class="btn sm" onclick="userForm(${u.id})">Edit</button></td></tr>`).join('')}</table>
    <p class="muted small" style="margin-top:12px">ADMIN: everything. OPERATOR: add/edit devices, back up, upload, restore. VIEWER: read-only.</p></div>`;
}

function userForm(id) {
  const u = userList.find(x => x.id === id);
  const roles = ['ADMIN', 'OPERATOR', 'VIEWER'].map(r => `<option ${u && u.role === r ? 'selected' : ''}>${r}</option>`).join('');
  openModal(`<h3>${u ? 'Edit ' + esc(u.username) : 'Add user'}</h3><div class="form-grid">
    ${u ? '' : '<label>Username<input id="u-name"></label>'}
    <label>${u ? 'New password (leave empty to keep)' : 'Password (8+ characters)'}<input id="u-pass" type="password"></label>
    <label>Role<select id="u-role">${roles}</select></label>
    ${u ? `<label>Status<select id="u-en"><option value="true" ${u.enabled ? 'selected' : ''}>Enabled</option><option value="false" ${u.enabled ? '' : 'selected'}>Disabled</option></select></label>` : ''}
    </div><div class="modal-actions"><button class="btn" onclick="closeModal()">Cancel</button>
    <button class="btn primary" onclick="saveUser(${id || 'null'})">Save user</button></div>`);
}

function saveUser(id) {
  run(async () => {
    if (id) {
      await api('/users/' + id, { method: 'PUT', body: { role: $('u-role').value, enabled: $('u-en').value === 'true', password: $('u-pass').value } });
    } else {
      await api('/users', { method: 'POST', body: { username: $('u-name').value, password: $('u-pass').value, role: $('u-role').value } });
    }
    closeModal(); toast('User saved'); usersView();
  });
}

/* ---------- Audit log (ADMIN) ---------- */
async function auditView() {
  $('title').textContent = 'Audit log';
  $('content').innerHTML = `<div class="toolbar">
    <label>User<input id="a-user" placeholder="any"></label>
    <label>Action<select id="a-action"><option value="">Any</option>${['LOGIN', 'BACKUP', 'BACKUP_SCHEDULED', 'SCHEDULER_RUN', 'UPLOAD', 'RESTORE', 'COMPARE',
      'DEVICE_CREATE', 'DEVICE_UPDATE', 'DEVICE_DELETE', 'USER_CREATE', 'USER_UPDATE', 'PASSWORD_CHANGE', 'ACCESS_DENIED'].map(a => `<option>${a}</option>`).join('')}</select></label>
    <label>Status<select id="a-status"><option value="">Any</option><option>SUCCESS</option><option>FAILED</option><option>DENIED</option></select></label>
    <button class="btn primary" onclick="loadAudit()">Search</button></div>
    <div class="panel" id="audit-list"></div>`;
  await loadAudit();
}
function loadAudit() {
  return run(async () => {
    const q = new URLSearchParams({ user: $('a-user').value, action: $('a-action').value, status: $('a-status').value, limit: 200 });
    $('audit-list').innerHTML = auditTable(await api('/audit?' + q));
  });
}

if (token) startApp();
