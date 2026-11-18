import test from 'node:test';
import assert from 'node:assert/strict';
import https from 'node:https';
import { once } from 'node:events';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Readable, Writable } from 'node:stream';
import { PhoneClient, ProfileStore, certificatePin, parsePairing, validateEndpoint, pairDevice } from '../client.mjs';
import { privateAddress, records } from '../mdns.mjs';
import { serveStdio } from '../bridge.mjs';

async function serverFixture(t, handle) {
  const directory = await mkdtemp(path.join(tmpdir(), 'murmur-tls-'));
  execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', path.join(directory, 'key.pem'), '-out', path.join(directory, 'cert.pem'), '-subj', '/CN=Murmur Android Test', '-days', '1'], { stdio: 'ignore' });
  const cert = await readFile(path.join(directory, 'cert.pem'));
  const key = await readFile(path.join(directory, 'key.pem'));
  const raw = Buffer.from(cert.toString().replace(/-----[^-]+-----|\s/g, ''), 'base64');
  const requests = [];
  const server = https.createServer({ cert, key }, async (request, response) => {
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = Buffer.concat(chunks);
    requests.push({ path: request.url, headers: request.headers, body });
    const reply = await handle(request, body);
    response.writeHead(reply.status ?? 200, { 'Content-Type': reply.contentType ?? 'application/json' });
    response.end(typeof reply.body === 'string' ? reply.body : JSON.stringify(reply.body));
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await rm(directory, { recursive: true }); });
  const endpoint = `https://127.0.0.1:${server.address().port}`;
  return { profile: { endpoint, fingerprint: certificatePin(raw) }, requests, directory };
}
const credential = 'a'.repeat(43);

test('private endpoint and pairing code validation reject public/cleartext/credential URLs', () => {
  for (const value of ['8.8.8.8', '127.0.0.1', '172.32.0.1', '10..1.2', '10.0.0x1.2', '192.168.1.256']) assert.equal(privateAddress(value), false);
  for (const value of ['10.0.0.1', '192.168.1.30', '172.16.0.2', '169.254.1.2']) assert.equal(privateAddress(value), true);
  for (const value of ['http://192.168.1.2:4', 'https://8.8.8.8:4', 'https://key@192.168.1.2:4', 'https://192.168.1.2:4/a', 'https://example.com:4']) assert.throws(() => validateEndpoint(value));
  const good = `murmur-mcp://pair?v=1&host=192.168.1.2&port=4000&pin=${'b'.repeat(64)}&secret=${credential}`;
  assert.equal(parsePairing(good).endpoint, 'https://192.168.1.2:4000');
  assert.throws(() => parsePairing(good.replace('v=1', 'v=2')));
  assert.throws(() => parsePairing(good.replace('192.168.1.2', '8.8.8.8')));
});

test('pinned TLS is verified before an authorization header can reach the server', async t => {
  const fixture = await serverFixture(t, () => ({ body: { ok: true } }));
  const bad = new PhoneClient({ ...fixture.profile, fingerprint: '0'.repeat(64) }, credential, { allowLoopback: true, discovery: async () => null });
  await assert.rejects(bad.request('/mcp', { hello: 'test' }), /certificate changed/);
  assert.equal(fixture.requests.length, 0, 'bad pin must not send HTTP headers or body');
  const good = new PhoneClient(fixture.profile, credential, { allowLoopback: true });
  assert.deepEqual(await good.request('/mcp', { hello: 'test' }), { ok: true });
  assert.equal(fixture.requests[0].headers.authorization, `Bearer ${credential}`);
});

