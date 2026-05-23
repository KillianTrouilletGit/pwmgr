/**
 * Background service worker — the single point of contact with the native messaging host.
 *
 * Architecture:
 *   - Content scripts and the popup never talk to the host directly. They send messages
 *     (typed [BgRequest]) to this worker via `chrome.runtime.sendMessage`, and the worker
 *     forwards them as [IpcRequest]s over a persistent native-messaging Port.
 *   - One Port, lazily opened on first request, reused for the whole browser session.
 *     We pay the JVM startup cost (~500 ms) once instead of per autofill.
 *   - In-flight requests are correlated by `id`. Disconnect drops them all with a
 *     "transport" error and clears the Port; the next request reconnects.
 *
 * The worker is event-driven — no top-level await, no module-level state that survives
 * worker termination beyond what Chrome rehydrates from `chrome.storage`. The Port is
 * recreated on demand whenever Chrome wakes the worker back up.
 */

import type { IpcResponse, BgRequest } from "./types";

const NATIVE_HOST_NAME = "com.pwmgr.host";

let port: chrome.runtime.Port | null = null;
let nextId = 1;
const inflight = new Map<number, (response: IpcResponse) => void>();

function ensurePort(): chrome.runtime.Port {
  if (port) return port;
  const p = chrome.runtime.connectNative(NATIVE_HOST_NAME);
  p.onMessage.addListener((raw) => {
    const msg = raw as IpcResponse;
    const resolver = inflight.get(msg.id);
    if (resolver) {
      inflight.delete(msg.id);
      resolver(msg);
    }
  });
  p.onDisconnect.addListener(() => {
    const err = chrome.runtime.lastError?.message ?? "host disconnected";
    inflight.forEach((resolve, id) => {
      resolve({ id, op: "error", code: "transport", message: err });
    });
    inflight.clear();
    port = null;
  });
  port = p;
  return p;
}

function send(message: object): Promise<IpcResponse> {
  return new Promise((resolve) => {
    const id = nextId++;
    inflight.set(id, resolve);
    try {
      ensurePort().postMessage({ id, ...message });
    } catch (e) {
      inflight.delete(id);
      resolve({ id, op: "error", code: "transport", message: String(e) });
    }
  });
}

chrome.runtime.onMessage.addListener((raw, _sender, sendResponse) => {
  const msg = raw as BgRequest;
  if (msg?.target !== "background") return false;

  let promise: Promise<IpcResponse>;
  switch (msg.op) {
    case "status":
      promise = send({ op: "status" });
      break;
    case "match":
      promise = send({ op: "match", host: msg.params.host });
      break;
    case "reveal":
      promise = send({ op: "reveal", entryId: msg.params.entryId });
      break;
    default:
      promise = Promise.resolve({ id: 0, op: "error", code: "bad_request", message: "unknown op" });
  }
  promise.then(sendResponse);
  return true; // tells Chrome we'll respond asynchronously
});
