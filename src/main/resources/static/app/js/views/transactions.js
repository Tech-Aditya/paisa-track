// Transactions: search, filter, page through history; delete (void) and restore.

import { api, errorText } from '../api.js';
import { txRow } from '../components.js';
import { el, empty, fill, icon, input, select, skeleton, toast } from '../dom.js';
import { openTransactionDialog } from '../forms.js';
import { dayHeading, money } from '../format.js';
import { store } from '../store.js';

const PAGE_SIZE = 25;
const filters = { q: '', type: '', categoryId: '', accountId: '', from: '', to: '', includeVoided: false, page: 0 };
let searchTimer;

export async function renderTransactions(root) {
    const [accounts, expenseCats, incomeCats] = await Promise.all([
        store.accounts(), store.expenseCategories(), store.incomeCategories(),
    ]);
    const list = el('div', {});
    const reload = () => loadPage(list);

    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Transactions' }), el('p', { text: 'Every expense, income and transfer you have recorded.' })),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn primary desktop-only', type: 'button', onclick: () => openTransactionDialog({ onSaved: reload }) },
                icon('plus'), 'Add transaction')));

    const set = (key) => (e) => {
        filters[key] = e.target.type === 'checkbox' ? e.target.checked : e.target.value;
        filters.page = 0;
        reload();
    };
    const topLevel = (tree, label) => ({ group: label, options: tree.map((c) => ({ value: c.id, label: c.name, selected: c.id === filters.categoryId })) });

    const bar = el('div', { class: 'card filters' },
        el('div', { class: 'search' }, icon('search'),
            input('q', { type: 'search', placeholder: 'Search description or merchant', value: filters.q, 'aria-label': 'Search',
                oninput: (e) => {
                    clearTimeout(searchTimer);
                    searchTimer = setTimeout(() => { filters.q = e.target.value; filters.page = 0; reload(); }, 300);
                } })),
        el('label', { class: 'field' }, el('span', { text: 'Type' }), select('type', [
            { value: '', label: 'All types' }, { value: 'EXPENSE', label: 'Expenses' },
            { value: 'INCOME', label: 'Income' }, { value: 'TRANSFER', label: 'Transfers' },
            { value: 'LOAN_PRINCIPAL', label: 'Loan repayments' }, { value: 'LOAN_DISBURSEMENT', label: 'Loans received' },
        ].map((o) => ({ ...o, selected: o.value === filters.type })), { onchange: set('type') })),
        el('label', { class: 'field' }, el('span', { text: 'Category' }), select('categoryId', [
            { value: '', label: 'All categories' }, topLevel(expenseCats, 'Expense'), topLevel(incomeCats, 'Income'),
        ], { onchange: set('categoryId') })),
        el('label', { class: 'field' }, el('span', { text: 'Account' }), select('accountId', [
            { value: '', label: 'All accounts' }, ...accounts.map((a) => ({ value: a.id, label: a.name, selected: a.id === filters.accountId })),
        ], { onchange: set('accountId') })),
        el('label', { class: 'field' }, el('span', { text: 'From' }), input('from', { type: 'date', value: filters.from, onchange: set('from') })),
        el('label', { class: 'field' }, el('span', { text: 'To' }), input('to', { type: 'date', value: filters.to, onchange: set('to') })),
        el('label', { class: 'btn ghost sm' },
            el('input', { type: 'checkbox', checked: filters.includeVoided, onchange: set('includeVoided') }), 'Show deleted'));

    fill(root, head, bar, el('section', { class: 'card' }, list));
    await loadPage(list);
}

async function loadPage(list) {
    fill(list, [1, 2, 3, 4, 5].map(() => el('div', { class: 'row' }, skeleton(36, '36px'), el('div', { class: 'grow' }, skeleton(14, '50%')), skeleton(14, '80px'))));
    const params = new URLSearchParams({ page: filters.page, size: PAGE_SIZE });
    for (const key of ['q', 'type', 'categoryId', 'accountId', 'from', 'to']) {
        if (filters[key]) params.set(key, filters[key]);
    }
    if (filters.includeVoided) params.set('includeVoided', 'true');

    let page;
    try {
        page = await api.get(`/transactions?${params}`);
    } catch (e) {
        fill(list, empty(errorText(e), 'alert'));
        return;
    }
    if (!page.items.length) {
        fill(list, empty(filters.q || filters.type || filters.categoryId || filters.accountId || filters.from || filters.to
            ? 'No transactions match these filters.' : 'No transactions yet.', 'inbox'));
        return;
    }

    const reload = () => loadPage(list);
    const groups = [];
    for (const t of page.items) {
        const last = groups.at(-1);
        if (last?.date === t.transactionDate) last.items.push(t);
        else groups.push({ date: t.transactionDate, items: [t] });
    }
    const first = page.page * page.size + 1;
    const lastIndex = first + page.items.length - 1;

    fill(list,
        groups.map((g) => {
            const spent = g.items.filter((t) => t.type === 'EXPENSE' && t.status === 'ACTIVE');
            const currency = spent[0]?.currency;
            const daySpend = spent.filter((t) => t.currency === currency).reduce((s, t) => s + Number(t.amount), 0);
            return el('div', {},
                el('div', { class: 'day-head' }, el('span', { text: dayHeading(g.date) }),
                    spent.length ? el('span', { class: 'num', text: `Spent ${money(daySpend, currency)}` }) : null),
                el('div', { class: 'rows' }, g.items.map((t) => txRow(t, rowAction(t, reload)))));
        }),
        el('div', { class: 'pager' },
            el('span', { text: `Showing ${first}–${lastIndex} of ${page.totalItems}` }),
            el('div', { class: 'head-actions' },
                el('button', { class: 'btn sm', type: 'button', disabled: page.page === 0, 'aria-label': 'Previous page',
                    onclick: () => { filters.page -= 1; reload(); } }, icon('left')),
                el('button', { class: 'btn sm', type: 'button', disabled: page.page + 1 >= page.totalPages, 'aria-label': 'Next page',
                    onclick: () => { filters.page += 1; reload(); } }, icon('right')))));
}

function rowAction(t, reload) {
    if (t.loanId) {
        // Loan entries change only through the loan (pay, prepay, void).
        return el('a', { class: 'btn ghost sm icon-only', href: '#/loans', title: 'Managed by a loan', 'aria-label': 'Open loans' }, icon('bank'));
    }
    const voided = t.status === 'VOIDED';
    return el('button', {
        class: `btn ghost sm icon-only ${voided ? '' : 'danger'}`, type: 'button',
        title: voided ? 'Restore' : 'Delete', 'aria-label': voided ? 'Restore transaction' : 'Delete transaction',
        onclick: async () => {
            if (!voided && !window.confirm('Delete this transaction? It will be removed from balances and totals, and can be restored later.')) return;
            try {
                await api.post(`/transactions/${t.id}/${voided ? 'restore' : 'void'}`);
                store.invalidate('accounts');
                toast(voided ? 'Transaction restored' : 'Transaction deleted');
                reload();
            } catch (e) {
                toast(errorText(e), 'error');
            }
        },
    }, icon(voided ? 'undo' : 'trash'));
}
