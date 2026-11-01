// Settings: profile, preferences (currency, timezone, week start, theme), exchange rates, reminders, password.

import { api, errorText } from '../api.js';
import { card } from '../components.js';
import { confirmDialog, el, empty, field, fill, icon, input, select, skeleton, toast } from '../dom.js';
import { isoDate, parseAmount, shortDate, titleCase } from '../format.js';
import { currencyOptions, store } from '../store.js';

const TYPES = {
    EMI: ['Loan EMIs', 'Before each EMI and when one is missed'],
    BILL: ['Bills & rent', 'Rent, utilities, insurance'],
    SUBSCRIPTION: ['Subscriptions', 'Renewals like OTT and gym'],
    RECURRING: ['Other recurring', 'SIPs, recurring income and the rest'],
    LENDING: ['Money lent', 'When someone should pay you back'],
    BORROWING: ['Money borrowed', 'When you should pay someone back'],
    BUDGET: ['Budgets', 'When a budget reaches an alert threshold'],
    SALARY: ['Salary & income', 'When expected income is due or late'],
};
const DAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];

export async function renderSettings(root, { onThemeChange, onPasswordChanged, onProfileChanged } = {}) {
    const head = el('header', { class: 'page-head' },
        el('div', {}, el('h1', { text: 'Settings' }), el('p', { text: 'Your profile, preferences and reminders.' })));
    fill(root, head, el('div', { class: 'card' }, skeleton(18, '30%'), skeleton(40)));

    let me;
    let prefs;
    let currencies;
    try {
        [me, prefs, currencies] = await Promise.all([api.get('/users/me'), api.get('/notifications/preferences'), store.currencies()]);
    } catch (e) {
        fill(root, head, el('div', { class: 'card' }, empty(errorText(e), 'alert')));
        return;
    }
    const p = me.preferences;
    const zones = typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [p.timezone];
    if (!zones.includes(p.timezone)) zones.unshift(p.timezone);

    const profile = el('form', { class: 'settings-form', onsubmit: (e) => save(e, async (f) => {
        await api.patch('/users/me', { fullName: f.fullName.value.trim() || null });
        store.invalidate('me');
        onProfileChanged?.();
    }, 'Profile saved') },
    el('div', { class: 'form-grid' },
        field('Full name', input('fullName', { value: me.fullName, maxlength: 100, required: true, autocomplete: 'name' })),
        field('Email', input('email', { value: me.email, disabled: true }))),
    el('div', { class: 'form-actions' }, el('button', { class: 'btn primary', type: 'submit', text: 'Save profile' })));

    const preferences = el('form', { class: 'settings-form', onsubmit: (e) => save(e, async (f) => {
        const theme = f.theme.value;
        await api.patch('/users/me/preferences', {
            defaultCurrency: f.defaultCurrency.value, timezone: f.timezone.value, weekStartDay: f.weekStartDay.value, theme,
        });
        onThemeChange?.(theme);
    }, 'Preferences saved') },
    el('div', { class: 'form-grid' },
        field('Default currency', select('defaultCurrency', currencyOptions(currencies, p.defaultCurrency))),
        field('Timezone', select('timezone', zones.map((z) => ({ value: z, label: z.replaceAll('_', ' '), selected: z === p.timezone })))),
        field('Week starts on', select('weekStartDay', DAYS.map((d) => ({ value: d, label: titleCase(d), selected: d === p.weekStartDay })))),
        field('Theme', select('theme', ['SYSTEM', 'LIGHT', 'DARK'].map((t) => ({ value: t, label: titleCase(t), selected: t === p.theme }))))),
    el('p', { class: 'faint', text: 'Existing records keep their own currency. "Today", weeks and due dates follow your timezone.' }),
    el('div', { class: 'form-actions' }, el('button', { class: 'btn primary', type: 'submit', text: 'Save preferences' })));

    const reminders = el('form', { class: 'settings-form', onsubmit: (e) => save(e, async (f) => {
        await api.put('/notifications/preferences', {
            preferences: Object.keys(TYPES).map((t) => ({
                type: t, enabled: f[`on-${t}`].checked, daysBefore: Number(f[`days-${t}`].value),
            })),
        });
    }, 'Reminders saved') },
    el('div', { class: 'pref-list' }, prefs.map((x) => {
        const [label, hint] = TYPES[x.type] ?? [x.type, ''];
        return el('div', { class: 'pref' },
            el('label', { class: 'switch' },
                el('input', { type: 'checkbox', name: `on-${x.type}`, checked: x.enabled }),
                el('span', { class: 'track', 'aria-hidden': 'true' }),
                el('span', {}, el('b', { text: label }), el('span', { class: 'faint', text: hint }))),
            x.type === 'BUDGET'
                ? el('input', { type: 'hidden', name: `days-${x.type}`, value: x.daysBefore })
                : el('label', { class: 'days' }, input(`days-${x.type}`, { type: 'number', min: 0, max: 30, value: x.daysBefore, 'aria-label': `${label}: days before` }),
                    el('span', { class: 'faint', text: 'days before' })));
    })),
    el('div', { class: 'form-actions' }, el('button', { class: 'btn primary', type: 'submit', text: 'Save reminders' })));

    const password = el('form', { class: 'settings-form', onsubmit: (e) => save(e, async (f) => {
        await api.put('/users/me/password', { currentPassword: f.currentPassword.value, newPassword: f.newPassword.value });
        f.reset();
        setTimeout(() => onPasswordChanged?.(), 1500);
    }, 'Password changed. Please sign in again.') },
    el('div', { class: 'form-grid' },
        field('Current password', input('currentPassword', { type: 'password', autocomplete: 'current-password', required: true })),
        field('New password', input('newPassword', { type: 'password', autocomplete: 'new-password', required: true, minlength: 8 }))),
    el('p', { class: 'faint', text: 'At least 8 characters, with a letter and a digit.' }),
    el('div', { class: 'form-actions' }, el('button', { class: 'btn', type: 'submit', text: 'Change password' })));

    fill(root, head, el('div', { class: 'stack' },
        card('Profile', {}, profile),
        card('Preferences', {}, preferences),
        ratesCard(currencies, p.defaultCurrency),
        card('Reminders', { sub: 'in-app, checked hourly' }, reminders),
        card('Password', {}, password)));
}

