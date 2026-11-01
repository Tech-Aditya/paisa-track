// Receipts on a transaction: list, attach, preview and remove. Files are fetched with the session
// token and shown from blob: URLs, so they are never reachable by a plain link.

import { api, errorText, fetchFile, upload } from './api.js';
import { confirmDialog, el, fill, icon, openPanel, toast } from './dom.js';

export const RECEIPT_TYPES = 'image/jpeg,image/png,image/webp,application/pdf';
const MAX_BYTES = 5 * 1024 * 1024;
const MAX_FILES = 5;

const size = (n) => (n >= 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1024))} KB`);

/** Problems with a file the server would refuse anyway, so the user hears about it at once. */
export function receiptProblem(file) {
    if (!RECEIPT_TYPES.split(',').includes(file.type)) return `${file.name}: only JPG, PNG, WEBP or PDF`;
    if (file.size > MAX_BYTES) return `${file.name}: larger than 5 MB`;
    return null;
}

export async function uploadReceipts(transactionId, files) {
    let done = 0;
    for (const file of files) {
        const form = new FormData();
        form.append('file', file);
        try {
            await upload(`/transactions/${transactionId}/attachments`, form);
            done += 1;
        } catch (e) {
            toast(`${file.name}: ${errorText(e)}`, 'error');
        }
    }
    return done;
}

/** Shows an image in a panel; PDFs open in a new tab. */
export async function previewReceipt(a) {
    let url;
    try {
        const blob = await (await fetchFile(`/attachments/${a.id}/content`)).blob();
        url = URL.createObjectURL(blob);
    } catch (e) {
        toast(errorText(e), 'error');
        return;
    }
    if (a.contentType === 'application/pdf') {
        const link = el('a', { href: url, target: '_blank', rel: 'noopener' });
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 60000);
        return;
    }
    openPanel({
        title: a.fileName,
        content: () => el('div', { class: 'receipt-view' }, el('img', { src: url, alt: a.fileName })),
        onClose: () => URL.revokeObjectURL(url),
    });
}

/** The receipts block inside the edit dialog of a saved transaction. */
export function receiptsSection(transactionId, { onChange } = {}) {
    const list = el('div', { class: 'receipts' });
    const picker = el('input', { type: 'file', accept: RECEIPT_TYPES, multiple: true, hidden: true });
    const addBtn = el('button', { class: 'btn ghost sm', type: 'button', onclick: () => picker.click() }, icon('clip'), 'Attach receipt');
    let count = 0;

    const load = async () => {
        let items;
        try {
            items = await api.get(`/transactions/${transactionId}/attachments`);
        } catch (e) {
            fill(list, el('p', { class: 'faint', text: errorText(e) }));
            return;
        }
        count = items.length;
        addBtn.hidden = count >= MAX_FILES;
        fill(list, items.length ? items.map((a) => el('div', { class: 'receipt' },
            el('button', { class: 'receipt-open', type: 'button', title: 'Open', onclick: () => previewReceipt(a) },
                icon(a.contentType === 'application/pdf' ? 'file' : 'eye'),
                el('span', { class: 'name', text: a.fileName }), el('span', { class: 'faint', text: size(a.sizeBytes) })),
            el('button', { class: 'btn ghost sm icon-only danger', type: 'button', 'aria-label': `Remove ${a.fileName}`, title: 'Remove',
                onclick: async () => {
                    if (!await confirmDialog({ title: 'Remove this receipt?', danger: true, confirmLabel: 'Remove',
                        message: 'The file is deleted. The transaction stays as it is.' })) return;
                    try {
                        await api.del(`/attachments/${a.id}`);
                        toast('Receipt removed');
                        onChange?.();
                        load();
                    } catch (e) {
                        toast(errorText(e), 'error');
                    }
                } }, icon('trash')))) : el('p', { class: 'faint', text: 'No receipts yet. JPG, PNG, WEBP or PDF, up to 5 MB.' }));
    };

    picker.addEventListener('change', async () => {
        const files = [...picker.files].slice(0, MAX_FILES - count);
        picker.value = '';
        const bad = files.map(receiptProblem).filter(Boolean);
        bad.forEach((m) => toast(m, 'error'));
        const ok = files.filter((f) => !receiptProblem(f));
        if (!ok.length) return;
        addBtn.disabled = true;
        const n = await uploadReceipts(transactionId, ok);
        addBtn.disabled = false;
        if (n) {
            toast(n === 1 ? 'Receipt attached' : `${n} receipts attached`);
            onChange?.();
        }
        load();
    });

    load();
    return el('div', { class: 'field span-2 receipts-field' },
        el('div', { class: 'receipts-head' }, el('span', { text: 'Receipts' }), addBtn), list, picker);
}

/** File picker for a new transaction; the files are uploaded once it is saved. */
export function pendingReceipts(state) {
    const picker = el('input', { type: 'file', accept: RECEIPT_TYPES, multiple: true, hidden: true });
    const label = el('span', { class: 'faint' });
    const btnText = el('span');
    const show = () => {
        label.textContent = state.files.length ? state.files.map((f) => f.name).join(', ') : 'Optional · JPG, PNG, WEBP or PDF';
        btnText.textContent = state.files.length ? 'Change' : 'Attach';
    };
    picker.addEventListener('change', () => {
        const files = [...picker.files].slice(0, MAX_FILES);
        files.map(receiptProblem).filter(Boolean).forEach((m) => toast(m, 'error'));
        state.files = files.filter((f) => !receiptProblem(f));
        picker.value = '';
        show();
    });
    show();
    return el('div', { class: 'field span-2 receipts-field' },
        el('div', { class: 'receipts-head' }, el('span', { text: 'Receipt' }),
            el('button', { class: 'btn ghost sm', type: 'button', onclick: () => picker.click() }, icon('clip'), btnText)),
        label, picker);
}
