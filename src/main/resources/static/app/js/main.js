// App entry: auth gate, shell (sidebar / mobile tab bar), hash routing, theme, reminders and shortcuts.

import { api, errorText, session, whenSignedOut } from './api.js';
import { brand, rememberTheme, renderAuth, savedTheme } from './auth.js';
import { el, fill, icon, toast } from './dom.js';
import { openTransactionDialog } from './forms.js';
import { initials } from './format.js';
import { bellButton, refreshNotifications } from './notifications.js';
import { store } from './store.js';
import { renderDebts } from './views/debts.js';
import { renderLoans } from './views/loans.js';
import { renderOverview } from './views/overview.js';
import { renderRecurring } from './views/recurring.js';
import { renderReports } from './views/reports.js';
import { renderSettings } from './views/settings.js';
import { renderTransactions } from './views/transactions.js';

const ROUTES = {
    '#/overview': { label: 'Overview', short: 'Home', iconName: 'home', group: 'Money', render: renderOverview },
    '#/transactions': { label: 'Transactions', short: 'Activity', iconName: 'list', group: 'Money', render: renderTransactions },
    '#/reports': { label: 'Reports', short: 'Reports', iconName: 'chart', group: 'Money', render: renderReports },
    '#/recurring': { label: 'Bills & recurring', short: 'Bills', iconName: 'repeat', group: 'Plan', render: renderRecurring },
    '#/loans': { label: 'Loans & EMIs', short: 'Loans', iconName: 'bank', group: 'Plan', render: renderLoans },
    '#/debts': { label: 'Lending', short: 'Lending', iconName: 'people', group: 'Plan', render: renderDebts },
    '#/settings': {
        label: 'Settings', short: 'Settings', iconName: 'settings', group: 'Account',
        render: (root) => renderSettings(root, {
            onThemeChange: applyTheme, onPasswordChanged: signOut,
            onProfileChanged: async () => { me = await store.me(); renderNav(); },
        }),
    },
};
/** Phone tab bar; everything else lives under "More". */
const TABS = ['#/overview', '#/transactions', '#/reports', '#/recurring'];

const root = document.getElementById('root');
let main;

// ---- Theme ----

function applyTheme(theme) {
    if (theme === 'LIGHT' || theme === 'DARK') document.documentElement.dataset.theme = theme.toLowerCase();
    else delete document.documentElement.dataset.theme;
    if (theme) rememberTheme(theme); // so the sign-in screen opens in the same theme
    renderNav();
}

function isDark() {
    const forced = document.documentElement.dataset.theme;
    return forced ? forced === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
}

async function toggleTheme() {
    const next = isDark() ? 'LIGHT' : 'DARK';
    applyTheme(next);
    await api.patch('/users/me/preferences', { theme: next }).catch(() => null);
    store.invalidate('me');
}

// ---- Shell ----

const currentHash = () => (ROUTES[location.hash] ? location.hash : '#/overview');

function navLink(hash, { short = false } = {}) {
    const r = ROUTES[hash];
    return el('a', { class: 'nav-link', href: hash, 'aria-current': hash === currentHash() ? 'page' : null },
        icon(r.iconName), el('span', { text: short ? r.short : r.label }));
}

let sidebar;
let tabbar;
let mobileBar;
let me;

function renderNav() {
    if (!sidebar) return;
    const groups = [...new Set(Object.values(ROUTES).map((r) => r.group))];
    fill(sidebar,
        el('div', { class: 'sidebar-top' },
            el('a', { class: 'brand-link', href: '#/overview', 'aria-label': 'PaisaTrack home' }, brand()),
            bellButton()),
        el('button', { class: 'btn primary wide quick-add', type: 'button', onclick: () => newTransaction() }, icon('plus'), 'Add transaction',
            el('kbd', { text: 'N' })),
        groups.map((g) => el('nav', { class: 'nav-group', 'aria-label': g },
            el('span', { class: 'nav-label', text: g }),
            Object.keys(ROUTES).filter((h) => ROUTES[h].group === g).map((h) => navLink(h)))),
        el('div', { class: 'sidebar-foot' },
            el('button', { class: 'nav-link', type: 'button', onclick: toggleTheme }, icon(isDark() ? 'sun' : 'moon'),
                el('span', { text: isDark() ? 'Light mode' : 'Dark mode' })),
            el('button', { class: 'nav-link', type: 'button', onclick: signOut }, icon('logout'), el('span', { text: 'Sign out' })),
            el('a', { class: 'user-chip', href: '#/settings', title: 'Settings' },
                el('span', { class: 'avatar', text: initials(me.fullName) }),
                el('span', { class: 'who' }, el('b', { text: me.fullName }), el('span', { text: me.email })))));
    const moreActive = !TABS.includes(currentHash());
    fill(tabbar, TABS.map((h) => navLink(h, { short: true })),
        el('button', { class: 'nav-link', type: 'button', 'aria-current': moreActive ? 'page' : null, onclick: openMore },
            icon('more'), el('span', { text: 'More' })));
    fill(mobileBar,
        el('a', { class: 'brand-link', href: '#/overview', 'aria-label': 'PaisaTrack home' }, brand()),
        el('div', { class: 'head-actions' },
            bellButton(),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': isDark() ? 'Light mode' : 'Dark mode', onclick: toggleTheme },
                icon(isDark() ? 'sun' : 'moon'))));
}

