#!/usr/bin/env node
import readline from 'node:readline';
import { pathToFileURL, fileURLToPath } from 'node:url';
import path from 'node:path';
import { PhoneClient, ProfileStore, parsePairing, pairDevice } from './client.mjs';
import { SystemKeychain } from './keychain.mjs';

/** JSON-RPC stdio is line framed. Diagnostics stay on stderr and never contain credentials. */
export async function serveStdio(client, input = process.stdin, output = process.stdout) {
  const running = new Map();
  let pending = new Set();
  input.setEncoding('utf8');
  let buffer = '';
  const write = message => output.write(JSON.stringify(message) + '\n');
  const handle = async message => {
    const id = message?.id;
    if (!message || message.jsonrpc !== '2.0' || typeof message.method !== 'string' || Array.isArray(message)) {
      write({ jsonrpc: '2.0', id: id ?? null, error: { code: -32600, message: 'Invalid JSON-RPC request' } }); return;
    }
    if (message.method === 'notifications/cancelled') running.get(message.params?.requestId)?.abort(new Error('Request cancelled'));
    const controller = new AbortController();
    if (id != null) {
      if (running.has(id)) { write({ jsonrpc: '2.0', id, error: { code: -32600, message: 'Duplicate request id' } }); return; }
      running.set(id, controller);
    }
    try {
      const response = await client.rpc(message, controller.signal);
      if (id != null && response) write({ ...response, id });
    } catch (error) {
      if (id != null) write({ jsonrpc: '2.0', id, error: { code: controller.signal.aborted ? -32800 : -32000,
        message: error.message || 'Android connection failed' } });
    } finally { if (id != null) running.delete(id); }
  };
  for await (const chunk of input) {
    buffer += chunk;
    if (Buffer.byteLength(buffer) > 16 * 1024 * 1024) throw new Error('MCP input exceeds maximum size');
    let newline;
    while ((newline = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, newline).trim(); buffer = buffer.slice(newline + 1);
      if (!line) continue;
      let message;
      try { message = JSON.parse(line); }
      catch { write({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Invalid JSON' } }); continue; }
      const task = handle(message); pending.add(task); task.finally(() => pending.delete(task));
    }
  }
  if (buffer.trim()) write({ jsonrpc: '2.0', id: null, error: { code: -32700, message: 'Incomplete JSON-RPC line' } });
  await Promise.allSettled(pending);
}

async function main(args) {
  const store = new ProfileStore(); const keychain = new SystemKeychain();
  const command = args[0] && !args[0].startsWith('--') ? args[0] : 'serve';
  const value = option => { const index = args.indexOf(option); return index >= 0 ? args[index + 1] : undefined; };
  if (command === 'pair') {
    const terminal = readline.createInterface({ input: process.stdin, output: process.stderr });
    const code = await new Promise(resolve => terminal.question('Enganxa el codi de Connector Claude del mòbil: ', resolve));
    terminal.close();
    const pairing = parsePairing(code);
    const profile = await pairDevice(pairing, { store, keychain, name: value('--name'), onPending: () => process.stderr.write('Accepta la petició al mòbil.\n') });
    process.stderr.write(`Dispositiu aparellat: ${profile.id}\n`);
    process.stderr.write(`Claude Desktop: {"mcpServers":{"murmur-android":{"command":${JSON.stringify(process.execPath)},"args":[${JSON.stringify(fileURLToPath(import.meta.url))},"serve","--device",${JSON.stringify(profile.id)}]}}}\n`);
    process.stderr.write(`Claude Code: claude mcp add murmur-android -- ${JSON.stringify(process.execPath)} ${JSON.stringify(fileURLToPath(import.meta.url))} serve --device ${profile.id}\n`);
    return;
  }
  if (command === 'list') {
    for (const profile of (await store.read()).devices) process.stdout.write(`${profile.id}\t${profile.name}\t${profile.endpoint}\n`);
    return;
  }
  if (command === 'forget') {
    const profile = await store.profile(value('--device')); await keychain.remove(profile.id);
    const config = await store.read(); config.devices = config.devices.filter(item => item.id !== profile.id); await store.write(config);
    process.stderr.write('Credencial local eliminada. Retira també el client al mòbil.\n'); return;
  }
  if (command !== 'serve') throw new Error('Use pair, list, serve or forget');
  const profile = await store.profile(value('--device')); const token = await keychain.get(profile.id);
  const client = new PhoneClient(profile, token, { updateEndpoint: endpoint => store.updateEndpoint(profile.id, endpoint) });
  await serveStdio(client);
}
if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  main(process.argv.slice(2)).catch(() => { process.stderr.write('Murmur Android: no s’ha pogut completar l’operació. Comprova l’aparellament, la xarxa local i el clauer del sistema.\n'); process.exitCode = 1; });
}
