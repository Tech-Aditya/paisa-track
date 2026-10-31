// Create dialogs. Each calls `onSaved` after a successful save so the current view can refresh.

import { api } from './api.js';
import { el, field, input, openSheet, segmented, select, toast } from './dom.js';
import { isoDate, titleCase } from './format.js';
import { accountOptions, categoryOptions, store } from './store.js';

const value = (form, name) => {
    const v = form.elements.namedItem(name)?.value?.trim();
    return v === '' || v === undefined ? null : v;
};

/** Expense, income or transfer. `preset` can fix the type and prefill fields (e.g. a salary receipt). */
export async function openTransactionDialog({ type = 'EXPENSE', preset = {}, onSaved }) {
    const [accounts, expenseCats, incomeCats, sources] = await Promise.all([
        store.accounts(), store.expenseCategories(), store.incomeCategories(), store.incomeSources(),
    ]);
    let current = type;
    const idempotencyKey = crypto.randomUUID(); // one per dialog: a double submit creates one record
    const today = preset.today ?? isoDate(new Date());

    const sheet = openSheet({
        title: 'New transaction',
        submitLabel: 'Save',
        fields: (form) => {
            const typeSwitch = segmented(
                [{ value: 'EXPENSE', label: 'Expense' }, { value: 'INCOME', label: 'Income' }, { value: 'TRANSFER', label: 'Transfer' }],
                current, (next) => { current = next; sheet.render(); }, 'Transaction type');
            const amount = field('Amount', input('amount', {
                class: 'input amount', inputmode: 'decimal', autocomplete: 'off', placeholder: '0.00',
                required: true, value: preset.amount ?? '',
            }));
            const date = field('Date', input('transactionDate', { type: 'date', value: preset.date ?? today, max: today }));
            const note = () => field('Description', input('description', { maxlength: 200, placeholder: 'Optional' }), { span: true });

            if (current === 'TRANSFER') {
                return [typeSwitch, amount, el('div', { class: 'form-grid' },
                    field('From account', select('fromAccountId', accountOptions(accounts))),
                    field('To account', select('toAccountId', accountOptions(accounts, accounts.find((a) => !a.defaultAccount)?.id))),
                    date, note()),
                el('p', { class: 'faint', text: 'Transfers move money between your own accounts. They are never income or spending — use one to pay a credit card bill.' })];
            }
            if (current === 'INCOME') {
                const sourceSel = select('incomeSourceId', [{ value: '', label: 'None' },
                    ...sources.map((s) => ({ value: s.id, label: s.name, selected: s.id === preset.incomeSourceId }))]);
                return [typeSwitch, amount, el('div', { class: 'form-grid' },
                    field('Income source', sourceSel),
                    field('Category', select('categoryId', [{ value: '', label: 'From income source' }, ...categoryOptions(incomeCats)])),
                    field('Into account', select('accountId', [{ value: '', label: 'Source / default account' }, ...accountOptions(accounts, '__none')])),
                    field('For month', input('periodMonth', { type: 'month', value: preset.periodMonth ?? '' })),
                    date,
                    field('Payer', input('payer', { maxlength: 100, placeholder: 'Optional' })),
                    note())];
            }
            return [typeSwitch, amount, el('div', { class: 'form-grid' },
                field('Category', select('categoryId', [{ value: '', label: 'Choose a category' }, ...categoryOptions(expenseCats)], { required: true }), { span: true }),
                field('Paid from', select('accountId', accountOptions(accounts))),
                date,
                field('Merchant', input('merchant', { maxlength: 100, placeholder: 'Optional' })),
                field('Description', input('description', { maxlength: 200, placeholder: 'Optional' })))];
        },
        submit: async (form) => {
            const common = { amount: value(form, 'amount'), transactionDate: value(form, 'transactionDate'), description: value(form, 'description') };
            const headers = { 'Idempotency-Key': idempotencyKey };
            if (current === 'TRANSFER') {
                await api.post('/transfers', { ...common, fromAccountId: value(form, 'fromAccountId'), toAccountId: value(form, 'toAccountId') }, headers);
            } else if (current === 'INCOME') {
                await api.post('/income', {
                    ...common, incomeSourceId: value(form, 'incomeSourceId'), categoryId: value(form, 'categoryId'),
                    accountId: value(form, 'accountId'), periodMonth: value(form, 'incomeSourceId') ? value(form, 'periodMonth') : null,
                    payer: value(form, 'payer'),
                }, headers);
            } else {
                await api.post('/expenses', {
                    ...common, categoryId: value(form, 'categoryId'), accountId: value(form, 'accountId'), merchant: value(form, 'merchant'),
                }, headers);
            }
            store.invalidate('accounts', 'sources');
            toast(`${titleCase(current)} saved`);
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
                scope, period, amount: value(form, 'amount'), name: value(form, 'name'),
                categoryId: scope === 'CATEGORY' ? value(form, 'categoryId') : null,
                startDate: period === 'CUSTOM' ? value(form, 'startDate') : null,
                endDate: period === 'CUSTOM' ? value(form, 'endDate') : null,
            });
            toast('Budget created');
            onSaved?.();
        },
    });
}

const ACCOUNT_TYPES = ['SAVINGS', 'CURRENT', 'CREDIT_CARD', 'CASH', 'WALLET', 'OTHER'];

export async function openAccountDialog({ onSaved }) {
    const me = await store.me();
    let type = 'SAVINGS';
    const sheet = openSheet({
        title: 'New account',
        submitLabel: 'Add account',
        fields: () => [
            field('Name', input('name', { maxlength: 60, required: true, placeholder: 'e.g. HDFC Savings' })),
            el('div', { class: 'form-grid' },
                field('Type', select('type', ACCOUNT_TYPES.map((t) => ({ value: t, label: titleCase(t), selected: t === type })),
                    { onchange: (e) => { type = e.target.value; sheet.render(); } })),
                field('Currency', input('currency', { maxlength: 3, value: me.preferences.defaultCurrency, autocapitalize: 'characters' })),
                field(type === 'CREDIT_CARD' ? 'Amount owed now' : 'Current balance', input('openingBalance', { inputmode: 'decimal', placeholder: '0.00' })),
                type === 'CREDIT_CARD' ? field('Credit limit', input('creditLimit', { inputmode: 'decimal', placeholder: 'Optional' })) : null,
                field('Bank / provider', input('institution', { maxlength: 100, placeholder: 'Optional' })),
                field('Last 4 digits', input('accountLast4', { maxlength: 4, inputmode: 'numeric', placeholder: 'Optional' }))),
        ],
        submit: async (form) => {
            await api.post('/accounts', {
                name: value(form, 'name'), type, currency: value(form, 'currency')?.toUpperCase(),
                openingBalance: value(form, 'openingBalance'), creditLimit: value(form, 'creditLimit'),
                institution: value(form, 'institution'), accountLast4: value(form, 'accountLast4'),
            });
            store.invalidate('accounts');
            toast('Account added');
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
            const expectedAmount = value(form, 'expectedAmount');
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
