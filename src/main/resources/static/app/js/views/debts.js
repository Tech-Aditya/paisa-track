// Lending & borrowing: who owes whom, repayments, write-offs. Tracking only — balances are untouched.

import { api, errorText } from '../api.js';
import { kpi } from '../components.js';
import { el, empty, field, fill, icon, input, openPanel, openSheet, segmented, select, skeleton, toast } from '../dom.js';
import { initials, money, plural, shortDate, titleCase } from '../format.js';
import { store } from '../store.js';

const PATH = { LENT: '/lending', BORROWED: '/borrowing' };
const STATUS = {
    OPEN: ['Open', ''], PARTIALLY_REPAID: ['Part repaid', 'warn'], OVERDUE: ['Overdue', 'bad'],
    SETTLED: ['Settled', 'ok'], WRITTEN_OFF: ['Written off', ''],
};
const METHODS = ['CASH', 'UPI', 'NET_BANKING', 'DEBIT_CARD', 'CREDIT_CARD', 'WALLET', 'CHEQUE', 'OTHER'];
const methodLabel = (m) => (m === 'UPI' ? 'UPI' : titleCase(m));

let direction = 'LENT';
let show = 'ACTIVE';

export async function renderDebts(root) {
    const reload = () => renderDebts(root);
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Lending & borrowing' }),
            el('p', { text: 'Money between you and other people. Tracked separately: not part of balances, income or spending.' })),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn primary', type: 'button', onclick: () => openDebtDialog({ direction, onSaved: reload }) },
                icon('plus'), 'New record')));
    const stats = el('div', { class: 'kpis' }, [1, 2, 3].map(() => el('section', { class: 'card' }, skeleton(14, '40%'), skeleton(28))));
    const list = el('div', { class: 'rows' }, [1, 2, 3].map(() => el('div', { class: 'row' }, skeleton(36, '36px'), el('div', { class: 'grow' }, skeleton(14, '50%')))));
    const controls = el('div', { class: 'card-head' },
        segmented([{ value: 'LENT', label: 'They owe me' }, { value: 'BORROWED', label: 'I owe' }], direction,
            (v) => { direction = v; reload(); }, 'Direction'),
        segmented([{ value: 'ACTIVE', label: 'Open' }, { value: 'CLOSED', label: 'Closed' }, { value: 'ALL', label: 'All' }], show,
            (v) => { show = v; reload(); }, 'Show'));
    fill(root, head, stats, el('section', { class: 'card' }, controls, list));

    try {
        const me = await store.me();
        const [summary, items] = await Promise.all([api.get('/debts/summary'), api.get(PATH[direction])]);
        fill(stats, summaryCards(summary, me.preferences.defaultCurrency));
        const visible = items.filter((d) => show === 'ALL'
            || (show === 'ACTIVE') === !['SETTLED', 'WRITTEN_OFF'].includes(d.status));
        fill(list, visible.length
            ? visible.map((d) => debtRow(d, () => openDebtDetail(d, reload)))
            : empty(show === 'ACTIVE'
                ? (direction === 'LENT' ? 'Nobody owes you money right now.' : "You don't owe anyone right now.")
                : 'Nothing here yet.', 'people'));
    } catch (e) {
        fill(list, empty(errorText(e), 'alert'));
    }
}

function summaryCards(summary, currency) {
    const t = summary.currencies.find((c) => c.currency === currency)
        ?? { currency, owedToYou: 0, youOwe: 0, overdueOwedToYou: 0, overdueYouOwe: 0, openLent: 0, openBorrowed: 0, overdueCount: 0 };
    const others = summary.currencies.filter((c) => c.currency !== currency).map((c) => c.currency);
    return [
        kpi({ label: 'Owed to you', value: money(t.owedToYou, currency), iconName: 'down', tone: 'pos',
            foot: [plural(t.openLent, 'open record'), Number(t.overdueOwedToYou) ? `${money(t.overdueOwedToYou, currency)} overdue` : null] }),
        kpi({ label: 'You owe', value: money(t.youOwe, currency), iconName: 'up', tone: 'neg',
            foot: [plural(t.openBorrowed, 'open record'), Number(t.overdueYouOwe) ? `${money(t.overdueYouOwe, currency)} overdue` : null] }),
        kpi({ label: 'Overdue', value: String(t.overdueCount), iconName: 'clock', tone: t.overdueCount ? 'neg' : '',
            foot: [others.length ? `Also open in ${others.join(', ')}` : 'Past their due date'] }),
    ];
}

export function dueText(d) {
    if (d.status === 'SETTLED') return d.lastRepaymentDate ? `Settled ${shortDate(d.lastRepaymentDate)}` : 'Settled';
    if (d.status === 'WRITTEN_OFF') return 'Written off';
    if (d.overdue) return `Overdue by ${plural(d.daysOverdue, 'day')}`;
    return d.dueDate ? `Due ${shortDate(d.dueDate)}` : `Since ${shortDate(d.startDate)}`;
}

