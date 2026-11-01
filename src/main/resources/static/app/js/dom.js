// Tiny DOM toolkit. User data is always set with textContent / attributes, never parsed as HTML.

const SVG_NS = 'http://www.w3.org/2000/svg';

export function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    applyProps(node, props);
    append(node, children);
    return node;
}

export function svg(tag, attrs = {}, ...children) {
    const node = document.createElementNS(SVG_NS, tag);
    for (const [key, value] of Object.entries(attrs)) {
        if (value === undefined || value === null) continue;
        if (key === 'style') Object.assign(node.style, value);
        else if (key === 'text') node.textContent = value;
        else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
        else node.setAttribute(key, value);
    }
    append(node, children);
    return node;
}

function applyProps(node, props) {
    for (const [key, value] of Object.entries(props)) {
        if (value === undefined || value === null || value === false) continue;
        if (key === 'class') node.className = value;
        else if (key === 'text') node.textContent = value;
        else if (key === 'style') Object.assign(node.style, value);
        else if (key === 'dataset') Object.assign(node.dataset, value);
        else if (key.startsWith('on') && typeof value === 'function') node.addEventListener(key.slice(2), value);
        else if (key === 'value') node.value = value;
        else node.setAttribute(key, value === true ? '' : value);
    }
}

function append(node, children) {
    for (const child of children.flat(Infinity)) {
        if (child === null || child === undefined || child === false) continue;
        node.append(child instanceof Node ? child : String(child));
    }
}

export function fill(container, ...children) {
    container.replaceChildren();
    append(container, children);
    return container;
}

// Line icons (24x24, stroke). Kept here so there are no external requests.
const ICONS = {
    home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
    list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
    plus: 'M12 5v14M5 12h14',
    sun: 'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
    moon: 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z',
    logout: 'M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9',
    wallet: 'M3 7a2 2 0 0 1 2-2h13v4M3 7v10a2 2 0 0 0 2 2h15V9H5a2 2 0 0 1-2-2zM16 14h.01',
    card: 'M2 6a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2zM2 10h20M6 15h4',
    bank: 'M3 10h18M5 10v8M9 10v8M15 10v8M19 10v8M3 21h18M12 3l9 5H3z',
    cash: 'M2 7h20v10H2zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM6 12h.01M18 12h.01',
    down: 'M12 5v14M5 12l7 7 7-7',
    up: 'M12 19V5M5 12l7-7 7 7',
    swap: 'M7 7h13l-4-4M17 17H4l4 4',
    target: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 12h.01',
    trend: 'M3 17l6-6 4 4 8-8M15 7h6v6',
    calendar: 'M3 6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2zM3 10h18M8 2v4M16 2v4',
    search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM21 21l-5-5',
    x: 'M18 6 6 18M6 6l12 12',
    check: 'M5 12l5 5L20 7',
    inbox: 'M22 12h-6l-2 3h-4l-2-3H2M5.5 5h13L22 12v6a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-6z',
    undo: 'M9 14 4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3',
    trash: 'M3 6h18M8 6V4h8v2M6 6l1 14h10l1-14',
    left: 'M15 18l-6-6 6-6',
    right: 'M9 18l6-6-6-6',
    income: 'M12 3v18M17 7.5C17 5.6 14.8 4 12 4S7 5.6 7 7.5 9.2 11 12 11s5 1.6 5 3.5S14.8 18 12 18s-5-1.6-5-3.5',
    alert: 'M12 9v4M12 17h.01M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z',
    people: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM22 21v-2a4 4 0 0 0-3-3.9M16 3.1a4 4 0 0 1 0 7.8',
    clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
    bell: 'M18 8a6 6 0 1 0-12 0c0 7-3 9-3 9h18s-3-2-3-9M13.7 21a2 2 0 0 1-3.4 0',
    repeat: 'M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3',
    settings: 'M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z',
    more: 'M5 12h.01M12 12h.01M19 12h.01',
    edit: 'M12 20h9M16.5 3.5a2.1 2.1 0 1 1 3 3L7 19l-4 1 1-4z',
    pause: 'M8 5v14M16 5v14',
    play: 'M7 4l13 8-13 8z',
    stop: 'M6 6h12v12H6z',
    skip: 'M5 4l10 8-10 8zM19 5v14',
    chart: 'M3 3v18h18M8 17V11M13 17V7M18 17v-4',
    mail: 'M4 5h16a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zM3 7l9 6 9-6',
    lock: 'M6 11h12a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1v-8a1 1 0 0 1 1-1zM8 11V7a4 4 0 1 1 8 0v4',
    eye: 'M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
    'eye-off': 'M3 3l18 18M10.6 10.6a3 3 0 0 0 4.2 4.2M9.9 5.1A9.7 9.7 0 0 1 12 5c6.5 0 10 7 10 7a17 17 0 0 1-3.2 4.2M6.6 6.6C3.8 8.4 2 12 2 12s3.5 7 10 7a9.6 9.6 0 0 0 5.4-1.6',
    user: 'M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2M12 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z',
    'user-plus': 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM19 8v6M22 11h-6',
    shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10zM9 12l2 2 4-4',
    sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.8 2.2L22 19l-2.2.8L19 22l-.8-2.2L16 19l2.2-.8z',
    bolt: 'M13 2L4 14h7l-1 8 9-12h-7z',
    'arrow-right': 'M5 12h14M13 6l6 6-6 6',
    clip: 'M21.4 11.1l-9.2 9.2a6 6 0 0 1-8.5-8.5l9.2-9.2a4 4 0 0 1 5.7 5.7l-9.2 9.2a2 2 0 0 1-2.8-2.8l8.5-8.5',
    mic: 'M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3zM19 10v2a7 7 0 0 1-14 0v-2M12 19v3',
    download: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3',
    upload: 'M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12',
    file: 'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6',
};

