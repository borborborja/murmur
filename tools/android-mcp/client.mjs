import https from 'node:https';
import tls from 'node:tls';
import { createHash, randomUUID } from 'node:crypto';
import { readFile, writeFile, mkdir, chmod, stat } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { privateAddress, discover } from './mdns.mjs';

const MAX_REPLY = 16 * 1024 * 1024;
const PROTOCOL = '2025-11-25';
export function certificatePin(raw) { return createHash('sha256').update(raw).digest('hex'); }
export function validateEndpoint(value, allowLoopback = false) {
  const url = new URL(value);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)
      || (!privateAddress(url.hostname) && !(allowLoopback && url.hostname === '127.0.0.1')) || !url.port) throw new Error('A private LAN HTTPS address with a port is required');
  return url.origin;
}
export function parsePairing(value) {
  const url = new URL(value.trim());
  if (url.protocol !== 'murmur-mcp:' || url.hostname !== 'pair' || url.searchParams.get('v') !== '1') throw new Error('Invalid Murmur Android pairing code');
  const pin = url.searchParams.get('pin')?.toLowerCase();
  const secret = url.searchParams.get('secret');
  const host = url.searchParams.get('host'); const port = Number(url.searchParams.get('port'));
  if (!/^[a-f0-9]{64}$/.test(pin ?? '') || !/^[A-Za-z0-9_-]{40,100}$/.test(secret ?? '') || !privateAddress(host ?? '') || !Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid Murmur Android pairing code');
  return { endpoint: validateEndpoint(`https://${host}:${port}`), fingerprint: pin, secret };
}

/** HTTP receives a socket only after TLS has completed and its exact certificate was checked. */
class PinnedAgent extends https.Agent {
  constructor(pin) { super({ keepAlive: false }); this.pin = pin; }
  createConnection(options, callback) {
    const socket = tls.connect({ ...options, rejectUnauthorized: false, minVersion: 'TLSv1.2', servername: '' });
    let called = false;
    const finish = (error, value) => { if (called) return; called = true; callback(error, value); };
    socket.once('error', error => finish(error));
    socket.once('secureConnect', () => {
      const raw = socket.getPeerCertificate(true)?.raw;
      if (!raw || certificatePin(raw) !== this.pin) {
        const error = new Error('Android certificate changed; pair the phone again'); error.code = 'MURMUR_PIN_MISMATCH';
        socket.destroy(error); finish(error); return;
      }
      finish(null, socket);
    });
    return undefined;
  }
}

export class PhoneClient {
  constructor(profile, token, { allowLoopback = false, discovery = discover, updateEndpoint = async () => {} } = {}) {
    this.endpoint = validateEndpoint(profile.endpoint, allowLoopback);
    this.fingerprint = profile.fingerprint;
    if (!/^[a-f0-9]{64}$/.test(this.fingerprint)) throw new Error('Invalid certificate pin');
    if (token != null && !/^[A-Za-z0-9_-]{40,100}$/.test(token)) throw new Error('Invalid device credential');
    this.token = token; this.allowLoopback = allowLoopback; this.discovery = discovery; this.updateEndpoint = updateEndpoint;
  }
  async request(route, body, { signal, token = this.token, contentType = 'application/json', fileName, length, retry = true } = {}) {
    let payload;
    if (body instanceof Uint8Array || typeof body === 'string' || body?.pipe) payload = body;
    else payload = Buffer.from(JSON.stringify(body));
    try { return await this.send(route, payload, { signal, token, contentType, fileName, length }); }
    catch (error) {
      if (retry && !signal?.aborted && !body?.pipe && ['ECONNREFUSED', 'ETIMEDOUT', 'EHOSTUNREACH', 'ENETUNREACH', 'MURMUR_PIN_MISMATCH'].includes(error.code)) {
        const endpoint = await this.discovery(this.fingerprint);
        if (endpoint && endpoint !== this.endpoint) {
          this.endpoint = validateEndpoint(endpoint, this.allowLoopback);
          const result = await this.send(route, payload, { signal, token, contentType, fileName, length });
          await this.updateEndpoint(this.endpoint); return result;
        }
      }
      throw error;
    }
  }
  send(route, payload, { signal, token, contentType, fileName, length }) {
    if (!['/mcp', '/uploads', '/pair', '/pair/status', '/pair/ack'].includes(route)) throw new Error('Invalid endpoint');
    const url = new URL(route, this.endpoint);
    const agent = new PinnedAgent(this.fingerprint);
    const headers = { 'Content-Type': contentType, Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': PROTOCOL,
      'Content-Length': length ?? Buffer.byteLength(payload) };
    if (token) headers.Authorization = `Bearer ${token}`;
    if (fileName) headers['X-File-Name'] = path.basename(fileName).replace(/[^a-zA-Z0-9_.-]/g, '_').slice(-180);
    return new Promise((resolve, reject) => {
      const request = https.request(url, { method: 'POST', headers, agent, signal }, response => {
        const chunks = []; let size = 0;
        response.on('data', chunk => { size += chunk.length; if (size > MAX_REPLY) request.destroy(new Error('Reply exceeds maximum size')); else chunks.push(chunk); });
        response.on('error', reject);
        response.on('end', () => {
          if (response.statusCode === 401) return reject(new Error('Device credential was revoked; pair the phone again'));
          if (response.statusCode !== 200 && response.statusCode !== 202) return reject(new Error(`Android request failed (HTTP ${response.statusCode})`));
          if (size === 0) return resolve(null);
          try {
            const text = Buffer.concat(chunks).toString('utf8');
            if (response.headers['content-type']?.startsWith('text/event-stream')) {
              const messages = text.split(/\r?\n\r?\n/).flatMap(event => {
                const data = event.split(/\r?\n/).filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n');
                return data ? [JSON.parse(data)] : [];
              });
              const result = messages.findLast(item => item.id != null);
              if (!result) throw new Error('No JSON-RPC response');
              resolve(result);
            } else resolve(JSON.parse(text));
          } catch { reject(new Error('Invalid Android response')); }
        });
      });
      request.setTimeout(60_000, () => { const error = new Error('Android request timed out'); error.code = 'ETIMEDOUT'; request.destroy(error); });
      request.on('error', reject);
      request.on('close', () => agent.destroy());
      if (payload?.pipe) { payload.on('error', error => request.destroy(error)); payload.pipe(request); }
      else request.end(payload);
    });
  }
  async rpc(message, signal) {
    if (message.method === 'tools/call' && message.params?.name === 'transcribe_file') {
      const args = message.params.arguments ?? {};
      if (typeof args.path !== 'string' || !path.isAbsolute(args.path)) throw new Error('transcribe_file requires an absolute file path on this computer');
      const metadata = await stat(args.path);
      if (!metadata.isFile() || metadata.size < 1 || metadata.size > 512 * 1024 * 1024) throw new Error('Audio/video files must be between 1 byte and 512 MiB');
      // Open only the requested file. No filename is ever interpreted as an Android path or URL.
      const upload = await this.request('/uploads', createReadStream(args.path), { signal, contentType: 'application/octet-stream', fileName: args.path, length: metadata.size, retry: false });
      if (typeof upload?.upload_id !== 'string') throw new Error('Android did not accept the uploaded file');
      message = { ...message, params: { ...message.params, arguments: { ...args, path: `upload:${upload.upload_id}`, upload_id: upload.upload_id } } };
    }
    return this.request('/mcp', message, { signal });
  }
}

export function configFolder(env = process.env, platform = process.platform) {
  if (platform === 'win32') return path.join(env.APPDATA || path.join(os.homedir(), 'AppData', 'Roaming'), 'murmur-android-mcp');
  if (platform === 'darwin') return path.join(os.homedir(), 'Library', 'Application Support', 'murmur-android-mcp');
  return path.join(env.XDG_CONFIG_HOME || path.join(os.homedir(), '.config'), 'murmur-android-mcp');
}
export class ProfileStore {
  constructor(folder = configFolder()) { this.folder = folder; this.file = path.join(folder, 'devices.json'); }
  async read() {
    try { const config = JSON.parse(await readFile(this.file, 'utf8')); if (!Array.isArray(config.devices)) throw new Error(); return config; }
    catch (error) { if (error.code === 'ENOENT') return { version: 1, devices: [] }; throw new Error('Device configuration cannot be read'); }
  }
  async write(config) {
    for (const item of config.devices) {
      if (Object.keys(item).some(key => !['id', 'name', 'endpoint', 'fingerprint', 'clientId'].includes(key))) throw new Error('Unexpected device field');
      validateEndpoint(item.endpoint);
    }
    await mkdir(this.folder, { recursive: true, mode: 0o700 });
    const temporary = `${this.file}.${randomUUID()}.tmp`;
    await writeFile(temporary, JSON.stringify(config, null, 2) + '\n', { mode: 0o600 });
    const { rename } = await import('node:fs/promises'); await rename(temporary, this.file);
    await chmod(this.file, 0o600);
  }
  async profile(id) {
    const config = await this.read();
    const profile = id ? config.devices.find(item => item.id === id) : config.devices.length === 1 ? config.devices[0] : null;
    if (!profile) throw new Error('Select a paired device with --device <id>; use pair first');
    return profile;
  }
  async updateEndpoint(id, endpoint) { const config = await this.read(); const item = config.devices.find(item => item.id === id); if (item) { item.endpoint = endpoint; await this.write(config); } }
}

export async function pairDevice(pairing, { keychain, store, name = os.hostname(), signal, onPending = () => {}, interval = 1000, clientOptions = {} }) {
  const client = new PhoneClient(pairing, null, clientOptions);
  const request = await client.request('/pair', { secret: pairing.secret, name }, { signal, token: null });
  if (!request?.requestId) throw new Error('Pairing request was not accepted');
  onPending();
  const end = Date.now() + 5 * 60_000;
  while (Date.now() < end) {
    if (signal?.aborted) throw signal.reason;
    const result = await client.request('/pair/status', { requestId: request.requestId, secret: pairing.secret }, { signal, token: null });
    if (result.status === 'approved') {
      if (!/^[A-Za-z0-9_-]{40,100}$/.test(result.token ?? '')) throw new Error('Invalid device credential');
      const id = randomUUID();
      await keychain.put(id, result.token);
      if (await keychain.get(id) !== result.token) throw new Error('System credential store did not retain the credential');
      const config = await store.read();
      config.devices.push({ id, name: name.slice(0, 80), endpoint: client.endpoint, fingerprint: pairing.fingerprint, clientId: result.clientId });
      await store.write(config);
      await client.request('/pair/ack', { requestId: request.requestId, secret: pairing.secret }, { signal, token: null });
      return config.devices.at(-1);
    }
    await new Promise((resolve, reject) => { const timer = setTimeout(resolve, interval); signal?.addEventListener('abort', () => { clearTimeout(timer); reject(signal.reason); }, { once: true }); });
  }
  throw new Error('Pairing expired; show a new code on the phone');
}
