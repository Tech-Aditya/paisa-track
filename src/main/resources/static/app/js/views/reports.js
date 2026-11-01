// Reports: day, week, month, year or custom range, with like-for-like comparison, insights and a
// breakdown that keeps income, spending, transfers, investments, loans and lending apart.

import { api, errorText } from '../api.js';
import { dailyBars, trendBars } from '../charts.js';
import { card } from '../components.js';
import { el, empty, field, fill, icon, input, segmented, select, skeleton } from '../dom.js';
import { isoDate, money, plural, shortDate, titleCase } from '../format.js';
import { categoryOptions, currencyOptions, store } from '../store.js';

const KINDS = [
    { value: 'daily', label: 'Day' }, { value: 'weekly', label: 'Week' }, { value: 'monthly', label: 'Month' },
    { value: 'yearly', label: 'Year' }, { value: 'custom', label: 'Custom' },
];
const TYPES = [
    { value: '', label: 'All types' }, { value: 'EXPENSE', label: 'Expenses' }, { value: 'INCOME', label: 'Income' },
    { value: 'TRANSFER', label: 'Transfers' }, { value: 'LOAN_PRINCIPAL', label: 'Loan repayments' },
];
const TONE_ICON = { POSITIVE: 'check', WARNING: 'alert', NEUTRAL: 'trend' };
const fmt = (iso, opts) => new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', opts);

const state = { kind: 'monthly', anchor: null, from: '', to: '', categoryId: '', accountId: '', type: '', currency: '' };

/** Moves the anchor date one period back or forward. */
function shift(anchor, kind, step) {
    const d = new Date(`${anchor}T00:00:00`);
    if (kind === 'daily') d.setDate(d.getDate() + step);
    if (kind === 'weekly') d.setDate(d.getDate() + 7 * step);
    if (kind === 'monthly') { d.setDate(1); d.setMonth(d.getMonth() + step); }
    if (kind === 'yearly') { d.setMonth(0, 1); d.setFullYear(d.getFullYear() + step); }
    return isoDate(d);
}

function url() {
    const a = state.anchor;
    const cur = state.currency ? `&currency=${state.currency}` : '';
    if (state.kind === 'daily') return `/reports/daily?date=${a}${cur}`;
    if (state.kind === 'weekly') return `/reports/weekly?date=${a}${cur}`;
    if (state.kind === 'monthly') return `/reports/monthly?month=${a.slice(0, 7)}${cur}`;
    if (state.kind === 'yearly') return `/reports/yearly?year=${a.slice(0, 4)}${cur}`;
    const p = new URLSearchParams({ from: state.from, to: state.to });
    if (state.currency) p.set('currency', state.currency);
    ['categoryId', 'accountId', 'type'].forEach((k) => { if (state[k]) p.set(k, state[k]); });
    return `/reports/custom?${p}`;
}

