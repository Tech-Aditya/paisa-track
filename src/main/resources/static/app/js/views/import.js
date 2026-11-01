// Bank statement import: upload a CSV, check the preview (duplicates are flagged and unticked),
// adjust categories, then import the ticked rows in one go.

import { api, errorText, upload } from '../api.js';
import { card } from '../components.js';
import { el, empty, field, fill, icon, select, toast } from '../dom.js';
import { dayHeading, money, plural, shortDate } from '../format.js';
import { categoryOptions, store } from '../store.js';

let preview = null; // last preview, kept while you adjust it
let file = null;
let accountId = '';

export async function renderImport(root) {
    const [accounts, expenseCats, incomeCats] = await Promise.all([store.accounts(), store.expenseCategories(), store.incomeCategories()]);
    const active = accounts.filter((a) => a.active !== false);
    accountId ||= active.find((a) => a.defaultAccount)?.id ?? active[0]?.id ?? '';
    const result = el('div', { class: 'stack' });

    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Import statement' }),
            el('p', { text: 'Add many transactions at once from your bank’s CSV statement. Nothing is saved until you confirm.' })),
        el('div', { class: 'head-actions' }, el('a', { class: 'btn sm', href: '#/transactions' }, icon('list'), 'Transactions')));

    if (!active.length) {
        fill(root, head, el('section', { class: 'card' }, empty('Add an account first, then import its statement.', 'wallet',
            el('a', { class: 'btn primary', href: '#/accounts', text: 'Go to accounts' }))));
        return;
    }

    const fileLabel = el('span', { class: 'faint', text: file ? file.name : 'CSV file, up to 2 MB' });
    const picker = el('input', { type: 'file', accept: '.csv,text/csv,text/plain', hidden: true });
    picker.addEventListener('change', () => {
        file = picker.files[0] ?? null;
        picker.value = '';
        fileLabel.textContent = file ? file.name : 'CSV file, up to 2 MB';
        preview = null;
        if (file) run();
    });
    const accountSel = select('accountId', active.map((a) => ({ value: a.id, label: `${a.name} · ${a.currency}`, selected: a.id === accountId })),
        { onchange: (e) => { accountId = e.target.value; if (file) run(); } });

    const uploadCard = card('1. Choose the statement', { sub: 'Download it from net banking as CSV (Excel users: Save as CSV).' },
        el('div', { class: 'import-pick' },
            field('Account it belongs to', accountSel),
            el('div', { class: 'field' }, el('span', { text: 'File' }),
                el('div', { class: 'file-pick' },
                    el('button', { class: 'btn', type: 'button', onclick: () => picker.click() }, icon('upload'), file ? 'Choose another' : 'Choose file'),
                    fileLabel)),
            picker));

    async function run(mapping = null) {
        fill(result, el('section', { class: 'card' }, el('div', { class: 'spinner', 'aria-label': 'Reading the file' })));
        const form = new FormData();
        form.append('file', file);
        form.append('accountId', accountId);
        if (mapping) Object.entries(mapping).forEach(([k, v]) => { if (v !== null && v !== '' && v !== undefined) form.append(k, v); });
        try {
            preview = await upload('/imports/preview', form);
            preview.key = crypto.randomUUID();
            showPreview();
        } catch (e) {
            preview = null;
            fill(result, el('section', { class: 'card' }, empty(errorText(e), 'alert')));
        }
    }

    function showPreview() {
        const pv = preview;
        const colOptions = (selected, none = true) => [
            ...(none ? [{ value: '', label: '—' }] : []),
            ...pv.headers.map((h, i) => ({ value: String(i), label: h || `Column ${i + 1}`, selected: i === selected })),
        ];
        const m = pv.mapping;
        const split = m.debitColumn !== null && m.debitColumn !== undefined;
        const mappingForm = el('form', { class: 'mapping-grid', onsubmit: (e) => {
            e.preventDefault();
            const f = e.target;
            const v = (n) => f.elements.namedItem(n)?.value ?? '';
            run({ headerRow: m.headerRow, dateColumn: v('dateColumn'), descriptionColumn: v('descriptionColumn'),
                debitColumn: v('debitColumn'), creditColumn: v('creditColumn'), amountColumn: v('amountColumn'), dateFormat: v('dateFormat') });
        } },
        field('Date', select('dateColumn', colOptions(m.dateColumn, false))),
        field('Description', select('descriptionColumn', colOptions(m.descriptionColumn))),
        field('Withdrawal (money out)', select('debitColumn', colOptions(split ? m.debitColumn : null))),
        field('Deposit (money in)', select('creditColumn', colOptions(split ? m.creditColumn : null))),
        field('Or one amount column', select('amountColumn', colOptions(split ? null : m.amountColumn))),
        field('Date format', select('dateFormat', pv.dateFormats.map((f) => ({ value: f, label: f, selected: f === m.dateFormat })))),
        el('button', { class: 'btn', type: 'submit', text: 'Read again' }));

        const counter = el('b');
        const importBtn = el('button', { class: 'btn primary', type: 'button', onclick: () => commit(importBtn) });
        const refresh = () => {
            const n = pv.rows.filter((r) => r.selected).length;
            counter.textContent = `${n} of ${plural(pv.rows.length, 'row')} ticked`;
            importBtn.textContent = n ? `Import ${plural(n, 'transaction')}` : 'Nothing ticked';
            importBtn.disabled = !n;
        };
        const ticks = [];
        const all = el('input', { type: 'checkbox', 'aria-label': 'Tick all rows without errors', onchange: (e) => {
            pv.rows.forEach((r, i) => { if (!r.error) { r.selected = e.target.checked; ticks[i].checked = e.target.checked; } });
            refresh();
        } });

        const rows = pv.rows.map((r, i) => {
            const tick = el('input', { type: 'checkbox', checked: r.selected, disabled: Boolean(r.error), 'aria-label': `Import line ${r.line}`,
                onchange: (e) => { r.selected = e.target.checked; refresh(); } });
            ticks.push(tick);
            const cats = r.type === 'INCOME' ? incomeCats : expenseCats;
            const cat = r.error ? el('span', { class: 'faint', text: '—' })
                : select(`cat${i}`, [{ value: '', label: 'Choose…' }, ...categoryOptions(cats, r.categoryId)], {
                    class: 'input compact', 'aria-label': `Category for line ${r.line}`,
                    onchange: (e) => { r.categoryId = e.target.value || null; e.target.classList.remove('invalid'); },
                });
            const note = r.error ? el('span', { class: 'chip bad', text: r.error })
                : r.duplicateReason ? el('span', { class: 'chip warn', title: r.duplicateReason, text: r.duplicateReason })
                    : r.categoryReason === 'HISTORY' ? el('span', { class: 'chip ok', text: 'Like your past entries' }) : null;
            return el('tr', { class: r.error ? 'is-error' : r.duplicateReason ? 'is-dup' : '' },
                el('td', {}, tick),
                el('td', { class: 'nowrap', text: r.date ? shortDate(r.date) : '—', title: r.date ? dayHeading(r.date) : '' }),
                el('td', { class: 'desc' }, el('div', { text: r.description || '—' }), note),
                el('td', { class: `num nowrap ${r.type === 'INCOME' ? 'pos' : 'neg'}`, text: r.amount
                    ? `${r.type === 'INCOME' ? '+' : '−'}${money(r.amount, pv.currency)}` : '—' }),
                el('td', {}, cat));
        });
        refresh();
        all.checked = pv.rows.every((r) => r.selected || r.error);

        fill(result,
            card('2. Check what will be added', {
                sub: `${pv.fileName ?? 'Statement'} → ${pv.accountName}. Rows that look already recorded are unticked; tick them if they are new.`,
            },
            el('div', { class: 'import-stats' },
                stat(pv.rowCount, 'rows'), stat(pv.selectedCount, 'new', 'pos'),
                stat(pv.duplicateCount, 'possible duplicates', pv.duplicateCount ? 'warn' : ''),
                stat(pv.errorCount, 'unreadable', pv.errorCount ? 'neg' : '')),
            el('details', { class: 'mapping' }, el('summary', { text: 'Columns look wrong? Change them' }), mappingForm),
            el('div', { class: 'table-wrap' }, el('table', { class: 'import-table' },
                el('thead', {}, el('tr', {}, el('th', {}, all), el('th', { text: 'Date' }), el('th', { text: 'Description' }),
                    el('th', { class: 'num', text: 'Amount' }), el('th', { text: 'Category' }))),
                el('tbody', {}, rows))),
            el('div', { class: 'import-foot' }, counter, importBtn)));
    }

    async function commit(btn) {
        const chosen = preview.rows.map((r, i) => ({ r, i })).filter(({ r }) => r.selected && !r.error);
        const missing = chosen.filter(({ r }) => !r.categoryId);
        if (missing.length) {
            missing.forEach(({ i }) => result.querySelector(`[name="cat${i}"]`)?.classList.add('invalid'));
            result.querySelector(`[name="cat${missing[0].i}"]`)?.focus();
            toast(`Pick a category for ${plural(missing.length, 'ticked row')}`, 'error');
            return;
        }
        btn.disabled = true;
        try {
            const res = await api.post('/imports/commit', {
                accountId: preview.accountId, fileName: preview.fileName,
                items: chosen.map(({ r }) => ({ date: r.date, type: r.type, amount: r.amount, categoryId: r.categoryId, description: r.description })),
            }, { 'Idempotency-Key': preview.key });
            store.invalidate('accounts');
            toast(`${plural(res.imported, 'transaction')} imported`);
            preview = null;
            file = null;
            renderImport(root);
        } catch (e) {
            toast(errorText(e), 'error');
            btn.disabled = false;
        }
    }

    const history = el('div', {});
    fill(root, head, uploadCard, result, card('Recent imports', {}, history));
    if (preview) showPreview();
    try {
        const recent = await api.get('/imports');
        const names = new Map(accounts.map((a) => [a.id, a.name]));
        fill(history, recent.length ? el('div', { class: 'rows' }, recent.map((b) => el('div', { class: 'row' },
            el('div', { class: 'tile-icon' }, icon('file')),
            el('div', { class: 'grow' }, el('div', { class: 'title', text: b.fileName ?? 'Statement' }),
                el('div', { class: 'meta', text: `${names.get(b.accountId) ?? 'Account'} · ${dayHeading(b.createdAt.slice(0, 10))}` })),
            el('div', { class: 'num', text: plural(b.imported, 'entry', 'entries') }))))
            : el('p', { class: 'faint', text: 'No imports yet.' }));
    } catch (e) {
        fill(history, el('p', { class: 'faint', text: errorText(e) }));
    }
}

function stat(n, label, tone = '') {
    return el('div', { class: `import-stat ${tone}`.trim() }, el('b', { class: 'num', text: String(n) }), el('span', { text: label }));
}
