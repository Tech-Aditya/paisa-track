'use strict';

// PaisaTrack basic dashboard. Talks only to /api/v1. Tokens live in sessionStorage (cleared when the
// tab closes). All user data is inserted with textContent, never as HTML.

const API = '/api/v1';
const $ = (id) => document.getElementById(id);

const session = {
    get access() { return sessionStorage.getItem('pt.access'); },
    get refresh() { return sessionStorage.getItem('pt.refresh'); },
    save(auth) {
        sessionStorage.setItem('pt.access', auth.accessToken);
        sessionStorage.setItem('pt.refresh', auth.refreshToken);
    },
    clear() {
        sessionStorage.removeItem('pt.access');
        sessionStorage.removeItem('pt.refresh');
    },
};

class ApiError extends Error {
    constructor(message, code, fields) {
        super(message);
        this.code = code;
        this.fields = fields || [];
    }
}

// ---- HTTP ----

async function api(path, { method = 'GET', body, auth = true, headers = {}, retried = false } = {}) {
    const init = { method, headers: { Accept: 'application/json', ...headers } };
    if (body !== undefined) {
        init.headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(body);
    }
    if (auth && session.access) {
        init.headers.Authorization = `Bearer ${session.access}`;
    }
    const res = await fetch(API + path, init);
    if (res.status === 401 && auth && !retried && session.refresh && await refreshTokens()) {
        return api(path, { method, body, auth, headers, retried: true });
    }
    if (res.status === 401 && auth) {
        signedOut();
        throw new ApiError('Your session has expired. Please sign in again.', 'UNAUTHENTICATED');
    }
    if (res.status === 204) {
        return null;
    }
    const json = await res.json().catch(() => null);
    if (!res.ok || !json || !json.success) {
        const err = json && json.error ? json.error : {};
        throw new ApiError((json && json.message) || `Request failed (${res.status})`, err.code, err.fields);
    }
    return json.data;
}

let refreshing = null;

/** One refresh at a time: the API treats a reused refresh token as theft. */
function refreshTokens() {
    if (!refreshing) {
        refreshing = api('/auth/refresh', { method: 'POST', auth: false, body: { refreshToken: session.refresh } })
            .then((data) => { session.save(data); return true; })
            .catch(() => false)
            .finally(() => { refreshing = null; });
    }
    return refreshing;
}

function errorText(e) {
    if (e instanceof ApiError && e.fields.length) {
        return e.fields.map((f) => `${f.field} ${f.message}`).join('; ');
    }
    return e.message || 'Something went wrong';
}

// ---- DOM helpers ----

function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
        if (value === undefined || value === null || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
        if (child !== null && child !== undefined) node.append(child);
    }
    return node;
}

function fill(container, ...children) {
    container.replaceChildren(...children.flat());
}

function emptyNote(text) {
    return el('p', { class: 'empty', text });
}

const formatters = new Map();

function money(amount, currency) {
    if (amount === null || amount === undefined) return '–';
    let f = formatters.get(currency);
    if (!f) {
        f = new Intl.NumberFormat('en-IN', { style: 'currency', currency });
        formatters.set(currency, f);
    }
    return f.format(Number(amount));
}

function shortDate(iso) {
    return new Date(`${iso}T00:00:00`).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
}

function monthLabel(yearMonth) {
    return new Date(`${yearMonth}-01T00:00:00`).toLocaleDateString('en-IN', { month: 'short', year: '2-digit' });
}

function plural(n, one, many = `${one}s`) {
    return `${n} ${n === 1 ? one : many}`;
}

function showNotice(text) {
    const notice = $('notice');
    notice.textContent = text;
    notice.hidden = false;
    clearTimeout(showNotice.timer);
    showNotice.timer = setTimeout(() => { notice.hidden = true; }, 4000);
}

// ---- Auth view ----

let authMode = 'login';

function setAuthMode(mode) {
    authMode = mode;
    document.querySelectorAll('[data-auth-tab]').forEach((tab) => tab.classList.toggle('active', tab.dataset.authTab === mode));
    document.querySelectorAll('.register-only').forEach((node) => { node.hidden = mode !== 'register'; });
    $('auth-submit').textContent = mode === 'login' ? 'Sign in' : 'Create account';
    $('auth-form').password.autocomplete = mode === 'login' ? 'current-password' : 'new-password';
    $('auth-error').hidden = true;
}

