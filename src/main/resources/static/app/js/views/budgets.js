// Budgets: every limit with how much is used this period, plus edit, pause and delete.

import { api, errorText } from '../api.js';
import { confirmDialog, el, empty, field, fill, icon, input, openSheet, segmented, skeleton, toast } from '../dom.js';
import { openBudgetDialog } from '../forms.js';
import { money, parseAmount, plural, shortDate, titleCase } from '../format.js';

const STATUS = { ON_TRACK: ['On track', 'ok'], ALERT: ['Near limit', 'warn'], EXCEEDED: ['Over', 'bad'], NOT_IN_EFFECT: ['Not in effect', ''] };
let showAll = false;

export async function renderBudgets(root) {
    const reload = () => renderBudgets(root);
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Budgets' }),
            el('p', { text: 'Limits on real spending. Transfers, loan principal and planned bills never count.' })),
        el('div', { class: 'head-actions' },
            el('button', { class: 'btn primary', type: 'button', onclick: () => openBudgetDialog({ onSaved: reload }).catch((e) => toast(errorText(e), 'error')) },
                icon('plus'), 'New budget')));
    const list = el('div', { class: 'budget-grid' }, [1, 2].map(() => el('section', { class: 'card' }, skeleton(14, '40%'), skeleton(40))));
    const controls = el('div', { class: 'card-head' }, el('h2', { text: 'Your budgets' }),
        segmented([{ value: false, label: 'Active' }, { value: true, label: 'All' }], showAll, (v) => { showAll = v; reload(); }, 'Show'));
    fill(root, head, el('section', { class: 'card' }, controls, list));

    let budgets;
    try {
        budgets = await api.get(`/budgets?includeInactive=${showAll}`);
    } catch (e) {
        fill(list, empty(errorText(e), 'alert'));
        return;
    }
    fill(list, budgets.length
        ? budgets.map((b) => budgetCard(b, reload))
        : empty('No budgets yet. Set a monthly limit for everything, or for one category like Food.', 'target',
            el('button', { class: 'btn primary', type: 'button', onclick: () => openBudgetDialog({ onSaved: reload }) }, icon('plus'), 'New budget')));
}

function budgetCard(b, reload) {
    const u = b.utilization;
    const [label, tone] = b.active ? (STATUS[u?.status] ?? ['—', '']) : ['Paused', ''];
    const pct = u ? Math.min(100, Number(u.percentUsed)) : 0;
    const scope = b.scope === 'OVERALL' ? 'All spending' : b.category?.name ?? 'Category';
    return el('article', { class: `budget-card ${b.active ? '' : 'muted-row'}`.trim() },
        el('div', { class: 'card-head' },
            el('div', {}, el('div', { class: 'title', text: b.name }),
                el('div', { class: 'meta', text: `${scope} · ${titleCase(b.period)}` })),
            el('span', { class: `chip ${tone}`, text: label })),
        u && u.periodStart ? [
            el('div', { class: 'budget-top' },
                el('span', { class: 'num' }, el('b', { text: money(u.spent, b.currency) }), el('span', { class: 'faint', text: ` of ${money(b.amount, b.currency)}` })),
                el('span', { class: 'num faint', text: `${u.percentUsed}%` })),
            el('div', { class: `progress ${tone === 'bad' ? 'bad' : tone === 'warn' ? 'warn' : ''}` }, el('i', { style: { width: `${pct}%` } })),
            el('div', { class: 'budget-foot' },
                el('span', { text: `${shortDate(u.periodStart)} – ${shortDate(u.periodEnd)}` }),
                el('span', { text: u.status === 'EXCEEDED' ? `Over by ${money(-u.remaining, b.currency)}`
                    : `${money(u.remaining, b.currency)} left · ${money(u.dailyAllowance, b.currency)}/day for ${plural(u.daysLeft, 'day')}` })),
        ] : el('p', { class: 'faint', text: `${money(b.amount, b.currency)} · not in effect today` }),
        el('div', { class: 'budget-actions' },
            el('span', { class: 'faint small-note', text: `Alerts at ${b.alertThresholds.join('%, ')}%` }),
            el('div', { class: 'row-actions' },
                el('button', { class: 'btn ghost sm', type: 'button', onclick: () => openBudgetEdit(b, reload) }, icon('edit'), 'Edit'),
                el('button', { class: 'btn ghost sm', type: 'button', onclick: async () => {
                    try {
                        await api.post(`/budgets/${b.id}/${b.active ? 'deactivate' : 'activate'}`);
                        toast(b.active ? 'Budget paused' : 'Budget resumed');
                        reload();
                    } catch (e) { toast(errorText(e), 'error'); }
                } }, icon(b.active ? 'pause' : 'play'), b.active ? 'Pause' : 'Resume'),
                el('button', { class: 'btn ghost sm icon-only danger', type: 'button', 'aria-label': `Delete ${b.name}`, onclick: async () => {
                    if (!await confirmDialog({ title: `Delete ${b.name}?`, confirmLabel: 'Delete', danger: true,
                        message: 'The budget goes away. Your transactions are not affected.' })) return;
                    try { await api.del(`/budgets/${b.id}`); toast('Budget deleted'); reload(); } catch (e) { toast(errorText(e), 'error'); }
                } }, icon('trash')))));
}

function openBudgetEdit(b, onSaved) {
    openSheet({
        title: `Edit ${b.name}`,
        submitLabel: 'Save changes',
        fields: () => [
            el('div', { class: 'form-grid' },
                field('Name', input('name', { value: b.name, maxlength: 60 })),
                field(`Limit (${b.currency})`, input('amount', { class: 'input', inputmode: 'decimal', value: b.amount, required: true })),
                field('Ends on', input('endDate', { type: 'date', value: b.endDate ?? '' })),
                field('Alert at (%)', input('alertThresholds', { value: b.alertThresholds.join(', '), placeholder: 'e.g. 75, 90, 100' }))),
            el('p', { class: 'faint', text: 'A new limit applies to every period, including the current one.' }),
        ],
        submit: async (form) => {
            const thresholds = form.alertThresholds.value.split(/[,\s]+/).filter(Boolean).map(Number);
            await api.patch(`/budgets/${b.id}`, {
                name: form.name.value.trim() || null, amount: parseAmount(form.amount.value),
                endDate: form.endDate.value || null, alertThresholds: thresholds.length ? thresholds : null,
            });
            toast('Budget saved');
            onSaved();
        },
    });
}

