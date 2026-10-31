// API client. Tokens live in sessionStorage (cleared when the tab closes). Refreshes are serialised
// because the server treats a reused refresh token as theft and signs the session out.

const BASE = '/api/v1';

export const session = {
    get access() { return sessionStorage.getItem('pt.access'); },
    get refresh() { return sessionStorage.getItem('pt.refresh'); },
    save(auth) {
        sessionStorage.setItem('pt.access', auth.accessToken);
        sessionStorage.setItem('pt.refresh', auth.refreshToken);
    },
    clear() {
        sessionStorage.removeItem('pt.access');
        sessionStorage.removeItem('pt.refresh');
    },
};

export class ApiError extends Error {
    constructor(message, code, fields = [], status = 0) {
        super(message);
        this.code = code;
        this.fields = fields;
        this.status = status;
    }
}

let onSignedOut = () => {};
export function whenSignedOut(callback) { onSignedOut = callback; }

let refreshing = null;

function refreshTokens() {
    if (!refreshing) {
        refreshing = request('/auth/refresh', { method: 'POST', auth: false, body: { refreshToken: session.refresh } })
            .then((data) => { session.save(data); return true; })
            .catch(() => false)
            .finally(() => { refreshing = null; });
    }
    return refreshing;
}

export async function request(path, { method = 'GET', body, auth = true, headers = {}, retried = false } = {}) {
    const init = { method, headers: { Accept: 'application/json', ...headers } };
    if (body !== undefined) {
        init.headers['Content-Type'] = 'application/json';
        init.body = JSON.stringify(body);
    }
    if (auth && session.access) init.headers.Authorization = `Bearer ${session.access}`;

    let res;
    try {
        res = await fetch(BASE + path, init);
    } catch {
        throw new ApiError('Network error. Check your connection and try again.', 'NETWORK');
    }
    if (res.status === 401 && auth) {
        if (!retried && session.refresh && await refreshTokens()) {
            return request(path, { method, body, auth, headers, retried: true });
        }
        session.clear();
        onSignedOut();
        throw new ApiError('Your session has ended. Please sign in again.', 'UNAUTHENTICATED', [], 401);
    }
    if (res.status === 204) return null;
    const json = await res.json().catch(() => null);
    if (!res.ok || !json?.success) {
        const err = json?.error ?? {};
        throw new ApiError(json?.message ?? `Request failed (${res.status})`, err.code, err.fields ?? [], res.status);
    }
    return json.data;
}

export const api = {
    get: (path) => request(path),
    post: (path, body, headers) => request(path, { method: 'POST', body, headers }),
    put: (path, body) => request(path, { method: 'PUT', body }),
    patch: (path, body) => request(path, { method: 'PATCH', body }),
    del: (path) => request(path, { method: 'DELETE' }),
    public: (path, body) => request(path, { method: 'POST', body, auth: false }),
};

/** Readable message, including per-field details when the server sent them. */
export function errorText(e) {
    if (e instanceof ApiError && e.fields.length) {
        return e.fields.map((f) => `${f.field}: ${f.message}`).join(' · ');
    }
    return e?.message ?? 'Something went wrong';
}
