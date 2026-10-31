import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';

const VERSION = 'v1';

export class SecretBox {
  private constructor(
    private readonly key: Buffer,
    readonly ephemeral: boolean,
  ) {}

  static fromKeyFile(path: string): SecretBox {
    const key = Buffer.from(readFileSync(path, 'utf8').trim(), 'base64');
    if (key.length !== 32) {
      throw new Error(
        `The secrets key in ${path} must be 32 bytes, base64-encoded (openssl rand -base64 32)`,
      );
    }
    return new SecretBox(key, false);
  }

  static ephemeral(): SecretBox {
    return new SecretBox(randomBytes(32), true);
  }

  seal(plain: string): string {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    const data = Buffer.concat([cipher.update(plain, 'utf8'), cipher.final()]);
    return [
      VERSION,
      iv.toString('base64'),
      cipher.getAuthTag().toString('base64'),
      data.toString('base64'),
    ].join(':');
  }

  open(sealed: string): string {
    const [version, iv, tag, data] = sealed.split(':');
    if (version !== VERSION || !iv || !tag || data === undefined) {
      throw new Error('Stored secret is not in a format this version can read');
    }
    const decipher = createDecipheriv('aes-256-gcm', this.key, Buffer.from(iv, 'base64'));
    decipher.setAuthTag(Buffer.from(tag, 'base64'));
    return Buffer.concat([decipher.update(Buffer.from(data, 'base64')), decipher.final()]).toString(
      'utf8',
    );
  }
}
