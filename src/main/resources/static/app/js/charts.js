// Dependency-free SVG charts with hover tooltips. Colours come from CSS variables so themes apply.

import { el, svg } from './dom.js';
import { money, moneyCompact, shortDate, monthLabel } from './format.js';

const PALETTE = ['--chart-1', '--chart-2', '--chart-3', '--chart-4', '--chart-5', '--chart-6', '--chart-7'];
export const colorVar = (i) => `var(${PALETTE[i % PALETTE.length]})`;

let tip;

function showTip(event, text) {
    if (!tip) {
        tip = el('div', { class: 'chart-tip', role: 'tooltip' });
        document.body.append(tip);
    }
    tip.textContent = text;
    tip.hidden = false;
    tip.style.left = `${event.clientX}px`;
    tip.style.top = `${event.clientY}px`;
}

function hideTip() {
    if (tip) tip.hidden = true;
}

function withTip(node, text) {
    node.addEventListener('mousemove', (e) => showTip(e, text));
    node.addEventListener('mouseleave', hideTip);
    node.append(svg('title', { text }));
    return node;
}

/** Draw charts at about their on-screen width, so axis text keeps the same size on any screen. */
function chartWidth(desktop) {
    const w = window.innerWidth;
    if (w <= 860) return Math.max(300, w - 64);              // single column, no sidebar
    if (w <= 1100) return Math.max(300, w - 236 - 56 - 38);  // full-width cards next to the sidebar
    return desktop;
}

/** Rounds up to 1, 2, 2.5 or 5 times a power of ten, so axis labels look tidy. */
function niceMax(value) {
    if (value <= 0) return 1;
    const power = 10 ** Math.floor(Math.log10(value));
    const step = [1, 2, 2.5, 5, 10].find((m) => m * power >= value);
    return step * power;
}

function yAxis(width, height, pad, max, currency) {
    return [0, 0.5, 1].map((f) => {
        const y = pad.top + (height - pad.top - pad.bottom) * (1 - f);
        return svg('g', {},
            svg('line', { class: 'grid-line', x1: pad.left, x2: width - pad.right, y1: y, y2: y }),
            svg('text', { class: 'axis-label', x: pad.left - 8, y: y + 4, 'text-anchor': 'end', text: moneyCompact(max * f, currency) }));
    });
}

/** Daily spending bars. */
export function dailyBars(days, currency) {
    const W = chartWidth(720), H = 210, pad = { top: 10, right: 6, bottom: 26, left: 52 };
    const max = niceMax(Math.max(0, ...days.map((d) => Number(d.amount))));
    const plotW = W - pad.left - pad.right;
    const plotH = H - pad.top - pad.bottom;
    const slot = plotW / Math.max(days.length, 1);
    const barW = Math.max(2, Math.min(18, slot * 0.66));

    const bars = days.map((d, i) => {
        const v = Number(d.amount);
        const h = v > 0 ? Math.max(3, (v / max) * plotH) : 2;
        const x = pad.left + i * slot + (slot - barW) / 2;
        return withTip(svg('rect', { class: `bar ${v === 0 ? 'zero' : ''}`, x, y: pad.top + plotH - h, width: barW, height: h, rx: Math.min(4, barW / 2) }),
            `${shortDate(d.date)} · ${money(v, currency)}`);
    });
    const labelIdx = days.length <= 1 ? [0] : [0, Math.floor((days.length - 1) / 2), days.length - 1];
    const labels = [...new Set(labelIdx)].filter((i) => days[i]).map((i) => svg('text', {
        class: 'axis-label', x: pad.left + i * slot + slot / 2, y: H - 6, 'text-anchor': 'middle', text: shortDate(days[i].date),
    }));
    return svg('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Daily spending chart' },
        yAxis(W, H, pad, max, currency), bars, labels);
}

/** Income vs expense per month, grouped bars. */
export function trendBars(months, currency) {
    const W = chartWidth(560), H = 220, pad = { top: 10, right: 6, bottom: 26, left: 52 };
    const max = niceMax(Math.max(0, ...months.flatMap((m) => [Number(m.income), Number(m.expense)])));
    const plotW = W - pad.left - pad.right;
    const plotH = H - pad.top - pad.bottom;
    const slot = plotW / Math.max(months.length, 1);
    const barW = Math.min(22, slot * 0.3);

    const groups = months.map((m, i) => {
        const cx = pad.left + i * slot + slot / 2;
        const bar = (value, cls, dx, label) => {
            const h = Number(value) > 0 ? Math.max(3, (Number(value) / max) * plotH) : 2;
            return withTip(svg('rect', { class: cls, x: cx + dx, y: pad.top + plotH - h, width: barW, height: h, rx: 4 }),
                `${monthLabel(m.month)} ${label} · ${money(value, currency)}`);
        };
        return svg('g', {},
            bar(m.income, 'bar-in', -barW - 2, 'income'),
            bar(m.expense, 'bar-out', 2, 'expense'),
            svg('text', { class: 'axis-label', x: cx, y: H - 6, 'text-anchor': 'middle', text: monthLabel(m.month) }));
    });
    return svg('svg', { class: 'chart', viewBox: `0 0 ${W} ${H}`, role: 'img', 'aria-label': 'Income versus expense by month' },
        yAxis(W, H, pad, max, currency), groups);
}

/** Donut of category shares; the largest six get their own slice, the rest are grouped. */
export function donut(items, currency) {
    const top = items.slice(0, 6);
    const rest = items.slice(6);
    const slices = rest.length
        ? [...top, { name: 'Other', total: rest.reduce((s, r) => s + Number(r.total), 0) }]
        : top;
    const total = slices.reduce((s, r) => s + Number(r.total), 0);
    const R = 62, C = 2 * Math.PI * R;
    let offset = 0;

    const arcs = slices.map((s, i) => {
        const len = total > 0 ? (Number(s.total) / total) * C : 0;
        const circle = svg('circle', {
            cx: 80, cy: 80, r: R, fill: 'none', 'stroke-width': 22,
            'stroke-dasharray': `${Math.max(0, len - 2)} ${C}`, 'stroke-dashoffset': -offset,
            transform: 'rotate(-90 80 80)', style: { stroke: colorVar(i) },
        });
        offset += len;
        return withTip(circle, `${s.name} · ${money(s.total, currency)}`);
    });
    const chart = svg('svg', { class: 'chart', viewBox: '0 0 160 160', width: 160, height: 160, role: 'img', 'aria-label': 'Spending by category' },
        svg('circle', { cx: 80, cy: 80, r: R, fill: 'none', 'stroke-width': 22, style: { stroke: 'var(--bg-sunken)' } }),
        arcs,
        svg('text', { class: 'donut-center', x: 80, y: 74, 'text-anchor': 'middle', text: 'Total spent' }),
        svg('text', { class: 'donut-total num', x: 80, y: 96, 'text-anchor': 'middle', text: moneyCompact(total, currency) }));
    return { chart, slices };
}