export function debtRow(d, onOpen) {
    const [label, tone] = STATUS[d.status] ?? [d.status, ''];
    const repaidPct = Number(d.principal) ? Math.min(100, (Number(d.repaid) / Number(d.principal)) * 100) : 0;
    return el('button', { class: 'row debt-row', type: 'button', onclick: onOpen },
        el('span', { class: 'avatar', text: initials(d.person?.name) }),
        el('div', { class: 'grow' },
            el('div', { class: 'title', text: d.person?.name ?? '—' }),
            el('div', { class: 'meta', text: [d.description, dueText(d)].filter(Boolean).join(' · ') }),
            el('div', { class: 'progress thin' }, el('i', { style: { width: `${repaidPct}%` } }))),
        el('div', { class: 'debt-amount' },
            el('div', { class: 'amount num', text: money(d.outstanding, d.currency) }),
            el('span', { class: `chip ${tone}`, text: label })));
}

/** New lending or borrowing record. People are matched by name, or created. */
export async function openDebtDialog({ direction: initial = 'LENT', onSaved }) {
    const [people, me] = await Promise.all([store.people(), store.me()]);
    const key = crypto.randomUUID();
    let dir = initial;
    const listId = `people-${key}`;
    const sheet = openSheet({
        title: 'New record',
        submitLabel: 'Save',
        fields: () => [
            segmented([{ value: 'LENT', label: 'I lent money' }, { value: 'BORROWED', label: 'I borrowed money' }], dir,
                (v) => { dir = v; sheet.render(); }, 'Direction'),
            field(dir === 'LENT' ? 'Lent to' : 'Borrowed from', input('personName', {
                list: listId, maxlength: 80, required: true, autocomplete: 'off', placeholder: 'Name, e.g. Rahul' })),
            el('datalist', { id: listId }, people.map((p) => el('option', { value: p.name }))),
            field('Amount', input('amount', { class: 'input amount', inputmode: 'decimal', placeholder: '0.00', required: true })),
            el('div', { class: 'form-grid' },
                field('Date', input('startDate', { type: 'date' })),
                field('Due by', input('dueDate', { type: 'date' })),
                field('Currency', input('currency', { maxlength: 3, value: me.preferences.defaultCurrency })),
                field('Paid via', select('paymentMethod', [{ value: '', label: 'Not specified' },
                    ...METHODS.map((m) => ({ value: m, label: methodLabel(m) }))]))),
            field('What for', input('description', { maxlength: 200, placeholder: 'Optional, e.g. Rent advance' })),
            el('p', { class: 'faint', text: 'This only tracks who owes whom. It does not change account balances, income or spending.' }),
        ],
        submit: async (form) => {
            const v = (n) => form.elements.namedItem(n).value.trim() || null;
            await api.post(PATH[dir], {
                personName: v('personName'), amount: v('amount'), currency: v('currency')?.toUpperCase(),
                startDate: v('startDate'), dueDate: v('dueDate'), paymentMethod: v('paymentMethod'), description: v('description'),
            }, { 'Idempotency-Key': key });
            store.invalidate('people');
            toast('Record saved');
            onSaved?.();
        },
    });
}