export function icon(name, extraClass = '') {
    return svg('svg', { class: `icon ${extraClass}`.trim(), viewBox: '0 0 24 24', 'aria-hidden': 'true' },
        svg('path', { d: ICONS[name] ?? ICONS.inbox }));
}

export function empty(text, iconName = 'inbox', action = null) {
    return el('div', { class: 'empty' }, icon(iconName), el('p', { text }), action);
}

export function skeleton(height = 16, width = '100%') {
    return el('div', { class: 'skeleton', style: { height: `${height}px`, width } });
}

// ---- Toasts ----

export function toast(message, kind = 'info') {
    const box = document.getElementById('toasts');
    const node = el('div', { class: `toast ${kind}`, role: kind === 'error' ? 'alert' : 'status' },
        icon(kind === 'error' ? 'alert' : 'check'), el('span', { text: message }));
    box.append(node);
    setTimeout(() => node.remove(), kind === 'error' ? 6000 : 3500);
}

// ---- Dialogs ----

/**
 * Opens a modal form. `fields(form)` builds the body; `submit(form)` runs on submit and may throw
 * an ApiError whose field errors are shown next to the matching inputs.
 */
export function openSheet({ title, fields, submitLabel = 'Save', submit, onClose, danger = null }) {
    const errorBox = el('div', { class: 'form-error', role: 'alert', hidden: true });
    const body = el('div', { class: 'sheet-body' });
    const saveBtn = el('button', { class: 'btn primary', type: 'submit', text: submitLabel });
    const form = el('form', { method: 'dialog', novalidate: true },
        el('div', { class: 'sheet-head' }, el('h2', { text: title }),
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Close', onclick: () => close() }, icon('x'))),
        body,
        el('div', { class: 'sheet-foot' },
            danger ? el('button', { class: 'btn ghost danger push-left', type: 'button', text: danger.label,
                onclick: async () => { try { if (await danger.action()) close(); } catch (err) { showErrors(form, errorBox, err); } } }) : null,
            el('button', { class: 'btn ghost', type: 'button', text: 'Cancel', onclick: () => close() }), saveBtn));
    const dialog = el('dialog', { class: 'sheet' }, form);

    const close = () => { dialog.close(); dialog.remove(); onClose?.(); };
    const render = () => { fill(body, errorBox, fields(form)); };
    dialog.addEventListener('cancel', (e) => { e.preventDefault(); close(); });

    form.addEventListener('submit', async (e) => {
        e.preventDefault();
        clearErrors(form);
        errorBox.hidden = true;
        saveBtn.disabled = true;
        try {
            await submit(form);
            close();
        } catch (err) {
            showErrors(form, errorBox, err);
        } finally {
            saveBtn.disabled = false;
        }
    });

    render();
    document.body.append(dialog);
    dialog.showModal();
    form.querySelector('input:not([type=hidden]), select')?.focus();
    return { dialog, form, close, render };
}

/** A read-only modal with action buttons; content() is re-run by 
ender() after changes. */
export function openPanel({ title, content, onClose }) {
    const body = el('div', { class: 'sheet-body' });
    const heading = el('h2', { text: title });
    const setTitle = (text) => { heading.textContent = text; };
    const dialog = el('dialog', { class: 'sheet' },
        el('div', { class: 'sheet-head' }, heading,
            el('button', { class: 'btn ghost icon-only', type: 'button', 'aria-label': 'Close', onclick: () => close() }, icon('x'))),
        body);
    const close = () => { dialog.close(); dialog.remove(); onClose?.(); };
    const render = async () => {
        try {
            fill(body, await content({ close, render, setTitle }));
        } catch (err) {
            fill(body, empty(err?.message ?? 'Something went wrong', 'alert'));
        }
    };
    dialog.addEventListener('cancel', (e) => { e.preventDefault(); close(); });
    document.body.append(dialog);
    dialog.showModal();
    render();
    return { close, render };
}

/** "transactionDate" -> "Transaction date"; nested paths keep their last part. */
export function fieldLabel(name) {
    const last = String(name).split('.').pop().replace(/\[\d+\]/g, '').replace(/Id$/, '');
    const words = last.replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
    return words.charAt(0).toUpperCase() + words.slice(1);
}

function clearErrors(form) {
    form.querySelectorAll('.err').forEach((n) => n.remove());
    form.querySelectorAll('.invalid').forEach((n) => n.classList.remove('invalid'));
}

function showErrors(form, errorBox, err) {
    let unmatched = [];
    for (const f of err.fields ?? []) {
        const input = form.elements.namedItem(f.field);
        if (input instanceof Element) {
            input.classList.add('invalid');
            input.closest('.field')?.append(el('span', { class: 'err', text: f.message }));
        } else {
            unmatched.push(`${fieldLabel(f.field)}: ${f.message}`);
        }
    }
    if (!err.fields?.length || unmatched.length) {
        errorBox.textContent = unmatched.length ? unmatched.join(' · ') : err.message;
        errorBox.hidden = false;
    }
}

/**
 * In-app confirmation (replaces the browser's confirm/prompt). Resolves to null when cancelled,
 * otherwise to { reason } (reason only when reasonLabel is given).
 */
export function confirmDialog({ title, message, confirmLabel = 'Confirm', danger = false, reasonLabel = null }) {
    return new Promise((resolve) => {
        let done = false;
        const sheet = openSheet({
            title,
            submitLabel: confirmLabel,
            fields: () => [
                el('p', { class: 'muted confirm-text', text: message }),
                reasonLabel ? field(reasonLabel, input('reason', { maxlength: 200, placeholder: 'Optional' })) : null,
            ],
            submit: async (form) => {
                done = true;
                resolve({ reason: reasonLabel ? form.elements.namedItem('reason').value.trim() || null : null });
            },
            onClose: () => { if (!done) resolve(null); },
        });
        sheet.dialog.classList.add('confirm');
        if (danger) sheet.form.querySelector('button[type=submit]').classList.add('danger-fill');
        sheet.form.querySelector('button[type=submit]').focus();
    });
}

export function field(label, control, extra = {}) {
    return el('label', { class: `field ${extra.span ? 'span-2' : ''}`.trim() }, el('span', { text: label }), control);
}

export function input(name, props = {}) {
    return el('input', { class: 'input', name, ...props });
}

export function select(name, options, props = {}) {
    return el('select', { class: 'input', name, ...props },
        options.map((o) => (o.group
            ? el('optgroup', { label: o.group }, o.options.map((x) => el('option', { value: x.value, text: x.label, selected: x.selected })))
            : el('option', { value: o.value, text: o.label, selected: o.selected }))));
}

export function segmented(options, current, onChange, label) {
    return el('div', { class: 'segmented', role: 'group', 'aria-label': label },
        options.map((o) => el('button', {
            type: 'button', text: o.label, 'aria-pressed': String(o.value === current),
            onclick: () => onChange(o.value),
        })));
}
