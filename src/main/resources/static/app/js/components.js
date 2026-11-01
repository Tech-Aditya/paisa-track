// Shared bits of page UI.

import { el, icon } from './dom.js';
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
            el('div', { class: 'title', text: txTitle(t) }),
            el('div', { class: 'meta', text: meta || '—' })),
        el('div', {},
            el('div', { class: `amount num ${t.type === 'EXPENSE' ? 'neg' : t.type === 'INCOME' ? 'pos' : ''}`, text: sign + money(t.amount, t.currency) }),
            t.toAmount ? el('div', { class: 'meta num', text: `→ ${money(t.toAmount, t.toCurrency)}` }) : null,
            t.status === 'VOIDED' ? el('div', { class: 'meta', text: 'Deleted' }) : null),
        actions);
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
