// Loans & EMIs: outstanding principal, EMI schedule, payments and prepayments.
// Interest is spending; principal only reduces the loan (and counts against net worth).

import { api, errorText } from '../api.js';
import { kpi } from '../components.js';
import { el, empty, field, fill, icon, input, openPanel, openSheet, segmented, select, skeleton, toast } from '../dom.js';
import { isoDate, money, parseAmount, plural, shortDate, titleCase } from '../format.js';
import { accountOptions, store } from '../store.js';

const TYPES = ['HOME', 'VEHICLE', 'PERSONAL', 'EDUCATION', 'GOLD', 'OTHER'];
const INSTALLMENT = { PAID: ['Paid', 'ok'], MISSED: ['Missed', 'bad'], DUE: ['Due', 'warn'], UPCOMING: ['Upcoming', ''] };
const longDate = (iso) => new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' });

let show = 'ACTIVE';

export async function renderLoans(root) {
    const reload = () => renderLoans(root);
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Loans & EMIs' }),
            el('p', { text: 'Interest you pay is spending. Principal reduces the loan, and what is left counts against your net worth.' })),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn primary', type: 'button', onclick: () => openLoanDialog({ onSaved: reload }).catch((e) => toast(errorText(e), 'error')) },
                icon('plus'), 'Add loan')));
    const stats = el('div', { class: 'kpis' }, [1, 2, 3].map(() => el('section', { class: 'card' }, skeleton(14, '40%'), skeleton(28))));
    const upcoming = el('div');
    const list = el('div', { class: 'rows' }, [1, 2].map(() => el('div', { class: 'row' }, skeleton(36, '36px'), el('div', { class: 'grow' }, skeleton(14, '50%')))));
    const controls = el('div', { class: 'card-head' },
        el('h2', { text: 'Your loans' }),
        segmented([{ value: 'ACTIVE', label: 'Active' }, { value: 'ALL', label: 'All' }], show, (v) => { show = v; reload(); }, 'Show'));
    fill(root, head, stats, upcoming, el('section', { class: 'card' }, controls, list));

    try {
        const [me, summary, loans] = await Promise.all([store.me(), api.get('/loans/summary'), api.get(`/loans?includeClosed=${show === 'ALL'}`)]);
        fill(stats, summaryCards(summary, me.preferences.defaultCurrency));
        if (summary.upcoming.length) {
            fill(upcoming, el('section', { class: 'card', style: { marginBottom: '16px' } },
                el('div', { class: 'card-head' }, el('h2', {}, 'EMIs to pay', el('span', { class: 'sub', text: 'missed or due in 30 days' }))),
                el('div', { class: 'rows' }, summary.upcoming.map((u) => emiRow(u, loans.find((l) => l.id === u.loanId), reload)))));
        }
        fill(list, loans.length
            ? loans.map((l) => loanRow(l, () => openLoanDetail(l, reload)))
            : empty(show === 'ACTIVE' ? 'No active loans. Add a home, car or personal loan to track its EMIs.' : 'No loans yet.', 'bank'));
    } catch (e) {
        fill(list, empty(errorText(e), 'alert'));
    }
}

function summaryCards(summary, currency) {
    const t = summary.currencies.find((c) => c.currency === currency)
        ?? { currency, activeLoans: 0, outstandingPrincipal: 0, monthlyEmi: 0, principalPaidThisYear: 0, interestPaidThisYear: 0, missedCount: 0 };
    const others = summary.currencies.filter((c) => c.currency !== currency).map((c) => c.currency);
    return [
        kpi({ label: 'Outstanding', value: money(t.outstandingPrincipal, currency), iconName: 'bank', tone: 'neg',
            foot: [plural(t.activeLoans, 'active loan'), others.length ? `Also in ${others.join(', ')}` : null] }),
        kpi({ label: 'Monthly EMI', value: money(t.monthlyEmi, currency), iconName: 'calendar', tone: 'accent',
            foot: [t.missedCount ? `${plural(t.missedCount, 'EMI')} missed` : 'All EMIs on time'] }),
        kpi({ label: 'Interest this year', value: money(t.interestPaidThisYear, currency), iconName: 'trend',
            foot: [`Principal repaid ${money(t.principalPaidThisYear, currency)}`] }),
    ];
}

