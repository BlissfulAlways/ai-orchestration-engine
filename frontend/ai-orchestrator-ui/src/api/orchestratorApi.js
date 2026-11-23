// All calls to the backend go through this file.

const BASE_URL = import.meta.env.VITE_API_URL ?? '';
const API = `${BASE_URL}/api/v1`;

// A job in one of these states will not change again, so the UI stops polling.
export const TERMINAL_STATUSES = ['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'];

// The API key lives in sessionStorage: it survives a page reload but is erased when the tab
// closes. It is never written into the source code or the repository.
const KEY_STORAGE = 'orchestrator.apiKey';
let keyInMemory = ''; // fallback for browsers that block storage

export function getApiKey() {
  try {
    return sessionStorage.getItem(KEY_STORAGE) || keyInMemory;
  } catch {
    return keyInMemory;
  }
}

export function setApiKey(key) {
  keyInMemory = key;
  try {
    sessionStorage.setItem(KEY_STORAGE, key);
  } catch {
    /* storage is blocked: the in-memory copy is enough for this tab */
  }
}

/** Turns an HTTP error into a sentence a user can act on. */
function messageFor(response) {
  switch (response.status) {
    case 400:
      return 'The goal was rejected. Is it empty or longer than 4000 characters?';
    case 401:
      return 'API key missing or invalid.';
    case 404:
      return 'Job not found, or it belongs to another user.';
    case 429: {
      const wait = response.headers.get('Retry-After');
      return wait
        ? `Too many active jobs. Try again in ${wait} seconds.`
        : 'Too many active jobs. Try again shortly.';
    }
    default:
      return `Request failed (HTTP ${response.status}).`;
  }
}

/** One place that adds the key header and handles every error the same way. */
async function request(path, options = {}) {
  const key = getApiKey();
  if (!key) throw new Error('Enter your API key first.');

  let response;
  try {
    response = await fetch(`${API}${path}`, {
      ...options,
      headers: {
        'X-API-Key': key,
        ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      },
    });
  } catch {
    throw new Error('Cannot reach the backend. Is it running?');
  }
  if (!response.ok) throw new Error(messageFor(response));
  return response.json();
}

// The server takes the user from the key, so only the goal is sent.
export function submitJob(goal) {
  return request('/jobs', { method: 'POST', body: JSON.stringify({ goal }) });
}

export function getJob(jobId) {
  return request(`/jobs/${jobId}`);
}

export function getJobTrace(jobId) {
  return request(`/jobs/${jobId}/trace`);
}

export function getJobProgress(jobId) {
  return request(`/jobs/${jobId}/progress`);
}