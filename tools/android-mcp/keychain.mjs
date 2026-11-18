import { spawn } from 'node:child_process';

const SERVICE = 'dev.murmur.android.mcp';
function command(executable, args, input = '', env = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...env }, windowsHide: true });
    const chunks = [];
    child.stdout.on('data', chunk => chunks.push(chunk));
    child.stderr.resume(); // Credential utility diagnostics can contain values; never echo them.
    child.on('error', () => reject(new Error('System credential store is unavailable')));
    child.on('exit', code => code === 0 ? resolve(Buffer.concat(chunks).toString('utf8').trim()) : reject(new Error('System credential operation failed')));
    child.stdin.on('error', () => {});
    child.stdin.end(input);
  });
}

const WINDOWS = String.raw`
Add-Type @'
using System;
using System.Runtime.InteropServices;
public static class MurmurCred {
 [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] public struct Credential {
  public UInt32 Flags; public UInt32 Type; public string TargetName; public string Comment;
  public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten; public UInt32 CredentialBlobSize;
  public IntPtr CredentialBlob; public UInt32 Persist; public UInt32 AttributeCount;
  public IntPtr Attributes; public string TargetAlias; public string UserName;
 }
 [DllImport("advapi32.dll", EntryPoint="CredWriteW", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool Write(ref Credential c, UInt32 flags);
 [DllImport("advapi32.dll", EntryPoint="CredReadW", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool Read(string name, UInt32 type, UInt32 flags, out IntPtr c);
 [DllImport("advapi32.dll", EntryPoint="CredDeleteW", CharSet=CharSet.Unicode, SetLastError=true)] public static extern bool Delete(string name, UInt32 type, UInt32 flags);
 [DllImport("advapi32.dll")] public static extern void CredFree(IntPtr p);
}
'@
$target = 'dev.murmur.android.mcp/' + $env:MURMUR_DEVICE_ID
if ($env:MURMUR_CRED_OPERATION -eq 'put') {
 $secret = [Console]::In.ReadToEnd()
 $pointer = [Runtime.InteropServices.Marshal]::StringToHGlobalUni($secret)
 try {
  $credential = New-Object MurmurCred+Credential
  $credential.Type = 1; $credential.TargetName = $target; $credential.UserName = 'murmur-android'
  $credential.Persist = 2; $credential.CredentialBlobSize = [Text.Encoding]::Unicode.GetByteCount($secret)
  $credential.CredentialBlob = $pointer
  if (-not [MurmurCred]::Write([ref]$credential, 0)) { exit 1 }
 } finally { [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer) }
} elseif ($env:MURMUR_CRED_OPERATION -eq 'get') {
 $pointer = [IntPtr]::Zero
 if (-not [MurmurCred]::Read($target, 1, 0, [ref]$pointer)) { exit 1 }
 try {
  $credential = [Runtime.InteropServices.Marshal]::PtrToStructure($pointer, [type][MurmurCred+Credential])
  [Console]::Out.Write([Runtime.InteropServices.Marshal]::PtrToStringUni($credential.CredentialBlob, $credential.CredentialBlobSize / 2))
 } finally { [MurmurCred]::CredFree($pointer) }
} else { if (-not [MurmurCred]::Delete($target, 1, 0)) { exit 1 } }
`;

/** Tokens live in the user's system credential store, never the MCP configuration file. */
export class SystemKeychain {
  constructor(platform = process.platform) { this.platform = platform; }
  async put(id, token) {
    validateId(id);
    if (this.platform === 'darwin') {
      // security accepts a password through an interactive command on stdin, avoiding argv/ps.
      const escaped = value => '"' + value.replace(/[\\"]/g, '\\$&') + '"';
      await command('/usr/bin/security', ['-i'], `add-generic-password -U -a ${escaped(id)} -s ${escaped(SERVICE)} -w ${escaped(token)}\nquit\n`);
    } else if (this.platform === 'linux') {
      await command('secret-tool', ['store', '--label=Murmur Android MCP', 'service', SERVICE, 'device', id], token);
    } else if (this.platform === 'win32') await this.windows('put', id, token);
    else throw new Error('Unsupported system credential store');
  }
  async get(id) {
    validateId(id);
    const token = this.platform === 'darwin' ? await command('/usr/bin/security', ['find-generic-password', '-a', id, '-s', SERVICE, '-w'])
      : this.platform === 'linux' ? await command('secret-tool', ['lookup', 'service', SERVICE, 'device', id])
      : this.platform === 'win32' ? await this.windows('get', id) : '';
    if (!/^[A-Za-z0-9_-]{40,100}$/.test(token)) throw new Error('Pair this device again: its credential is missing');
    return token;
  }
  async remove(id) {
    validateId(id);
    if (this.platform === 'darwin') await command('/usr/bin/security', ['delete-generic-password', '-a', id, '-s', SERVICE]);
    else if (this.platform === 'linux') await command('secret-tool', ['clear', 'service', SERVICE, 'device', id]);
    else if (this.platform === 'win32') await this.windows('remove', id);
  }
  windows(operation, id, input = '') {
    return command('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(WINDOWS, 'utf16le').toString('base64')], input,
      { MURMUR_DEVICE_ID: id, MURMUR_CRED_OPERATION: operation });
  }
}
function validateId(id) { if (!/^[a-zA-Z0-9_-]{1,80}$/.test(id)) throw new Error('Invalid device id'); }
