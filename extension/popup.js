const elements = {
  indicator: document.getElementById("indicator"),
  status: document.getElementById("status"),
  sync: document.getElementById("sync"),
  backfill: document.getElementById("backfill"),
  serverUrl: document.getElementById("serverUrl"),
  apiKey: document.getElementById("apiKey"),
  autoSync: document.getElementById("autoSync"),
  intervalMinutes: document.getElementById("intervalMinutes"),
};

function send(message) {
  return chrome.runtime.sendMessage(message);
}

function setStatus(text, kind = "") {
  elements.status.textContent = text;
  elements.status.className = `status ${kind}`.trim();
  elements.indicator.className = `indicator ${kind}`.trim();
}

function describe(result) {
  if (!result) {
    return "Not synced yet. Sign in at leetcode.com, then press Sync now.";
  }
  if (!result.ok) {
    return result.error;
  }
  const when = new Date(result.at).toLocaleString();
  if (result.imported === 0) {
    return `Up to date — nothing new in ${result.seen} submission(s) checked. Last run ${when}.`;
  }
  return `Imported ${result.imported} submission(s) across ${result.problems} problem(s) `
    + `from ${result.seen} checked. Last run ${when}.`;
}

/** Reports whether the coach server is reachable, so a wrong URL is obvious before syncing. */
async function checkServer(serverUrl) {
  try {
    const response = await fetch(`${serverUrl.replace(/\/+$/, "")}/api/status`);
    if (!response.ok) {
      return null;
    }
    return await response.json();
  } catch {
    return null;
  }
}

async function refresh() {
  const { settings, lastResult } = await send({ type: "status" });

  elements.serverUrl.value = settings.serverUrl;
  elements.apiKey.value = settings.apiKey;
  elements.autoSync.checked = settings.autoSync;
  elements.intervalMinutes.value = settings.intervalMinutes;

  const server = await checkServer(settings.serverUrl);
  if (!server) {
    setStatus(`No coach server at ${settings.serverUrl}. Start it, or change the URL below.`, "bad");
    return;
  }
  setStatus(describe(lastResult), lastResult && !lastResult.ok ? "bad" : "ok");
}

async function sync(full) {
  elements.sync.disabled = true;
  elements.backfill.disabled = true;
  setStatus(full ? "Backfilling your whole history — this can take a while…" : "Syncing…");

  const result = await send({ type: "sync", full });
  setStatus(describe({ ...result, at: new Date().toISOString() }), result.ok ? "ok" : "bad");

  elements.sync.disabled = false;
  elements.backfill.disabled = false;
}

function saveSettings() {
  chrome.storage.local.set({
    serverUrl: elements.serverUrl.value.trim() || "http://127.0.0.1:8080",
    apiKey: elements.apiKey.value.trim(),
    autoSync: elements.autoSync.checked,
    intervalMinutes: Number(elements.intervalMinutes.value) || 15,
  });
}

elements.sync.addEventListener("click", () => sync(false));
elements.backfill.addEventListener("click", () => sync(true));
for (const field of [elements.serverUrl, elements.apiKey, elements.autoSync, elements.intervalMinutes]) {
  field.addEventListener("change", saveSettings);
}

refresh();
