// Financial calendar: each day's spending and income, plus bills, EMIs, debt due dates and
// expected salary. Tap a day for its details.

import { api, errorText } from '../api.js';
import { card } from '../components.js';
import { el, empty, fill, icon, openPanel, skeleton } from '../dom.js';
import { isoDate, longDate, money, moneyCompact } from '../format.js';

const KIND = {
    RECURRING: { label: 'Bill', icon: 'repeat' },
    EMI: { label: 'EMI', icon: 'bank' },
    DEBT_DUE: { label: 'Lending', icon: 'people' },
    INCOME_EXPECTED: { label: 'Expected income', icon: 'income' },
};
const STATUS = { DONE: ['Done', 'ok'], OVERDUE: ['Overdue', 'bad'], DUE: ['Due today', 'warn'], UPCOMING: ['Upcoming', ''], SKIPPED: ['Skipped', ''] };
const WEEK = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

let month = null; // "YYYY-MM"

function shiftMonth(ym, step) {
    const d = new Date(`${ym}-01T00:00:00`);
    d.setMonth(d.getMonth() + step);
    return isoDate(d).slice(0, 7);
}

export async function renderCalendar(root) {
    month ??= isoDate(new Date()).slice(0, 7);
    const reload = () => renderCalendar(root);
    const title = new Date(`${month}-01T00:00:00`).toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Calendar' }), el('p', { text: 'What you spent and earned each day, and what is coming up.' })),
        el('div', { class: 'head-actions period-nav' },
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Previous month', onclick: () => { month = shiftMonth(month, -1); reload(); } }, icon('left')),
            el('b', { class: 'period-label', text: title }),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Next month', onclick: () => { month = shiftMonth(month, 1); reload(); } }, icon('right')),
            el('button', { class: 'btn sm', type: 'button', text: 'Today', onclick: () => { month = isoDate(new Date()).slice(0, 7); reload(); } })));
    const body = el('div', { class: 'stack' }, el('section', { class: 'card' }, skeleton(320)));
    fill(root, head, body);

    let c;
    try {
        c = await api.get(`/calendar?month=${month}`);
    } catch (e) {
        fill(body, el('section', { class: 'card' }, empty(errorText(e), 'alert')));
        return;
    }
    const days = new Map(c.days.map((d) => [d.date, d]));
    const events = new Map();
    c.events.forEach((e) => events.set(e.date, [...(events.get(e.date) ?? []), e]));

    const first = new Date(`${c.month}-01T00:00:00`);
    const lead = (first.getDay() + 6) % 7; // Monday first
    const count = new Date(first.getFullYear(), first.getMonth() + 1, 0).getDate();
    const cells = [];
    for (let i = 0; i < lead; i++) cells.push(el('div', { class: 'cal-cell blank', 'aria-hidden': 'true' }));
    for (let n = 1; n <= count; n++) {
        const date = `${c.month}-${String(n).padStart(2, '0')}`;
        const d = days.get(date);
        const ev = events.get(date) ?? [];
        const open = () => openDay(date, d, ev, c.currency);
        cells.push(el('button', {
            class: `cal-cell ${date === c.today ? 'today' : ''} ${date > c.today ? 'future' : ''}`.trim(), type: 'button', onclick: open,
            'aria-label': `${longDate(date)}${d ? `, spent ${money(d.expense, c.currency)}` : ''}${ev.length ? `, ${ev.length} due` : ''}`,
        },
        el('span', { class: 'cal-day', text: String(n) }),
        d && Number(d.expense) ? el('span', { class: 'cal-amt neg num', text: `−${moneyCompact(d.expense, c.currency)}` }) : null,
        d && Number(d.income) ? el('span', { class: 'cal-amt pos num', text: `+${moneyCompact(d.income, c.currency)}` }) : null,
        ev.length ? el('span', { class: 'cal-dots' }, ev.slice(0, 4).map((e) => el('i', {
            class: `dot ${e.status === 'DONE' ? 'done' : e.status === 'OVERDUE' ? 'overdue' : e.direction === 'IN' ? 'in' : 'out'}`,
        }))) : null));
    }

    const net = Number(c.income) - Number(c.expense);
    const pending = c.events.filter((e) => e.status !== 'DONE' && e.status !== 'SKIPPED');
    fill(body,
        el('div', { class: 'kpis' },
            kpiBox('Income', money(c.income, c.currency), 'pos'),
            kpiBox('Spent', money(c.expense, c.currency), 'neg'),
            kpiBox('Net', money(net, c.currency), net < 0 ? 'neg' : 'pos'),
            kpiBox('Still due', String(pending.length), pending.some((e) => e.status === 'OVERDUE') ? 'neg' : '')),
        el('section', { class: 'card cal' },
            el('div', { class: 'cal-grid cal-week', 'aria-hidden': 'true' }, WEEK.map((w) => el('span', { text: w }))),
            el('div', { class: 'cal-grid' }, cells),
            el('div', { class: 'cal-legend faint' },
                el('span', {}, el('i', { class: 'dot out' }), 'Bill / EMI'), el('span', {}, el('i', { class: 'dot in' }), 'Money due to you'),
                el('span', {}, el('i', { class: 'dot overdue' }), 'Overdue'), el('span', {}, el('i', { class: 'dot done' }), 'Done')),
            c.unconverted.length ? el('p', { class: 'faint', text: `Not in the totals (no exchange rate): ${c.unconverted.join(', ')}` }) : null),
        card('This month’s dates', { sub: 'Bills, EMIs, lending and expected income' },
            c.events.length ? el('div', { class: 'rows' }, c.events.map((e) => eventRow(e)))
                : empty('Nothing scheduled this month.', 'calendar')));
}

function kpiBox(label, value, tone) {
    return el('section', { class: 'card kpi' }, el('span', { class: 'faint', text: label }), el('b', { class: `num ${tone}`.trim(), text: value }));
}

function eventRow(e) {
    const [statusLabel, tone] = STATUS[e.status] ?? [e.status, ''];
    const k = KIND[e.kind] ?? { label: e.kind, icon: 'calendar' };
    return el('div', { class: 'row' },
        el('div', { class: `tile-icon ${e.direction === 'IN' ? 'income' : 'expense'}` }, icon(k.icon)),
        el('div', { class: 'grow' }, el('div', { class: 'title', text: e.title }),
            el('div', { class: 'meta', text: `${k.label} · ${new Date(`${e.date}T00:00:00`).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' })}` })),
        el('div', { class: 'debt-amount' },
            el('span', { class: `num ${e.direction === 'IN' ? 'pos' : e.direction === 'OUT' ? 'neg' : ''}`, text: money(e.amount, e.currency) }),
            el('span', { class: `chip ${tone}`.trim(), text: statusLabel })));
}

function openDay(date, d, ev, currency) {
    openPanel({
        title: longDate(date),
        content: () => el('div', { class: 'stack' },
            el('div', { class: 'debt-stats' },
                el('div', {}, el('span', { class: 'faint', text: 'Spent' }), el('b', { class: 'num neg', text: money(d?.expense ?? 0, currency) })),
                el('div', {}, el('span', { class: 'faint', text: 'Income' }), el('b', { class: 'num pos', text: money(d?.income ?? 0, currency) }))),
            d ? el('p', { class: 'faint', text: `${d.entries} ${d.entries === 1 ? 'entry' : 'entries'} recorded.` }) : null,
            ev.length ? el('div', { class: 'rows' }, ev.map(eventRow)) : el('p', { class: 'faint', text: 'Nothing due on this day.' })),
    });
}