/** Your own rates (and the provider's, when one is configured), used to convert other currencies. */
function ratesCard(currencies, home) {
    const list = el('div', {}, skeleton(40));
    const providerSlot = el('div', { class: 'head-actions' });
    const load = async () => {
        try {
            const [rates, provider] = await Promise.all([api.get('/exchange-rates'), api.get('/exchange-rates/provider')]);
            fill(providerSlot, provider.enabled
                ? el('button', { class: 'btn sm', type: 'button', onclick: async () => {
                    try {
                        const r = await api.post('/exchange-rates/refresh');
                        toast(`Fetched ${r.stored} rates for ${shortDate(r.date)}`);
                        load();
                    } catch (e) { toast(errorText(e), 'error'); }
                } }, icon('repeat'), 'Fetch latest rates')
                : el('span', { class: 'faint small-note', text: 'Online rates are off; add rates yourself.' }));
            fill(list, rates.length
                ? el('div', { class: 'table-wrap' }, el('table', { class: 'schedule report-table' },
                    el('thead', {}, el('tr', {}, ['Pair', 'Rate', 'Date', 'Source', ''].map((h) => el('th', { text: h })))),
                    el('tbody', {}, rates.map((r) => el('tr', {},
                        el('td', { text: `1 ${r.base} → ${r.quote}` }),
                        el('td', { class: 'num', text: String(r.rate) }),
                        el('td', { text: shortDate(r.date) }),
                        el('td', {}, el('span', { class: `chip ${r.manual ? 'ok' : ''}`, text: r.manual ? 'You' : titleCase(r.source) })),
                        el('td', {}, r.manual ? el('button', { class: 'btn ghost sm icon-only', type: 'button', 'aria-label': `Delete ${r.base} to ${r.quote} rate`,
                            onclick: async () => {
                                if (!await confirmDialog({ title: 'Delete this rate?', confirmLabel: 'Delete', danger: true,
                                    message: 'Reports and the dashboard will use the next closest rate, or show the entries as unconverted.' })) return;
                                try { await api.del(`/exchange-rates/${r.id}`); toast('Rate deleted'); load(); } catch (e) { toast(errorText(e), 'error'); }
                            } }, icon('trash')) : null))))))
                : empty('No rates yet. Add one to include other currencies in your totals.', 'swap'));
        } catch (e) {
            fill(list, empty(errorText(e), 'alert'));
        }
    };
    const options = (selected) => currencyOptions(currencies, selected);
    const form = el('form', { class: 'settings-form', onsubmit: (e) => save(e, async (f) => {
        await api.post('/exchange-rates', { base: f.base.value, quote: f.quote.value, rate: parseAmount(f.rate.value), date: f.date.value || null });
        f.rate.value = '';
        load();
    }, 'Rate saved') },
    el('div', { class: 'form-grid rate-grid' },
        field('1 unit of', select('base', options('USD'))),
        field('equals', input('rate', { inputmode: 'decimal', placeholder: 'e.g. 83.25', required: true })),
        field('in', select('quote', options(home))),
        field('On', input('date', { type: 'date', value: isoDate(new Date()), max: isoDate(new Date()) }))),
    el('p', { class: 'faint', text: 'Each entry is converted with the latest rate on or before its own date, so past totals never shift. '
        + 'Amounts are never rewritten: every record keeps its own currency.' }),
    el('div', { class: 'form-actions' }, el('button', { class: 'btn primary', type: 'submit', text: 'Add rate' })));
    load();
    return card('Exchange rates', { action: providerSlot }, form, el('h3', { class: 'section-title rates-title', text: 'Saved rates' }), list);
}

async function save(e, action, message) {
    e.preventDefault();
    const form = e.target;
    const button = form.querySelector('button[type=submit]');
    form.querySelector('.form-error')?.remove();
    button.disabled = true;
    try {
        await action(form);
        store.invalidate('me');
        toast(message);
    } catch (err) {
        form.prepend(el('div', { class: 'form-error', role: 'alert', text: errorText(err) }));
    } finally {
        button.disabled = false;
    }
}
