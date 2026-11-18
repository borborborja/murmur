#!/usr/bin/env node
import { readFile, writeFile, mkdir, copyFile, readdir } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const android = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.resolve(android, '../..');
const require = createRequire(path.join(repo, 'app/package.json'));
const { build } = await import(require.resolve('vite'));
const original = await readFile(path.join(repo, 'app/src/lib/notes.ts'), 'utf8');
const extensions = original.slice(0, original.indexOf('const text =')).replace(/^import[\s\S]*?;\n/gm, '');
const header = `// Generated from app/src/lib/notes.ts. Do not edit.\nimport { Extension } from '@tiptap/core';\nimport { Plugin, PluginKey } from '@tiptap/pm/state';\nconst clock=(s:number)=>Math.floor(s/60)+':'+String(Math.floor(s%60)).padStart(2,'0');\n`;
await writeFile(path.join(android, 'web/notes-extensions.generated.ts'), header + extensions);
const target = path.join(android, 'app/src/main/assets/notes');
const notices = path.join(android, 'app/src/main/assets/licenses/generated');
await mkdir(target, { recursive: true });
await mkdir(notices, { recursive: true });
await build({
  configFile: false,
  root: path.join(android, 'web'),
  resolve: { alias: ['@tiptap/core', '@tiptap/pm/state', '@tiptap/starter-kit', '@tiptap/extension-list', '@tiptap/extensions'].map(name => ({ find: new RegExp(`^${name}$`), replacement: require.resolve(name).replace(/\.cjs$/, '.js') })) },
  plugins: [{
    name: 'murmur-notes-notices',
    async generateBundle(_options, bundle) {
      const packages = new Map();
      for (const chunk of Object.values(bundle)) {
        if (chunk.type !== 'chunk') continue;
        for (const id of Object.keys(chunk.modules)) {
          if (!id.includes(`${path.sep}node_modules${path.sep}`)) continue;
          for (let dir = path.dirname(id); dir.includes(`${path.sep}node_modules${path.sep}`); dir = path.dirname(dir)) {
            const manifest = await readFile(path.join(dir, 'package.json'), 'utf8').catch(error => {
              if (error.code === 'ENOENT') return null;
              throw error;
            });
            if (!manifest) continue;
            const { name, version } = JSON.parse(manifest);
            if (!name || !version) continue;
            packages.set(`${name}@${version}`, dir);
            break;
          }
        }
      }
      const texts = await Promise.all([...packages].sort().map(async ([name, dir]) => {
        const files = (await readdir(dir)).filter(file => /^(license|notice|copying)(\.|$)/i.test(file)).sort();
        if (!files.length) throw new Error(`Missing license for bundled editor dependency ${name}`);
        const contents = await Promise.all(files.map(file => readFile(path.join(dir, file), 'utf8')));
        return `${name}\n\n${contents.map(content => content.trim()).join('\n\n')}`;
      }));
      await writeFile(path.join(notices, 'notes.txt'), texts.join('\n\n----\n\n') + '\n');
    },
  }],
  build: { outDir: target, emptyOutDir: false, sourcemap: false, minify: true, lib: { entry: path.join(android, 'web/notes.ts'), name: 'MurmurNotes', formats: ['iife'], fileName: () => 'notes.js' } },
});
for (const file of ['index.html', 'notes.css']) await copyFile(path.join(android, 'web', file), path.join(target, file));
console.log('Bundled original TipTap note extensions for offline Android use');
