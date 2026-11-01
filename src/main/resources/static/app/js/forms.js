// Create dialogs. Each calls `onSaved` after a successful save so the current view can refresh.

import { api } from './api.js';
import { confirmDialog, el, field, input, openSheet, segmented, select, toast } from './dom.js';
import { isoDate, parseAmount, titleCase } from './format.js';
import { accountOptions, categoryOptions, currencyOptions, store } from './store.js';

const value = (form, name) => {
    const v = form.elements.namedItem(name)?.value?.trim();
    return v === '' || v === undefined ? null : v;
};
const amount = (form, name) => parseAmount(value(form, name));

/** Account choices, keeping an account the entry already uses even if it is now deactivated. */
function accountChoices(accounts, selectedId, keep) {
    const list = keep && !accounts.some((a) => a.id === keep.id)
        ? [...accounts, { id: keep.id, name: `${keep.name} (inactive)`, currency: '' }] : accounts;
    return accountOptions(list, selectedId);
}

const PATHS = { EXPENSE: '/expenses', INCOME: '/income', TRANSFER: '/transfers' };

/** Categories used most recently for expenses, newest first (this device only): one tap to pick again. */
const recentCategories = {
    get() {
        try { return JSON.parse(localStorage.getItem('pt.recentCategories') ?? '[]'); } catch { return []; }
    },
    add(id) {
        try { localStorage.setItem('pt.recentCategories', JSON.stringify([id, ...this.get().filter((x) => x !== id)].slice(0, 6))); } catch { /* ignore */ }
    },
};

function flatCategories(tree) {
    return tree.flatMap((c) => [{ id: c.id, name: c.name }, ...(c.children ?? []).map((s) => ({ id: s.id, name: s.name, parent: c.name }))]);
}

/** The account last used for a kind of entry, remembered on this device (a convenience only). */
const lastAccount = {
    get(kind, accounts) {
        try {
            const id = localStorage.getItem(`pt.lastAccount.${kind}`);
            return accounts.some((a) => a.id === id) ? id : undefined;
        } catch { return undefined; }
    },
    set(kind, id) {
        try { if (id) localStorage.setItem(`pt.lastAccount.${kind}`, id); } catch { /* ignore */ }
    },
};

/**
 * Expense, income or transfer. `preset` can fix the type and prefill fields (e.g. a salary receipt).
 * Pass `existing` (a transaction) to edit it instead; its type can't change.
 */
