import { z } from 'zod';

export const Healthz = z.object({
  ok: z.boolean(),
  version: z.string(),
  schedulerLastTickAt: z.string().nullable(),
});
export type Healthz = z.infer<typeof Healthz>;