function title(r) {
    if (r.kind === 'DAILY') return fmt(r.from, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
    if (r.kind === 'MONTHLY') return fmt(r.from, { month: 'long', year: 'numeric' });
    if (r.kind === 'YEARLY') return r.from.slice(0, 4);
    return `${fmt(r.from, { day: 'numeric', month: 'short' })} – ${fmt(r.to, { day: 'numeric', month: 'short', year: 'numeric' })}`;
}

export async function renderReports(root) {
    const reload = () => renderReports(root);
    const today = isoDate(new Date());
    state.anchor ??= today;
    if (!state.from) { state.to = today; state.from = shift(today, 'monthly', -1); }
    const [accounts, expenseCats, incomeCats, me, currencies] = await Promise.all([store.accounts(), store.expenseCategories(),
        store.incomeCategories(), store.me(), store.currencies()]);
    const used = [...new Set([me.preferences.defaultCurrency, ...accounts.map((a) => a.currency)])];

    const nav = state.kind === 'custom'
        ? el('form', { class: 'report-filters', onsubmit: (e) => {
            e.preventDefault();
            const f = e.target;
            Object.assign(state, { from: f.from.value, to: f.to.value, categoryId: f.categoryId.value, accountId: f.accountId.value, type: f.type.value });
            reload();
        } },
        field('From', input('from', { type: 'date', value: state.from, max: today, required: true })),
        field('To', input('to', { type: 'date', value: state.to, required: true })),
        field('Category', select('categoryId', [{ value: '', label: 'All categories' },
            ...categoryOptions(expenseCats, state.categoryId), ...categoryOptions(incomeCats, state.categoryId)])),
        field('Account', select('accountId', [{ value: '', label: 'All accounts' },
            ...accounts.map((a) => ({ value: a.id, label: a.name, selected: a.id === state.accountId }))])),
        field('Type', select('type', TYPES.map((t) => ({ ...t, selected: t.value === state.type })))),
        el('button', { class: 'btn primary', type: 'submit', text: 'Run report' }))
        : null;
    const period = el('div', { class: 'period-nav' });
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Reports' }), el('p', { text: 'Built from recorded transactions only. Plans and expected income never count.' })),
        el('div', { class: 'head-actions' },
            used.length > 1 || state.currency
                ? select('reportCurrency', [{ group: 'Report in', options: used.map((code) => ({ value: code, label: code,
                    selected: code === (state.currency || me.preferences.defaultCurrency) })) }],
                { class: 'input compact', 'aria-label': 'Report currency', onchange: (e) => {
                    state.currency = e.target.value === me.preferences.defaultCurrency ? '' : e.target.value; reload();
                } })
                : null,
            segmented(KINDS, state.kind, (v) => { state.kind = v; reload(); }, 'Report period')));
    const body = el('div', { class: 'stack' },
        el('div', { class: 'kpis' }, [1, 2, 3, 4].map(() => el('section', { class: 'card' }, skeleton(14, '40%'), skeleton(28)))),
        el('section', { class: 'card' }, skeleton(200)));
    fill(root, head, nav, period, body);

    let r;
    try {
        r = await api.get(url());
    } catch (e) {
        fill(body, el('section', { class: 'card' }, empty(errorText(e), 'alert')));
        return;
    }
    if (state.kind !== 'custom') {
        const next = shift(state.anchor, state.kind, 1);
        const atEnd = r.to >= r.asOf;
        fill(period,
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Previous period',
                onclick: () => { state.anchor = shift(state.anchor, state.kind, -1); reload(); } }, icon('left')),
            el('div', { class: 'period-label' }, el('b', { text: title(r) }),
                el('span', { class: 'faint', text: r.complete ? 'Complete' : `So far, up to ${shortDate(r.asOf)}` })),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Next period', disabled: atEnd,
                onclick: () => { state.anchor = next > today ? today : next; reload(); } }, icon('right')),
            atEnd ? null : el('button', { class: 'btn sm', type: 'button', text: 'Today', onclick: () => { state.anchor = today; reload(); } }));
    } else {
        fill(period, el('div', { class: 'period-label' }, el('b', { text: title(r) }), r.filters ? el('span', { class: 'chip', text: 'Filtered' }) : null));
    }
    fill(body, content(r));
}

function delta(pct, { inverse = false } = {}) {
    if (pct === null || pct === undefined) return null;
    const n = Number(pct);
    if (n === 0) return el('span', { class: 'delta', text: '0%' });
    const good = inverse ? n < 0 : n > 0;
    return el('span', { class: `delta ${good ? 'good' : 'bad'}` }, icon(n > 0 ? 'up' : 'down'), `${Math.abs(Math.round(n))}%`);
}

function stat(label, value, sub, d, tone = '') {
    return el('section', { class: 'card kpi' },
        el('div', { class: 'kpi-top' }, el('span', { class: 'kpi-label', text: label }), d),
        el('div', { class: `kpi-value num ${tone}`.trim(), text: value }),
        el('div', { class: 'kpi-foot' }, sub.filter(Boolean).map((x) => el('span', { text: x }))));
}