export async function openTransactionDialog({ type = 'EXPENSE', preset = {}, existing = null, onSaved }) {
    const [accounts, expenseCats, incomeCats, sources] = await Promise.all([
        store.accounts(), store.expenseCategories(), store.incomeCategories(), store.incomeSources(),
    ]);
    const editing = Boolean(existing);
    let current = existing?.type ?? type;
    const idempotencyKey = crypto.randomUUID(); // one per dialog: a double submit creates one record
    const today = preset.today ?? isoDate(new Date());
    const p = existing ? {
        amount: existing.amount, date: existing.transactionDate, categoryId: existing.category?.id, accountId: existing.account?.id,
        toAccountId: existing.toAccount?.id, description: existing.description, counterparty: existing.counterparty,
        incomeSourceId: existing.incomeSource?.id, periodMonth: existing.incomePeriod, notes: existing.notes,
    } : { ...preset, accountId: preset.accountId ?? lastAccount.get(type, accounts) };

    const sheet = openSheet({
        title: editing ? `Edit ${current === 'TRANSFER' ? 'transfer' : current.toLowerCase()}` : 'New transaction',
        submitLabel: editing ? 'Save changes' : 'Save',
        danger: editing ? {
            label: 'Delete',
            action: async () => {
                if (!await confirmDialog({ title: 'Delete this transaction?', danger: true, confirmLabel: 'Delete',
                    message: 'It is removed from balances and totals. You can restore it from Transactions › Show deleted.' })) return false;
                await api.post(`/transactions/${existing.id}/void`);
                store.invalidate('accounts');
                toast('Transaction deleted');
                onSaved?.();
                return true;
            },
        } : null,
        fields: () => {
            const typeSwitch = editing ? null : segmented(
                [{ value: 'EXPENSE', label: 'Expense' }, { value: 'INCOME', label: 'Income' }, { value: 'TRANSFER', label: 'Transfer' }],
                current, (next) => { current = next; sheet.render(); }, 'Transaction type');
            const amountField = field('Amount', input('amount', {
                class: 'input amount', inputmode: 'decimal', autocomplete: 'off', placeholder: '0.00',
                required: true, value: p.amount ?? '',
            }));
            const date = field('Date', input('transactionDate', { type: 'date', value: p.date ?? today, max: today }));
            const note = () => field('Description', input('description', { maxlength: 200, placeholder: 'Optional', value: p.description ?? '' }), { span: true });

            if (current === 'TRANSFER') {
                // Across currencies the amount that arrived is entered too; the rate is implied.
                const received = field('Amount received', input('toAmount', { class: 'input', inputmode: 'decimal', placeholder: '0.00',
                    value: existing?.toAmount ?? '' }));
                const currencyOf = (id) => accounts.find((a) => a.id === id)?.currency;
                const sync = () => {
                    const f = received.closest('form');
                    const fromC = currencyOf(f?.elements.namedItem('fromAccountId').value);
                    const toC = currencyOf(f?.elements.namedItem('toAccountId').value);
                    const cross = fromC && toC && fromC !== toC;
                    received.hidden = !cross;
                    received.querySelector('span').textContent = cross ? `Amount received (${toC})` : 'Amount received';
                };
                queueMicrotask(sync);
                return [typeSwitch, amountField, el('div', { class: 'form-grid' },
                    field('From account', select('fromAccountId', accountChoices(accounts, p.accountId ?? lastAccount.get('TRANSFER', accounts), existing?.account),
                        { onchange: sync })),
                    field('To account', select('toAccountId', accountChoices(accounts,
                        p.toAccountId ?? accounts.find((a) => !a.defaultAccount)?.id, existing?.toAccount), { onchange: sync })),
                    received,
                    date, note()),
                el('p', { class: 'faint', text: 'Transfers move money between your own accounts. They are never income or spending — use one to pay a credit card bill.' })];
            }
            if (current === 'INCOME') {
                const sourceSel = select('incomeSourceId', [{ value: '', label: 'None' },
                    ...sources.map((s) => ({ value: s.id, label: s.name, selected: s.id === p.incomeSourceId }))]);
                const accountSel = editing
                    ? select('accountId', accountChoices(accounts, p.accountId, existing.account))
                    : select('accountId', [{ value: '', label: 'Source / default account' }, ...accountOptions(accounts, '__none')]);
                return [typeSwitch, amountField, el('div', { class: 'form-grid' },
                    field('Income source', sourceSel),
                    field('Category', select('categoryId', [{ value: '', label: 'From income source' }, ...categoryOptions(incomeCats, p.categoryId)])),
                    field('Into account', accountSel),
                    field('For month', input('periodMonth', { type: 'month', value: p.periodMonth ?? '' })),
                    date,
                    field('Payer', input('payer', { maxlength: 100, placeholder: 'Optional', value: p.counterparty ?? '' })),
                    note())];
            }
            const known = new Map(flatCategories(expenseCats).map((c) => [c.id, c]));
            const recent = recentCategories.get().map((id) => known.get(id)).filter(Boolean);
            const quick = recent.length && !editing ? el('div', { class: 'quick-cats', role: 'group', 'aria-label': 'Recent categories' },
                recent.map((c) => el('button', { type: 'button', class: 'chip-btn', text: c.name, title: c.parent ? `${c.parent} › ${c.name}` : c.name,
                    'aria-pressed': String(c.id === p.categoryId),
                    onclick: (ev) => {
                        const f = ev.target.closest('form');
                        f.elements.namedItem('categoryId').value = c.id;
                        f.querySelectorAll('.chip-btn').forEach((b) => b.setAttribute('aria-pressed', String(b === ev.target)));
                    } }))) : null;
            return [typeSwitch, amountField, quick, el('div', { class: 'form-grid' },
                field('Category', select('categoryId', [{ value: '', label: 'Choose a category' }, ...categoryOptions(expenseCats, p.categoryId)], { required: true }), { span: true }),
                field('Paid from', select('accountId', accountChoices(accounts, p.accountId, existing?.account))),
                date,
                field('Merchant', input('merchant', { maxlength: 100, placeholder: 'Optional', value: p.counterparty ?? '' })),
                field('Description', input('description', { maxlength: 200, placeholder: 'Optional', value: p.description ?? '' })))];
        },
        submit: async (form) => {
            const common = {
                amount: amount(form, 'amount'), transactionDate: value(form, 'transactionDate'), description: value(form, 'description'),
                notes: p.notes ?? null, transactionTime: existing?.transactionTime ?? null,
            };
            if (existing?.paymentMethod && current !== 'TRANSFER') common.paymentMethod = existing.paymentMethod;
            let body;
            if (current === 'TRANSFER') {
                body = { ...common, fromAccountId: value(form, 'fromAccountId'), toAccountId: value(form, 'toAccountId'),
                    toAmount: form.elements.namedItem('toAmount')?.closest('.field')?.hidden ? null : amount(form, 'toAmount') };
            } else if (current === 'INCOME') {
                body = {
                    ...common, incomeSourceId: value(form, 'incomeSourceId'), categoryId: value(form, 'categoryId'),
                    accountId: value(form, 'accountId'), periodMonth: value(form, 'incomeSourceId') ? value(form, 'periodMonth') : null,
                    payer: value(form, 'payer'),
                };
            } else {
                body = { ...common, categoryId: value(form, 'categoryId'), accountId: value(form, 'accountId'), merchant: value(form, 'merchant') };
            }
            if (editing) {
                await api.put(`${PATHS[current]}/${existing.id}`, body);
            } else {
                await api.post(PATHS[current], body, { 'Idempotency-Key': idempotencyKey });
                lastAccount.set(current, current === 'TRANSFER' ? body.fromAccountId : body.accountId);
                if (current === 'EXPENSE' && body.categoryId) recentCategories.add(body.categoryId);
            }
            store.invalidate('accounts', 'sources');
            toast(editing ? 'Changes saved' : `${titleCase(current)} saved`);
            onSaved?.();
        },
    });
}