/** Details, repayment history and actions for one record. */
export function openDebtDetail(summaryRow, onChanged) {
    const path = `${PATH[summaryRow.direction]}/${summaryRow.id}`;
    let changed = false;
    openPanel({
        title: summaryRow.direction === 'LENT' ? `Lent to ${summaryRow.person.name}` : `Borrowed from ${summaryRow.person.name}`,
        onClose: () => { if (changed) onChanged?.(); },
        content: async ({ render, close }) => {
            let d;
            try {
                d = await api.get(path);
            } catch (e) {
                return empty(errorText(e), 'alert');
            }
            const after = (msg) => { changed = true; toast(msg); render(); };
            const act = (fn, msg) => async () => {
                try { await fn(); after(msg); } catch (e) { if (e.message !== 'Cancelled') toast(errorText(e), 'error'); }
            };
            const [label, tone] = STATUS[d.status] ?? [d.status, ''];
            const c = d.currency;
            const open = !['SETTLED', 'WRITTEN_OFF'].includes(d.status);
            const activeRepayments = d.repayments.filter((r) => !r.voided).length;

            const facts = el('div', { class: 'facts' },
                fact('Outstanding', money(d.outstanding, c), true),
                fact('Original amount', money(d.principal, c)),
                fact('Repaid', money(d.repaid, c)),
                Number(d.writtenOff) ? fact('Written off', money(d.writtenOff, c)) : null,
                fact(d.direction === 'LENT' ? 'Lent on' : 'Borrowed on', shortDate(d.startDate)),
                fact('Due', d.dueDate ? shortDate(d.dueDate) : '—'));

            const history = d.repayments.length
                ? el('div', { class: 'rows' }, d.repayments.slice().reverse().map((r) => el('div', { class: `row ${r.voided ? 'voided' : ''}` },
                    el('div', { class: 'tile-icon income' }, icon('check')),
                    el('div', { class: 'grow' },
                        el('div', { class: 'title', text: money(r.amount, c) }),
                        el('div', { class: 'meta', text: [shortDate(r.repaymentDate), r.paymentMethod && methodLabel(r.paymentMethod), r.notes,
                            r.voided ? `Voided${r.voidReason ? `: ${r.voidReason}` : ''}` : null].filter(Boolean).join(' · ') })),
                    !r.voided && d.status !== 'WRITTEN_OFF'
                        ? el('button', { class: 'btn ghost sm', type: 'button', title: 'Void this repayment',
                            onclick: act(async () => {
                                if (!window.confirm('Void this repayment? It stays in the history and the outstanding amount goes back up.')) throw new Error('Cancelled');
                                await api.post(`/repayments/${r.id}/void`);
                            }, 'Repayment voided') }, icon('undo'))
                        : null)))
                : el('p', { class: 'faint', text: 'No repayments yet.' });

            const actions = el('div', { class: 'head-actions' },
                open ? el('button', { class: 'btn primary', type: 'button',
                    onclick: () => openRepaymentDialog(d, () => after('Repayment recorded')) }, icon('plus'), 'Record repayment') : null,
                open ? el('button', { class: 'btn', type: 'button', onclick: act(async () => {
                    const reason = window.prompt(`Write off ${money(d.outstanding, c)}? This closes the record. It is not counted as income or spending.\n\nReason (optional):`, '');
                    if (reason === null) throw new Error('Cancelled');
                    await api.post(`${path}/write-off`, { reason: reason || null });
                }, 'Written off') }, 'Write off') : null,
                d.status === 'WRITTEN_OFF' ? el('button', { class: 'btn', type: 'button',
                    onclick: act(() => api.post(`${path}/reopen`), 'Reopened') }, 'Reopen') : null,
                activeRepayments === 0 ? el('button', { class: 'btn ghost danger', type: 'button', onclick: async () => {
                    if (!window.confirm('Delete this record? Use this only for mistakes.')) return;
                    try { await api.del(path); changed = true; toast('Record deleted'); close(); } catch (e) { toast(errorText(e), 'error'); }
                } }, icon('trash'), 'Delete') : null);

            return [
                el('div', { class: 'card-head' },
                    el('div', {}, el('div', { class: 'title', text: d.description || (d.direction === 'LENT' ? 'Money lent' : 'Money borrowed') }),
                        el('div', { class: 'meta', text: dueText(d) })),
                    el('span', { class: `chip ${tone}`, text: label })),
                el('div', { class: 'progress' }, el('i', { style: { width: `${Number(d.principal) ? Math.min(100, (Number(d.repaid) / Number(d.principal)) * 100) : 0}%` } })),
                facts,
                d.notes ? el('p', { class: 'muted', text: d.notes }) : null,
                d.writeOffReason ? el('p', { class: 'muted', text: `Write-off reason: ${d.writeOffReason}` }) : null,
                el('h3', { class: 'section-title', text: 'Repayments' }),
                history,
                actions,
            ];
        },
    });
}

function fact(label, value, strong = false) {
    return el('div', { class: 'fact' }, el('span', { class: 'faint', text: label }), el(strong ? 'b' : 'span', { class: 'num', text: value }));
}

function openRepaymentDialog(d, onSaved) {
    const key = crypto.randomUUID();
    openSheet({
        title: d.direction === 'LENT' ? `Repayment from ${d.person.name}` : `Repayment to ${d.person.name}`,
        submitLabel: 'Record repayment',
        fields: () => [
            field(`Amount (outstanding ${money(d.outstanding, d.currency)})`, input('amount', {
                class: 'input amount', inputmode: 'decimal', value: d.outstanding, required: true })),
            el('div', { class: 'form-grid' },
                field('Date', input('repaymentDate', { type: 'date' })),
                field('Paid via', select('paymentMethod', [{ value: '', label: 'Not specified' },
                    ...METHODS.map((m) => ({ value: m, label: methodLabel(m) }))]))),
            field('Note', input('notes', { maxlength: 500, placeholder: 'Optional' })),
        ],
        submit: async (form) => {
            const v = (n) => form.elements.namedItem(n).value.trim() || null;
            await api.post(`${PATH[d.direction]}/${d.id}/repayments`, {
                amount: v('amount'), repaymentDate: v('repaymentDate'), paymentMethod: v('paymentMethod'), notes: v('notes'),
            }, { 'Idempotency-Key': key });
            onSaved();
        },
    });
}