function content(r) {
    const c = r.currency;
    const s = r.summary;
    const cmp = r.comparison;
    const vs = r.kind === 'DAILY' ? 'vs the day before' : cmp.basis === 'SAME_POINT' ? 'vs same point last period' : 'vs previous period';

    const kpis = el('div', { class: 'kpis' },
        stat('Income', money(s.income, c), [plural(s.incomeCount, 'entry', 'entries'), `prev ${money(cmp.income, c)}`], delta(cmp.incomeChange)),
        stat('Spent', money(s.expense, c), [Number(s.interest) ? `incl. ${money(s.interest, c)} interest` : plural(s.expenseCount, 'expense'),
            `prev ${money(cmp.expense, c)}`], delta(cmp.expenseChange, { inverse: true })),
        stat('Net cash flow', money(s.netCashFlow, c), [s.savingsRate !== null ? `Saved ${Math.round(Number(s.savingsRate))}%` : 'No income', vs],
            null, Number(s.netCashFlow) >= 0 ? 'pos' : 'neg'),
        stat('Average per day', money(s.averageDailyExpense, c), [`Avg expense ${money(s.averageExpense, c)}`,
            r.highestSpending && Number(r.highestSpending.amount) ? `Peak ${r.granularity === 'MONTH' ? fmt(r.highestSpending.date, { month: 'short' }) : shortDate(r.highestSpending.date)}` : null], null));

    const insights = r.insights.length ? card('Insights', {},
        el('div', { class: 'insights' }, r.insights.map((i) => el('div', { class: `insight ${i.tone.toLowerCase()}` },
            el('span', { class: 'insight-icon' }, icon(TONE_ICON[i.tone] ?? 'trend')), el('p', { text: i.message }))))) : null;

    const chart = r.kind === 'DAILY' ? null : card(r.granularity === 'MONTH' ? 'Income vs spending by month' : 'Spending by day', {},
        r.series.some((p) => Number(p.expense) || Number(p.income))
            ? (r.granularity === 'MONTH'
                ? [trendBars(r.series.map((p) => ({ month: p.date.slice(0, 7), income: p.income, expense: p.expense })), c),
                    el('div', { class: 'legend' }, el('span', {}, el('i', { style: { background: 'var(--income)' } }), 'Income'),
                        el('span', {}, el('i', { style: { background: 'var(--expense)' } }), 'Spending'))]
                : dailyBars(r.series.map((p) => ({ date: p.date, amount: p.expense })), c))
            : empty('Nothing recorded in this period.', 'calendar'));

    const categories = card('Spending by category', { sub: vs }, categoryTable(r.expenseByCategory, c, 'No spending in this period.'));
    const incomeCats = r.incomeByCategory.length ? card('Income by category', {}, categoryTable(r.incomeByCategory, c, '')) : null;

    const grid = el('div', { class: 'grid' },
        chart ? el('div', { class: 'col-8' }, chart) : null,
        el('div', { class: chart ? 'col-4' : 'col-6' }, movements(r)),
        el('div', { class: 'col-6' }, categories),
        el('div', { class: 'col-6' }, accountsCard(r)),
        incomeCats ? el('div', { class: 'col-6' }, incomeCats) : null,
        r.budgets?.length ? el('div', { class: 'col-6' }, budgetsCard(r)) : null,
        r.topExpenses.length ? el('div', { class: 'col-6' }, topCard(r)) : null);

    const note = el('p', { class: 'faint report-note' },
        r.basis,
        ...r.conversions.filter((x) => x.convertedEntries).map((x) => ` ${x.currency}: ${plural(x.convertedEntries, 'entry', 'entries')} converted`
            + ` with rates from ${shortDate(x.firstRateDate)}${x.lastRateDate !== x.firstRateDate ? ` to ${shortDate(x.lastRateDate)}` : ''}.`),
        r.unconvertedEntries ? ` ${plural(r.unconvertedEntries, 'entry', 'entries')} had no exchange rate and aren't included.` : '',
        ` Compared with ${shortDate(cmp.from)} – ${shortDate(cmp.to)}.`);
    return [kpis, insights, grid, note];
}

function categoryTable(lines, c, emptyText) {
    if (!lines.length) return empty(emptyText, 'target');
    const max = Math.max(...lines.map((l) => Number(l.total)));
    return el('div', { class: 'rows' }, lines.map((l) => el('div', { class: 'row cat-row' },
        el('div', { class: 'grow' },
            el('div', { class: 'cat-top' }, el('span', { class: 'title', text: l.name ?? '—' }),
                el('span', { class: 'amount num', text: money(l.total, c) })),
            el('div', { class: 'progress thin' }, el('i', { style: { width: `${max ? (Number(l.total) / max) * 100 : 0}%` } })),
            el('div', { class: 'cat-foot' }, el('span', { text: `${Math.round(Number(l.share ?? 0))}% · ${plural(l.count, 'entry', 'entries')}` }),
                l.change === null ? (Number(l.total) ? el('span', { class: 'faint', text: 'new' }) : null) : delta(l.change, { inverse: true }))))));
}

