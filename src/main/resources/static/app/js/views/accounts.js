// Accounts and income sources: balances, details with history, edit, default, deactivate;
// salary and other income with expected amounts, month-by-month history and recording.

import { api, errorText } from '../api.js';
import { card, kpi, txRow } from '../components.js';
import { confirmDialog, el, empty, field, fill, icon, input, openPanel, openSheet, segmented, select, skeleton, toast } from '../dom.js';
import { openAccountDialog, openIncomeSourceDialog, openTransactionDialog, recordExpectedIncome } from '../forms.js';
import { isoDate, money, parseAmount, plural, shortDate, titleCase } from '../format.js';
import { accountOptions, categoryOptions, store } from '../store.js';

const ACCOUNT_ICON = { CASH: 'cash', SAVINGS: 'bank', CURRENT: 'bank', CREDIT_CARD: 'card', WALLET: 'wallet', INVESTMENT: 'trend', OTHER: 'wallet' };
const SOURCE_TYPES = ['SALARY', 'FREELANCE', 'BUSINESS', 'RENTAL', 'INTEREST', 'BONUS', 'GIFT', 'OTHER'];
const INCOME_STATUS = {
    RECEIVED: ['Received', 'ok'], PARTIALLY_RECEIVED: ['Part received', 'warn'], PENDING: ['Pending', ''],
    OVERDUE: ['Overdue', 'bad'], NOT_EXPECTED: ['Not expected', ''],
};
const monthName = (ym) => new Date(`${ym}-01T00:00:00`).toLocaleDateString('en-IN', { month: 'short', year: 'numeric' });

let showInactive = false;

export async function renderAccounts(root) {
    const reload = () => renderAccounts(root);
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Accounts' }), el('p', { text: 'Where your money is, and where it comes from.' })),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn', type: 'button', onclick: () => openIncomeSourceDialog({ onSaved: reload }).catch(fail) }, icon('income'), 'Income source'),
            el('button', { class: 'btn primary', type: 'button', onclick: () => openAccountDialog({ onSaved: reload }).catch(fail) }, icon('plus'), 'Add account')));
    const stats = el('div', { class: 'kpis kpis-3' }, [1, 2, 3].map(() => el('section', { class: 'card' }, skeleton(14, '40%'), skeleton(28))));
    const accountList = el('div', { class: 'rows' }, skeleton(40));
    const sourceList = el('div', { class: 'rows' }, skeleton(40));
    fill(root, head, stats, el('div', { class: 'grid' },
        el('div', { class: 'col-6' }, card('Accounts', {
            action: segmented([{ value: false, label: 'Active' }, { value: true, label: 'All' }], showInactive,
                (v) => { showInactive = v; reload(); }, 'Show') }, accountList)),
        el('div', { class: 'col-6' }, card('Income sources', { sub: 'this month' }, sourceList))));

    try {
        const [me, accounts, summary, sources] = await Promise.all([
            store.me(), api.get(`/accounts?includeInactive=${showInactive}`), api.get('/accounts/summary'), api.get('/income-sources?includeInactive=true'),
        ]);
        const c = me.preferences.defaultCurrency;
        const t = summary.currencies.find((x) => x.currency === c) ?? { totalAssets: 0, totalLiabilities: 0, loanOutstanding: 0, netWorth: 0, accountCount: 0 };
        const others = summary.currencies.filter((x) => x.currency !== c);
        fill(stats,
            kpi({ label: 'Assets', value: money(t.totalAssets, c), iconName: 'wallet', tone: 'pos', foot: [plural(t.accountCount, 'account')] }),
            kpi({ label: 'Card dues and loans', value: money(Number(t.totalLiabilities) + Number(t.loanOutstanding), c), iconName: 'card', tone: 'neg',
                foot: [`Cards ${money(t.totalLiabilities, c)}`, Number(t.loanOutstanding) ? `Loans ${money(t.loanOutstanding, c)}` : null] }),
            kpi({ label: `Net worth (${c})`, value: money(t.netWorth, c), iconName: 'trend', tone: 'accent',
                foot: others.length ? others.map((x) => `${x.currency} ${money(x.netWorth, x.currency)}`) : ['All accounts in one currency'] }));
        fill(accountList, accounts.length
            ? accounts.map((a) => accountRow(a, () => openAccountDetail(a, reload)))
            : empty('No accounts yet.', 'wallet'));
        fill(sourceList, sources.length
            ? sources.map((s) => sourceRow(s, () => openSourceDetail(s, reload)))
            : empty('Add your salary or other regular income to see when it is due and whether it arrived.', 'income',
                el('button', { class: 'btn sm', type: 'button', onclick: () => openIncomeSourceDialog({ onSaved: reload }).catch(fail) }, icon('plus'), 'Add income source')));
    } catch (e) {
        fill(accountList, empty(errorText(e), 'alert'));
    }
}

