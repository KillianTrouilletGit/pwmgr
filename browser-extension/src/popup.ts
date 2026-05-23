/**
 * Popup UI — opens when the user clicks the toolbar icon. Shows connection status and the
 * list of credentials matched against the active tab's host. Clicking an entry copies the
 * password (with auto-clear in 20 s) and shows the username.
 *
 * This isn't the primary autofill path — that's the content-script icon next to password
 * fields. The popup is a fallback for sites where field detection fails, and a quick way
 * to inspect what the extension thinks it has for the current site.
 */

import { type BgRequest, type IpcCandidate, type IpcResponse } from "./types";

const statusEl = document.getElementById("status") as HTMLSpanElement;
const entriesEl = document.getElementById("entries") as HTMLDivElement;
const footerEl = document.getElementById("footer") as HTMLDivElement;

const CLIPBOARD_CLEAR_MS = 20_000;

async function init() {
  const tab = await getActiveTab();
  const host = tab?.url ? new URL(tab.url).hostname : "";

  const statusRes = (await chrome.runtime.sendMessage({ target: "background", op: "status" } satisfies BgRequest)) as IpcResponse;
  if (statusRes.op === "error") {
    setStatus("err", "Desktop offline");
    renderError(errorLabel(statusRes.code, statusRes.message));
    return;
  }
  if (statusRes.op !== "status_ok") {
    setStatus("err", "Bad response");
    renderError("Unexpected status response");
    return;
  }
  if (!statusRes.unlocked) {
    setStatus("locked", "Locked");
    renderError("Unlock PwMgr on the desktop to see credentials.");
    return;
  }
  setStatus("ok", "Unlocked");

  if (!host) {
    renderError("Open a website to see saved credentials.");
    return;
  }

  const matchRes = (await chrome.runtime.sendMessage({ target: "background", op: "match", params: { host } } satisfies BgRequest)) as IpcResponse;
  if (matchRes.op !== "match_ok") {
    renderError("Failed to load credentials.");
    return;
  }
  if (matchRes.candidates.length === 0) {
    renderError(`No saved credentials for ${host}.`);
    return;
  }
  renderEntries(matchRes.candidates);
}

function setStatus(kind: "ok" | "locked" | "err", label: string) {
  statusEl.className = `status status-${kind}`;
  statusEl.textContent = label;
}

function renderError(message: string) {
  entriesEl.innerHTML = "";
  const div = document.createElement("div");
  div.className = "empty";
  div.textContent = message;
  entriesEl.appendChild(div);
}

function renderEntries(candidates: IpcCandidate[]) {
  entriesEl.innerHTML = "";
  for (const c of candidates) {
    const btn = document.createElement("button");
    btn.className = "entry";
    btn.innerHTML =
      `<div class="entry-title">${escapeHtml(c.title)}</div>` +
      (c.username ? `<div class="entry-username">${escapeHtml(c.username)}</div>` : "");
    btn.addEventListener("click", () => copyPassword(c));
    entriesEl.appendChild(btn);
  }
}

async function copyPassword(c: IpcCandidate) {
  const req: BgRequest = { target: "background", op: "reveal", params: { entryId: c.id } };
  const res = (await chrome.runtime.sendMessage(req)) as IpcResponse;
  if (res.op !== "reveal_ok") {
    footerEl.textContent = "Reveal failed.";
    return;
  }
  await navigator.clipboard.writeText(res.password);
  footerEl.textContent = `Copied password for ${c.title}. Clipboard clears in ${CLIPBOARD_CLEAR_MS / 1000}s.`;
  scheduleClipboardClear(res.password);
}

function scheduleClipboardClear(expected: string) {
  setTimeout(async () => {
    try {
      const current = await navigator.clipboard.readText();
      if (current === expected) {
        await navigator.clipboard.writeText("");
      }
    } catch {
      // Reading clipboard requires the document to be focused; the popup may have closed.
      // In that case the password stays — best-effort hygiene only.
    }
  }, CLIPBOARD_CLEAR_MS);
}

function errorLabel(code: string, message: string): string {
  switch (code) {
    case "locked":
      return "Vault is locked — unlock PwMgr on the desktop.";
    case "transport":
      return "PwMgr desktop is not running. Launch it and reopen this popup.";
    case "bad_auth":
      return "Handshake failed. Restart the desktop app.";
    default:
      return message || code;
  }
}

async function getActiveTab(): Promise<chrome.tabs.Tab | undefined> {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  return tab;
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    c === "&" ? "&amp;" :
    c === "<" ? "&lt;" :
    c === ">" ? "&gt;" :
    c === '"' ? "&quot;" : "&#39;",
  );
}

init();