async function submitAuth(event) {
    event.preventDefault();
    const form = event.target;
    const body = { email: form.email.value.trim(), password: form.password.value };
    if (authMode === 'register') {
        body.fullName = form.fullName.value.trim();
        body.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    }
    $('auth-submit').disabled = true;
    try {
        const data = await api(`/auth/${authMode === 'login' ? 'login' : 'register'}`, { method: 'POST', auth: false, body });
        session.save(data);
        form.reset();
        await showApp();
    } catch (e) {
        $('auth-error').textContent = errorText(e);
        $('auth-error').hidden = false;
    } finally {
        $('auth-submit').disabled = false;
    }
}

function signedOut() {
    session.clear();
    $('app-view').hidden = true;
    $('auth-view').hidden = false;
}

async function logout() {
    const refreshToken = session.refresh;
    if (refreshToken) {
        await api('/auth/logout', { method: 'POST', auth: false, body: { refreshToken } }).catch(() => null);
    }
    signedOut();
}

// ---- Theme ----

let theme = 'SYSTEM';

function applyTheme(value) {
    theme = value;
    if (value === 'LIGHT' || value === 'DARK') document.documentElement.dataset.theme = value.toLowerCase();
    else delete document.documentElement.dataset.theme;
}

async function toggleTheme() {
    const dark = document.documentElement.dataset.theme
        ? document.documentElement.dataset.theme === 'dark'
        : window.matchMedia('(prefers-color-scheme: dark)').matches;
    const next = dark ? 'LIGHT' : 'DARK';
    applyTheme(next);
    await api('/users/me/preferences', { method: 'PATCH', body: { theme: next } }).catch(() => null);
}

// ---- Dashboard ----

let range = null;
let dashboard = null;

async function showApp() {
    $('auth-view').hidden = true;
    $('app-view').hidden = false;
    const me = await api('/users/me');
    $('user-name').textContent = me.fullName;
    applyTheme(me.preferences.theme);
    await loadDashboard();
}

async function loadDashboard() {
    const query = range ? `?from=${range.from}&to=${range.to}` : '';
    dashboard = await api(`/dashboard${query}`);
    render(dashboard);
}

function render(d) {
    const c = d.currency;
    $('range-label').textContent = range ? `${shortDate(range.from)} – ${shortDate(range.to)}` : 'this month';
    $('basis').textContent = d.basis
        + (d.otherCurrencyTransactions ? ` ${d.otherCurrencyTransactions} transaction(s) in other currencies this month are not shown.` : '');

    const cards = [['Today', d.periods.today], ['This week', d.periods.week], ['This month', d.periods.month]];
    if (d.periods.selected) cards.push(['Selected range', d.periods.selected]);
    fill($('period-cards'), cards.map(([label, p]) => el('article', { class: 'card period' },
        el('h3', { text: label }),
        el('div', { class: 'big num expense', text: money(p.expense, c) }),
        el('div', { class: 'row' }, el('span', { class: 'muted', text: 'Spent' }), el('span', { class: 'num', text: plural(p.expenseCount, 'entry', 'entries') })),
        el('div', { class: 'row' }, el('span', { class: 'muted', text: 'Income' }), el('span', { class: 'num income', text: money(p.income, c) })),
        el('div', { class: 'row' }, el('span', { class: 'muted', text: 'Net cash flow' }), el('span', { class: 'num', text: money(p.netCashFlow, c) })),
        label === 'This month' && d.savingsRate !== null
            ? el('div', { class: 'row' }, el('span', { class: 'muted', text: 'Savings rate' }), el('span', { class: 'num', text: `${d.savingsRate}%` }))
            : null)));

    renderAccounts(d.accounts, c);
    renderBudgets(d.budgets);
    renderExpectedIncome(d.expectedIncome);
    renderCategories(d.expenseByCategory, c);
    renderDaily(d.dailySpending, c);
    renderTrend(d.monthlyTrend, c);
    renderRecent(d.recentTransactions);
}

function renderAccounts(a, c) {
    const rows = a.accounts.map((acc) => el('div', { class: 'list-row' },
        el('span', { class: 'name', text: acc.name + (acc.nature === 'LIABILITY' ? ' (owed)' : '') }),
        el('span', { class: `num ${acc.nature === 'LIABILITY' ? 'expense' : ''}`, text: money(acc.currentBalance, acc.currency) })));
    fill($('accounts'), el('div', { class: 'list' }, rows,
        el('div', { class: 'list-row total-row' }, el('span', { text: 'Net worth' }), el('span', { class: 'num', text: money(a.netWorth, c) }))));
}

function budgetClass(status) {
    return status === 'EXCEEDED' ? 'bad' : status === 'ALERT' ? 'warn' : '';
}