/** Every kind of money movement, kept apart, so nothing is counted twice. */
function movements(r) {
    const c = r.currency;
    const s = r.summary;
    const line = (label, value, hint, cls = '') => (Number(value) || cls === 'always'
        ? el('div', { class: 'move' }, el('span', {}, el('span', { text: label }), hint ? el('small', { class: 'faint', text: hint }) : null),
            el('b', { class: 'num', text: money(value, c) }))
        : null);
    const rows = [
        line('Income', s.income, null, 'always'),
        line('Spending', s.consumption, 'consumption', 'always'),
        line('Loan interest', s.interest, 'part of spending'),
        line('Transfers', s.transfers, 'between your accounts'),
        line('Card bills paid', s.cardBillPayments, 'purchases already counted'),
        line('Invested', s.invested, 'into investment accounts'),
        line('Withdrawn', s.withdrawn, 'from investments'),
        line('Loan principal repaid', s.loanPrincipalRepaid, 'reduces the loan'),
        line('Loan received', s.loanDisbursed, 'not income'),
        r.lending ? line('Lent', r.lending.lent) : null,
        r.lending ? line('Repaid to you', r.lending.repaidToYou) : null,
        r.lending ? line('Borrowed', r.lending.borrowed) : null,
        r.lending ? line('You repaid', r.lending.youRepaid) : null,
    ].filter(Boolean);
    const salary = r.salary && Number(r.salary.received)
        ? el('div', { class: 'salary' },
            el('div', { class: 'budget-top' }, el('span', { text: 'Salary used' }),
                el('b', { class: 'num', text: `${Math.round(Number(r.salary.utilization))}%` })),
            el('div', { class: `progress ${Number(r.salary.utilization) > 100 ? 'bad' : Number(r.salary.utilization) > 85 ? 'warn' : ''}` },
                el('i', { style: { width: `${Math.min(100, Number(r.salary.utilization))}%` } })),
            el('div', { class: 'budget-foot' }, el('span', { text: `Spent ${money(r.salary.spent, c)} of ${money(r.salary.received, c)} salary` })))
        : null;
    return card('Where money moved', {}, el('div', { class: 'moves' }, rows), salary);
}

function accountsCard(r) {
    const c = r.currency;
    if (!r.accounts.length) return card('Accounts', {}, empty('No accounts yet.', 'wallet'));
    return card('Accounts', { sub: 'money in and out' },
        el('div', { class: 'table-wrap' }, el('table', { class: 'schedule report-table' },
            el('thead', {}, el('tr', {}, ['Account', 'In', 'Out', 'Balance now'].map((h) => el('th', { text: h })))),
            el('tbody', {}, r.accounts.map((a) => el('tr', {},
                el('td', {}, el('div', { text: a.name }), el('small', { class: 'faint', text: `${titleCase(a.type)} · ${a.currency}` })),
                el('td', { class: 'num pos', text: Number(a.inflow) ? money(a.inflow, a.currency) : '—' }),
                el('td', { class: 'num neg', text: Number(a.outflow) ? money(a.outflow, a.currency) : '—' }),
                el('td', { class: 'num', text: money(a.currentBalance, a.currency) })))))));
}

function budgetsCard(r) {
    const c = r.currency;
    return card('Budgets', { sub: `as of ${shortDate(r.to < r.asOf ? r.to : r.asOf)}` }, el('div', {}, r.budgets.map((b) => {
        const tone = b.status === 'EXCEEDED' ? 'bad' : b.status === 'ALERT' ? 'warn' : '';
        return el('div', { class: 'budget' },
            el('div', { class: 'budget-top' }, el('span', { class: 'title', text: b.name }),
                el('span', { class: 'num' }, el('b', { text: money(b.spent, c) }), el('span', { class: 'faint', text: ` / ${money(b.amount, c)}` }))),
            el('div', { class: `progress ${tone}` }, el('i', { style: { width: `${Math.min(100, Number(b.percentUsed))}%` } })),
            el('div', { class: 'budget-foot' }, el('span', { text: `${b.percentUsed}% used` }),
                el('span', { text: `${shortDate(b.periodStart)} – ${shortDate(b.periodEnd)}` })));
    })));
}

function topCard(r) {
    const c = r.currency;
    return card('Largest expenses', {}, el('div', { class: 'rows' }, r.topExpenses.map((t) => el('div', { class: 'row' },
        el('div', { class: 'tile-icon expense' }, icon('down')),
        el('div', { class: 'grow' },
            el('div', { class: 'title', text: t.merchant || t.description || t.category || 'Expense' }),
            el('div', { class: 'meta', text: [t.category, shortDate(t.date),
                t.originalCurrency !== c ? money(t.originalAmount, t.originalCurrency) : null].filter(Boolean).join(' · ') })),
        el('div', { class: 'amount num', text: money(t.amount, c) })))));
}