function fail(e) {
    toast(errorText(e), 'error');
}

function accountRow(a, onOpen) {
    const meta = [titleCase(a.type), a.institution, a.currency, a.defaultAccount ? 'Default' : null].filter(Boolean).join(' · ');
    return el('button', { class: `row debt-row ${a.active ? '' : 'muted-row'}`.trim(), type: 'button', onclick: onOpen },
        el('div', { class: `tile-icon ${a.nature === 'LIABILITY' ? 'expense' : 'loan'}` }, icon(ACCOUNT_ICON[a.type] ?? 'wallet')),
        el('div', { class: 'grow' }, el('div', { class: 'title', text: a.name }), el('div', { class: 'meta', text: meta })),
        el('div', { class: 'debt-amount' },
            el('div', { class: `amount num ${a.nature === 'LIABILITY' ? 'neg' : ''}`, text: money(a.currentBalance, a.currency) }),
            a.active ? (a.availableCredit !== null && a.availableCredit !== undefined
                ? el('span', { class: 'meta', text: `${money(a.availableCredit, a.currency)} available` }) : null)
                : el('span', { class: 'chip', text: 'Inactive' })));
}

function fact(label, value, strong = false) {
    return el('div', { class: 'fact' }, el('span', { class: 'faint', text: label }), el(strong ? 'b' : 'span', { class: 'num', text: value }));
}

function openAccountDetail(row, onChanged) {
    let changed = false;
    openPanel({
        title: row.name,
        onClose: () => { if (changed) onChanged(); },
        content: async ({ render, setTitle }) => {
            let a;
            let page;
            try {
                [a, page] = await Promise.all([api.get(`/accounts/${row.id}`), api.get(`/accounts/${row.id}/transactions?size=20`)]);
            } catch (e) {
                return empty(errorText(e), 'alert');
            }
            const c = a.currency;
            setTitle(a.name);
            const after = (msg) => { changed = true; store.invalidate('accounts'); toast(msg); render(); };
            const act = (path, msg) => async () => {
                try { await api.post(`/accounts/${a.id}/${path}`); after(msg); } catch (e) { fail(e); }
            };
            const liability = a.nature === 'LIABILITY';
            return [
                el('div', { class: 'card-head' },
                    el('div', {}, el('div', { class: 'title', text: [titleCase(a.type), a.institution].filter(Boolean).join(' · ') }),
                        el('div', { class: 'meta', text: `${c}${a.defaultAccount ? ' · default account' : ''}` })),
                    el('span', { class: `chip ${a.active ? 'ok' : ''}`, text: a.active ? 'Active' : 'Inactive' })),
                el('div', { class: 'facts' },
                    fact(liability ? 'Amount owed' : 'Balance', money(a.currentBalance, c), true),
                    fact(liability ? 'Owed when added' : 'Opening balance', money(a.openingBalance, c)),
                    a.creditLimit ? fact('Credit limit', money(a.creditLimit, c)) : null,
                    a.availableCredit !== null && a.availableCredit !== undefined ? fact('Available', money(a.availableCredit, c)) : null,
                    fact('Since', shortDate(a.createdAt.slice(0, 10)))),
                a.notes ? el('p', { class: 'muted', text: a.notes }) : null,
                el('div', { class: 'head-actions' },
                    el('button', { class: 'btn', type: 'button', onclick: () => openAccountDialog({ existing: a, onSaved: () => after('Account saved') }).catch(fail) },
                        icon('edit'), 'Edit'),
                    a.active ? el('button', { class: 'btn', type: 'button',
                        onclick: () => openTransactionDialog({ type: liability ? 'TRANSFER' : 'EXPENSE', preset: liability ? { toAccountId: a.id } : { accountId: a.id },
                            onSaved: () => after('Saved') }).catch(fail) }, icon('plus'), liability ? 'Pay bill' : 'Add expense') : null,
                    a.active && !a.defaultAccount ? el('button', { class: 'btn ghost', type: 'button', onclick: act('make-default', 'Default account set') },
                        icon('check'), 'Make default') : null,
                    a.active && !a.defaultAccount ? el('button', { class: 'btn ghost', type: 'button', onclick: async () => {
                        if (!await confirmDialog({ title: `Deactivate ${a.name}?`, confirmLabel: 'Deactivate',
                            message: 'It stops appearing when you add transactions. Its history and balance stay, and you can activate it again.' })) return;
                        await act('deactivate', 'Account deactivated')();
                    } }, icon('pause'), 'Deactivate') : null,
                    !a.active ? el('button', { class: 'btn primary', type: 'button', onclick: act('activate', 'Account activated') }, icon('play'), 'Activate') : null),
                a.defaultAccount ? el('p', { class: 'faint small-note', text: 'This is your default account: new entries use it unless you pick another.' }) : null,
                el('h3', { class: 'section-title', text: `Recent activity${page.totalItems > page.items.length ? ` · ${page.items.length} of ${page.totalItems}` : ''}` }),
                page.items.length ? el('div', { class: 'rows' }, page.items.map((t) => txRow(t))) : el('p', { class: 'faint', text: 'No transactions yet.' }),
            ];
        },
    });
}

