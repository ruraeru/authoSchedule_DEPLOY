import { useQuery } from '@tanstack/react-query';
import { api } from '.';

export const keys = {
  store: ['store'] as const,
  staff: ['staff'] as const,
  schedules: ['schedules'] as const,
  schedule: (id: number) => ['schedules', id] as const,
  templates: ['templates'] as const,
};

export const useStore = () => useQuery({ queryKey: keys.store, queryFn: api.getStore });
export const useStaff = () => useQuery({ queryKey: keys.staff, queryFn: api.listStaff });
export const useSchedules = () => useQuery({ queryKey: keys.schedules, queryFn: api.listSchedules });
export const useSchedule = (id: number) => useQuery({ queryKey: keys.schedule(id), queryFn: () => api.getSchedule(id) });
export const useTemplates = () => useQuery({ queryKey: keys.templates, queryFn: api.listTemplates });
