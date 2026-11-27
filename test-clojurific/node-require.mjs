// The Node transport loads the websocket package through CommonJS.
import { createRequire } from 'node:module';
globalThis.require = createRequire(import.meta.url);
