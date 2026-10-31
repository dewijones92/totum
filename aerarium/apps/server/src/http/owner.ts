export const EMAIL_HEADER = 'x-forwarded-email';

export type OwnerVerdict =
  { kind: 'owner' } | { kind: 'no-owner-configured' } | { kind: 'stranger'; email: string | null };

export function judgeOwner(email: string | undefined, ownerEmail: string): OwnerVerdict {
  if (ownerEmail === '') return { kind: 'no-owner-configured' };
  const seen = email?.trim().toLowerCase() ?? '';
  if (seen !== '' && seen === ownerEmail.trim().toLowerCase()) return { kind: 'owner' };
  return { kind: 'stranger', email: seen === '' ? null : seen };
}
