/**
 * Server→client push over SSE.
 *
 * The bridge was strictly request/response before this: the tablet could ask for
 * things, but nothing on the Mac could reach the tablet unprompted. Agent-authored
 * canvas objects need exactly that, so this is the channel they arrive on.
 */

/** @type {Set<{ id: string, res: import("express").Response }>} */
const clients = new Set();

let nextClientId = 1;

/** Proxies and phone radios drop idle connections; this keeps the socket warm. */
const HEARTBEAT_MS = 20_000;

export function clientCount() {
  return clients.size;
}

export function attachEventStream(req, res) {
  res.status(200);
  res.setHeader("Content-Type", "text/event-stream; charset=utf-8");
  res.setHeader("Cache-Control", "no-cache, no-transform");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no");
  if (typeof res.flushHeaders === "function") res.flushHeaders();

  const client = { id: `c${nextClientId++}`, res };
  clients.add(client);

  send(client, "hello", { clientId: client.id, at: Date.now() });

  const timer = setInterval(() => {
    // A comment frame is a no-op to EventSource but still traffic on the wire.
    if (!res.writableEnded) res.write(`: ping ${Date.now()}\n\n`);
  }, HEARTBEAT_MS);

  const drop = () => {
    clearInterval(timer);
    clients.delete(client);
  };
  req.on("close", drop);
  req.on("error", drop);
  res.on("error", drop);

  return client.id;
}

function send(client, event, data) {
  if (client.res.writableEnded) return false;
  try {
    client.res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`);
    return true;
  } catch {
    clients.delete(client);
    return false;
  }
}

/**
 * Pushes an event to every connected client.
 *
 * @returns {number} how many clients received it
 */
export function broadcast(event, data) {
  let n = 0;
  for (const client of [...clients]) {
    if (send(client, event, data)) n++;
    else clients.delete(client);
  }
  return n;
}

export function closeAll() {
  for (const client of [...clients]) {
    try {
      client.res.end();
    } catch {
      /* ignore */
    }
  }
  clients.clear();
}
