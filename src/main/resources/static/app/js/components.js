// Shared bits of page UI.

import { download, errorText } from './api.js';
import { el, icon, toast } from './dom.js';
import { money, plural } from './format.js';

const TYPE_ICON = { EXPENSE: 'down', INCOME: 'up', TRANSFER: 'swap', LOAN_DISBURSEMENT: 'bank', LOAN_PRINCIPAL: 'bank' };
const LOAN_TITLE = { LOAN_DISBURSEMENT: 'Loan received', LOAN_PRINCIPAL: 'Loan repayment' };

export function txTitle(t) {
    if (t.type === 'TRANSFER') return `${t.account?.name ?? ''} → ${t.toAccount?.name ?? ''}`;
    if (LOAN_TITLE[t.type]) return LOAN_TITLE[t.type];
    if (!t.category) return t.type === 'INCOME' ? 'Income' : 'Expense';
    return t.category.parentName ? `${t.category.parentName} · ${t.category.name}` : t.category.name;
}

/** One transaction. With `onOpen`, the row is clickable (and keyboard-activatable). */
export function txRow(t, actions = null, onOpen = null) {
    const sign = ['EXPENSE', 'LOAN_PRINCIPAL'].includes(t.type) ? '−' : ['INCOME', 'LOAN_DISBURSEMENT'].includes(t.type) ? '+' : '';
    const meta = [t.description || t.counterparty, t.type === 'TRANSFER' ? 'Transfer' : t.account?.name, t.incomeSource?.name]
        .filter(Boolean).join(' · ');
    const open = onOpen ? (e) => {
        if (e.type === 'keydown' && e.key !== 'Enter' && e.key !== ' ') return;
        if (e.target.closest('button, a')) return;
        e.preventDefault();
        onOpen(t);
    } : null;
    return el('div', {
        class: `row ${t.status === 'VOIDED' ? 'voided' : ''} ${onOpen ? 'clickable' : ''}`.trim(),
        role: onOpen ? 'button' : null, tabindex: onOpen ? '0' : null, onclick: open, onkeydown: open,
        'aria-label': onOpen ? `Edit ${txTitle(t)}` : null,
    },
        el('div', { class: `tile-icon ${t.loanId ? 'loan' : t.type.toLowerCase()}` }, icon(TYPE_ICON[t.type])),
        el('div', { class: 'grow' },
            el('div', { class: 'title' }, txTitle(t),
                t.attachmentCount ? el('span', { class: 'clip', title: `${t.attachmentCount} receipt${t.attachmentCount > 1 ? 's' : ''}`,
                    'aria-label': 'Has receipts' }, icon('clip')) : null),
            el('div', { class: 'meta', text: meta || '—' })),
        el('div', {},
            el('div', { class: `amount num ${t.type === 'EXPENSE' ? 'neg' : t.type === 'INCOME' ? 'pos' : ''}`, text: sign + money(t.amount, t.currency) }),
            t.toAmount ? el('div', { class: 'meta num', text: `→ ${money(t.toAmount, t.toCurrency)}` }) : null,
            t.status === 'VOIDED' ? el('div', { class: 'meta', text: 'Deleted' }) : null),
        actions);
}

/** "Export" menu: CSV, Excel or PDF of whatever `pathFor(format)` points at. */
export function exportMenu(pathFor) {
    const menu = el('details', { class: 'menu' });
    const choose = async (format) => {
        menu.open = false;
        try {
            const name = await download(pathFor(format), `paisatrack.${format.toLowerCase()}`);
            toast(`Downloaded ${name}`);
        } catch (e) {
            toast(errorText(e), 'error');
        }
    };
    menu.append(el('summary', { class: 'btn sm' }, icon('download'), 'Export'),
        el('div', { class: 'menu-list', role: 'menu' },
            [['CSV', 'CSV (spreadsheet text)'], ['XLSX', 'Excel workbook'], ['PDF', 'PDF document']].map(([f, label]) =>
                el('button', { type: 'button', class: 'menu-item', role: 'menuitem', text: label, onclick: () => choose(f) }))));
    document.addEventListener('click', (e) => { if (menu.open && !menu.contains(e.target)) menu.open = false; });
    return menu;
}

export function card(title, { sub, action, className = '' } = {}, ...body) {
    return el('section', { class: `card ${className}`.trim() },
        el('div', { class: 'card-head' },
            el('h2', {}, title, sub ? el('span', { class: 'sub', text: sub }) : null),
            action),
        ...body);
}

export function kpi({ label, value, iconName, tone = '', foot = [] }) {
    return el('section', { class: 'card kpi' },
        el('div', { class: 'kpi-top' },
            el('span', { class: 'kpi-label', text: label }),
            el('span', { class: `kpi-icon ${tone}` }, icon(iconName))),
        el('div', { class: 'kpi-value num', text: value }),
        el('div', { class: 'kpi-foot' }, foot.filter(Boolean).map((f) => el('span', { text: f }))));
}

export const entries = (n) => plural(n, 'entry', 'entries');

export function smallButton(label, onClick, iconName = 'plus') {
    return el('button', { class: 'btn sm', type: 'button', onclick: onClick }, icon(iconName), label);
}
