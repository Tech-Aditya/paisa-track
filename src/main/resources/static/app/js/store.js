// Small per-session cache of reference data used by forms and filters.

import { api } from './api.js';

const cache = new Map();

function cached(key, loader) {
    if (!cache.has(key)) {
        cache.set(key, loader().catch((e) => { cache.delete(key); throw e; }));
    }
    return cache.get(key);
}

export const store = {
    me: () => cached('me', () => api.get('/users/me')),
    accounts: () => cached('accounts', () => api.get('/accounts')),
    expenseCategories: () => cached('cat:EXPENSE', () => api.get('/categories?type=EXPENSE')),
    incomeCategories: () => cached('cat:INCOME', () => api.get('/categories?type=INCOME')),
    incomeSources: () => cached('sources', () => api.get('/income-sources')),

    /** Forget cached data after a change so the next read is fresh. */
    invalidate(...keys) {
        if (!keys.length) cache.clear();
        keys.forEach((k) => cache.delete(k));
    },
};

/** Category tree as grouped <select> options: each parent, then "Parent › Child". */
export function categoryOptions(tree, selectedId) {
    return tree.map((parent) => ({
        group: parent.name,
        options: [
            { value: parent.id, label: parent.name, selected: parent.id === selectedId },
            ...(parent.children ?? []).map((c) => ({ value: c.id, label: `${parent.name} › ${c.name}`, selected: c.id === selectedId })),
        ],
    }));
}

export function accountOptions(accounts, selectedId) {
    return accounts.map((a) => ({
        value: a.id,
        label: `${a.name} · ${a.currency}`,
        selected: selectedId ? a.id === selectedId : a.defaultAccount,
    }));
}
