// Bills & recurring: rent, bills, subscriptions and SIPs. Occurrences are plans until confirmed;
// confirming records the real transaction, skipping says it didn't happen this time.

import { api, errorText } from '../api.js';
import { kpi } from '../components.js';
import { confirmDialog, el, empty, field, fill, icon, input, openPanel, openSheet, segmented, select, skeleton, toast } from '../dom.js';
import { isoDate, money, parseAmount, plural, shortDate, titleCase } from '../format.js';
import { accountOptions, categoryOptions, store } from '../store.js';

const KINDS = ['RENT', 'BILL', 'SUBSCRIPTION', 'INSURANCE', 'INVESTMENT', 'OTHER'];
const KIND_ICON = { RENT: 'home', BILL: 'calendar', SUBSCRIPTION: 'repeat', INSURANCE: 'target', INVESTMENT: 'trend', OTHER: 'repeat' };
const FREQUENCIES = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'YEARLY'];
const UNIT = { DAILY: ['day', 'days'], WEEKLY: ['week', 'weeks'], MONTHLY: ['month', 'months'], QUARTERLY: ['quarter', 'quarters'], YEARLY: ['year', 'years'] };
const OCCURRENCE = {
    UPCOMING: ['Upcoming', ''], DUE: ['Due today', 'warn'], OVERDUE: ['Overdue', 'bad'], CONFIRMED: ['Done', 'ok'], SKIPPED: ['Skipped', ''],
};
const longDate = (iso) => new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

let show = 'ACTIVE';

export function frequencyText(r) {
    const [one, many] = UNIT[r.frequency];
    return r.interval === 1 ? `Every ${one}` : `Every ${r.interval} ${many}`;
}

function signed(o) {
    const sign = o.type === 'INCOME' ? '+' : o.type === 'EXPENSE' ? '−' : '';
    return sign + money(o.actualAmount ?? o.expectedAmount, o.currency);
}

export async function renderRecurring(root) {
    const reload = () => renderRecurring(root);
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Bills & recurring' }),
            el('p', { text: 'Rent, bills, subscriptions and SIPs. Nothing reaches your balances until you confirm it.' })),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn primary', type: 'button', onclick: () => openRuleDialog({ onSaved: reload }).catch((e) => toast(errorText(e), 'error')) },
                icon('plus'), 'Add recurring')));
    const stats = el('div', { class: 'kpis kpis-3' }, [1, 2, 3].map(() => el('section', { class: 'card' }, skeleton(14, '40%'), skeleton(28))));
    const due = el('section', { class: 'card' }, el('div', { class: 'card-head' }, el('h2', { text: 'To confirm' })), skeleton(40));
    const list = el('div', { class: 'rows' }, [1, 2].map(() => el('div', { class: 'row' }, skeleton(36, '36px'), el('div', { class: 'grow' }, skeleton(14, '50%')))));
    const rules = el('section', { class: 'card' },
        el('div', { class: 'card-head' }, el('h2', { text: 'Your recurring items' }),
            segmented([{ value: 'ACTIVE', label: 'Current' }, { value: 'ALL', label: 'All' }], show, (v) => { show = v; reload(); }, 'Show')),
        list);
    fill(root, head, stats, el('div', { class: 'stack' }, due, rules));

    try {
        const [me, summary, items] = await Promise.all([
            store.me(), api.get('/recurring-transactions/summary?days=30'), api.get(`/recurring-transactions?includeStopped=${show === 'ALL'}`),
        ]);
        const c = me.preferences.defaultCurrency;
        const t = summary.currencies.find((x) => x.currency === c) ?? { expectedOutflow: 0, expectedInflow: 0, overdueCount: 0, dueCount: 0 };
        fill(stats,
            kpi({ label: 'To pay · 30 days', value: money(t.expectedOutflow, c), iconName: 'down', tone: 'neg',
                foot: [plural(summary.expected.filter((o) => o.type !== 'INCOME' && o.currency === c).length, 'payment')] }),
            kpi({ label: 'Expected in · 30 days', value: money(t.expectedInflow, c), iconName: 'up', tone: 'pos', foot: ['Plans, not income yet'] }),
            kpi({ label: 'Overdue', value: String(t.overdueCount), iconName: 'clock', tone: t.overdueCount ? 'neg' : '',
                foot: [t.overdueCount ? 'Confirm or skip them' : 'Nothing overdue'] }));
        const byRule = new Map(items.map((r) => [r.id, r]));
        fill(due, el('div', { class: 'card-head' }, el('h2', {}, 'To confirm', el('span', { class: 'sub', text: 'overdue and next 30 days' }))),
            summary.expected.length
                ? el('div', { class: 'rows' }, summary.expected.map((o) => occurrenceRow(o, byRule.get(o.ruleId), reload)))
                : empty('Nothing to confirm right now.', 'check'));
        fill(list, items.length
            ? items.map((r) => ruleRow(r, () => openRuleDetail(r, reload)))
            : empty('Add rent, bills or subscriptions to get reminded and confirm them in one tap.', 'repeat',
                el('button', { class: 'btn primary', type: 'button', onclick: () => openRuleDialog({ onSaved: reload }) }, icon('plus'), 'Add recurring')));
    } catch (e) {
        fill(list, empty(errorText(e), 'alert'));
    }
}

