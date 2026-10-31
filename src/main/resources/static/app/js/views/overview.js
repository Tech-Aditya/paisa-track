// Overview: KPIs, charts, budgets, expected income, accounts and recent activity.

import { api, errorText } from '../api.js';
import { colorVar, dailyBars, donut, trendBars } from '../charts.js';
import { card, entries, kpi, smallButton, txRow } from '../components.js';
import { el, empty, fill, icon, input, segmented, skeleton, toast } from '../dom.js';
import { openAccountDialog, openBudgetDialog, openIncomeSourceDialog, openTransactionDialog, recordExpectedIncome } from '../forms.js';
import { addDays, greeting, isoDate, longDate, money, plural, shortDate } from '../format.js';
import { store } from '../store.js';
import { debtRow, openDebtDetail } from './debts.js';
import { openLoanDetail } from './loans.js';
import { occurrenceRow } from './recurring.js';

const PRESETS = [
    { value: 'month', label: 'This month' },
    { value: 'last-month', label: 'Last month' },
    { value: '30d', label: '30 days' },
    { value: 'custom', label: 'Custom' },
];

let preset = 'month';
let custom = { from: '', to: '' };

function rangeFor(choice, today) {
    const t = new Date(`${today}T00:00:00`);
    if (choice === 'last-month') {
        const first = new Date(t.getFullYear(), t.getMonth() - 1, 1);
        const last = new Date(t.getFullYear(), t.getMonth(), 0);
        return { from: isoDate(first), to: isoDate(last) };
    }
    if (choice === '30d') return { from: addDays(today, -29), to: today };
    if (choice === 'custom' && custom.from && custom.to) return { ...custom };
    return null;
}

export async function renderOverview(root) {
    const me = await store.me();
    const today = isoDate(new Date());
    const range = rangeFor(preset, today);
    const reload = () => renderOverview(root);

    fill(root, header(me, today, reload), loadingGrid());
    let d;
    try {
        d = await api.get(`/dashboard${range ? `?from=${range.from}&to=${range.to}` : ''}`);
    } catch (e) {
        fill(root, header(me, today, reload), el('div', { class: 'card' }, empty(errorText(e), 'alert',
            el('button', { class: 'btn', type: 'button', text: 'Try again', onclick: reload }))));
        return;
    }
    fill(root, header(me, d.today, reload), content(d, range, reload));
}

function header(me, today, reload) {
    const first = me.fullName.split(' ')[0];
    const customInputs = preset === 'custom'
        ? el('form', { class: 'head-actions', onsubmit: (e) => {
            e.preventDefault();
            custom = { from: e.target.from.value, to: e.target.to.value };
            if (custom.from && custom.to) reload();
        } },
        input('from', { type: 'date', value: custom.from, 'aria-label': 'From date' }),
        input('to', { type: 'date', value: custom.to, 'aria-label': 'To date' }),
        el('button', { class: 'btn', type: 'submit', text: 'Apply' }))
        : null;
    return el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: `${greeting()}, ${first}` }), el('p', { text: longDate(today) })),
        el('div', { class: 'head-actions' },
            segmented(PRESETS, preset, (v) => { preset = v; reload(); }, 'Date range'),
            customInputs));
}

function loadingGrid() {
    const block = (h) => el('section', { class: 'card' }, skeleton(14, '40%'), el('div', { style: { height: '12px' } }), skeleton(h));
    return [
        el('div', { class: 'kpis' }, [1, 2, 3, 4].map(() => block(28))),
        el('div', { class: 'grid' }, el('div', { class: 'col-8' }, block(180)), el('div', { class: 'col-4' }, block(180))),
    ];
}

