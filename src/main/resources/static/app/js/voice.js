// Voice entry with the browser's own speech recognition (nothing is sent to PaisaTrack but the
// final form). "Spent 250 at Swiggy for lunch yesterday" becomes amount, merchant, description
// and date; the user always checks and saves.

import { addDays } from './format.js';

const Recognition = window.SpeechRecognition ?? window.webkitSpeechRecognition;

export const voiceSupported = () => Boolean(Recognition);

/** Listens once. Resolves with the transcript, or rejects with a readable message. */
export function listen({ onInterim } = {}) {
    let rec;
    const promise = new Promise((resolve, reject) => {
        rec = new Recognition();
        rec.lang = 'en-IN';
        rec.interimResults = true;
        rec.maxAlternatives = 1;
        let finalText = '';
        rec.onresult = (e) => {
            let interim = '';
            for (let i = e.resultIndex; i < e.results.length; i++) {
                if (e.results[i].isFinal) finalText += e.results[i][0].transcript;
                else interim += e.results[i][0].transcript;
            }
            onInterim?.(finalText + interim);
        };
        rec.onerror = (e) => reject(new Error(e.error === 'not-allowed' || e.error === 'service-not-allowed'
            ? 'Microphone access was blocked. Allow it in the browser to use voice entry.'
            : e.error === 'no-speech' ? "Didn't catch that. Try again." : 'Voice entry stopped. Try again.'));
        rec.onend = () => resolve(finalText.trim());
        rec.start();
    });
    return { promise, stop: () => rec?.stop() };
}

const WEEKDAYS = ['sunday', 'monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday'];
const INCOME_WORDS = /\b(received|receive|got|earned|salary|credited|income|refund)\b/;
const STOP = '(?=\\s+(?:for|at|from|on|to|via|using|yesterday|today|day before yesterday|last)\\b|[.,]|$)';

/** Pulls amount, type, merchant, description and date out of a sentence. Unknown parts are left null. */
export function parseSpoken(text, today) {
    const s = ` ${String(text).toLowerCase().replace(/₹/g, ' rs ').replace(/\s+/g, ' ').trim()} `;
    const out = { amount: null, type: INCOME_WORDS.test(s) ? 'INCOME' : 'EXPENSE', merchant: null, description: null, date: null };

    const m = /(\d[\d,]*(?:\.\d+)?)\s*(k|thousand|lakh|lakhs|lac|crore)?\b/.exec(s);
    if (m) {
        let v = Number(m[1].replace(/,/g, ''));
        const unit = m[2];
        if (unit === 'k' || unit === 'thousand') v *= 1000;
        if (unit === 'lakh' || unit === 'lakhs' || unit === 'lac') v *= 100000;
        if (unit === 'crore') v *= 10000000;
        if (Number.isFinite(v) && v > 0) out.amount = String(Math.round(v * 100) / 100);
    }

    if (/\bday before yesterday\b/.test(s)) out.date = addDays(today, -2);
    else if (/\byesterday\b/.test(s)) out.date = addDays(today, -1);
    else if (/\btoday\b/.test(s)) out.date = today;
    else {
        const w = new RegExp(`\\b(?:on |last )?(${WEEKDAYS.join('|')})\\b`).exec(s);
        if (w) {
            const now = new Date(`${today}T00:00:00`).getDay();
            const back = (now - WEEKDAYS.indexOf(w[1]) + 7) % 7 || 7;
            out.date = addDays(today, -back);
        }
    }

    const clean = (v) => v?.replace(/\b(rs|rupees?|inr)\b/g, '').replace(/\s+/g, ' ').trim() || null;
    const cap = (v) => (v ? v.replace(/\b\w/g, (c) => c.toUpperCase()) : null);
    const at = new RegExp(`\\b(?:at|on|to)\\s+([a-z0-9&' .-]+?)${STOP}`).exec(s.replace(/\bon (monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b/g, ''));
    const from = new RegExp(`\\bfrom\\s+([a-z0-9&' .-]+?)${STOP}`).exec(s);
    const party = out.type === 'INCOME' ? (from ?? at) : (at ?? from);
    if (party && !/^\d/.test(party[1])) out.merchant = cap(clean(party[1]))?.slice(0, 100) ?? null;
    const forWhat = new RegExp(`\\bfor\\s+([a-z0-9&' .-]+?)${STOP}`).exec(s);
    if (forWhat && !/^\d/.test(forWhat[1])) out.description = clean(forWhat[1])?.replace(/^./, (c) => c.toUpperCase()).slice(0, 200) ?? null;
    return out;
}