function loanRow(l, onOpen) {
    const paidPct = Number(l.principal) ? Math.min(100, (Number(l.principalPaid) / Number(l.principal)) * 100) : 0;
    const n = l.nextInstallment;
    const next = l.status === 'CLOSED' ? 'Closed'
        : n ? `${n.status === 'MISSED' ? 'Missed' : 'Next EMI'} ${shortDate(n.dueDate)} · ${money(n.emiAmount, l.currency)}` : '';
    const chip = l.status === 'CLOSED' ? ['Closed', 'ok'] : l.missedCount ? [`${plural(l.missedCount, 'missed EMI')}`, 'bad'] : [titleCase(l.type), ''];
    return el('button', { class: 'row debt-row', type: 'button', onclick: onOpen },
        el('div', { class: 'tile-icon loan' }, icon('bank')),
        el('div', { class: 'grow' },
            el('div', { class: 'title', text: l.name }),
            el('div', { class: 'meta', text: [l.lender, next].filter(Boolean).join(' · ') }),
            el('div', { class: 'progress thin' }, el('i', { style: { width: `${paidPct}%` } }))),
        el('div', { class: 'debt-amount' },
            el('div', { class: 'amount num', text: money(l.outstandingPrincipal, l.currency) }),
            el('span', { class: `chip ${chip[1]}`, text: chip[0] })));
}

function emiRow(u, loan, reload) {
    const [label] = INSTALLMENT[u.status] ?? [u.status];
    return el('div', { class: 'row' },
        el('div', { class: `tile-icon ${u.status === 'MISSED' ? 'expense' : 'loan'}` }, icon('calendar')),
        el('div', { class: 'grow' },
            el('div', { class: 'title', text: `${u.loanName} · EMI ${u.number}` }),
            el('div', { class: `meta ${u.status === 'MISSED' ? 'neg' : ''}`.trim(), text: `${label} ${shortDate(u.dueDate)} · interest ${money(u.interestAmount, u.currency)}` })),
        el('div', { class: 'debt-amount' },
            el('div', { class: 'amount num', text: money(u.emiAmount, u.currency) }),
            loan ? el('button', { class: 'btn sm primary', type: 'button',
                onclick: () => openEmiDialog(loan, () => { toast('EMI paid'); reload(); }).catch((e) => toast(errorText(e), 'error')) }, 'Pay') : null));
}

