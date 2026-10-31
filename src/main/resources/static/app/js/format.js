// Formatting helpers. Currency formatting follows the Indian numbering system (1,00,000).

const moneyFormats = new Map();
const compactFormats = new Map();

export function money(amount, currency = 'INR') {
    if (amount === null || amount === undefined) return '–';
    let f = moneyFormats.get(currency);
    if (!f) {
        f = new Intl.NumberFormat('en-IN', { style: 'currency', currency });
        moneyFormats.set(currency, f);
    }
    return f.format(Number(amount));
}

/** Short form for chart axes: ₹1.2L, ₹45K. */
export function moneyCompact(amount, currency = 'INR') {
    let f = compactFormats.get(currency);
    if (!f) {
        f = new Intl.NumberFormat('en-IN', { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 });
        compactFormats.set(currency, f);
    }
    return f.format(Number(amount));
}

const asDate = (iso) => new Date(`${iso}T00:00:00`);

export const shortDate = (iso) => asDate(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
export const longDate = (iso) => asDate(iso).toLocaleDateString('en-IN', { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' });
export const dayHeading = (iso) => asDate(iso).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
export const monthLabel = (yearMonth) => asDate(`${yearMonth}-01`).toLocaleDateString('en-IN', { month: 'short' });

export function isoDate(date) {
    const pad = (n) => String(n).padStart(2, '0');
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

export function addDays(iso, days) {
    const d = asDate(iso);
    d.setDate(d.getDate() + days);
    return isoDate(d);
}

export function plural(n, one, many = `${one}s`) {
    return `${n} ${n === 1 ? one : many}`;
}

export function initials(text = '') {
    return text.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0].toUpperCase()).join('') || '•';
}

export function greeting(hour = new Date().getHours()) {
    if (hour < 12) return 'Good morning';
    if (hour < 17) return 'Good afternoon';
    return 'Good evening';
}

export const titleCase = (s) => s.charAt(0) + s.slice(1).toLowerCase().replaceAll('_', ' ');