function content(d, range, reload) {
    const c = d.currency;
    const p = d.periods;
    const span = p.selected ?? p.month;
    const spanLabel = range ? `${shortDate(range.from)} – ${shortDate(range.to)}` : 'This month';
    const overall = d.budgets.find((b) => b.scope === 'OVERALL' && b.period === 'MONTHLY' && b.utilization?.status !== 'NOT_IN_EFFECT');

    const kpis = el('div', { class: 'kpis' },
        kpi({ label: 'Spent today', value: money(p.today.expense, c), iconName: 'calendar', tone: 'neg',
            foot: [entries(p.today.expenseCount)] }),
        kpi({ label: 'This week', value: money(p.week.expense, c), iconName: 'down',
            foot: [entries(p.week.expenseCount), `${shortDate(p.week.from)} – ${shortDate(p.week.to)}`] }),
        kpi({ label: range ? 'Spent in range' : 'Spent this month', value: money(span.expense, c), iconName: 'target', tone: 'accent',
            foot: [entries(span.expenseCount), !range && overall ? `${overall.utilization.percentUsed}% of ${money(overall.amount, c)} budget` : null] }),
        kpi({ label: 'Net cash flow', value: money(p.month.netCashFlow, c), iconName: 'trend',
            tone: Number(p.month.netCashFlow) >= 0 ? 'pos' : 'neg',
            foot: [`Income ${money(p.month.income, c)}`, d.savingsRate !== null ? `Saved ${d.savingsRate}%` : null] }));

    const grid = el('div', { class: 'grid' },
        el('div', { class: 'col-8' }, card('Daily spending', { sub: spanLabel }, dailyChart(d))),
        el('div', { class: 'col-4' }, card('By category', { sub: spanLabel }, categoryBreakdown(d))),
        el('div', { class: 'col-4' }, card('Budgets', { action: smallButton('New', () => openBudgetDialog({ onSaved: reload })) }, budgets(d))),
        el('div', { class: 'col-4' }, card('Upcoming bills', {
            action: el('a', { class: 'btn sm ghost', href: '#/recurring' }, 'Open', icon('right')) }, billsCard(d, reload))),
        el('div', { class: 'col-4' }, card('Accounts', { action: smallButton('Account', () => openAccountDialog({ onSaved: reload })) }, accounts(d))),
        el('div', { class: 'col-8' }, card('Income vs expense', { sub: 'last 6 months' }, trend(d))),
        el('div', { class: 'col-4' }, card('Expected income', { sub: 'this month',
            action: smallButton('Source', () => openIncomeSourceDialog({ onSaved: reload })) }, expectedIncome(d, reload))),
        el('div', { class: 'col-6' }, card('Loans & EMIs', {
            action: el('a', { class: 'btn sm ghost', href: '#/loans' }, 'Open', icon('right')) }, loansCard(d, reload))),
        el('div', { class: 'col-6' }, card('Lending & borrowing', {
            action: el('a', { class: 'btn sm ghost', href: '#/debts' }, 'Open', icon('right')) }, debtsCard(d, reload))),
        el('div', { class: 'col-12' }, card('Recent activity', {
            action: el('a', { class: 'btn sm ghost', href: '#/transactions' }, 'View all', icon('right')) }, recent(d, reload))));

    const note = el('p', { class: 'faint', style: { marginTop: '16px', fontSize: '12.5px' } },
        d.basis + (d.otherCurrencyTransactions ? ` ${plural(d.otherCurrencyTransactions, 'entry', 'entries')} in other currencies this month aren't included.` : ''));
    return [kpis, grid, note];
}

function dailyChart(d) {
    if (!d.dailySpending.some((x) => Number(x.amount) > 0)) return empty('No spending in this period yet.', 'calendar');
    return dailyBars(d.dailySpending, d.currency);
}

function categoryBreakdown(d) {
    if (!d.expenseByCategory.length) return empty('Spending by category appears here.', 'target');
    const { chart, slices } = donut(d.expenseByCategory, d.currency);
    const total = slices.reduce((s, x) => s + Number(x.total), 0);
    return el('div', { class: 'donut-wrap' }, chart,
        el('div', { class: 'rows' }, slices.map((s, i) => el('div', { class: 'row' },
            el('span', { class: 'swatch', style: { background: colorVar(i) } }),
            el('span', { class: 'grow title', text: s.name }),
            el('span', { class: 'faint num', text: `${total ? Math.round((Number(s.total) / total) * 100) : 0}%` }),
            el('span', { class: 'amount num', text: money(s.total, d.currency) })))));
}