/** An expected occurrence with Confirm / Skip. Used on this page and the overview. */
export function occurrenceRow(o, rule, onChanged) {
    const [label, tone] = OCCURRENCE[o.status] ?? [o.status, ''];
    const when = [o.status === 'OVERDUE' ? `Was due ${shortDate(o.dueDate)}` : shortDate(o.dueDate),
        o.type === 'TRANSFER' ? 'Transfer' : o.type === 'INCOME' ? 'Income' : titleCase(o.kind)].join(' · ');
    return el('div', { class: 'row' },
        el('div', { class: `tile-icon ${o.status === 'OVERDUE' ? 'expense' : o.type === 'INCOME' ? 'income' : 'loan'}` }, icon(KIND_ICON[o.kind] ?? 'repeat')),
        el('div', { class: 'grow' },
            el('div', { class: 'title', text: o.ruleName }),
            el('div', { class: `meta ${o.status === 'OVERDUE' ? 'neg' : ''}`.trim(), text: when })),
        el('div', { class: 'debt-amount' },
            el('div', { class: `amount num ${o.type === 'INCOME' ? 'pos' : ''}`, text: signed(o) }),
            el('span', { class: `chip ${tone}`, text: label })),
        el('div', { class: 'row-actions' },
            el('button', { class: 'btn sm primary', type: 'button', title: 'Record it',
                onclick: () => openConfirmDialog(o, rule, () => { toast(`${o.ruleName} recorded`); onChanged?.(); }).catch((e) => toast(errorText(e), 'error')) },
            icon('check'), el('span', { class: 'label', text: o.type === 'INCOME' ? 'Received' : 'Paid' })),
            el('button', { class: 'btn sm ghost icon-only', type: 'button', title: "Skip: didn't happen this time", 'aria-label': `Skip ${o.ruleName}`,
                onclick: async () => {
                    try {
                        await api.post(`/recurring-occurrences/${o.id}/skip`, {});
                        toast('Skipped');
                        onChanged?.();
                    } catch (e) { toast(errorText(e), 'error'); }
                } }, icon('skip'))));
}

function ruleRow(r, onOpen) {
    const next = r.nextOccurrence;
    const status = r.status === 'ACTIVE' ? (r.overdueCount ? [plural(r.overdueCount, 'overdue'), 'bad'] : [titleCase(r.kind), ''])
        : r.status === 'PAUSED' ? ['Paused', 'warn'] : ['Stopped', ''];
    const meta = [frequencyText(r), next ? `next ${shortDate(next.dueDate)}` : r.status === 'ACTIVE' ? 'no upcoming dates' : null,
        r.type === 'TRANSFER' ? `${r.account?.name} → ${r.toAccount?.name}` : r.account?.name].filter(Boolean).join(' · ');
    return el('button', { class: 'row debt-row', type: 'button', onclick: onOpen },
        el('div', { class: `tile-icon ${r.type === 'INCOME' ? 'income' : 'loan'}` }, icon(KIND_ICON[r.kind] ?? 'repeat')),
        el('div', { class: 'grow' }, el('div', { class: 'title', text: r.name }), el('div', { class: 'meta', text: meta })),
        el('div', { class: 'debt-amount' },
            el('div', { class: `amount num ${r.type === 'INCOME' ? 'pos' : ''}`, text: money(r.amount, r.currency) }),
            el('span', { class: `chip ${status[1]}`, text: status[0] })));
}

