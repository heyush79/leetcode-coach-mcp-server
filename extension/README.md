# LeetCode Coach Sync — browser extension

Reads your own LeetCode submissions **in your browser** and posts them to your LeetCode Coach
server.

## Why this exists

The server can read your submissions itself, but only if you hand it your `LEETCODE_SESSION`
cookie. That cookie is not read-only — it can submit code and change your account settings — so
storing it anywhere is a real decision, and a server syncing for several people would also send all
of their traffic from one IP, which looks like a bot farm to LeetCode.

This extension removes both problems. Your browser is already signed in to leetcode.com, so it makes
the request: the cookie never leaves it, and the traffic comes from your own IP. The server ends up
with exactly the same data and needs no credentials at all.

Everything after the data arrives is unchanged: same grading, same spaced-repetition schedule, same
MCP tools. Only the transport differs.

## Install

The extension talks to a server on your own machine, so it is distributed here rather than through
the Chrome Web Store.

1. Start the coach server (see the [main README](../README.md)).
2. Open `chrome://extensions` and turn on **Developer mode** (top right).
3. Click **Load unpacked** and choose this `extension/` folder.
4. Sign in at [leetcode.com](https://leetcode.com) in the same browser.
5. Click the extension icon, then **Sync now**.

Works the same in any Chromium browser: Chrome, Edge, Brave, Arc, Opera.

## Using it

| Control | What it does |
|---|---|
| **Sync now** | Reads your recent submissions and stops as soon as it finds nothing new |
| **Backfill all** | Walks your entire history. Run once; it can take a few minutes |
| **Coach server** | Where to post. `http://127.0.0.1:8080` unless you changed the port |
| **API key** | Only if you set `MCP_API_KEY` on the server |
| **Sync automatically** | Background sync on a timer, default every 15 minutes |

The popup reports whether the server is reachable before you sync, so a wrong URL is obvious rather
than mysterious.

## What it can and cannot see

It sends LeetCode's own submission records: problem, verdict, language, runtime, memory, timestamp.
That is everything LeetCode's submission list exposes.

It does **not** send your source code, and it cannot see how long you spent on a problem before
submitting, or whether you read the hints on leetcode.com — LeetCode does not expose those. Sittings
synced this way are therefore graded on attempts alone. Hints and time budgets only count for
sessions you run through the assistant, where the server observed them directly.

## Permissions, and why each is needed

| Permission | Reason |
|---|---|
| `https://leetcode.com/*` | Make the submission request as the signed-in you |
| `cookies` | Read the `csrftoken` cookie, which LeetCode requires as a request header. The session cookie itself is `httpOnly` and is never read — the browser attaches it |
| `http://127.0.0.1/*`, `http://localhost/*` | Post the results to your own server |
| `storage` | Remember your server URL and preferences |
| `alarms` | Run the periodic background sync |

There is no analytics, no remote logging, and no server other than the one you configure.

## Duplicates

None. LeetCode's own submission id is unique in the database, so pressing **Sync now** repeatedly, or
running the extension alongside the server's own sync, cannot double-count anything. The report
tells you how many were genuinely new.

## Publishing to the Chrome Web Store

Not required for it to work, and worth weighing: an extension whose whole job is talking to
`localhost` is an unusual submission, and review tends to ask about it. If you do want to:

```bash
cd extension && zip -r ../leetcode-coach-sync.zip . -x '.*'
```

You will also need a developer account (one-off fee), a privacy policy URL, and a justification for
each permission above. Loading unpacked from this repository stays the simpler route for anyone
already running the server.

## Troubleshooting

| Symptom | Cause |
|---|---|
| "No leetcode.com session in this browser" | Sign in at leetcode.com, then retry |
| "LeetCode did not return your submissions" | Signed out, or the session expired |
| "No coach server at …" | Server not running, or the wrong URL in settings |
| HTTP 401 from the server | `MCP_API_KEY` is set; paste the same key into the popup |
| HTTP 403 from the server | The server does not recognise the extension origin; check you loaded this folder unpacked |