/** Details, schedule, payments and actions for one loan. */
export function openLoanDetail(row, onChanged) {
    let changed = false;
    let fullSchedule = false;
    openPanel({
        title: row.name,
        onClose: () => { if (changed) onChanged?.(); },
        content: async ({ render, close }) => {
            let l;
            let schedule;
            try {
                [l, schedule] = await Promise.all([api.get(`/loans/${row.id}`), api.get(`/loans/${row.id}/schedule`)]);
            } catch (e) {
                return empty(errorText(e), 'alert');
            }
            const c = l.currency;
            const after = (msg) => { changed = true; toast(msg); render(); };
            const open = l.status === 'ACTIVE';
            const active = l.payments.filter((p) => !p.voided);
            const latest = active[active.length - 1];
            const paidPct = Number(l.principal) ? Math.min(100, (Number(l.principalPaid) / Number(l.principal)) * 100) : 0;

            const facts = el('div', { class: 'facts' },
                fact('Outstanding', money(l.outstandingPrincipal, c), true),
                fact('EMI', money(l.emiAmount, c), true),
                fact('Rate', `${Number(l.annualRate)}% a year`),
                fact('Borrowed', money(l.principal, c)),
                fact('Interest paid', money(l.interestPaid, c)),
                fact('Total interest', money(l.projectedTotalInterest, c)),
                fact('EMIs', `${l.installmentsPaid} paid · ${l.installmentsRemaining} left`),
                fact('Started', longDate(l.startDate)),
                fact('Ends', l.projectedEndDate ? longDate(l.projectedEndDate) : '—'));

            const unpaidFrom = schedule.findIndex((i) => i.status !== 'PAID');
            const visible = fullSchedule ? schedule
                : schedule.slice(Math.max(0, (unpaidFrom < 0 ? schedule.length : unpaidFrom) - 2), (unpaidFrom < 0 ? schedule.length : unpaidFrom) + 6);
            const table = el('div', { class: 'table-wrap' }, el('table', { class: 'schedule' },
                el('thead', {}, el('tr', {}, ['#', 'Due', 'Principal', 'Interest', 'Balance left', ''].map((h) => el('th', { text: h })))),
                el('tbody', {}, visible.map((i) => {
                    const [label, tone] = INSTALLMENT[i.status] ?? [i.status, ''];
                    return el('tr', { class: i.status === 'PAID' ? 'done' : '' },
                        el('td', { class: 'num', text: String(i.number) }),
                        el('td', { text: longDate(i.dueDate) }),
                        el('td', { class: 'num', text: money(i.principalAmount, c) }),
                        el('td', { class: 'num', text: money(i.interestAmount, c) }),
                        el('td', { class: 'num', text: money(i.closingBalance, c) }),
                        el('td', {}, el('span', { class: `chip ${tone}`, text: label })));
                }))));
            const scheduleToggle = schedule.length > visible.length || fullSchedule
                ? el('button', { class: 'btn ghost sm', type: 'button', onclick: () => { fullSchedule = !fullSchedule; render(); } },
                    fullSchedule ? 'Show fewer' : `Show all ${schedule.length} EMIs`)
                : null;

            const payments = l.payments.length
                ? el('div', { class: 'rows' }, l.payments.slice().reverse().map((p) => el('div', { class: `row ${p.voided ? 'voided' : ''}` },
                    el('div', { class: `tile-icon ${p.kind === 'PREPAYMENT' ? 'income' : 'loan'}` }, icon(p.kind === 'PREPAYMENT' ? 'trend' : 'check')),
                    el('div', { class: 'grow' },
                        el('div', { class: 'title', text: p.kind === 'EMI' ? `EMI ${p.installmentNumber} · ${money(p.totalAmount, c)}` : `Prepayment · ${money(p.totalAmount, c)}` }),
                        el('div', { class: 'meta', text: [shortDate(p.paymentDate),
                            p.kind === 'EMI' ? `principal ${money(p.principalAmount, c)}, interest ${money(p.interestAmount, c)}` : null,
                            p.inLedger ? p.account?.name : 'before tracking', p.voided ? 'Voided' : null].filter(Boolean).join(' · ') })),
                    p === latest
                        ? el('button', { class: 'btn ghost sm', type: 'button', title: 'Void this payment', 'aria-label': 'Void this payment',
                            onclick: async () => {
                                if (!window.confirm('Void this payment? Its account entries are reversed and the loan goes back to how it was before.')) return;
                                try { await api.post(`/loans/${l.id}/payments/${p.id}/void`); after('Payment voided'); } catch (e) { toast(errorText(e), 'error'); }
                            } }, icon('undo'))
                        : null)))
                : el('p', { class: 'faint', text: 'No payments yet.' });

            const actions = el('div', { class: 'head-actions' },
                open && l.nextInstallment ? el('button', { class: 'btn primary', type: 'button',
                    onclick: () => openEmiDialog(l, () => after('EMI paid')).catch((e) => toast(errorText(e), 'error')) },
                icon('check'), `Pay EMI ${l.nextInstallment.number}`) : null,
                open ? el('button', { class: 'btn', type: 'button',
                    onclick: () => openPrepayDialog(l, () => after('Prepayment recorded')).catch((e) => toast(errorText(e), 'error')) },
                icon('trend'), 'Prepay') : null,
                active.length === 0 ? el('button', { class: 'btn ghost danger', type: 'button', onclick: async () => {
                    if (!window.confirm('Delete this loan? Any disbursement entry is removed from your account too.')) return;
                    try { await api.del(`/loans/${l.id}`); changed = true; toast('Loan deleted'); close(); } catch (e) { toast(errorText(e), 'error'); }
                } }, icon('trash'), 'Delete') : null);

            return [
                el('div', { class: 'card-head' },
                    el('div', {}, el('div', { class: 'title', text: [titleCase(l.type) + ' loan', l.lender].filter(Boolean).join(' · ') }),
                        el('div', { class: 'meta', text: `${Math.round(paidPct)}% of principal repaid` })),
                    el('span', { class: `chip ${open ? (l.missedCount ? 'bad' : '') : 'ok'}`,
                        text: open ? (l.missedCount ? plural(l.missedCount, 'missed EMI') : 'Active') : 'Closed' })),
                el('div', { class: 'progress' }, el('i', { style: { width: `${paidPct}%` } })),
                facts,
                l.notes ? el('p', { class: 'muted', text: l.notes }) : null,
                actions,
                el('h3', { class: 'section-title', text: 'EMI schedule' }),
                table,
                scheduleToggle,
                el('h3', { class: 'section-title', text: 'Payments' }),
                payments,
            ];
        },
    });
}