/** Phone "More" menu: the pages that don't fit the tab bar, plus account actions. */
function openMore() {
    const close = () => { dialog.close(); dialog.remove(); };
    const go = (hash) => () => { close(); location.hash = hash; };
    const dialog = el('dialog', { class: 'sheet more-sheet', 'aria-label': 'More' },
        el('div', { class: 'sheet-head' },
            el('div', { class: 'user-chip' }, el('span', { class: 'avatar', text: initials(me.fullName) }),
                el('span', { class: 'who' }, el('b', { text: me.fullName }), el('span', { text: me.email }))),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Close', onclick: close }, icon('x'))),
        el('div', { class: 'sheet-body more-list' },
            Object.keys(ROUTES).filter((h) => !TABS.includes(h)).map((h) => el('button', { class: 'nav-link', type: 'button', onclick: go(h) },
                icon(ROUTES[h].iconName), el('span', { text: ROUTES[h].label }))),
            el('button', { class: 'nav-link', type: 'button', onclick: () => { close(); toggleTheme(); } }, icon(isDark() ? 'sun' : 'moon'),
                el('span', { text: isDark() ? 'Light mode' : 'Dark mode' })),
            el('button', { class: 'nav-link danger', type: 'button', onclick: () => { close(); signOut(); } }, icon('logout'), el('span', { text: 'Sign out' }))));
    dialog.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
    dialog.addEventListener('click', (e) => { if (e.target === dialog) close(); });
    document.body.append(dialog);
    dialog.showModal();
}

function renderShell() {
    sidebar = el('aside', { class: 'sidebar', 'aria-label': 'Main navigation' });
    tabbar = el('nav', { class: 'tabbar', 'aria-label': 'Main navigation' });
    mobileBar = el('header', { class: 'mobile-bar' });
    main = el('main', { class: 'main', id: 'main', tabindex: '-1' });
    const fab = el('button', { class: 'fab', type: 'button', 'aria-label': 'Add transaction', onclick: () => newTransaction() }, icon('plus'));

    root.className = '';
    fill(root, el('div', { class: 'shell' }, sidebar, el('div', { class: 'content' }, mobileBar, main)), tabbar, fab);
    renderNav();
}

let routeSeq = 0;

async function route() {
    if (!main) return;
    if (!ROUTES[location.hash]) {
        history.replaceState(null, '', '#/overview');
    }
    renderNav();
    const view = ROUTES[currentHash()];
    document.title = `${view.label} · PaisaTrack`;
    const seq = ++routeSeq;
    main.classList.remove('enter');
    void main.offsetWidth; // restart the page-enter animation
    main.classList.add('enter');
    window.scrollTo({ top: 0 });
    try {
        await view.render(main);
    } catch (e) {
        if (seq === routeSeq) toast(errorText(e), 'error');
    }
    // New bills, EMIs or budgets may have created reminders; check at most once a minute.
    if (Date.now() - lastReminderCheck > 60_000) {
        lastReminderCheck = Date.now();
        refreshNotifications();
    }
}

let lastReminderCheck = 0;

function newTransaction() {
    openTransactionDialog({ onSaved: route }).catch((e) => toast(errorText(e), 'error'));
}

// ---- Session ----

async function start() {
    try {
        me = await store.me();
    } catch {
        showAuth();
        return;
    }
    applyTheme(me.preferences.theme);
    renderShell();
    await route();
}

function showAuth() {
    main = null;
    sidebar = null;
    store.invalidate();
    root.className = '';
    renderAuth(root, start);
}

async function signOut() {
    const refreshToken = session.refresh;
    if (refreshToken) await api.public('/auth/logout', { refreshToken }).catch(() => null);
    session.clear();
    showAuth();
}

whenSignedOut(showAuth);
window.addEventListener('hashchange', route);
window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => renderNav());
// Re-check reminders when the tab comes back after a while.
let hiddenAt = 0;
document.addEventListener('visibilitychange', () => {
    if (document.hidden) hiddenAt = Date.now();
    else if (main && Date.now() - hiddenAt > 10 * 60 * 1000) refreshNotifications();
});
window.addEventListener('keydown', (e) => {
    const typing = e.target.closest?.('input, select, textarea, [contenteditable]') || document.querySelector('dialog[open]');
    if (!typing && main && e.key === 'n' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        e.preventDefault();
        newTransaction();
    }
});

// Theme before we know the account's preference: the one last used on this device.
const remembered = savedTheme();
if (remembered === 'LIGHT' || remembered === 'DARK') document.documentElement.dataset.theme = remembered.toLowerCase();

if (session.access) start(); else showAuth();
