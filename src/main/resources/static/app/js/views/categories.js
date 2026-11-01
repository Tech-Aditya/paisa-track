// Categories: the two-level expense and income trees. Add, rename, add subcategories,
// hide (deactivate) and delete unused ones. Used categories can't be deleted, only hidden.

import { api, errorText } from '../api.js';
import { confirmDialog, el, empty, field, fill, icon, input, openSheet, segmented, skeleton, toast } from '../dom.js';
import { plural } from '../format.js';
import { store } from '../store.js';

let type = 'EXPENSE';

export async function renderCategories(root) {
    const reload = () => renderCategories(root);
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Categories' }), el('p', { text: 'Organise spending and income the way you think about it.' })),
        el('div', { class: 'head-actions' },
            segmented([{ value: 'EXPENSE', label: 'Spending' }, { value: 'INCOME', label: 'Income' }], type, (v) => { type = v; reload(); }, 'Category type'),
            el('button', { class: 'btn primary', type: 'button', onclick: () => openCategoryDialog({ type, onSaved: reload }) }, icon('plus'), 'New category')));
    const list = el('div', { class: 'cat-tree' }, skeleton(40), skeleton(40));
    fill(root, head, el('section', { class: 'card' }, list));

    let tree;
    try {
        tree = await api.get(`/categories?type=${type}&includeInactive=true`);
    } catch (e) {
        fill(list, empty(errorText(e), 'alert'));
        return;
    }
    const changed = (msg) => { store.invalidate(type === 'EXPENSE' ? 'cat:EXPENSE' : 'cat:INCOME'); toast(msg); reload(); };
    fill(list, tree.length ? tree.map((c) => el('div', { class: 'cat-group' },
        categoryRow(c, true, changed),
        (c.children ?? []).map((sub) => categoryRow(sub, false, changed)))) : empty('No categories.', 'target'));
}

function categoryRow(c, topLevel, changed) {
    const act = (path, msg) => async () => {
        try { await api.post(`/categories/${c.id}/${path}`); changed(msg); } catch (e) { toast(errorText(e), 'error'); }
    };
    return el('div', { class: `row cat-item ${topLevel ? 'top' : 'sub'} ${c.active ? '' : 'muted-row'}`.trim() },
        el('div', { class: 'grow' },
            el('div', { class: 'title', text: c.name }),
            topLevel ? el('div', { class: 'meta', text: `${plural((c.children ?? []).length, 'subcategory', 'subcategories')}${c.systemCode ? ' · built in' : ''}` }) : null),
        c.active ? null : el('span', { class: 'chip', text: 'Hidden' }),
        el('div', { class: 'row-actions' },
            topLevel && c.active ? el('button', { class: 'btn ghost sm icon-only', type: 'button', title: 'Add subcategory', 'aria-label': `Add subcategory to ${c.name}`,
                onclick: () => openCategoryDialog({ type: c.type, parent: c, onSaved: () => changed('Subcategory added') }) }, icon('plus')) : null,
            el('button', { class: 'btn ghost sm icon-only', type: 'button', title: 'Rename', 'aria-label': `Rename ${c.name}`,
                onclick: () => openRenameDialog(c, () => changed('Renamed')) }, icon('edit')),
            el('button', { class: 'btn ghost sm icon-only', type: 'button', title: c.active ? 'Hide' : 'Show again',
                'aria-label': `${c.active ? 'Hide' : 'Show'} ${c.name}`, onclick: act(c.active ? 'deactivate' : 'activate', c.active ? 'Hidden from new entries' : 'Shown again') },
            icon(c.active ? 'eye-off' : 'eye')),
            el('button', { class: 'btn ghost sm icon-only danger', type: 'button', title: 'Delete', 'aria-label': `Delete ${c.name}`, onclick: async () => {
                if (!await confirmDialog({ title: `Delete ${c.name}?`, confirmLabel: 'Delete', danger: true,
                    message: 'Only possible if no transaction, budget, bill or loan uses it. Otherwise hide it instead.' })) return;
                try { await api.del(`/categories/${c.id}`); changed('Category deleted'); } catch (e) { toast(errorText(e), 'error'); }
            } }, icon('trash'))));
}

function openCategoryDialog({ type, parent = null, onSaved }) {
    openSheet({
        title: parent ? `New subcategory of ${parent.name}` : `New ${type === 'EXPENSE' ? 'spending' : 'income'} category`,
        submitLabel: 'Add',
        fields: () => [field('Name', input('name', { maxlength: 60, required: true, placeholder: parent ? 'e.g. Snacks' : 'e.g. Pets' }))],
        submit: async (form) => {
            await api.post('/categories', { name: form.name.value.trim(), type, parentId: parent?.id ?? null });
            onSaved();
        },
    });
}

function openRenameDialog(c, onSaved) {
    openSheet({
        title: `Rename ${c.name}`,
        submitLabel: 'Save',
        fields: () => [field('Name', input('name', { maxlength: 60, required: true, value: c.name }))],
        submit: async (form) => {
            await api.patch(`/categories/${c.id}`, { name: form.name.value.trim() });
            onSaved();
        },
    });
}