function sourceRow(s, onOpen) {
    const m = s.thisMonth;
    const [label, tone] = s.active ? (INCOME_STATUS[m?.status] ?? ['—', '']) : ['Paused', ''];
    const expected = s.currentExpectation?.amount
        ? `${money(s.currentExpectation.amount, s.currency)} on day ${s.currentExpectation.expectedDay}` : 'No fixed amount';
    return el('button', { class: `row debt-row ${s.active ? '' : 'muted-row'}`.trim(), type: 'button', onclick: onOpen },
        el('div', { class: 'tile-icon income' }, icon('income')),
        el('div', { class: 'grow' }, el('div', { class: 'title', text: s.name }),
            el('div', { class: 'meta', text: [titleCase(s.type), expected].join(' · ') })),
        el('div', { class: 'debt-amount' },
            el('div', { class: 'amount num pos', text: money(m?.receivedAmount ?? 0, s.currency) }),
            el('span', { class: `chip ${tone}`, text: label })));
}

function openSourceDetail(row, onChanged) {
    let changed = false;
    openPanel({
        title: row.name,
        onClose: () => { if (changed) onChanged(); },
        content: async ({ render, setTitle }) => {
            let s;
            let history;
            try {
                [s, history] = await Promise.all([api.get(`/income-sources/${row.id}`), api.get(`/income-sources/${row.id}/history`)]);
            } catch (e) {
                return empty(errorText(e), 'alert');
            }
            const c = s.currency;
            setTitle(s.name);
            const after = (msg) => { changed = true; store.invalidate('sources'); toast(msg); render(); };
            const m = s.thisMonth;
            const open = s.active && m && ['PENDING', 'OVERDUE', 'PARTIALLY_RECEIVED'].includes(m.status);
            return [
                el('div', { class: 'card-head' },
                    el('div', {}, el('div', { class: 'title', text: titleCase(s.type) }),
                        el('div', { class: 'meta', text: [s.account?.name, s.category?.name].filter(Boolean).join(' · ') })),
                    el('span', { class: `chip ${s.active ? 'ok' : ''}`, text: s.active ? 'Active' : 'Paused' })),
                el('div', { class: 'facts' },
                    fact('Expected', s.currentExpectation?.amount ? money(s.currentExpectation.amount, c) : 'Not fixed', true),
                    fact('Usually on', s.currentExpectation?.expectedDay ? `Day ${s.currentExpectation.expectedDay}` : '—'),
                    fact('This month', m ? money(m.receivedAmount, c) : '—')),
                el('div', { class: 'head-actions' },
                    open ? el('button', { class: 'btn primary', type: 'button', onclick: () => recordExpectedIncome(m, isoDate(new Date()), () => after('Income recorded')) },
                        icon('plus'), 'Record this month') : null,
                    s.active ? el('button', { class: 'btn', type: 'button', onclick: () => openExpectationDialog(s, () => after('Expected amount updated')) },
                        icon('target'), 'Change expected') : null,
                    el('button', { class: 'btn', type: 'button', onclick: () => openSourceEdit(s, () => after('Saved')).catch(fail) }, icon('edit'), 'Edit'),
                    el('button', { class: 'btn ghost', type: 'button', onclick: async () => {
                        try { await api.post(`/income-sources/${s.id}/${s.active ? 'deactivate' : 'activate'}`); after(s.active ? 'Paused' : 'Resumed'); } catch (e) { fail(e); }
                    } }, icon(s.active ? 'pause' : 'play'), s.active ? 'Pause' : 'Resume')),
                el('h3', { class: 'section-title', text: 'Month by month' }),
                history.length
                    ? el('div', { class: 'table-wrap' }, el('table', { class: 'schedule report-table' },
                        el('thead', {}, el('tr', {}, ['Month', 'Expected', 'Received', ''].map((h) => el('th', { text: h })))),
                        el('tbody', {}, history.slice().reverse().map((h) => {
                            const [label, tone] = INCOME_STATUS[h.status] ?? [h.status, ''];
                            return el('tr', {},
                                el('td', { text: monthName(h.month) }),
                                el('td', { class: 'num', text: h.expectedAmount ? money(h.expectedAmount, c) : '—' }),
                                el('td', { class: 'num', text: money(h.receivedAmount, c) }),
                                el('td', {}, el('span', { class: `chip ${tone}`, text: label })));
                        }))))
                    : el('p', { class: 'faint', text: 'No history yet.' }),
            ];
        },
    });
}

