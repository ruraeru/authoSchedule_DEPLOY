// 근무표 에디터 상태: 편집 중인 근무표와 실행 취소 이력
import { create } from 'zustand';
import type { Schedule } from '../types';

const HISTORY_LIMIT = 50;

interface EditorState {
  draft: Schedule | null;
  past: Schedule[];
  future: Schedule[];
  dirty: boolean;
  load: (s: Schedule) => void;
  /** 복사본을 받아 수정한다. 한 번 호출 = 실행 취소 한 단계 */
  edit: (fn: (s: Schedule) => void) => void;
  undo: () => void;
  redo: () => void;
  markSaved: (s: Schedule) => void;
}

export const useEditor = create<EditorState>((set, get) => ({
  draft: null,
  past: [],
  future: [],
  dirty: false,
  load: (s) => set({ draft: s, past: [], future: [], dirty: false }),
  edit: (fn) => {
    const { draft, past } = get();
    if (!draft) return;
    const next = structuredClone(draft);
    fn(next);
    set({ draft: next, past: [...past, draft].slice(-HISTORY_LIMIT), future: [], dirty: true });
  },
  undo: () => {
    const { draft, past, future } = get();
    if (!draft || !past.length) return;
    set({ draft: past[past.length - 1], past: past.slice(0, -1), future: [draft, ...future], dirty: true });
  },
  redo: () => {
    const { draft, past, future } = get();
    if (!draft || !future.length) return;
    set({ draft: future[0], past: [...past, draft], future: future.slice(1), dirty: true });
  },
  markSaved: (s) => set({ draft: s, dirty: false }),
}));