function openRuleDetail(row, onChanged) {
    let changed = false;
    openPanel({
        title: row.name,
        onClose: () => { if (changed) onChanged?.(); },
        content: async ({ render, close }) => {
            let r;
            let occurrences;
            try {
                [r, occurrences] = await Promise.all([api.get(`/recurring-transactions/${row.id}`), api.get(`/recurring-transactions/${row.id}/occurrences`)]);
            } catch (e) {
                return empty(errorText(e), 'alert');
            }
            const after = (msg) => { changed = true; toast(msg); render(); };
            const act = (path, msg) => async () => {
                try { await api.post(`/recurring-transactions/${r.id}/${path}`); after(msg); } catch (e) { toast(errorText(e), 'error'); }
            };
            const facts = el('div', { class: 'facts' },
                fact('Amount', money(r.amount, r.currency), true),
                fact('Repeats', frequencyText(r)),
                fact('Type', r.type === 'TRANSFER' ? 'Transfer' : titleCase(r.type)),
                fact(r.type === 'INCOME' ? 'Into' : 'From', r.account?.name ?? '—'),
                r.type === 'TRANSFER' ? fact('To', r.toAccount?.name ?? '—') : fact('Category', r.category?.name ?? '—'),
                fact('Since', longDate(r.startDate)),
                fact('Ends', r.endDate ? longDate(r.endDate) : 'No end date'));
            const history = occurrences.length
                ? el('div', { class: 'rows' }, occurrences.slice(0, 24).map((o) => {
                    const [label, tone] = OCCURRENCE[o.status] ?? [o.status, ''];
                    const resolved = o.status === 'CONFIRMED' || o.status === 'SKIPPED';
                    return el('div', { class: 'row' },
                        el('div', { class: 'grow' },
                            el('div', { class: 'title', text: longDate(o.dueDate) }),
                            el('div', { class: 'meta', text: [o.status === 'CONFIRMED' ? `Recorded ${money(o.actualAmount, r.currency)}` : `Expected ${money(o.expectedAmount, r.currency)}`,
                                o.note].filter(Boolean).join(' · ') })),
                        el('span', { class: `chip ${tone}`, text: label }),
                        resolved
                            ? el('button', { class: 'btn ghost sm icon-only', type: 'button', title: 'Undo', 'aria-label': 'Undo',
                                onclick: async () => {
                                    if (o.status === 'CONFIRMED' && !await confirmDialog({ title: 'Undo this payment?', confirmLabel: 'Undo', danger: true,
                                        message: 'The recorded transaction is deleted (it can be restored from Transactions) and the date is expected again.' })) return;
                                    try { await api.post(`/recurring-occurrences/${o.id}/reopen`); after('Reopened'); } catch (e) { toast(errorText(e), 'error'); }
                                } }, icon('undo'))
                            : el('button', { class: 'btn sm', type: 'button',
                                onclick: () => openConfirmDialog(o, r, () => after('Recorded')).catch((e) => toast(errorText(e), 'error')) }, 'Record'));
                }))
                : el('p', { class: 'faint', text: r.status === 'ACTIVE' ? 'No dates in the next few weeks.' : 'No dates.' });

            const actions = el('div', { class: 'head-actions' },
                r.status !== 'STOPPED' ? el('button', { class: 'btn', type: 'button',
                    onclick: () => openRuleDialog({ existing: r, onSaved: () => after('Saved') }).catch((e) => toast(errorText(e), 'error')) }, icon('edit'), 'Edit') : null,
                r.status === 'ACTIVE' ? el('button', { class: 'btn', type: 'button', onclick: act('pause', 'Paused') }, icon('pause'), 'Pause') : null,
                r.status === 'PAUSED' ? el('button', { class: 'btn primary', type: 'button', onclick: act('resume', 'Resumed') }, icon('play'), 'Resume') : null,
                r.status !== 'STOPPED' ? el('button', { class: 'btn ghost', type: 'button', onclick: async () => {
                    if (!await confirmDialog({ title: `Stop "${r.name}"?`, confirmLabel: 'Stop for good', danger: true,
                        message: 'Past entries stay; no new dates are created. Use Pause if you may need it again.' })) return;
                    await act('stop', 'Stopped')();
                } }, icon('stop'), 'Stop') : null,
                el('button', { class: 'btn ghost danger', type: 'button', onclick: async () => {
                    if (!await confirmDialog({ title: 'Delete this recurring item?', confirmLabel: 'Delete', danger: true,
                        message: 'Only possible if nothing was recorded from it; otherwise stop it instead.' })) return;
                    try { await api.del(`/recurring-transactions/${r.id}`); changed = true; toast('Deleted'); close(); } catch (e) { toast(errorText(e), 'error'); }
                } }, icon('trash'), 'Delete'));

            return [
                el('div', { class: 'card-head' },
                    el('div', {}, el('div', { class: 'title', text: [titleCase(r.kind), r.counterparty].filter(Boolean).join(' · ') }),
                        el('div', { class: 'meta', text: r.nextOccurrence ? `Next: ${longDate(r.nextOccurrence.dueDate)}` : 'No upcoming date' })),
                    el('span', { class: `chip ${r.status === 'ACTIVE' ? 'ok' : r.status === 'PAUSED' ? 'warn' : ''}`, text: titleCase(r.status) })),
                facts,
                r.notes ? el('p', { class: 'muted', text: r.notes }) : null,
                actions,
                el('h3', { class: 'section-title', text: 'Dates' }),
                history,
            ];
        },
    });
}

