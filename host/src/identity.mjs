/**
 * Who this host is: a stable id (so the app can tell computers apart and keep each
 * one's documents apart) and a name to show. Kept in `<stateDir>/host.json`.
 */
import { execFileSync } from "node:child_process";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** The name the user gave this computer ("Alex's MacBook Air"), else its host name. */
export function defaultHostName() {
  if (process.platform === "darwin") {
    try {
      const n = execFileSync("scutil", ["--get", "ComputerName"], { encoding: "utf8", timeout: 2000 }).trim();
      if (n) return n;
    } catch {
      /* fall back to the host name */
    }
  }
  const raw = os.hostname().replace(/\.local$/i, "");
  return raw.replace(/[-_]+/g, " ").trim() || "Computer";
}

export class HostIdentity {
  /** @param {{ stateDir: string, name?: string }} opts */
  constructor(opts) {
    this.file = path.join(opts.stateDir, "host.json");
    this.nameOverride = opts.name || "";
    this.state = null;
  }

  init() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    try {
      this.state = JSON.parse(fs.readFileSync(this.file, "utf8"));
    } catch {
      this.state = null;
    }
    if (!this.state || typeof this.state.hostId !== "string") {
      this.state = { hostId: crypto.randomUUID(), name: defaultHostName() };
    }
    if (this.nameOverride) this.state.name = this.nameOverride;
    fs.writeFileSync(this.file, JSON.stringify({ hostId: this.state.hostId, name: this.state.name }, null, 2));
    return this;
  }

  get hostId() {
    return this.state.hostId;
  }

  get name() {
    return this.state.name;
  }
}
