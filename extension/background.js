/**
 * Reads the signed-in user's submissions from leetcode.com and posts them to their LeetCode Coach
 * server.
 *
 * The point of doing this in the browser rather than on the server: the session cookie stays in the
 * browser that already holds it, and the request to LeetCode comes from the user's own IP. A server
 * syncing for many people would carry many people's credentials and egress from one address, which
 * is both a custody problem and a fast route to being blocked.
 *
 * This file never reads the session cookie itself. `credentials: "include"` lets the browser attach
 * it, which works even though it is httpOnly; only the CSRF token, which is not httpOnly, is read.
 */

const LEETCODE_GRAPHQL = "https://leetcode.com/graphql";
const PAGE_SIZE = 20;

/** Pages gathered before posting a batch, so a long backfill reports progress as it goes. */
const PAGES_PER_BATCH = 5;

/** A routine sync only looks at recent history; the server rejects anything it already has. */
const INCREMENTAL_MAX_PAGES = 5;

/** A backfill walks the whole history. LeetCode stops it sooner by reporting no next page. */
const FULL_MAX_PAGES = 200;

const SUBMISSION_QUERY = `query submissionList($offset: Int!, $limit: Int!, $lastKey: String) {
  submissionList(offset: $offset, limit: $limit, lastKey: $lastKey) {
    lastKey
    hasNext
    submissions {
      id
      title
      titleSlug
      statusDisplay
      lang
      runtime
      memory
      timestamp
      isPending
      url
    }
  }
}`;

const DEFAULT_SETTINGS = {
  serverUrl: "http://127.0.0.1:8080",
  apiKey: "",
  autoSync: true,
  intervalMinutes: 15,
};

export async function getSettings() {
  const stored = await chrome.storage.local.get(DEFAULT_SETTINGS);
  return { ...DEFAULT_SETTINGS, ...stored };
}

async function getLastResult() {
  const { lastResult } = await chrome.storage.local.get({ lastResult: null });
  return lastResult;
}

async function setLastResult(result) {
  await chrome.storage.local.set({ lastResult: { ...result, at: new Date().toISOString() } });
}

/**
 * LeetCode requires the CSRF token as a header as well as a cookie. It is not httpOnly, so the
 * cookies permission can read it; its absence means no signed-in leetcode.com session here.
 */
async function getCsrfToken() {
  const cookie = await chrome.cookies.get({ url: "https://leetcode.com", name: "csrftoken" });
  return cookie ? cookie.value : null;
}

async function fetchPage(csrfToken, offset, lastKey) {
  const response = await fetch(LEETCODE_GRAPHQL, {
    method: "POST",
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      "x-csrftoken": csrfToken,
      Referer: "https://leetcode.com/",
    },
    body: JSON.stringify({
      query: SUBMISSION_QUERY,
      variables: { offset, limit: PAGE_SIZE, lastKey },
    }),
  });

  if (!response.ok) {
    throw new Error(`LeetCode returned HTTP ${response.status}`);
  }

  const payload = await response.json();
  if (payload.errors && payload.errors.length) {
    throw new Error(`LeetCode rejected the query: ${payload.errors[0].message}`);
  }

  const list = payload.data && payload.data.submissionList;
  if (!list || list.submissions === null) {
    // The shape LeetCode returns to a caller it does not recognise as signed in.
    throw new Error("LeetCode did not return your submissions. Sign in at leetcode.com and retry.");
  }
  return list;
}

async function pushBatch(settings, submissions) {
  const headers = { "Content-Type": "application/json" };
  if (settings.apiKey) {
    headers["X-API-Key"] = settings.apiKey;
  }

  const response = await fetch(`${settings.serverUrl.replace(/\/+$/, "")}/api/sync/submissions`, {
    method: "POST",
    headers,
    body: JSON.stringify({ submissions }),
  });

  if (!response.ok) {
    const detail = await response.text().catch(() => "");
    throw new Error(`Coach server returned HTTP ${response.status}. ${detail.slice(0, 160)}`);
  }
  return response.json();
}

/**
 * Pages through the history, posting as it goes.
 *
 * A routine sync stops as soon as a batch turns up nothing the server did not already hold, since
 * LeetCode lists newest first and everything older was imported by an earlier run. Deduplication is
 * the server's job, so re-sending a page is harmless and the extension needs no memory of its own.
 */
export async function runSync({ full = false } = {}) {
  const settings = await getSettings();
  const csrfToken = await getCsrfToken();
  if (!csrfToken) {
    throw new Error("No leetcode.com session in this browser. Sign in at leetcode.com first.");
  }

  const maxPages = full ? FULL_MAX_PAGES : INCREMENTAL_MAX_PAGES;
  const totals = { seen: 0, imported: 0, problems: 0, pages: 0 };
  let batch = [];
  let lastKey = null;
  let offset = 0;
  let reachedEnd = false;

  for (let page = 0; page < maxPages; page++) {
    const result = await fetchPage(csrfToken, offset, lastKey);
    totals.pages++;
    totals.seen += result.submissions.length;
    batch = batch.concat(result.submissions);

    lastKey = result.lastKey;
    offset += result.submissions.length;
    reachedEnd = !result.hasNext || result.submissions.length === 0;

    const batchFull = (page + 1) % PAGES_PER_BATCH === 0;
    if (batch.length && (batchFull || reachedEnd || page === maxPages - 1)) {
      const report = await pushBatch(settings, batch);
      const run = report.run || {};
      totals.imported += run.submissionsImported || 0;
      totals.problems += (report.updatedProblems || []).length;
      batch = [];

      // Nothing new in a whole batch means the rest is older still, and already stored.
      if (!full && (run.submissionsImported || 0) === 0) {
        break;
      }
    }

    if (reachedEnd) {
      break;
    }
  }

  const result = { ok: true, full, ...totals, reachedEnd };
  await setLastResult(result);
  return result;
}

async function runSyncGuarded(options) {
  try {
    return await runSync(options);
  } catch (error) {
    const result = { ok: false, error: error.message };
    await setLastResult(result);
    return result;
  }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message.type === "sync") {
    runSyncGuarded({ full: Boolean(message.full) }).then(sendResponse);
    return true; // keeps the channel open for the async reply
  }
  if (message.type === "status") {
    Promise.all([getSettings(), getLastResult()])
      .then(([settings, lastResult]) => sendResponse({ settings, lastResult }));
    return true;
  }
  return false;
});

const ALARM_NAME = "leetcode-coach-sync";

async function rescheduleAlarm() {
  const settings = await getSettings();
  await chrome.alarms.clear(ALARM_NAME);
  if (settings.autoSync) {
    chrome.alarms.create(ALARM_NAME, { periodInMinutes: Math.max(1, settings.intervalMinutes) });
  }
}

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === ALARM_NAME) {
    runSyncGuarded({ full: false });
  }
});

chrome.runtime.onInstalled.addListener(rescheduleAlarm);
chrome.runtime.onStartup.addListener(rescheduleAlarm);
chrome.storage.onChanged.addListener((changes, area) => {
  if (area === "local" && (changes.autoSync || changes.intervalMinutes)) {
    rescheduleAlarm();
  }
});