function fact(label, value, strong = false) {
    return el('div', { class: 'fact' }, el('span', { class: 'faint', text: label }), el(strong ? 'b' : 'span', { class: 'num', text: value }));
}

const valueOf = (form, name) => form.elements.namedItem(name)?.value.trim() || null;

/** Add a rule, or edit one (`existing`): type can't change; amount and schedule apply from today on. */
export async function openRuleDialog({ existing = null, onSaved }) {
    const [accounts, expenseCats, incomeCats] = await Promise.all([store.accounts(), store.expenseCategories(), store.incomeCategories()]);
    const editing = Boolean(existing);
    const key = crypto.randomUUID();
    let type = existing?.type ?? 'EXPENSE';
    const e = existing ?? {};
    const sheet = openSheet({
        title: editing ? `Edit ${existing.name}` : 'Add recurring',
        submitLabel: editing ? 'Save changes' : 'Add',
        fields: () => [
            editing ? null : segmented([{ value: 'EXPENSE', label: 'Payment' }, { value: 'INCOME', label: 'Income' }, { value: 'TRANSFER', label: 'Transfer / SIP' }],
                type, (v) => { type = v; sheet.render(); }, 'Type'),
            el('div', { class: 'form-grid' },
                field('Name', input('name', { maxlength: 80, required: true, value: e.name ?? '', placeholder: type === 'TRANSFER' ? 'e.g. Index fund SIP' : 'e.g. House rent' })),
                field('Kind', select('kind', KINDS.map((k) => ({ value: k, label: titleCase(k), selected: k === (e.kind ?? (type === 'TRANSFER' ? 'INVESTMENT' : 'BILL')) }))))),
            field('Amount', input('amount', { class: 'input amount', inputmode: 'decimal', required: true, placeholder: '0.00', value: e.amount ?? '' })),
            el('div', { class: 'form-grid' },
                field('Repeats', select('frequency', FREQUENCIES.map((f) => ({ value: f, label: titleCase(f), selected: f === (e.frequency ?? 'MONTHLY') })))),
                field('Every', input('interval', { type: 'number', min: 1, max: 99, value: e.interval ?? 1 })),
                field(editing ? 'First date (anchors the schedule)' : 'First due date', input('startDate', { type: 'date', value: e.startDate ?? isoDate(new Date()) })),
                field('End date', input('endDate', { type: 'date', value: e.endDate ?? '' })),
                field(type === 'INCOME' ? 'Into account' : 'From account', select('accountId', accountOptions(accounts, e.account?.id))),
                type === 'TRANSFER'
                    ? field('To account', select('toAccountId', accountOptions(accounts, e.toAccount?.id ?? accounts.find((a) => !a.defaultAccount)?.id)))
                    : field('Category', select('categoryId', [{ value: '', label: 'Choose a category' },
                        ...categoryOptions(type === 'INCOME' ? incomeCats : expenseCats, e.category?.id)])),
                type === 'TRANSFER' ? null : field(type === 'INCOME' ? 'Payer' : 'Merchant', input('counterparty', { maxlength: 100, placeholder: 'Optional', value: e.counterparty ?? '' }))),
            el('p', { class: 'faint', text: editing
                ? 'Changes to the amount, account or dates apply from today on. Past entries stay as they are.'
                : 'You get a reminder before each date. Confirm when it is paid (or received); only then is it recorded.' }),
        ],
        submit: async (form) => {
            const v = (n) => valueOf(form, n);
            const body = {
                name: v('name'), kind: v('kind'), amount: parseAmount(v('amount')), frequency: v('frequency'),
                interval: v('interval') ? Number(v('interval')) : null, startDate: v('startDate'), endDate: v('endDate'),
                accountId: v('accountId'), toAccountId: type === 'TRANSFER' ? v('toAccountId') : null,
                categoryId: type === 'TRANSFER' ? null : v('categoryId'), counterparty: v('counterparty'),
            };
            if (editing) {
                if (!body.endDate && existing.endDate) body.noEndDate = true;
                await api.patch(`/recurring-transactions/${existing.id}`, body);
            } else {
                await api.post('/recurring-transactions', { ...body, type }, { 'Idempotency-Key': key });
                toast('Recurring item added');
            }
            onSaved?.();
        },
    });
}