function openExpectationDialog(s, onSaved) {
    const now = isoDate(new Date()).slice(0, 7);
    openSheet({
        title: `Expected from ${s.name}`,
        submitLabel: 'Save',
        fields: () => [
            el('div', { class: 'form-grid' },
                field('Amount per month', input('amount', { class: 'input', inputmode: 'decimal', value: s.currentExpectation?.amount ?? '',
                    placeholder: 'Empty: nothing expected' })),
                field('Usually arrives on day', input('expectedDay', { type: 'number', min: 1, max: 31, value: s.currentExpectation?.expectedDay ?? 1 })),
                field('From month', input('effectiveFrom', { type: 'month', value: now }))),
            el('p', { class: 'faint', text: 'Earlier months keep their old expected amount, so a raise never rewrites history.' }),
        ],
        submit: async (form) => {
            const amount = parseAmount(form.amount.value);
            await api.put(`/income-sources/${s.id}/expectation`, {
                amount, expectedDay: amount ? Number(form.expectedDay.value || 1) : null, effectiveFrom: form.effectiveFrom.value || null,
            });
            onSaved();
        },
    });
}

async function openSourceEdit(s, onSaved) {
    const [accounts, cats] = await Promise.all([store.accounts(), store.incomeCategories()]);
    openSheet({
        title: `Edit ${s.name}`,
        submitLabel: 'Save changes',
        fields: () => [
            el('div', { class: 'form-grid' },
                field('Name', input('name', { value: s.name, maxlength: 60, required: true })),
                field('Type', select('type', SOURCE_TYPES.map((t) => ({ value: t, label: titleCase(t), selected: t === s.type })))),
                field('Credited to', select('accountId', accountOptions(accounts, s.account?.id))),
                field('Category', select('categoryId', categoryOptions(cats, s.category?.id)))),
            field('Notes', input('notes', { value: s.notes ?? '', maxlength: 500, placeholder: 'Optional' })),
        ],
        submit: async (form) => {
            await api.patch(`/income-sources/${s.id}`, {
                name: form.name.value.trim(), type: form.type.value, accountId: form.accountId.value || null,
                categoryId: form.categoryId.value || null, notes: form.notes.value.trim(),
            });
            onSaved();
        },
    });
}
