// App entry: auth gate, shell (sidebar / mobile tab bar), hash routing, theme and shortcuts.

import { api, errorText, session, whenSignedOut } from './api.js';
import { renderAuth } from './auth.js';
import { el, fill, icon, toast } from './dom.js';
import { openTransactionDialog } from './forms.js';
import { initials } from './format.js';
import { store } from './store.js';
import { renderDebts } from './views/debts.js';
import { renderLoans } from './views/loans.js';
import { renderOverview } from './views/overview.js';
import { renderTransactions } from './views/transactions.js';

const ROUTES = {
    '#/overview': { label: 'Overview', iconName: 'home', render: renderOverview },
    '#/transactions': { label: 'Transactions', iconName: 'list', render: renderTransactions },
    '#/loans': { label: 'Loans', iconName: 'bank', render: renderLoans },
    '#/debts': { label: 'Lending', iconName: 'people', render: renderDebts },
};

const root = document.getElementById('root');
let main;

// ---- Theme ----

function applyTheme(theme) {
    if (theme === 'LIGHT' || theme === 'DARK') document.documentElement.dataset.theme = theme.toLowerCase();
    else delete document.documentElement.dataset.theme;
}

function isDark() {
    const forced = document.documentElement.dataset.theme;
    return forced ? forced === 'dark' : window.matchMedia('(prefers-color-scheme: dark)').matches;
}

async function toggleTheme() {
    const next = isDark() ? 'LIGHT' : 'DARK';
    applyTheme(next);
    renderNav();
    await api.patch('/users/me/preferences', { theme: next }).catch(() => null);
    store.invalidate('me');
}

// ---- Shell ----

function navLinks() {
    const current = ROUTES[location.hash] ? location.hash : '#/overview';
    return Object.entries(ROUTES).map(([hash, r]) => el('a', {
        class: 'nav-link', href: hash, 'aria-current': hash === current ? 'page' : null,
    }, icon(r.iconName), r.label));
}

let sidebar;
let tabbar;
let me;

function renderNav() {
    if (!sidebar) return;
    const themeLabel = isDark() ? 'Light mode' : 'Dark mode';
    fill(sidebar,
        el('a', { class: 'brand', href: '#/overview' }, el('span', { class: 'brand-mark', text: '₹' }), 'PaisaTrack'),
        navLinks(),
        el('div', { class: 'sidebar-foot' },
            el('button', { class: 'nav-link', type: 'button', onclick: toggleTheme }, icon(isDark() ? 'sun' : 'moon'), themeLabel),
            el('button', { class: 'nav-link', type: 'button', onclick: signOut }, icon('logout'), 'Sign out'),
            el('div', { class: 'user-chip' },
                el('span', { class: 'avatar', text: initials(me.fullName) }),
                el('span', { class: 'who' }, el('b', { text: me.fullName }), el('span', { text: me.email })))));
    fill(tabbar, navLinks());
}

function renderShell() {
    sidebar = el('aside', { class: 'sidebar', 'aria-label': 'Main navigation' });
    tabbar = el('nav', { class: 'tabbar', 'aria-label': 'Main navigation' });
    main = el('main', { class: 'main', id: 'main', tabindex: '-1' });
    const mobileBar = el('header', { class: 'mobile-bar' },
        el('a', { class: 'brand', href: '#/overview' }, el('span', { class: 'brand-mark', text: '₹' }), 'PaisaTrack'),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Toggle theme', onclick: toggleTheme }, icon('moon')),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Sign out', onclick: signOut }, icon('logout'))));
    const fab = el('button', { class: 'fab', type: 'button', 'aria-label': 'Add transaction', onclick: () => newTransaction() }, icon('plus'));

    root.className = '';
    fill(root, el('div', { class: 'shell' }, sidebar, el('div', {}, mobileBar, main)), tabbar, fab);
    renderNav();
}

async function route() {
    if (!main) return;
    if (!ROUTES[location.hash]) {
        history.replaceState(null, '', '#/overview');
    }
    renderNav();
    const view = ROUTES[location.hash] ?? ROUTES['#/overview'];
    document.title = `${view.label} · PaisaTrack`;
    try {
        await view.render(main);
    } catch (e) {
        toast(errorText(e), 'error');
    }
}

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
window.addEventListener('keydown', (e) => {
    const typing = e.target.closest?.('input, select, textarea, [contenteditable]') || document.querySelector('dialog[open]');
    if (!typing && main && e.key === 'n' && !e.ctrlKey && !e.metaKey && !e.altKey) {
        e.preventDefault();
        newTransaction();
    }
});

if (session.access) start(); else showAuth();