export async function openBudgetDialog({ onSaved }) {
    const categories = await store.expenseCategories();
    let scope = 'OVERALL';
    let period = 'MONTHLY';
    const sheet = openSheet({
        title: 'New budget',
        submitLabel: 'Create budget',
        fields: () => [
            el('div', { class: 'form-grid' },
                field('Applies to', segmented([{ value: 'OVERALL', label: 'All spending' }, { value: 'CATEGORY', label: 'A category' }],
                    scope, (v) => { scope = v; sheet.render(); }, 'Budget scope')),
                field('Period', segmented([{ value: 'MONTHLY', label: 'Monthly' }, { value: 'WEEKLY', label: 'Weekly' }, { value: 'CUSTOM', label: 'Dates' }],
                    period, (v) => { period = v; sheet.render(); }, 'Budget period'))),
            scope === 'CATEGORY' ? field('Category', select('categoryId', [{ value: '', label: 'Choose a category' }, ...categoryOptions(categories)])) : null,
            field('Limit', input('amount', { class: 'input amount', inputmode: 'decimal', placeholder: '0.00', required: true })),
            period === 'CUSTOM'
                ? el('div', { class: 'form-grid' }, field('From', input('startDate', { type: 'date' })), field('To', input('endDate', { type: 'date' })))
                : null,
            field('Name', input('name', { maxlength: 60, placeholder: 'Optional, e.g. Groceries cap' })),
            el('p', { class: 'faint', text: 'Only real expenses count. Transfers and deleted entries never do. Alerts at 75%, 90% and 100%.' }),
        ],
        submit: async (form) => {
            await api.post('/budgets', {
                scope, period, amount: amount(form, 'amount'), name: value(form, 'name'),
                categoryId: scope === 'CATEGORY' ? value(form, 'categoryId') : null,
                startDate: period === 'CUSTOM' ? value(form, 'startDate') : null,
                endDate: period === 'CUSTOM' ? value(form, 'endDate') : null,
            });
            toast('Budget created');
            onSaved?.();
        },
    });
}

const ACCOUNT_TYPES = ['SAVINGS', 'CURRENT', 'CREDIT_CARD', 'CASH', 'WALLET', 'INVESTMENT', 'OTHER'];

