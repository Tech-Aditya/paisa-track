// Sign-in and sign-up screen.

import { api, errorText, session } from './api.js';
import { el, field, fill, icon, input, segmented } from './dom.js';

export function renderAuth(root, onSignedIn) {
    let mode = 'login';

    const render = () => {
        const error = el('div', { class: 'form-error', role: 'alert', hidden: true });
        const submit = el('button', { class: 'btn primary wide', type: 'submit', text: mode === 'login' ? 'Sign in' : 'Create account' });
        const form = el('form', { novalidate: true, onsubmit: async (e) => {
            e.preventDefault();
            const f = e.target;
            const body = { email: f.email.value.trim(), password: f.password.value };
            if (mode === 'register') {
                body.fullName = f.fullName.value.trim();
                body.timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
            }
            submit.disabled = true;
            error.hidden = true;
            try {
                const data = await api.public(`/auth/${mode === 'login' ? 'login' : 'register'}`, body);
                session.save(data);
                onSignedIn();
            } catch (err) {
                error.textContent = errorText(err);
                error.hidden = false;
                submit.disabled = false;
            }
        } },
        mode === 'register' ? field('Full name', input('fullName', { autocomplete: 'name', maxlength: 100, required: true })) : null,
        field('Email', input('email', { type: 'email', autocomplete: 'email', maxlength: 254, required: true })),
        field('Password', input('password', { type: 'password', maxlength: 64, required: true,
            autocomplete: mode === 'login' ? 'current-password' : 'new-password' })),
        mode === 'register' ? el('p', { class: 'faint', text: 'At least 8 characters, with a letter and a digit.' }) : null,
        error, submit);

        fill(root, el('main', { class: 'auth' },
            el('section', { class: 'auth-hero' },
                el('span', { class: 'brand' }, el('span', { class: 'brand-mark', text: '₹' }), 'PaisaTrack'),
                el('div', {},
                    el('h2', { text: 'Record every rupee. Understand every expense.' }),
                    el('ul', {},
                        el('li', {}, icon('check'), 'Log an expense in seconds'),
                        el('li', {}, icon('check'), 'Budgets that warn you before you overspend'),
                        el('li', {}, icon('check'), 'Know when your salary is due, and when it arrived'))),
                el('p', { class: 'faint', style: { color: '#d3f5ea' }, text: 'Your data stays yours: private, per-user and audited.' })),
            el('section', { class: 'auth-panel' },
                el('div', { class: 'auth-card' },
                    el('div', {},
                        el('h1', { text: mode === 'login' ? 'Welcome back' : 'Create your account' }),
                        el('p', { class: 'muted', text: mode === 'login' ? 'Sign in to see your money at a glance.' : 'Free, and ready in a minute.' })),
                    segmented([{ value: 'login', label: 'Sign in' }, { value: 'register', label: 'Create account' }],
                        mode, (m) => { mode = m; render(); }, 'Sign in or create account'),
                    form))));
        form.querySelector('input')?.focus();
    };
    render();
}
