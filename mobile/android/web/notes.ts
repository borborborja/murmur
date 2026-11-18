import { Editor } from '@tiptap/core';
import StarterKit from '@tiptap/starter-kit';
import { TaskItem, TaskList } from '@tiptap/extension-list';
import { Placeholder } from '@tiptap/extensions';
import { BlockTime, Indent } from './notes-extensions.generated';

let time = 0;
let changing = false;
const bridge = () => (window as any).AndroidNotes;
const editor = new Editor({
  element: document.getElementById('editor')!,
  extensions: [
    StarterKit.configure({ heading: { levels: [1, 2, 3] }, link: { openOnClick: false } }),
    TaskList,
    TaskItem.configure({ nested: true }),
    Placeholder.configure({ placeholder: () => document.documentElement.dataset.placeholder ?? '' }),
    BlockTime.configure({ now: () => time }),
    Indent,
  ],
  content: { type: 'doc', content: [{ type: 'paragraph' }] },
  onUpdate: ({ editor }) => {
    if (!changing) bridge()?.changed(JSON.stringify(editor.getJSON()));
  },
  editorProps: {
    attributes: { class: 'notes-editor', spellcheck: 'true', autocapitalize: 'sentences' },
    handleDOMEvents: {
      blur: () => { bridge()?.changed(JSON.stringify(editor.getJSON())); return false; },
    },
  },
});

function setDocument(doc: object) {
  if (JSON.stringify(editor.getJSON()) === JSON.stringify(doc)) return;
  // Native state may echo an older autosave while typing. Never replace focused edits.
  if (editor.isFocused) return;
  changing = true;
  editor.commands.setContent(doc, { emitUpdate: false });
  changing = false;
}

(window as any).murmur = {
  configure(config: { doc: object; time: number; placeholder: string; colors: Record<string, string>; lang: string; zoom: number }) {
    time = config.time;
    document.documentElement.lang = config.lang;
    document.documentElement.dataset.placeholder = config.placeholder;
    Object.entries(config.colors).forEach(([key, value]) => document.documentElement.style.setProperty(`--${key}`, value));
    document.documentElement.style.fontSize = `${16 * (config.zoom || 1)}px`;
    setDocument(config.doc);
    editor.view.dom.setAttribute('aria-label', config.placeholder);
  },
  setTime(value: number) { time = value; },
  link(value: string) {
    const chain = editor.chain().focus().extendMarkRange('link');
    if (!value) chain.unsetLink().run();
    else if (/^https?:\/\//i.test(value)) chain.setLink({ href: value }).run();
  },
  command(name: string) {
    const chain = editor.chain().focus();
    switch (name) {
      case 'bold': chain.toggleBold().run(); break;
      case 'italic': chain.toggleItalic().run(); break;
      case 'underline': chain.toggleUnderline().run(); break;
      case 'strike': chain.toggleStrike().run(); break;
      case 'heading1': chain.toggleHeading({ level: 1 }).run(); break;
      case 'heading2': chain.toggleHeading({ level: 2 }).run(); break;
      case 'heading3': chain.toggleHeading({ level: 3 }).run(); break;
      case 'paragraph': chain.setParagraph().run(); break;
      case 'bullet': chain.toggleBulletList().run(); break;
      case 'ordered': chain.toggleOrderedList().run(); break;
      case 'tasks': chain.toggleTaskList().run(); break;
      case 'quote': chain.toggleBlockquote().run(); break;
      case 'code': chain.toggleCode().run(); break;
      case 'indent': editor.commands.keyboardShortcut('Tab'); break;
      case 'outdent': editor.commands.keyboardShortcut('Shift-Tab'); break;
      case 'undo': chain.undo().run(); break;
      case 'redo': chain.redo().run(); break;
    }
  },
  flush() { bridge()?.changed(JSON.stringify(editor.getJSON())); },
};
bridge()?.ready();
