// Notification bell: unread badge, a drawer with reminders, mark read. Reminders are created
// server-side (hourly, and on refresh); refreshing never duplicates them.

import { api, errorText } from './api.js';
import { el, empty, fill, icon, toast } from './dom.js';

const TYPE_ICON = {
    EMI: 'bank', BILL: 'calendar', SUBSCRIPTION: 'repeat', RECURRING: 'repeat',
    LENDING: 'people', BORROWING: 'people', BUDGET: 'target', SALARY: 'income',
};
const bells = new Set();
let unread = 0;

function relative(instant) {
    const minutes = Math.round((Date.now() - new Date(instant).getTime()) / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes} min ago`;
    const hours = Math.round(minutes / 60);
    if (hours < 24) return `${hours} h ago`;
    const days = Math.round(hours / 24);
    return days === 1 ? 'yesterday' : `${days} days ago`;
}

/** Bells replaced by a re-render are no longer on the page. */
function prune() {
    bells.forEach((b) => { if (!b.button.isConnected) bells.delete(b); });
}

function setUnread(n) {
    unread = n;
    prune();
    bells.forEach((b) => b.update(n));
}

/** Checks for due reminders now, then updates every bell. */
export async function refreshNotifications() {
    try {
        const r = await api.post('/notifications/refresh');
        setUnread(Number(r.unread));
    } catch {
        // Reminders are a convenience; never block the app on them.
    }
}

/** A bell button with a live unread badge. */
export function bellButton(extraClass = '') {
    const badge = el('span', { class: 'badge', hidden: unread === 0, text: unread > 9 ? '9+' : String(unread) });
    const button = el('button', { class: `btn ghost icon-only bell ${extraClass}`.trim(), type: 'button', 'aria-label': 'Notifications', onclick: openDrawer },
        icon('bell'), badge);
    const update = (n) => {
        badge.hidden = n === 0;
        badge.textContent = n > 9 ? '9+' : String(n);
        button.setAttribute('aria-label', n ? `Notifications, ${n} unread` : 'Notifications');
    };
    update(unread);
    bells.add({ button, update });
    queueMicrotask(prune); // after the caller has put this bell on the page
    return button;
}

function openDrawer() {
    const body = el('div', { class: 'drawer-body' });
    let onlyUnread = false;
    const filter = el('button', { class: 'btn ghost sm', type: 'button', text: 'Unread only', 'aria-pressed': 'false',
        onclick: () => { onlyUnread = !onlyUnread; filter.setAttribute('aria-pressed', String(onlyUnread)); load(); } });
    const readAll = el('button', { class: 'btn ghost sm', type: 'button', text: 'Mark all read', onclick: async () => {
        try { await api.post('/notifications/read-all'); setUnread(0); load(); } catch (e) { toast(errorText(e), 'error'); }
    } });
    const dialog = el('dialog', { class: 'drawer', 'aria-label': 'Notifications' },
        el('div', { class: 'sheet-head' }, el('h2', { text: 'Notifications' }),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Close', onclick: () => close() }, icon('x'))),
        el('div', { class: 'drawer-tools' }, filter, readAll),
        body);
    const close = () => { dialog.close(); dialog.remove(); };
    dialog.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
    dialog.addEventListener('click', (e) => { if (e.target === dialog) close(); });

    async function load() {
        fill(body, el('div', { class: 'empty' }, el('div', { class: 'spinner' })));
        try {
            const page = await api.get(`/notifications?size=50&unreadOnly=${onlyUnread}`);
            fill(body, page.items.length
                ? el('div', { class: 'notes' }, page.items.map((n) => item(n, close)))
                : empty(onlyUnread ? 'All caught up.' : 'No notifications yet. Reminders for EMIs, bills, budgets and dues appear here.', 'bell'));
        } catch (e) {
            fill(body, empty(errorText(e), 'alert'));
        }
    }

    document.body.append(dialog);
    dialog.showModal();
    load();
}

function item(n, closeDrawer) {
    const tone = n.stage === 'OVERDUE' ? 'bad' : n.stage === 'DUE' || n.stage === 'ALERT' ? 'warn' : '';
    const node = el('button', { class: `note ${n.read ? '' : 'unread'}`.trim(), type: 'button', onclick: async () => {
        if (!n.read) {
            try { await api.post(`/notifications/${n.id}/read`); setUnread(Math.max(0, unread - 1)); } catch { /* still navigate */ }
        }
        closeDrawer();
        if (n.link) location.hash = n.link;
    } },
    el('span', { class: `tile-icon ${tone === 'bad' ? 'expense' : tone === 'warn' ? 'warn' : 'loan'}` }, icon(TYPE_ICON[n.type] ?? 'bell')),
    el('span', { class: 'grow' },
        el('span', { class: 'note-title', text: n.title }),
        el('span', { class: 'note-text', text: n.message }),
        el('span', { class: 'note-time', text: relative(n.createdAt) })),
    n.read ? null : el('span', { class: 'dot', 'aria-label': 'Unread' }));
    return node;
}