function budgets(d) {
    const live = d.budgets.filter((b) => b.utilization && b.utilization.status !== 'NOT_IN_EFFECT');
    if (!live.length) return empty('Set a monthly limit to see how you are doing.', 'target');
    return el('div', {}, live.map((b) => {
        const u = b.utilization;
        const tone = u.status === 'EXCEEDED' ? 'bad' : u.status === 'ALERT' ? 'warn' : '';
        return el('div', { class: 'budget' },
            el('div', { class: 'budget-top' },
                el('span', { class: 'title', text: b.name }),
                el('span', { class: 'num' }, el('b', { text: money(u.spent, b.currency) }), el('span', { class: 'faint', text: ` / ${money(b.amount, b.currency)}` }))),
            el('div', { class: `progress ${tone}` }, el('i', { style: { width: `${Math.min(100, Number(u.percentUsed))}%` } })),
            el('div', { class: 'budget-foot' },
                el('span', { text: `${u.percentUsed}% used` }),
                el('span', { text: u.status === 'EXCEEDED'
                    ? `Over by ${money(-u.remaining, b.currency)}`
                    : `${money(u.dailyAllowance, b.currency)}/day · ${plural(u.daysLeft, 'day')} left` })));
    }));
}

const INCOME_STATUS = {
    RECEIVED: ['Received', 'ok'], PARTIALLY_RECEIVED: ['Partly received', 'warn'],
    PENDING: ['Pending', ''], OVERDUE: ['Overdue', 'bad'], NOT_EXPECTED: ['Unplanned', ''],
};

function expectedIncome(d, reload) {
    if (!d.expectedIncome.length) return empty('Add your salary to track when it arrives.', 'income');
    return el('div', { class: 'rows' }, d.expectedIncome.map((i) => {
        const [label, tone] = INCOME_STATUS[i.status] ?? [i.status, ''];
        const open = ['PENDING', 'OVERDUE', 'PARTIALLY_RECEIVED'].includes(i.status);
        return el('div', { class: 'row' },
            el('div', { class: 'tile-icon income' }, icon('income')),
            el('div', { class: 'grow' },
                el('div', { class: 'title', text: i.sourceName }),
                el('div', { class: 'meta', text: i.expectedAmount
                    ? `${money(i.receivedAmount, i.currency)} of ${money(i.expectedAmount, i.currency)} · due ${shortDate(i.expectedDate)}`
                    : `${money(i.receivedAmount, i.currency)} received` })),
            open
                ? el('button', { class: 'btn sm', type: 'button', onclick: () => recordExpectedIncome(i, d.today, reload) }, 'Record')
                : null,
            el('span', { class: `chip ${tone}`, text: label }));
    }));
}

const ACCOUNT_ICON = { CASH: 'cash', SAVINGS: 'bank', CURRENT: 'bank', CREDIT_CARD: 'card', WALLET: 'wallet', INVESTMENT: 'trend', OTHER: 'wallet' };

function accounts(d) {
    const a = d.accounts;
    return el('div', {},
        el('div', { class: 'rows' }, a.accounts.map((acc) => el('div', { class: 'row' },
            el('div', { class: 'tile-icon' }, icon(ACCOUNT_ICON[acc.type] ?? 'wallet')),
            el('div', { class: 'grow' },
                el('div', { class: 'title', text: acc.name }),
                el('div', { class: 'meta', text: acc.nature === 'LIABILITY' ? 'Amount owed' : acc.type.replace('_', ' ').toLowerCase() })),
            el('div', { class: `amount num ${acc.nature === 'LIABILITY' ? 'neg' : ''}`, text: money(acc.currentBalance, acc.currency) })))),
        Number(a.loanOutstanding)
            ? el('div', { class: 'total sub-total' }, el('span', { text: 'Loans outstanding' }),
                el('span', { class: 'num neg', text: `−${money(a.loanOutstanding, d.currency)}` }))
            : null,
        el('div', { class: 'total' }, el('span', { text: 'Net worth' }), el('span', { class: 'num', text: money(a.netWorth, d.currency) })));
}

