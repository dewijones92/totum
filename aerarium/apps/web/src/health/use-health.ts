import { useEffect } from 'react';
import { api } from '../api/client.ts';
import { useAsync } from '../ui/use-async.ts';

export function useHealth(everyMs = 30_000) {
  const state = useAsync(() => api.health(), 'health');
  const { reload } = state;
  useEffect(() => {
    const t = setInterval(reload, everyMs);
    return () => {
      clearInterval(t);
    };
  }, [reload, everyMs]);
  return state;
}
