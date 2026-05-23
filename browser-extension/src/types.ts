/**
 * Wire types shared with the PwMgr desktop IPC server. Kept in sync by hand with
 * `desktopApp/src/jvmMain/kotlin/com/pwmgr/desktop/ipc/IpcProtocol.kt`.
 *
 * IMPORTANT: this file is intentionally type-only (no runtime exports). Mixing types and
 * runtime values here forces Rollup to emit a shared chunk between background / content /
 * popup, which is incompatible with IIFE output. Anything runtime-needed lives in the
 * specific entry file that needs it (currently just NATIVE_HOST_NAME in background.ts).
 */

export type IpcRequest =
  | { id: number; op: "ping" }
  | { id: number; op: "status" }
  | { id: number; op: "match"; host: string }
  | { id: number; op: "reveal"; entryId: string };

export type IpcCandidate = { id: string; title: string; username: string | null };

export type IpcResponse =
  | { id: number; op: "pong" }
  | { id: number; op: "status_ok"; unlocked: boolean }
  | { id: number; op: "match_ok"; candidates: IpcCandidate[] }
  | { id: number; op: "reveal_ok"; username: string | null; password: string }
  | { id: number; op: "error"; code: string; message: string };

/** Internal message between content/popup scripts and the background service worker. */
export type BgRequest =
  | { target: "background"; op: "status" }
  | { target: "background"; op: "match"; params: { host: string } }
  | { target: "background"; op: "reveal"; params: { entryId: string } };
