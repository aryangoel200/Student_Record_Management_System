import axios from "axios";

// Configurable so the same build can point at a deployed API.
const baseURL =
    process.env.REACT_APP_API_BASE_URL || "http://127.0.0.1:8000/api/";

export const ACCESS_TOKEN_KEY = "access_token";
export const REFRESH_TOKEN_KEY = "refresh_token";

// Fired when the session can no longer be refreshed, so the app can clear
// its Redux state without axios needing to know about the store.
export const SESSION_EXPIRED_EVENT = "auth:session-expired";

export const getAccessToken = () => localStorage.getItem(ACCESS_TOKEN_KEY);
export const getRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY);

export const storeTokens = ({ access, refresh }) => {
    if (access) localStorage.setItem(ACCESS_TOKEN_KEY, access);
    if (refresh) localStorage.setItem(REFRESH_TOKEN_KEY, refresh);
};

export const clearTokens = () => {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
};

const axiosInstance = axios.create({
    baseURL,
    headers: { "Content-Type": "application/json" },
});

// Read the token per request. The old version captured it once at import time,
// so a token obtained later in the session was never attached.
axiosInstance.interceptors.request.use((config) => {
    const token = getAccessToken();
    if (token) {
        config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
});

const isAuthEndpoint = (url = "") =>
    url.includes("Auth/login") ||
    url.includes("Auth/register") ||
    url.includes("Auth/token/refresh");

// Shared so concurrent 401s trigger one refresh, not one each.
let pendingRefresh = null;

const refreshAccessToken = async () => {
    const refresh = getRefreshToken();
    if (!refresh) throw new Error("No refresh token stored.");

    if (!pendingRefresh) {
        pendingRefresh = axios
            .post(`${baseURL}Auth/token/refresh`, { refresh })
            .finally(() => {
                pendingRefresh = null;
            });
    }
    const { data } = await pendingRefresh;
    storeTokens(data);
    return data.access;
};

axiosInstance.interceptors.response.use(
    (response) => response,
    async (error) => {
        const { config, response } = error;

        const canRetry =
            response?.status === 401 &&
            config &&
            !config._retried &&
            !isAuthEndpoint(config.url) &&
            getRefreshToken();

        if (canRetry) {
            config._retried = true;
            try {
                await refreshAccessToken();
                return axiosInstance(config);
            } catch (refreshError) {
                clearTokens();
                window.dispatchEvent(new Event(SESSION_EXPIRED_EVENT));
            }
        }
        return Promise.reject(error);
    }
);

/**
 * Pull a displayable message out of an axios error.
 *
 * The API always answers failures with a non-2xx status and a `detail` string,
 * so callers no longer have to compare `response.data.msg` against English
 * prose to work out whether a 200 actually meant success.
 */
export const apiErrorMessage = (
    error,
    fallback = "Something went wrong. Please try again."
) => {
    const data = error?.response?.data;
    if (!data) {
        return error?.message === "Network Error"
            ? "Could not reach the server. Is the backend running?"
            : fallback;
    }
    if (typeof data === "string") return data;
    if (data.detail) return data.detail;
    if (data.errors && typeof data.errors === "object") {
        const [field, messages] = Object.entries(data.errors)[0] ?? [];
        if (field) {
            return Array.isArray(messages) ? messages[0] : String(messages);
        }
    }
    return fallback;
};

export default axiosInstance;