function billsCard(d, reload) {
    const x = d.recurring;
    if (!x.upcoming.length) {
        return empty('Add rent, bills and subscriptions to get reminded before they are due.', 'repeat',
            el('a', { class: 'btn sm', href: '#/recurring' }, icon('plus'), 'Add recurring'));
    }
    return el('div', {},
        el('div', { class: 'debt-stats' },
            el('div', {}, el('span', { class: 'faint', text: 'To pay' }), el('b', { class: 'num neg', text: money(x.expectedOutflow, d.currency) })),
            el('div', {}, el('span', { class: 'faint', text: x.overdueCount ? 'Overdue' : 'Expected in' }),
                el('b', { class: `num ${x.overdueCount ? 'neg' : 'pos'}`, text: x.overdueCount ? String(x.overdueCount) : money(x.expectedInflow, d.currency) }))),
        el('div', { class: 'rows compact' }, x.upcoming.slice(0, 4).map((o) => occurrenceRow(o, null, reload))));
}

function loansCard(d, reload) {
    const x = d.loans;
    if (!x.activeLoans) return empty('Add a home, car or personal loan to track EMIs.', 'bank');
    return el('div', {},
        el('div', { class: 'debt-stats' },
            el('div', {}, el('span', { class: 'faint', text: 'Outstanding' }), el('b', { class: 'num neg', text: money(x.outstandingPrincipal, d.currency) })),
            el('div', {}, el('span', { class: 'faint', text: 'Monthly EMI' }), el('b', { class: 'num', text: money(x.monthlyEmi, d.currency) }))),
        x.upcoming.length
            ? el('div', { class: 'rows' }, x.upcoming.slice(0, 4).map((u) => el('button', {
                class: 'row debt-row', type: 'button', onclick: () => openLoanDetail({ id: u.loanId, name: u.loanName }, reload) },
            el('div', { class: `tile-icon ${u.status === 'MISSED' ? 'expense' : 'loan'}` }, icon('calendar')),
            el('div', { class: 'grow' },
                el('div', { class: 'title', text: `${u.loanName} · EMI ${u.number}` }),
                el('div', { class: 'meta', text: u.status === 'MISSED' ? `Missed · was due ${shortDate(u.dueDate)}` : `Due ${shortDate(u.dueDate)}` })),
            el('div', { class: 'amount num', text: money(u.emiAmount, u.currency) }))))
            : el('p', { class: 'faint', text: 'No EMIs due in the next 30 days.' }));
}

function trend(d) {
    if (!d.monthlyTrend.some((m) => Number(m.income) || Number(m.expense))) return empty('Your monthly trend builds up as you record.', 'trend');
    return [trendBars(d.monthlyTrend, d.currency),
        el('div', { class: 'legend' },
            el('span', {}, el('i', { style: { background: 'var(--income)' } }), 'Income'),
            el('span', {}, el('i', { style: { background: 'var(--expense)' } }), 'Expense'))];
}

function debtsCard(d, reload) {
    const x = d.debts;
    return el('div', {},
        el('div', { class: 'debt-stats' },
            el('div', {}, el('span', { class: 'faint', text: 'Owed to you' }), el('b', { class: 'num pos', text: money(x.owedToYou, d.currency) })),
            el('div', {}, el('span', { class: 'faint', text: 'You owe' }), el('b', { class: 'num neg', text: money(x.youOwe, d.currency) }))),
        x.dueSoon.length
            ? el('div', { class: 'rows' }, x.dueSoon.map((debt) => debtRow(debt, () => openDebtDetail(debt, reload))))
            : el('p', { class: 'faint', text: x.overdueCount ? '' : 'Nothing due in the next two weeks.' }),
        el('p', { class: 'faint', style: { fontSize: '12px', marginTop: '8px' }, text: 'Tracked separately, not part of net worth.' }));
}

function recent(d, reload) {
    if (!d.recentTransactions.length) {
        return empty('Nothing recorded yet.', 'inbox',
            el('button', { class: 'btn primary', type: 'button', onclick: () => openTransactionDialog({ preset: { today: d.today }, onSaved: reload }).catch((e) => toast(errorText(e), 'error')) },
                icon('plus'), 'Add your first expense'));
    }
    const edit = (t) => openTransactionDialog({ existing: t, onSaved: reload }).catch((e) => toast(errorText(e), 'error'));
    return el('div', { class: 'rows' }, d.recentTransactions.slice(0, 8).map((t) => txRow(t, null, t.status === 'ACTIVE' && !t.loanId ? edit : null)));
}