/** Records what actually happened: amount, date and account can differ from the plan. */
async function openConfirmDialog(o, rule, onSaved) {
    const accounts = await store.accounts();
    const key = crypto.randomUUID();
    const income = o.type === 'INCOME';
    openSheet({
        title: `${o.ruleName} · ${shortDate(o.dueDate)}`,
        submitLabel: income ? 'Mark received' : 'Mark paid',
        fields: () => [
            field(`Amount (expected ${money(o.expectedAmount, o.currency)})`, input('amount', {
                class: 'input amount', inputmode: 'decimal', value: o.expectedAmount, required: true })),
            el('div', { class: 'form-grid' },
                field(income ? 'Received on' : 'Paid on', input('transactionDate', { type: 'date', value: isoDate(new Date()), max: isoDate(new Date()) })),
                field(income ? 'Into' : 'From', select('accountId', accountOptions(accounts, rule?.account?.id)))),
            field('Note', input('notes', { maxlength: 1000, placeholder: 'Optional' })),
            el('p', { class: 'faint', text: o.type === 'TRANSFER'
                ? 'Recorded as a transfer between your accounts: not spending.'
                : `Recorded as ${income ? 'income' : 'an expense'} and counted in your totals from now on.` }),
        ],
        submit: async (form) => {
            await api.post(`/recurring-occurrences/${o.id}/confirm`, {
                amount: parseAmount(valueOf(form, 'amount')), transactionDate: valueOf(form, 'transactionDate'),
                accountId: valueOf(form, 'accountId'), notes: valueOf(form, 'notes'),
            }, { 'Idempotency-Key': key });
            store.invalidate('accounts');
            onSaved?.();
        },
    });
}