function renderBudgets(budgets) {
    const active = budgets.filter((b) => b.utilization && b.utilization.status !== 'NOT_IN_EFFECT');
    if (!active.length) {
        fill($('budgets'), emptyNote('No budgets yet. Create one via POST /api/v1/budgets.'));
        return;
    }
    fill($('budgets'), el('div', { class: 'list' }, active.map((b) => {
        const u = b.utilization;
        const bar = el('span');
        bar.style.width = `${Math.min(100, Number(u.percentUsed))}%`;
        return el('div', {},
            el('div', { class: 'list-row' }, el('span', { class: 'name', text: b.name }),
                el('span', { class: 'num', text: `${money(u.spent, b.currency)} / ${money(b.amount, b.currency)}` })),
            el('div', { class: `progress ${budgetClass(u.status)}` }, bar),
            el('div', { class: 'meta' },
                el('span', { text: `${u.percentUsed}% used` }),
                el('span', { text: u.status === 'EXCEEDED'
                    ? `Over by ${money(-u.remaining, b.currency)}`
                    : `${money(u.dailyAllowance, b.currency)}/day for ${plural(u.daysLeft, 'day')}` })));
    })));
}

const INCOME_STATUS = {
    RECEIVED: ['Received', 'ok'],
    PARTIALLY_RECEIVED: ['Partly received', 'warn'],
    PENDING: ['Pending', ''],
    OVERDUE: ['Overdue', 'bad'],
    NOT_EXPECTED: ['Unplanned', ''],
};

function renderExpectedIncome(items) {
    if (!items.length) {
        fill($('expected-income'), emptyNote('No income sources yet. Add your salary via POST /api/v1/income-sources.'));
        return;
    }
    fill($('expected-income'), el('div', { class: 'list' }, items.map((i) => {
        const [label, cls] = INCOME_STATUS[i.status] || [i.status, ''];
        return el('div', {},
            el('div', { class: 'list-row' }, el('span', { class: 'name', text: i.sourceName }), el('span', { class: `chip ${cls}`, text: label })),
            el('div', { class: 'meta' },
                el('span', { text: i.expectedAmount ? `Expected ${money(i.expectedAmount, i.currency)} by ${shortDate(i.expectedDate)}` : 'No fixed amount' }),
                el('span', { class: 'num', text: `Received ${money(i.receivedAmount, i.currency)}` })));
    })));
}

function renderCategories(items, c) {
    if (!items.length) {
        fill($('categories'), emptyNote('No spending in this period.'));
        return;
    }
    fill($('categories'), el('div', { class: 'list' }, items.map((cat) => {
        const bar = el('span');
        bar.style.width = `${cat.share}%`;
        return el('div', {},
            el('div', { class: 'list-row' }, el('span', { class: 'name', text: cat.name }), el('span', { class: 'num', text: money(cat.total, c) })),
            el('div', { class: 'progress' }, bar),
            el('div', { class: 'meta' }, el('span', { text: `${cat.share}%` }), el('span', { text: plural(cat.count, 'entry', 'entries') })));
    })));
}

function renderDaily(days, c) {
    const max = Math.max(0, ...days.map((d) => Number(d.amount)));
    const bars = days.map((d) => {
        const bar = el('div', { class: `bar ${Number(d.amount) === 0 ? 'zero' : ''}`, title: `${shortDate(d.date)}: ${money(d.amount, c)}` });
        bar.style.height = max > 0 ? `${Math.max(2, (Number(d.amount) / max) * 100)}%` : '2px';
        return bar;
    });
    fill($('daily-chart'), bars);
    const axis = el('div', { class: 'axis' },
        el('span', { text: days.length ? shortDate(days[0].date) : '' }),
        el('span', { text: max > 0 ? `max ${money(max, c)}/day` : 'no spending' }),
        el('span', { text: days.length ? shortDate(days[days.length - 1].date) : '' }));
    $('daily-chart').after(axis);
    if (renderDaily.axis) renderDaily.axis.remove();
    renderDaily.axis = axis;
}

function renderTrend(months, c) {
    const max = Math.max(0, ...months.flatMap((m) => [Number(m.income), Number(m.expense)]));
    const scale = (v) => (max > 0 ? `${Math.max(2, (Number(v) / max) * 100)}%` : '2px');
    fill($('trend-chart'), months.map((m) => {
        const income = el('span', { class: 'in', title: `Income ${money(m.income, c)}` });
        const expense = el('span', { class: 'out', title: `Expense ${money(m.expense, c)}` });
        income.style.height = scale(m.income);
        expense.style.height = scale(m.expense);
        return el('div', { class: 'month' }, el('div', { class: 'bars' }, income, expense),
            el('span', { class: 'label', text: monthLabel(m.month) }));
    }));
    if (!renderTrend.legend) {
        renderTrend.legend = el('div', { class: 'legend' },
            el('span', {}, el('i', { class: 'in' }), 'Income'), el('span', {}, el('i', { class: 'out' }), 'Expense'));
        $('trend-chart').after(renderTrend.legend);
    }
}

