import { useMemo } from 'react';
import type { Schedule, Staff, Store } from '../types';
import { validate } from './rules';
import { datesBetween } from './time';

export function useValidation(schedule?: Schedule | null, staff?: Staff[], store?: Store) {
  return useMemo(
    () => (schedule && staff && store ? validate(schedule, staff, store, datesBetween(schedule.periodStart, schedule.periodEnd)) : null),
    [schedule, staff, store],
  );
}