function fact(label, value, strong = false) {
    return el('div', { class: 'fact' }, el('span', { class: 'faint', text: label }), el(strong ? 'b' : 'span', { class: 'num', text: value }));
}

const valueOf = (form, name) => form.elements.namedItem(name)?.value.trim() || null;

/** New loan with a live EMI preview. */
export async function openLoanDialog({ onSaved }) {
    const accounts = (await store.accounts()).filter((a) => a.active !== false);
    const key = crypto.randomUUID();
    const preview = el('div', { class: 'loan-preview', 'aria-live': 'polite' });
    const sheet = openSheet({
        title: 'Add loan',
        submitLabel: 'Add loan',
        fields: () => [
            el('div', { class: 'form-grid' },
                field('Name', input('name', { maxlength: 80, required: true, placeholder: 'e.g. Home loan' })),
                field('Lender', input('lender', { maxlength: 100, placeholder: 'e.g. HDFC Bank' })),
                field('Type', select('type', TYPES.map((t) => ({ value: t, label: titleCase(t) })))),
                field('Loan amount', input('principal', { class: 'input amount', inputmode: 'decimal', placeholder: '0.00', required: true })),
                field('Interest rate (% a year)', input('annualRate', { inputmode: 'decimal', placeholder: 'e.g. 8.5', required: true })),
                field('Tenure (months)', input('tenureMonths', { inputmode: 'numeric', placeholder: 'e.g. 240', required: true })),
                field('Loan start date', input('startDate', { type: 'date', value: isoDate(new Date()) })),
                field('First EMI date', input('firstEmiDate', { type: 'date' }))),
            preview,
            field('EMI (leave blank to calculate)', input('emiAmount', { inputmode: 'decimal', placeholder: 'As on your loan statement' })),
            el('div', { class: 'form-grid' },
                field('Money credited to', select('disbursementAccountId', [{ value: '', label: 'Not credited to my accounts' },
                    ...accounts.map((a) => ({ value: a.id, label: `${a.name} · ${a.currency}` }))])),
                field('EMIs paid from', select('paymentAccountId', accountOptions(accounts))),
                field('EMIs already paid', input('emisPaidBeforeTracking', { inputmode: 'numeric', placeholder: '0' }))),
            field('Notes', input('notes', { maxlength: 1000, placeholder: 'Optional' })),
            el('p', { class: 'faint', text: 'Money credited to an account raises its balance but is not income. EMIs paid before you started tracking are marked paid without touching your accounts.' }),
        ],
        submit: async (form) => {
            const v = (n) => valueOf(form, n);
            await api.post('/loans', {
                name: v('name'), lender: v('lender'), type: v('type'), principal: parseAmount(v('principal')), annualRate: v('annualRate'),
                tenureMonths: v('tenureMonths'), emiAmount: parseAmount(v('emiAmount')), startDate: v('startDate'), firstEmiDate: v('firstEmiDate'),
                disbursementAccountId: v('disbursementAccountId'), paymentAccountId: v('paymentAccountId'),
                emisPaidBeforeTracking: v('emisPaidBeforeTracking'), notes: v('notes'),
            }, { 'Idempotency-Key': key });
            store.invalidate('accounts');
            toast('Loan added');
            onSaved?.();
        },
    });

    let timer;
    let seq = 0;
    const refresh = async () => {
        const v = (n) => valueOf(sheet.form, n);
        if (!v('principal') || !v('annualRate') || !v('tenureMonths')) { fill(preview); return; }
        const mine = ++seq;
        try {
            const p = await api.post('/loans/preview', {
                principal: parseAmount(v('principal')), annualRate: v('annualRate'), tenureMonths: v('tenureMonths'), emiAmount: parseAmount(v('emiAmount')),
            });
            if (mine !== seq) return;
            fill(preview,
                el('div', {}, el('span', { class: 'faint', text: 'EMI' }), el('b', { class: 'num', text: money(p.emiAmount) })),
                el('div', {}, el('span', { class: 'faint', text: 'Total interest' }), el('b', { class: 'num', text: money(p.totalInterest) })),
                el('div', {}, el('span', { class: 'faint', text: 'Paid off in' }), el('b', { text: plural(p.installments, 'month') })));
        } catch (e) {
            if (mine === seq) fill(preview, el('span', { class: 'faint', text: e.fields?.[0]?.message ?? errorText(e) }));
        }
    };
    sheet.form.addEventListener('input', (e) => {
        if (!['principal', 'annualRate', 'tenureMonths', 'emiAmount'].includes(e.target.name)) return;
        clearTimeout(timer);
        timer = setTimeout(refresh, 300);
    });
}

