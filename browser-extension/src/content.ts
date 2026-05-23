/**
 * Content script — runs in every page (matches: ["<all_urls>"]) at document_idle.
 *
 * Job: find password fields, attach a small inline "PwMgr" icon next to each one, and on
 * click query the background worker for credentials matching the page's host. Show a
 * dropdown of candidates; on selection, fill the form fields.
 *
 * Field detection is intentionally simple. The Android side has a richer parser; for the
 * browser we just rely on `input[type=password]` (covers ~95% of real login forms) and
 * heuristically pick the nearest preceding text/email input as the username field.
 */

import type { BgRequest, IpcCandidate, IpcResponse } from "./types";

const HOST = location.hostname;
const PROCESSED = new WeakSet<HTMLInputElement>();
const ICON_LABEL = "PwMgr";

function isVisible(el: HTMLElement): boolean {
  if (!el.isConnected) return false;
  const rect = el.getBoundingClientRect();
  if (rect.width === 0 || rect.height === 0) return false;
  const style = getComputedStyle(el);
  return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0";
}

function findUsernameFieldFor(password: HTMLInputElement): HTMLInputElement | null {
  // Walk back through the form's controls; pick the nearest text/email input above the
  // password. If there's no form, walk the document in tree order.
  const form = password.form;
  const candidates: HTMLInputElement[] = form
    ? Array.from(form.elements).filter((e): e is HTMLInputElement => e instanceof HTMLInputElement)
    : Array.from(document.querySelectorAll<HTMLInputElement>("input"));
  const idx = candidates.indexOf(password);
  for (let i = idx - 1; i >= 0; i--) {
    const c = candidates[i];
    if (!isVisible(c)) continue;
    const t = (c.type || "text").toLowerCase();
    if (t === "text" || t === "email" || t === "tel" || c.autocomplete?.includes("username")) {
      return c;
    }
  }
  return null;
}

function createIcon(onClick: (anchor: HTMLElement) => void): HTMLElement {
  const btn = document.createElement("button");
  btn.type = "button";
  btn.textContent = "🔐";
  btn.title = ICON_LABEL;
  btn.setAttribute("aria-label", "PwMgr autofill");
  Object.assign(btn.style, {
    position: "absolute",
    zIndex: "2147483646",
    cursor: "pointer",
    background: "#4f46e5",
    color: "#fff",
    border: "1px solid #312e81",
    borderRadius: "4px",
    padding: "0 6px",
    height: "20px",
    lineHeight: "18px",
    fontSize: "12px",
    fontFamily: "system-ui, sans-serif",
  } as CSSStyleDeclaration);
  btn.addEventListener("click", (e) => {
    e.preventDefault();
    e.stopPropagation();
    onClick(btn);
  });
  return btn;
}

function positionIconNear(password: HTMLInputElement, icon: HTMLElement) {
  const r = password.getBoundingClientRect();
  icon.style.top = `${window.scrollY + r.top + (r.height - 20) / 2}px`;
  icon.style.left = `${window.scrollX + r.right - 24}px`;
}

function attachToPasswordField(password: HTMLInputElement) {
  if (PROCESSED.has(password)) return;
  PROCESSED.add(password);

  const icon = createIcon((anchor) => onIconClick(password, anchor));
  document.body.appendChild(icon);
  positionIconNear(password, icon);

  const reflow = () => positionIconNear(password, icon);
  window.addEventListener("scroll", reflow, { passive: true });
  window.addEventListener("resize", reflow);
  // Best-effort: re-position when the input itself moves due to layout changes.
  const ro = new ResizeObserver(reflow);
  ro.observe(password);
}

async function onIconClick(password: HTMLInputElement, anchor: HTMLElement) {
  const req: BgRequest = { target: "background", op: "match", params: { host: HOST } };
  const res = (await chrome.runtime.sendMessage(req)) as IpcResponse;
  if (res.op === "error") {
    showError(anchor, errorLabel(res.code, res.message));
    return;
  }
  if (res.op !== "match_ok") {
    showError(anchor, "Unexpected response");
    return;
  }
  if (res.candidates.length === 0) {
    showError(anchor, "No saved credentials for this site");
    return;
  }
  showDropdown(anchor, res.candidates, async (selected) => {
    const revealReq: BgRequest = { target: "background", op: "reveal", params: { entryId: selected.id } };
    const reveal = (await chrome.runtime.sendMessage(revealReq)) as IpcResponse;
    if (reveal.op !== "reveal_ok") {
      showError(anchor, "Reveal failed");
      return;
    }
    fillCredentials(password, reveal.username, reveal.password);
  });
}