/** New account, or edit one (`existing`): type and currency are fixed once created. */
export async function openAccountDialog({ existing = null, onSaved }) {
    const [me, currencies] = await Promise.all([store.me(), store.currencies()]);
    const editing = Boolean(existing);
    let type = existing?.type ?? 'SAVINGS';
    const e = existing ?? {};
    const sheet = openSheet({
        title: editing ? `Edit ${existing.name}` : 'New account',
        submitLabel: editing ? 'Save changes' : 'Add account',
        fields: () => [
            field('Name', input('name', { maxlength: 60, required: true, placeholder: 'e.g. HDFC Savings', value: e.name ?? '' })),
            el('div', { class: 'form-grid' },
                editing ? null : field('Type', select('type', ACCOUNT_TYPES.map((t) => ({ value: t, label: titleCase(t), selected: t === type })),
                    { onchange: (ev) => { type = ev.target.value; sheet.render(); } })),
                editing ? null : field('Currency', select('currency', currencyOptions(currencies, me.preferences.defaultCurrency))),
                field(type === 'CREDIT_CARD' ? (editing ? 'Amount owed when added' : 'Amount owed now') : (editing ? 'Opening balance' : 'Current balance'),
                    input('openingBalance', { inputmode: 'decimal', placeholder: '0.00', value: e.openingBalance ?? '' })),
                type === 'CREDIT_CARD' ? field('Credit limit', input('creditLimit', { inputmode: 'decimal', placeholder: 'Optional', value: e.creditLimit ?? '' })) : null,
                field('Bank / provider', input('institution', { maxlength: 100, placeholder: 'Optional', value: e.institution ?? '' })),
                editing ? field('Notes', input('notes', { maxlength: 500, placeholder: 'Optional', value: e.notes ?? '' }))
                    : field('Last 4 digits', input('accountLast4', { maxlength: 4, inputmode: 'numeric', placeholder: 'Optional' }))),
            editing ? el('p', { class: 'faint', text: 'Changing the opening balance moves the current balance by the same amount; transactions stay as they are.' }) : null,
        ],
        submit: async (form) => {
            if (editing) {
                await api.patch(`/accounts/${existing.id}`, {
                    name: value(form, 'name'), openingBalance: amount(form, 'openingBalance'), creditLimit: amount(form, 'creditLimit'),
                    institution: value(form, 'institution') ?? '', notes: value(form, 'notes') ?? '',
                });
            } else {
                await api.post('/accounts', {
                    name: value(form, 'name'), type, currency: value(form, 'currency')?.toUpperCase(),
                    openingBalance: amount(form, 'openingBalance'), creditLimit: amount(form, 'creditLimit'),
                    institution: value(form, 'institution'), accountLast4: value(form, 'accountLast4'),
                });
                toast('Account added');
            }
            store.invalidate('accounts');
            onSaved?.();
        },
    });
}

const SOURCE_TYPES = ['SALARY', 'FREELANCE', 'BUSINESS', 'RENTAL', 'INTEREST', 'BONUS', 'GIFT', 'OTHER'];

export async function openIncomeSourceDialog({ onSaved }) {
    const accounts = await store.accounts();
    openSheet({
        title: 'New income source',
        submitLabel: 'Add source',
        fields: () => [
            field('Name', input('name', { maxlength: 60, required: true, placeholder: 'e.g. Acme Corp salary' })),
            el('div', { class: 'form-grid' },
                field('Type', select('type', SOURCE_TYPES.map((t) => ({ value: t, label: titleCase(t) })))),
                field('Credited to', select('accountId', [{ value: '', label: 'Default account' }, ...accountOptions(accounts, '__none')])),
                field('Expected per month', input('expectedAmount', { inputmode: 'decimal', placeholder: 'Leave empty if irregular' })),
                field('Usually arrives on day', input('expectedDay', { type: 'number', min: 1, max: 31, value: 1 }))),
            el('p', { class: 'faint', text: 'Expected amounts are plans only. Income counts once you record it as received.' }),
        ],
        submit: async (form) => {
            const expectedAmount = amount(form, 'expectedAmount');
            await api.post('/income-sources', {
                name: value(form, 'name'), type: value(form, 'type'), accountId: value(form, 'accountId'),
                expectedAmount, expectedDay: expectedAmount ? Number(value(form, 'expectedDay') ?? 1) : null,
            });
            store.invalidate('sources');
            toast('Income source added');
            onSaved?.();
        },
    });
}

/** Records what is still due from an expected income, for its month. */
export function recordExpectedIncome(item, today, onSaved) {
    return openTransactionDialog({
        type: 'INCOME',
        preset: {
            amount: item.remainingAmount && Number(item.remainingAmount) > 0 ? item.remainingAmount : item.expectedAmount,
            incomeSourceId: item.sourceId, periodMonth: item.month, today,
        },
        onSaved,
    });
}
