import { createServer } from 'node:http';
import { createReadStream, existsSync, readFileSync } from 'node:fs';
import { extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PolymeteoApiService } from './service.js';
import { CopyTradeWebPushManager, validatePushSubscriptionShape } from './copyTradeWebPushManager.js';

const __filename = fileURLToPath(import.meta.url);
const __dirname = resolve(__filename, '..');
const ROOT = resolve(__dirname, '..', '..', '..');
const WEB_DIR = resolve(ROOT, 'web');

const PORT = Number(process.env.PORT || 8788);
const service = new PolymeteoApiService();
const copyTradeWebPush = new CopyTradeWebPushManager({ service });

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json; charset=utf-8'
};

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload, null, 2);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body),
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
  });
  res.end(body);
}

function sendText(res, statusCode, text) {
  res.writeHead(statusCode, {
    'Content-Type': 'text/plain; charset=utf-8',
    'Content-Length': Buffer.byteLength(text),
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*'
  });
  res.end(text);
}

function notFound(res, path) {
  sendJson(res, 404, { error: 'Not found', path });
}

async function serveStatic(res, requestPath) {
  const relative = requestPath.replace(/^\/app\/?/, '');
  const normalized = normalize(relative || 'index.html').replace(/^\/+/, '');
  const absolute = resolve(WEB_DIR, normalized);

  if (!absolute.startsWith(WEB_DIR)) {
    return notFound(res, requestPath);
  }

  let finalPath = absolute;
  if (existsSync(finalPath) && !extname(finalPath)) {
    finalPath = join(finalPath, 'index.html');
  }
  if (!existsSync(finalPath)) {
    if (!extname(finalPath)) {
      finalPath = join(WEB_DIR, 'index.html');
    } else {
      return notFound(res, requestPath);
    }
  }

  const ext = extname(finalPath).toLowerCase();
  const contentType = MIME_TYPES[ext] || 'application/octet-stream';
  const isServiceWorker = finalPath.endsWith('/sw.js') || finalPath.endsWith('\\sw.js');
  res.writeHead(200, {
    'Content-Type': contentType,
    'Cache-Control': (ext === '.html' || isServiceWorker) ? 'no-store' : 'public, max-age=3600'
  });
  createReadStream(finalPath).pipe(res);
}

function writeSse(res, event, payload) {
  res.write(`event: ${event}\n`);
  res.write(`data: ${JSON.stringify(payload)}\n\n`);
}

function parseBoolParam(raw, fallback = false) {
  if (raw == null) return fallback;
  const value = String(raw).trim().toLowerCase();
  if (['1', 'true', 'yes', 'on', 'si'].includes(value)) return true;
  if (['0', 'false', 'no', 'off'].includes(value)) return false;
  return fallback;
}

let cachedDefaultWallet = undefined;