function errorLabel(code: string, message: string): string {
  switch (code) {
    case "locked":
      return "Vault is locked — unlock PwMgr desktop";
    case "transport":
      return "PwMgr desktop is not running";
    case "bad_auth":
      return "Handshake failed — restart PwMgr";
    default:
      return message || code;
  }
}

function showDropdown(anchor: HTMLElement, candidates: IpcCandidate[], onSelect: (c: IpcCandidate) => void) {
  destroyPopover();
  const popover = document.createElement("div");
  Object.assign(popover.style, {
    position: "absolute",
    zIndex: "2147483647",
    background: "#fff",
    border: "1px solid #d1d5db",
    borderRadius: "6px",
    boxShadow: "0 8px 24px rgba(0,0,0,0.2)",
    fontFamily: "system-ui, sans-serif",
    minWidth: "240px",
    maxWidth: "400px",
  } as CSSStyleDeclaration);
  for (const c of candidates) {
    const row = document.createElement("button");
    row.type = "button";
    Object.assign(row.style, {
      display: "block",
      width: "100%",
      textAlign: "left",
      background: "transparent",
      border: "0",
      borderBottom: "1px solid #f3f4f6",
      padding: "8px 12px",
      cursor: "pointer",
      fontSize: "13px",
      color: "#111827",
    } as CSSStyleDeclaration);
    row.innerHTML = `<div style="font-weight:500">${escapeHtml(c.title)}</div>` +
      (c.username ? `<div style="opacity:.7;font-size:12px">${escapeHtml(c.username)}</div>` : "");
    row.addEventListener("click", (e) => {
      e.preventDefault();
      destroyPopover();
      onSelect(c);
    });
    popover.appendChild(row);
  }
  document.body.appendChild(popover);
  const r = anchor.getBoundingClientRect();
  popover.style.top = `${window.scrollY + r.bottom + 4}px`;
  popover.style.left = `${window.scrollX + r.left - 160}px`;
  setTimeout(() => document.addEventListener("click", destroyPopover, { once: true }), 0);
  POPOVER = popover;
}

function showError(anchor: HTMLElement, message: string) {
  destroyPopover();
  const popover = document.createElement("div");
  Object.assign(popover.style, {
    position: "absolute",
    zIndex: "2147483647",
    background: "#fef2f2",
    color: "#991b1b",
    border: "1px solid #fecaca",
    borderRadius: "6px",
    padding: "8px 10px",
    fontFamily: "system-ui, sans-serif",
    fontSize: "12px",
    maxWidth: "320px",
  } as CSSStyleDeclaration);
  popover.textContent = message;
  document.body.appendChild(popover);
  const r = anchor.getBoundingClientRect();
  popover.style.top = `${window.scrollY + r.bottom + 4}px`;
  popover.style.left = `${window.scrollX + r.left - 160}px`;
  setTimeout(() => document.addEventListener("click", destroyPopover, { once: true }), 0);
  POPOVER = popover;
}

let POPOVER: HTMLElement | null = null;
function destroyPopover() {
  if (POPOVER && POPOVER.isConnected) POPOVER.remove();
  POPOVER = null;
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) =>
    c === "&" ? "&amp;" :
    c === "<" ? "&lt;" :
    c === ">" ? "&gt;" :
    c === '"' ? "&quot;" : "&#39;",
  );
}

function fillCredentials(passwordField: HTMLInputElement, username: string | null, password: string) {
  const userField = findUsernameFieldFor(passwordField);
  if (userField && username) setNativeValue(userField, username);
  setNativeValue(passwordField, password);
}

/**
 * Setting `.value` directly on a React-controlled input fails — React's synthetic event
 * system caches the previous value via a property setter. We invoke the *native* setter
 * and then dispatch an `input` event so React re-reads the field.
 */
function setNativeValue(el: HTMLInputElement, value: string) {
  const proto = Object.getPrototypeOf(el);
  const desc = Object.getOwnPropertyDescriptor(proto, "value");
  desc?.set?.call(el, value);
  el.dispatchEvent(new Event("input", { bubbles: true }));
  el.dispatchEvent(new Event("change", { bubbles: true }));
}

function scan() {
  const pwFields = document.querySelectorAll<HTMLInputElement>("input[type=password]");
  pwFields.forEach((f) => {
    if (isVisible(f)) attachToPasswordField(f);
  });
}

scan();
// SPA pages add login forms dynamically; rescan on DOM mutations.
const observer = new MutationObserver(() => scan());
observer.observe(document.documentElement, { childList: true, subtree: true });