function renderRecent(items) {
    if (!items.length) {
        fill($('recent'), el('tbody', {}, el('tr', {}, el('td', { class: 'empty', text: 'Nothing recorded yet. Use “Add expense” to start.' }))));
        return;
    }
    const head = el('thead', {}, el('tr', {},
        el('th', { text: 'Date' }), el('th', { text: 'Type' }), el('th', { text: 'Category / details' }),
        el('th', { text: 'Account' }), el('th', { class: 'amount', text: 'Amount' })));
    const rows = items.map((t) => {
        const details = t.type === 'TRANSFER'
            ? `→ ${t.toAccount ? t.toAccount.name : ''}`
            : [t.category && (t.category.parentName ? `${t.category.parentName} / ${t.category.name}` : t.category.name),
                t.description || t.counterparty].filter(Boolean).join(' · ');
        const sign = t.type === 'EXPENSE' ? '−' : t.type === 'INCOME' ? '+' : '';
        return el('tr', {},
            el('td', { text: shortDate(t.transactionDate) }),
            el('td', { class: 'muted', text: t.type.toLowerCase() }),
            el('td', { text: details }),
            el('td', { class: 'muted', text: t.account ? t.account.name : '' }),
            el('td', { class: `amount num ${t.type === 'EXPENSE' ? 'expense' : t.type === 'INCOME' ? 'income' : ''}`,
                text: sign + money(t.amount, t.currency) }));
    });
    fill($('recent'), head, el('tbody', {}, rows));
}

// ---- Quick add expense ----

let expenseKey = null;

async function openExpenseForm() {
    const form = $('expense-form');
    $('expense-error').hidden = true;
    expenseKey = crypto.randomUUID(); // one key per form opening, so double-submits create one expense
    const [categories, accounts] = await Promise.all([api('/categories?type=EXPENSE'), api('/accounts')]);
    fill(form.categoryId, el('option', { value: '', text: 'Choose a category' }),
        categories.map((parent) => el('optgroup', { label: parent.name },
            el('option', { value: parent.id, text: parent.name }),
            (parent.children || []).map((child) => el('option', { value: child.id, text: `${parent.name} › ${child.name}` })))));
    fill(form.accountId, accounts.map((a) => el('option', { value: a.id, selected: a.defaultAccount, text: `${a.name} (${a.currency})` })));
    form.transactionDate.value = dashboard ? dashboard.today : '';
    $('expense-panel').hidden = false;
    form.amount.focus();
}

function closeExpenseForm() {
    $('expense-panel').hidden = true;
    $('expense-form').reset();
}

async function submitExpense(event) {
    event.preventDefault();
    const form = event.target;
    const body = {
        amount: form.amount.value.trim(),
        categoryId: form.categoryId.value || null,
        accountId: form.accountId.value || null,
        transactionDate: form.transactionDate.value || null,
        description: form.description.value.trim() || null,
    };
    try {
        await api('/expenses', { method: 'POST', body, headers: { 'Idempotency-Key': expenseKey } });
        closeExpenseForm();
        showNotice('Expense saved.');
        await loadDashboard();
    } catch (e) {
        $('expense-error').textContent = errorText(e);
        $('expense-error').hidden = false;
    }
}

// ---- Wiring ----

document.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('[data-auth-tab]').forEach((tab) => tab.addEventListener('click', () => setAuthMode(tab.dataset.authTab)));
    $('auth-form').addEventListener('submit', submitAuth);
    $('logout').addEventListener('click', logout);
    $('theme-toggle').addEventListener('click', toggleTheme);
    $('add-expense-open').addEventListener('click', () => openExpenseForm().catch((e) => showNotice(errorText(e))));
    $('add-expense-close').addEventListener('click', closeExpenseForm);
    $('expense-form').addEventListener('submit', submitExpense);
    $('range-form').addEventListener('submit', (event) => {
        event.preventDefault();
        const { from, to } = event.target;
        range = from.value && to.value ? { from: from.value, to: to.value } : null;
        loadDashboard().catch((e) => showNotice(errorText(e)));
    });
    $('range-reset').addEventListener('click', () => {
        range = null;
        $('range-form').reset();
        loadDashboard().catch((e) => showNotice(errorText(e)));
    });

    setAuthMode('login');
    if (session.access) {
        showApp().catch(() => signedOut());
    } else {
        signedOut();
    }
});
