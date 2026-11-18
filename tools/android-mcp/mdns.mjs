import dgram from 'node:dgram';

export function privateAddress(address) {
  if (typeof address !== 'string' || !/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/.test(address)) return false;
  const parts = address.split('.').map(Number);
  if (parts.length !== 4 || parts.some(x => !Number.isInteger(x) || x < 0 || x > 255)) return false;
  return parts[0] === 10 || (parts[0] === 172 && parts[1] >= 16 && parts[1] <= 31)
    || (parts[0] === 192 && parts[1] === 168) || (parts[0] === 169 && parts[1] === 254);
}
const SERVICE = '_murmur-mcp._tcp.local.';
function name(buffer, start, depth = 0) {
  if (depth > 16 || start >= buffer.length) throw new Error('Invalid DNS name');
  let offset = start;
  let end = null;
  const labels = [];
  for (let count = 0; count < 128; count++) {
    if (offset >= buffer.length) throw new Error('Invalid DNS name');
    const length = buffer[offset++];
    if (length === 0) return { value: labels.join('.') + '.', end: end ?? offset };
    if ((length & 192) === 192) {
      if (offset >= buffer.length) throw new Error('Invalid DNS pointer');
      const target = ((length & 63) << 8) | buffer[offset++];
      labels.push(name(buffer, target, depth + 1).value.replace(/\.$/, ''));
      return { value: labels.join('.') + '.', end: end ?? offset };
    }
    if (length > 63 || offset + length > buffer.length) throw new Error('Invalid DNS label');
    labels.push(buffer.toString('utf8', offset, offset + length)); offset += length;
  }
  throw new Error('Invalid DNS name');
}
export function records(buffer) {
  if (buffer.length < 12 || buffer.length > 65535) return [];
  const result = [];
  let offset = 12;
  try {
    for (let i = 0; i < buffer.readUInt16BE(4); i++) offset = name(buffer, offset).end + 4;
    const total = buffer.readUInt16BE(6) + buffer.readUInt16BE(8) + buffer.readUInt16BE(10);
    for (let i = 0; i < Math.min(total, 1000); i++) {
      const owner = name(buffer, offset); offset = owner.end;
      if (offset + 10 > buffer.length) return [];
      const type = buffer.readUInt16BE(offset); const size = buffer.readUInt16BE(offset + 8); offset += 10;
      if (offset + size > buffer.length) return [];
      let value;
      if (type === 33 && size >= 7) value = { port: buffer.readUInt16BE(offset + 4), target: name(buffer, offset + 6).value };
      else if (type === 1 && size === 4) value = [...buffer.subarray(offset, offset + 4)].join('.');
      else if (type === 16) {
        value = {};
        for (let p = offset; p < offset + size;) {
          const length = buffer[p++]; if (p + length > offset + size) break;
          const text = buffer.toString('utf8', p, p + length); p += length;
          const at = text.indexOf('='); if (at >= 0) value[text.slice(0, at)] = text.slice(at + 1);
        }
      }
      if (value) result.push({ owner: owner.value, type, value });
      offset += size;
    }
  } catch { return []; }
  return result;
}
export async function discover(fingerprint, timeout = 1500) {
  const found = [];
  return new Promise(resolve => {
    const socket = dgram.createSocket('udp4');
    let done = false;
    const finish = result => { if (done) return; done = true; clearTimeout(timer); try { socket.close(); } catch {} resolve(result); };
    const timer = setTimeout(() => finish(null), timeout);
    socket.on('error', () => finish(null));
    socket.on('message', packet => {
      found.push(...records(packet));
      const verified = found.filter(r => r.type === 16 && r.owner.endsWith(SERVICE) && r.value.pin === fingerprint);
      for (const txt of verified) {
        const srv = found.find(r => r.type === 33 && r.owner === txt.owner);
        const a = srv && found.find(r => r.type === 1 && r.owner === srv.value.target && privateAddress(r.value));
        if (a && srv.value.port > 0) return finish(`https://${a.value}:${srv.value.port}`);
      }
    });
    socket.bind(0, () => {
      const encoded = Buffer.concat(SERVICE.split('.').filter(Boolean).map(label => Buffer.concat([Buffer.from([label.length]), Buffer.from(label)])));
      const header = Buffer.alloc(12); header.writeUInt16BE(1, 4);
      // A legacy unicast query (source port !=5353) also works without owning UDP5353.
      const tail = Buffer.from([0, 0, 12, 0, 1]);
      socket.send(Buffer.concat([header, encoded, tail]), 5353, '224.0.0.251', error => { if (error) finish(null); });
    });
  });
}
