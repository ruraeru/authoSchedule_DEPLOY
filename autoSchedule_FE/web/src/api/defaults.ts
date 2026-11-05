// 처음 실행할 때의 매장 기본값. 매장 설정 화면에서 바꾼다.
import { partBreakFor } from '../lib/rules';
import type { Store } from '../types';

export const defaultStore: Store = {
  id: 1,
  name: '내 매장',
  branch: '',
  sizeMode: 'AUTO',
  size: 'UNDER_5',
  open: '08:00',
  close: '22:00',
  weekendOpen: '08:00',
  weekendClose: '22:00',
  parts: [
    { id: 'OPEN', name: '오픈', tone: 'open', start: '08:00', end: '12:00', breakMinutes: partBreakFor(240), required: 1 },
    { id: 'MIDDLE', name: '미들', tone: 'middle', start: '12:00', end: '17:00', breakMinutes: partBreakFor(300), required: 1 },
    { id: 'CLOSE', name: '마감', tone: 'close', start: '17:00', end: '22:00', breakMinutes: partBreakFor(300), required: 1 },
  ],
  minimumWage: 10320,
};