async function openEmiDialog(loan, onSaved) {
    const accounts = (await store.accounts()).filter((a) => a.active !== false);
    const n = loan.nextInstallment;
    const c = loan.currency;
    const key = crypto.randomUUID();
    openSheet({
        title: `Pay EMI ${n.number} · ${loan.name}`,
        submitLabel: `Pay ${money(n.emiAmount, c)}`,
        fields: () => [
            el('div', { class: 'facts' },
                fact('Due', longDate(n.dueDate)),
                fact('Principal', money(n.principalAmount, c)),
                fact('Interest', money(n.interestAmount, c))),
            el('div', { class: 'form-grid' },
                field('Paid on', input('paymentDate', { type: 'date', value: isoDate(new Date()) })),
                field('Paid from', select('accountId', accountOptions(accounts, loan.paymentAccount?.id)))),
            el('p', { class: 'faint', text: 'The interest is recorded as spending. The principal reduces the loan and is not counted as spending.' }),
        ],
        submit: async (form) => {
            await api.post(`/loans/${loan.id}/emi-payments`, {
                installmentNumber: n.number, paymentDate: valueOf(form, 'paymentDate'), accountId: valueOf(form, 'accountId'),
            }, { 'Idempotency-Key': key });
            store.invalidate('accounts');
            onSaved();
        },
    });
}

async function openPrepayDialog(loan, onSaved) {
    const accounts = (await store.accounts()).filter((a) => a.active !== false);
    const key = crypto.randomUUID();
    openSheet({
        title: `Prepay · ${loan.name}`,
        submitLabel: 'Record prepayment',
        fields: () => [
            field(`Amount (outstanding ${money(loan.outstandingPrincipal, loan.currency)})`, input('amount', {
                class: 'input amount', inputmode: 'decimal', placeholder: '0.00', required: true })),
            el('div', { class: 'form-grid' },
                field('Paid on', input('paymentDate', { type: 'date', value: isoDate(new Date()) })),
                field('Paid from', select('accountId', accountOptions(accounts, loan.paymentAccount?.id)))),
            el('p', { class: 'faint', text: 'Your EMI stays the same and the loan ends sooner. A prepayment is not spending.' }),
        ],
        submit: async (form) => {
            await api.post(`/loans/${loan.id}/prepayments`, {
                amount: parseAmount(valueOf(form, 'amount')), paymentDate: valueOf(form, 'paymentDate'), accountId: valueOf(form, 'accountId'),
            }, { 'Idempotency-Key': key });
            store.invalidate('accounts');
            onSaved();
        },
    });
}