function readDefaultPolymarketWalletAddress() {
  if (cachedDefaultWallet !== undefined) return cachedDefaultWallet;
  const fromEnv = String(process.env.POLYMARKET_WALLET_ADDRESS || '').trim().toLowerCase();
  if (fromEnv) {
    cachedDefaultWallet = fromEnv;
    return cachedDefaultWallet;
  }
  try {
    const localPropsPath = resolve(ROOT, 'local.properties');
    if (!existsSync(localPropsPath)) {
      cachedDefaultWallet = null;
      return cachedDefaultWallet;
    }
    const text = readFileSync(localPropsPath, 'utf8');
    const line = text.split(/\r?\n/).find((l) => l.trim().startsWith('POLYMARKET_WALLET_ADDRESS='));
    if (!line) {
      cachedDefaultWallet = null;
      return cachedDefaultWallet;
    }
    const value = line.split('=').slice(1).join('=').trim().replace(/^["']|["']$/g, '').toLowerCase();
    cachedDefaultWallet = value || null;
    return cachedDefaultWallet;
  } catch {
    cachedDefaultWallet = null;
    return cachedDefaultWallet;
  }
}

function isJsonContentType(req) {
  return String(req.headers['content-type'] || '').toLowerCase().includes('application/json');
}

async function readJsonBody(req, { maxBytes = 256_000 } = {}) {
  if (!isJsonContentType(req)) {
    throw new Error('Content-Type application/json requerido');
  }
  const chunks = [];
  let total = 0;
  for await (const chunk of req) {
    total += chunk.length;
    if (total > maxBytes) throw new Error('Body demasiado grande');
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString('utf8');
  if (!text.trim()) return {};
  try {
    return JSON.parse(text);
  } catch {
    throw new Error('JSON invalido');
  }
}

const server = createServer(async (req, res) => {
  try {
    const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const path = url.pathname;

    if (req.method === 'OPTIONS') {
      res.writeHead(204, {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Content-Type',
        'Access-Control-Allow-Methods': 'GET,POST,OPTIONS'
      });
      res.end();
      return;
    }

    if (req.method === 'POST' && path === '/api/v1/webpush/subscribe') {
      try {
        const body = await readJsonBody(req);
        validatePushSubscriptionShape(body);
        const result = await copyTradeWebPush.subscribe({
          username: body.username,
          subscription: body.subscription || body,
          copyTradeConfig: body.copyTradeConfig || body.config || {}
        });
        return sendJson(res, 200, result);
      } catch (error) {
        return sendJson(res, 400, { error: 'Subscribe Web Push no valido', details: String(error?.message || error) });
      }
    }

    if (req.method === 'POST' && path === '/api/v1/webpush/unsubscribe') {
      try {
        const body = await readJsonBody(req);
        validatePushSubscriptionShape(body);
        const result = await copyTradeWebPush.unsubscribe({
          subscription: body.subscription || body
        });
        return sendJson(res, 200, result);
      } catch (error) {
        return sendJson(res, 400, { error: 'Unsubscribe Web Push no valido', details: String(error?.message || error) });
      }
    }

    if (req.method === 'POST' && path === '/api/v1/webpush/test') {
      try {
        const body = await readJsonBody(req);
        validatePushSubscriptionShape(body);
        const result = await copyTradeWebPush.sendTest({
          username: body.username || 'test-user',
          subscription: body.subscription || body
        });
        return sendJson(res, 200, result);
      } catch (error) {
        return sendJson(res, 400, { error: 'Test Web Push no valido', details: String(error?.message || error) });
      }
    }

    if (req.method !== 'GET') {
      return sendJson(res, 405, { error: 'Method not allowed' });
    }

    if (path === '/' || path === '/healthz') {
      const health = await service.health();
      return sendJson(res, 200, {
        ...health,
        port: PORT
      });
    }

    if (path === '/api/v1/meta') {
      return sendJson(res, 200, await service.getMeta());
    }

    if (path === '/api/v1/cities/manifest') {
      return sendJson(res, 200, await service.getCitiesManifestPayload());
    }

    if (path === '/api/v1/cities') {
      return sendJson(res, 200, await service.getCitiesPayload());
    }

    if (path === '/api/v1/user-intel') {
      const username = String(url.searchParams.get('username') || '').trim();
      const limitRaw = Number(url.searchParams.get('limit') || '40');
      const limit = Number.isFinite(limitRaw) ? Math.max(10, Math.min(120, limitRaw)) : 40;
      if (!username) return sendJson(res, 400, { error: 'username query param requerido' });
      try {
        return sendJson(res, 200, await service.getUserIntelPayload(username, { limit }));
      } catch (error) {
        return sendJson(res, 502, { error: 'Intel usuario no disponible', details: String(error?.message || error) });
      }
    }

    if (path === '/api/v1/account/default-wallet') {
      const wallet = readDefaultPolymarketWalletAddress();
      return sendJson(res, 200, {
        ok: !!wallet,
        walletAddress: wallet || null,
        source: wallet
          ? (process.env.POLYMARKET_WALLET_ADDRESS ? 'env' : 'local.properties')
          : null
      });
    }

    if (path === '/api/v1/account') {
      const walletQuery = String(url.searchParams.get('wallet') || '').trim().toLowerCase();
      const walletAddress = walletQuery || readDefaultPolymarketWalletAddress();
      const limitRaw = Number(url.searchParams.get('limit') || '30');
      const limit = Number.isFinite(limitRaw) ? Math.max(10, Math.min(120, limitRaw)) : 30;
      if (!walletAddress) {
        return sendJson(res, 400, {
          error: 'wallet query param requerido o POLYMARKET_WALLET_ADDRESS en entorno/local.properties'
        });
      }
      try {
        const payload = await service.getPolymarketAccountPayload(walletAddress, { limit });
        return sendJson(res, 200, {
          ...payload,
          walletSource: walletQuery ? 'query' : (process.env.POLYMARKET_WALLET_ADDRESS ? 'env' : 'local.properties')
        });
      } catch (error) {
        return sendJson(res, 502, { error: 'Cuenta Polymarket no disponible', details: String(error?.message || error) });
      }
    }

    if (path === '/api/v1/webpush/config') {
      return sendJson(res, 200, await copyTradeWebPush.getConfig());
    }

    if (path === '/api/v1/webpush/subscriptions') {
      return sendJson(res, 200, await copyTradeWebPush.listSubscriptions());
    }

    if (path === '/api/v1/webpush/audit') {
      const limitRaw = Number(url.searchParams.get('limit') || '100');
      const limit = Number.isFinite(limitRaw) ? Math.max(1, Math.min(500, limitRaw)) : 100;
      return sendJson(res, 200, await copyTradeWebPush.getAuditLog({ limit }));
    }

    if (path === '/api/v1/copytrade/stream') {
      const username = String(url.searchParams.get('username') || '').trim();
      if (!username) return sendJson(res, 400, { error: 'username query param requerido' });

      const pollMsRaw = Number(url.searchParams.get('pollMs') || '3000');
      const limitRaw = Number(url.searchParams.get('limit') || '20');
      const pollMs = Number.isFinite(pollMsRaw) ? Math.max(2000, Math.min(15000, pollMsRaw)) : 3000;
      const limit = Number.isFinite(limitRaw) ? Math.max(5, Math.min(120, limitRaw)) : 20;
      const config = {
        alertBuys: parseBoolParam(url.searchParams.get('alertBuys'), true),
        alertSells: parseBoolParam(url.searchParams.get('alertSells'), true),
        alertOthers: parseBoolParam(url.searchParams.get('alertOthers'), false),
        onlyWeather: parseBoolParam(url.searchParams.get('onlyWeather'), true),
        onlyTrackedCities: parseBoolParam(url.searchParams.get('onlyTrackedCities'), false),
        buyMinUsdc: Number(url.searchParams.get('buyMinUsdc') || '0'),
        sellMinUsdc: Number(url.searchParams.get('sellMinUsdc') || '0')
      };

      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-store',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*',
        'X-Accel-Buffering': 'no'
      });
      res.write(': copytrade connected\n\n');
      const abortController = new AbortController();
      req.on('close', () => abortController.abort());

      try {
        await service.streamCopyTrade({
          username,
          pollMs,
          limit,
          config,
          signal: abortController.signal,
          onEvent: (event, payload) => writeSse(res, event, payload)
        });
      } catch (error) {
        writeSse(res, 'error', { message: String(error?.message || error) });
      } finally {
        if (!res.writableEnded) res.end();
      }
      return;
    }

    if (path === '/api/v1/cities/_ids') {
      return sendJson(res, 200, await service.getCityIdsPayload());
    }

    if (path === '/api/v1/cities/stream') {
      const paceMsRaw = Number(url.searchParams.get('paceMs') || '70');
      const paceMs = Number.isFinite(paceMsRaw) ? Math.max(0, Math.min(500, paceMsRaw)) : 70;
      res.writeHead(200, {
        'Content-Type': 'text/event-stream; charset=utf-8',
        'Cache-Control': 'no-store',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*'
      });
      res.write(': connected\n\n');
      const abortController = new AbortController();
      req.on('close', () => abortController.abort());
      try {
        await service.streamCities({
          paceMs,
          signal: abortController.signal,
          onEvent: (event, payload) => writeSse(res, event, payload)
        });
      } catch (error) {
        writeSse(res, 'error', { message: String(error?.message || error) });
      } finally {
        if (!res.writableEnded) {
          res.end();
        }
      }
      return;
    }

    const cityMatch = path.match(/^\/api\/v1\/cities\/([a-z0-9-]+)$/);
    if (cityMatch) {
      const cityId = cityMatch[1];
      const payload = await service.getCityDetailPayload(cityId);
      if (!payload) return notFound(res, path);
      return sendJson(res, 200, payload);
    }

    if (path === '/api/v1/openapi-lite') {
      return sendJson(res, 200, {
        version: '0.1',
        endpoints: [
          { method: 'GET', path: '/healthz', description: 'Estado del servicio' },
          { method: 'GET', path: '/api/v1/meta', description: 'Metadata del backend web (hibrido)' },
          { method: 'GET', path: '/api/v1/cities', description: 'Grid CIUDADES (resumen)' },
          { method: 'GET', path: '/api/v1/cities/:cityId', description: 'DETALLE de ciudad + horizontes + mercados (motor web real/hibrido)' },
          { method: 'GET', path: '/api/v1/user-intel?username=@user', description: 'Intel usuario Polymarket (lectura pública)' },
          { method: 'GET', path: '/api/v1/account?wallet=0x... (wallet opcional si hay default)', description: 'Cuenta Polymarket (lectura pública por wallet)' },
          { method: 'GET', path: '/api/v1/account/default-wallet', description: 'Wallet Polymarket por defecto (env/local.properties)' },
          { method: 'GET', path: '/api/v1/copytrade/stream?...', description: 'CopyTrade realtime (SSE, actividad publica Polymarket)' },
          { method: 'GET', path: '/api/v1/webpush/config', description: 'Config Web Push (VAPID/public key, disponibilidad)' },
          { method: 'GET', path: '/api/v1/webpush/subscriptions', description: 'Suscripciones Web Push registradas (auditable)' },
          { method: 'GET', path: '/api/v1/webpush/audit?limit=100', description: 'Historial de eventos Web Push / copytrade server-side' },
          { method: 'POST', path: '/api/v1/webpush/subscribe', description: 'Suscribir Web Push y activar monitor server-side' },
          { method: 'POST', path: '/api/v1/webpush/unsubscribe', description: 'Desuscribir Web Push' },
          { method: 'POST', path: '/api/v1/webpush/test', description: 'Enviar push de prueba a una suscripcion' },
          { method: 'GET', path: '/app', description: 'Frontend web local (CIUDADES + DETALLE)' }
        ]
      });
    }

    if (path.startsWith('/app')) {
      return serveStatic(res, path);
    }

    if (path === '/favicon.ico') {
      res.writeHead(204);
      res.end();
      return;
    }

    return notFound(res, path);
  } catch (error) {
    console.error('Request error', error);
    return sendJson(res, 500, { error: 'Internal server error', details: String(error?.message || error) });
  }
});

server.listen(PORT, async () => {
  let mode = String(process.env.POLYMETEO_WEB_PROVIDER || 'hybrid-metar');
  let sourceInfo = 'desconocida';
  try {
    const health = await service.health();
    mode = health.mode || mode;
    sourceInfo = health.provider?.source || sourceInfo;
  } catch {
    // no-op: keep fallback values for banner
  }
  const banner = [
    'polyMeteo Web API (Fase B1 scaffold)',
    `- API:  http://localhost:${PORT}/api/v1/cities`,
    `- Web:  http://localhost:${PORT}/app`,
    `- Provider mode: ${mode} (${sourceInfo})`
  ].join('\n');
  console.log(banner);
});

async function gracefulShutdown(signalName) {
  try {
    await copyTradeWebPush.shutdown();
  } catch (error) {
    console.error(`Shutdown error (${signalName})`, error);
  } finally {
    process.exit(0);
  }
}

process.on('SIGINT', () => { void gracefulShutdown('SIGINT'); });
process.on('SIGTERM', () => { void gracefulShutdown('SIGTERM'); });
