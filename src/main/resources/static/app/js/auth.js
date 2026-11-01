// Landing and sign-in: brand bar, hero with the account card, a product preview, features,
// how it works, privacy, footer. In-page links scroll; they never touch the app's #/ routes.

import { api, errorText, session } from './api.js';
import { el, field, fieldLabel, fill, icon, input } from './dom.js';

/** Theme before sign-in: remembered on this device only; after sign-in the account preference wins. */
export function savedTheme() {
    try { return localStorage.getItem('pt.theme'); } catch { return null; }
}

export function rememberTheme(theme) {
    try { localStorage.setItem('pt.theme', theme); } catch { /* private mode: fine */ }
}

function currentTheme() {
    const forced = document.documentElement.dataset.theme;
    if (forced) return forced;
    return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function brand() {
    return el('span', { class: 'brand' }, el('span', { class: 'brand-mark', text: '₹' }),
        el('span', { class: 'brand-text' }, 'PaisaTrack', el('small', { text: 'Track · Budget · Grow' })));
}

const FEATURES = [
    ['bolt', 'Expenses in seconds', 'Amount and category is all it takes. Cash, UPI, cards and wallets, each in its own account.'],
    ['target', 'Budgets with early alerts', 'Monthly, weekly or custom limits that warn you at 75%, 90% and 100%.'],
    ['repeat', 'Bills and subscriptions', 'Rent, utilities, OTT and SIPs remembered for you. Confirm in one tap when paid.'],
    ['bank', 'Loans and EMIs', 'Full EMI schedules, interest kept apart from principal, prepayments that shorten the loan.'],
    ['people', 'Lending and borrowing', 'Who owes whom, partial repayments, due dates and reminders. Never mixed into spending.'],
    ['chart', 'Reports that explain', 'Daily to yearly reports, fair comparisons with last period and plain-language insights.'],
];

const STEPS = [
    ['wallet', 'Add your accounts', 'Bank, cash, cards, wallets and investments, each with its own balance.'],
    ['plus', 'Record as you go', 'Log spending in seconds; salary, bills and EMIs remind you before they are due.'],
    ['trend', 'See where it goes', 'Budgets, reports and insights show what changed and what to do next.'],
];

export function renderAuth(root, onSignedIn) {
    let mode = 'login';
    let reveal = false;
    let page;
    const draft = { email: '', fullName: '' }; // survives switching between Sign in and Create account

    const scrollTo = (id) => page.querySelector(`#${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    const openForm = (next) => {
        if (mode !== next) { mode = next; render(); }
        const card = page.querySelector('.auth-card');
        card?.scrollIntoView({ behavior: 'smooth', block: 'center' });
        card?.querySelector('input')?.focus({ preventScroll: true });
    };

    function accountCard() {
        const error = el('div', { class: 'form-error', role: 'alert', hidden: true });
        const submit = el('button', { class: 'btn primary wide cta', type: 'submit' },
            mode === 'login' ? 'Sign in' : 'Create free account', icon('arrow-right'));
        const password = input('password', {
            type: reveal ? 'text' : 'password', maxlength: 64, required: true, class: 'input has-reveal',
            autocomplete: mode === 'login' ? 'current-password' : 'new-password', placeholder: mode === 'login' ? 'Your password' : 'Choose a password',
        });
        const revealButton = el('button', { class: 'btn ghost icon-only sm reveal', type: 'button',
            'aria-label': reveal ? 'Hide password' : 'Show password', 'aria-pressed': String(reveal), onclick: () => {
                reveal = !reveal;
                password.type = reveal ? 'text' : 'password';
                revealButton.setAttribute('aria-pressed', String(reveal));
                revealButton.setAttribute('aria-label', reveal ? 'Hide password' : 'Show password');
                fill(revealButton, icon(reveal ? 'eye-off' : 'eye'));
                password.focus();
            } }, icon(reveal ? 'eye-off' : 'eye'));
        const form = el('form', { novalidate: true, onsubmit: async (e) => {
            e.preventDefault();
            const f = e.target;
            const body = { email: f.email.value.trim(), password: f.password.value };
            if (mode === 'register') {
                body.fullName = f.fullName.value.trim();
                body.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
            }
            f.querySelectorAll('.err').forEach((n) => n.remove());
            f.querySelectorAll('.invalid').forEach((n) => n.classList.remove('invalid'));
            error.hidden = true;
            const missing = [mode === 'register' && !body.fullName ? 'your name' : null, !body.email ? 'your email' : null,
                !body.password ? 'a password' : null].filter(Boolean);
            if (missing.length) {
                const list = missing.length > 1 ? `${missing.slice(0, -1).join(', ')} and ${missing.at(-1)}` : missing[0];
                error.textContent = `Please enter ${list}.`;
                error.hidden = false;
                return;
            }
            submit.disabled = true;
            try {
                const data = await api.public(`/auth/${mode === 'login' ? 'login' : 'register'}`, body);
                session.save(data);
                onSignedIn();
            } catch (err) {
                // Field problems go under their field; anything else in the box above the button.
                const leftover = [];
                for (const fe of err.fields ?? []) {
                    const control = f.elements.namedItem(fe.field);
                    if (control instanceof Element) {
                        control.classList.add('invalid');
                        control.closest('.field')?.append(el('span', { class: 'err', text: `${fieldLabel(fe.field)} ${fe.message}` }));
                    } else {
                        leftover.push(`${fieldLabel(fe.field)} ${fe.message}`);
                    }
                }
                if (!err.fields?.length || leftover.length) {
                    error.textContent = leftover.length ? leftover.join(' · ') : errorText(err);
                    error.hidden = false;
                }
                submit.disabled = false;
            }
        } },
        mode === 'register' ? field('Full name', el('div', { class: 'with-icon' }, icon('user'),
            input('fullName', { autocomplete: 'name', maxlength: 100, required: true, placeholder: 'Your name', value: draft.fullName,
                oninput: (e) => { draft.fullName = e.target.value; } }))) : null,
        field('Email', el('div', { class: 'with-icon' }, icon('mail'),
            input('email', { type: 'email', autocomplete: 'email', maxlength: 254, required: true, placeholder: 'you@example.com',
                value: draft.email, oninput: (e) => { draft.email = e.target.value; } }))),
        field('Password', el('div', { class: 'with-icon' }, icon('lock'), password, revealButton)),
        mode === 'register' ? el('p', { class: 'faint hint', text: 'At least 8 characters, with a letter and a digit.' }) : null,
        error, submit);

        const tab = (value, iconName, label) => el('button', { type: 'button', 'aria-pressed': String(mode === value),
            onclick: () => { if (mode !== value) { mode = value; render(); page.querySelector('.auth-card input')?.focus(); } } },
        icon(iconName), label);

        return el('section', { class: 'auth-card', 'aria-label': mode === 'login' ? 'Sign in' : 'Create account' },
            el('div', {},
                el('h2', { text: mode === 'login' ? 'Welcome back' : 'Create your account' }),
                el('p', { class: 'muted', text: mode === 'login' ? 'Sign in to see your money at a glance.' : 'Free, private, and ready in a minute.' })),
            el('div', { class: 'auth-tabs', role: 'group', 'aria-label': 'Sign in or create account' },
                tab('login', 'user', 'Sign in'), tab('register', 'user-plus', 'Create account')),
            form,
            el('p', { class: 'auth-foot faint' }, icon('shield'), 'Private to your account. Every change is audited.'));
    }

    function render() {
        const scrollY = window.scrollY;
        page = el('div', { class: 'landing' },
            el('header', { class: 'land-nav' },
                el('div', { class: 'land-wrap land-nav-inner' },
                    brand(),
                    el('nav', { class: 'land-links', 'aria-label': 'Sections' },
                        el('button', { type: 'button', text: 'Features', onclick: () => scrollTo('features') }),
                        el('button', { type: 'button', text: 'How it works', onclick: () => scrollTo('how') }),
                        el('button', { type: 'button', text: 'Privacy', onclick: () => scrollTo('privacy') })),
                    el('div', { class: 'land-actions' },
                        themeSwitch(render),
                        el('button', { class: 'btn ghost nav-signin', type: 'button', onclick: () => openForm('login') }, 'Sign in')))),

            el('section', { class: 'land-wrap land-hero' },
                el('div', { class: 'hero-copy' },
                    el('span', { class: 'eyebrow' }, icon('sparkle'), 'Your personal finance companion'),
                    el('h1', {}, 'Record every rupee.', el('br'), el('span', { class: 'grad-text', text: 'Understand every expense.' })),
                    el('p', { class: 'lead', text: 'Expenses, budgets, bills, loans and reports in one calm place. Simple enough for every day, careful enough for every rupee.' }),
                    el('div', { class: 'hero-ctas' },
                        el('button', { class: 'btn primary cta', type: 'button', onclick: () => openForm('register') }, 'Create free account', icon('arrow-right')),
                        el('button', { class: 'btn cta', type: 'button', onclick: () => scrollTo('features') }, 'See features')),
                    el('ul', { class: 'checks' },
                        el('li', {}, icon('check'), 'No ads, no selling data'),
                        el('li', {}, icon('check'), 'Phone and desktop'),
                        el('li', {}, icon('check'), 'Light and dark'))),
                accountCard()),

            el('section', { class: 'land-band' },
                el('div', { class: 'land-wrap glance' },
                    el('div', { class: 'glance-copy' },
                        el('span', { class: 'kicker', text: 'At a glance' }),
                        el('h2', { text: 'Your month, in one screen' }),
                        el('p', { class: 'muted', text: 'What you spent, what came in, what is due next, and how you compare with last month. Plans and reminders never get counted as real spending.' }),
                        el('ul', { class: 'glance-points' },
                            glancePoint('trend', 'Fair comparisons', 'This month so far vs the same days last month.'),
                            glancePoint('bell', 'Reminders that matter', 'EMIs, bills, dues and budgets, before the date.'),
                            glancePoint('shield', 'Money kept apart', 'Transfers, loans and lending never inflate spending.'))),
                    devicePreview())),

            el('section', { class: 'land-wrap land-section', id: 'features' },
                el('div', { class: 'section-head' },
                    el('span', { class: 'kicker', text: 'Features' }),
                    el('h2', { text: 'Everything your money touches' }),
                    el('p', { class: 'muted', text: 'Built around how money really moves in India: UPI, cash, cards, EMIs, rent and salary credits.' })),
                el('div', { class: 'feature-grid' }, FEATURES.map(([i, t, d]) => el('article', { class: 'feature-card' },
                    el('span', { class: 'feature-icon' }, icon(i)), el('h3', { text: t }), el('p', { text: d }))))),

            el('section', { class: 'land-wrap land-section', id: 'how' },
                el('div', { class: 'section-head' },
                    el('span', { class: 'kicker', text: 'How it works' }),
                    el('h2', { text: 'Three steps to clarity' })),
                el('ol', { class: 'steps' }, STEPS.map(([i, t, d], n) => el('li', { class: 'step' },
                    el('span', { class: 'step-num', text: String(n + 1) }),
                    el('span', { class: 'feature-icon' }, icon(i)), el('h3', { text: t }), el('p', { text: d }))))),

            el('section', { class: 'land-wrap land-section', id: 'privacy' },
                el('div', { class: 'privacy' },
                    el('span', { class: 'privacy-icon' }, icon('shield')),
                    el('div', {},
                        el('h2', { text: 'Your data stays yours' }),
                        el('p', { class: 'muted', text: 'PaisaTrack is private by design. Nothing is shared, sold or shown to anyone else.' }),
                        el('ul', { class: 'checks' },
                            el('li', {}, icon('check'), 'Passwords are hashed, never stored as text'),
                            el('li', {}, icon('check'), 'Every account sees only its own data'),
                            el('li', {}, icon('check'), 'Every financial change is audited'),
                            el('li', {}, icon('check'), 'Sessions sign out when the tab closes'))),
                    el('button', { class: 'btn primary cta', type: 'button', onclick: () => openForm('register') }, 'Get started', icon('arrow-right')))),

            el('footer', { class: 'land-footer' },
                el('div', { class: 'land-wrap land-footer-inner' },
                    brand(),
                    el('span', { class: 'faint', text: `© ${new Date().getFullYear()} PaisaTrack. Every rupee, accounted for.` }),
                    el('button', { class: 'btn ghost sm', type: 'button', onclick: () => window.scrollTo({ top: 0, behavior: 'smooth' }) },
                        icon('up'), 'Back to top'))));
        fill(root, page);
        window.scrollTo({ top: scrollY });
    }

    render();
    page.querySelector('.auth-card input')?.focus({ preventScroll: true });
}

function glancePoint(iconName, title, text) {
    return el('li', {}, el('span', { class: 'feature-icon sm' }, icon(iconName)), el('div', {}, el('b', { text: title }), el('span', { text })));
}

function themeSwitch(onChange) {
    const set = (theme) => () => {
        document.documentElement.dataset.theme = theme;
        rememberTheme(theme.toUpperCase());
        onChange();
    };
    const now = currentTheme();
    return el('div', { class: 'theme-switch', role: 'group', 'aria-label': 'Theme' },
        el('button', { type: 'button', 'aria-label': 'Light theme', 'aria-pressed': String(now === 'light'), onclick: set('light') }, icon('sun')),
        el('button', { type: 'button', 'aria-label': 'Dark theme', 'aria-pressed': String(now === 'dark'), onclick: set('dark') }, icon('moon')));
}

/** A phone showing what the app looks like. Sample figures only; hidden from screen readers. */
function devicePreview() {
    const legend = [['var(--chart-1)', 'Food', '32%'], ['var(--chart-2)', 'Rent', '24%'], ['var(--chart-3)', 'Travel', '15%'], ['var(--chart-5)', 'Shopping', '12%']];
    const wrap = el('div', { class: 'device-wrap', 'aria-hidden': 'true' });
    const spark = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    spark.setAttribute('viewBox', '0 0 200 34');
    spark.setAttribute('preserveAspectRatio', 'none');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    path.setAttribute('d', 'M0 30 C20 28 30 22 50 22 S80 26 100 18 S130 10 150 12 S180 6 200 3');
    spark.append(path);
    wrap.append(
        el('div', { class: 'float-card fc-1' }, icon('check'), el('span', {}, el('b', { text: 'Rent paid' }), el('small', { text: '₹18,000 · on time' }))),
        el('div', { class: 'float-card fc-2' }, icon('target'), el('span', {}, el('b', { text: 'Budget 64% used' }), el('small', { text: '₹14,200 left this month' }))),
        el('div', { class: 'device' },
            el('span', { class: 'notch' }),
            el('div', { class: 'd-head' }, el('span', {}, 'Good morning,', el('b', { text: 'Asha' })), el('span', { text: 'This month' })),
            el('div', { class: 'd-spent' }, el('small', { text: 'Spent this month' }), el('b', { text: '₹32,840' }), spark),
            el('div', { class: 'd-tiles' },
                el('div', { class: 'd-tile' }, 'Income', el('b', { text: '₹75,000' })),
                el('div', { class: 'd-tile' }, 'Saved', el('b', { text: '56%' }))),
            el('div', { class: 'd-cats' }, el('div', { class: 'd-ring' }),
                el('div', { class: 'd-legend' }, legend.map(([c, n, p]) => el('span', {}, el('span', {}, el('i', { style: { background: c } }), n), el('b', { text: p }))))),
            el('div', { class: 'd-nav' }, icon('home'), icon('list'), el('span', { class: 'd-add' }, icon('plus')), icon('chart'), icon('more'))));
    return wrap;
}
