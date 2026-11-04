declare namespace Cloudflare {
  interface Env {
    [key: string]: unknown;
    WAFERAI_AUTH_SECRET?: string;
    WAFERAI_AUTH_PUBLIC_URL?: string;
    DB?: D1Database;
    BUCKET?: R2Bucket;
  }
}
