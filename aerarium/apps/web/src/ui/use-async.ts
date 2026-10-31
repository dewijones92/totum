import { useCallback, useEffect, useEffectEvent, useState } from 'react';

export interface AsyncState<T> {
  data: T | undefined;
  error: string | null;
  loading: boolean;
  reload: () => void;
  setData: (update: (current: T | undefined) => T | undefined) => void;
}

interface Settled<T> {
  key: string;
  request: string;
  data: T | undefined;
  error: string | null;
}

export function useAsync<T>(load: () => Promise<T>, key: string): AsyncState<T> {
  const [nonce, setNonce] = useState(0);
  const [settled, setSettled] = useState<Settled<T> | null>(null);
  const request = `${key}#${String(nonce)}`;
  const run = useEffectEvent(load);

  useEffect(() => {
    let cancelled = false;
    run().then(
      (data) => {
        if (!cancelled) setSettled({ key, request, data, error: null });
      },
      (e: unknown) => {
        if (!cancelled) {
          setSettled((s) => ({
            key,
            request,
            data: s?.key === key ? s.data : undefined,
            error: e instanceof Error ? e.message : String(e),
          }));
        }
      },
    );
    return () => {
      cancelled = true;
    };
  }, [key, request]);

  const reload = useCallback(() => {
    setNonce((n) => n + 1);
  }, []);
  const setData = useCallback((update: (current: T | undefined) => T | undefined) => {
    setSettled((s) => (s ? { ...s, data: update(s.data) } : s));
  }, []);

  const current = settled?.key === key ? settled : null;
  return {
    data: current?.data,
    error: current?.error ?? null,
    loading: settled?.request !== request,
    reload,
    setData,
  };
}