test('transcribe_file uploads requested local bytes and replaces the path with an opaque upload id', async t => {
  const uploadId = '00000000-0000-0000-0000-000000000001';
  const fixture = await serverFixture(t, (request, body) => ({ body: request.url === '/uploads' ? { upload_id: uploadId } : { jsonrpc: '2.0', id: JSON.parse(body).id, result: { content: [] } } }));
  const file = path.join(fixture.directory, 'meeting sample.wav'); await writeFile(file, Buffer.from([0, 1, 2, 3, 255]));
  const client = new PhoneClient(fixture.profile, credential, { allowLoopback: true });
  await client.rpc({ jsonrpc: '2.0', id: 7, method: 'tools/call', params: { name: 'transcribe_file', arguments: { path: file, language: 'ca', max_speakers: 4 } } });
  assert.equal(fixture.requests.length, 2);
  assert.equal(fixture.requests[0].path, '/uploads');
  assert.deepEqual([...fixture.requests[0].body], [0, 1, 2, 3, 255]);
  const remote = JSON.parse(fixture.requests[1].body).params.arguments;
  assert.equal(remote.path, `upload:${uploadId}`); assert.equal(remote.upload_id, uploadId); assert.equal(remote.max_speakers, 4);
  assert.equal(JSON.stringify(remote).includes(fixture.directory), false);
  await assert.rejects(client.rpc({ method: 'tools/call', params: { name: 'transcribe_file', arguments: { path: 'https://outside/file.wav' } } }), /absolute file path/);
});

test('pairing waits for visible approval and retains only a keychain reference in configuration', async t => {
  let polls = 0; let acknowledged = false;
  const fixture = await serverFixture(t, (request) => ({ body: request.url === '/pair' ? { requestId: 'req' }
    : request.url === '/pair/status' ? (++polls < 2 ? { status: 'pending' } : { status: 'approved', token: credential, clientId: 'phone-client' })
    : (acknowledged = true, { ok: true }) }));
  const credentials = new Map(); const config = { version: 1, devices: [] };
  const keychain = { put: async (id, value) => credentials.set(id, value), get: async id => credentials.get(id) };
  const store = { read: async () => config, write: async () => {} };
  const profile = await pairDevice({ ...fixture.profile, secret: 's'.repeat(43) }, { keychain, store, interval: 1, name: 'Test computer', clientOptions: { allowLoopback: true } });
  assert.ok(polls >= 2); assert.equal(acknowledged, true); assert.equal(credentials.get(profile.id), credential);
  assert.equal(JSON.stringify(config).includes(credential), false);
  assert.equal(JSON.stringify(config).includes('s'.repeat(43)), false);
});

test('profile files refuse credentials and unknown fields', async t => {
  const directory = await mkdtemp(path.join(tmpdir(), 'murmur-config-')); t.after(() => rm(directory, { recursive: true }));
  const store = new ProfileStore(directory);
  const profile = { id: 'test', name: 'Phone', endpoint: 'https://192.168.1.2:4000', fingerprint: 'f'.repeat(64), clientId: 'client' };
  await store.write({ version: 1, devices: [profile] }); assert.deepEqual(await store.profile('test'), profile);
  await assert.rejects(store.write({ version: 1, devices: [{ ...profile, token: credential }] }), /Unexpected/);
});

test('stdio JSON-RPC supports concurrent ids, notifications, and parse errors without extra stdout', async () => {
  const output = [];
  const sink = new Writable({ write(chunk, encoding, callback) { output.push(chunk.toString()); callback(); } });
  const input = Readable.from(['{"jsonrpc":"2.0","id":1,"method":"initialize"}\n{"jsonrpc":"2.0","method":"notifications/initialized"}\n', 'not-json\n{"jsonrpc":"2.0","id":2,"method":"tools/list"}\n']);
  await serveStdio({ rpc: async request => request.id == null ? null : { jsonrpc: '2.0', id: request.id, result: { method: request.method } } }, input, sink);
  const responses = output.join('').trim().split('\n').map(JSON.parse);
  assert.equal(responses.length, 3);
  assert.ok(responses.find(reply => reply.id === 1 && reply.result.method === 'initialize'));
  assert.ok(responses.find(reply => reply.id === 2 && reply.result.method === 'tools/list'));
  assert.ok(responses.find(reply => reply.error?.code === -32700));
});

test('malformed and cyclic mDNS records are rejected without an infinite parser loop', () => {
  assert.deepEqual(records(Buffer.alloc(4)), []);
  const bad = Buffer.alloc(30); bad.writeUInt16BE(1, 6); bad[12] = 0xc0; bad[13] = 12;
  assert.deepEqual(records(bad), []);
});
