import http from 'k6/http';
import encoding from 'k6/encoding';
import crypto from 'k6/crypto';
import { Counter, Trend } from 'k6/metrics';
import { sleep } from 'k6';

// DB migration rehearsal: API-only workload, with writes weighted at 80% by default.
// Each VU uses a test identity and run-tagged disposable account(s); pre-existing accounts are never mutated.
// K6_USERS_FILE maps one distinct user to each VU using a locally signed short-lived JWT; ACCESS_TOKEN and Kakao-code modes remain available.
// Order creation uses a low buy limit so it remains pending instead of intentionally generating fills.
const baseUrl = (__ENV.API_BASE_URL || '').replace(/\/+$/, '');
const accessToken = (__ENV.ACCESS_TOKEN || __ENV.BEARER_TOKEN || '').replace(/^Bearer\s+/i, '');
const authCodesFile = __ENV.KAKAO_AUTH_CODES_FILE || '';
const kakaoAuthorizationCodes = authCodesFile ? JSON.parse(open(authCodesFile)) : [];
const usersFilePath = __ENV.K6_USERS_FILE || '';
const userTokenRecords = usersFilePath ? JSON.parse(open(usersFilePath)) : [];
const versionManifestPath = __ENV.VERSION_MANIFEST_FILE || '';
const versionManifest = versionManifestPath ? JSON.parse(open(versionManifestPath)) : null;
const requireVersionManifest = (__ENV.REQUIRE_VERSION_MANIFEST || 'false').toLowerCase() === 'true';
const confirmWrites = __ENV.CONFIRM_WRITES || '';
const csrfDisabled = (__ENV.AUTH_CSRF_DISABLED || 'false').toLowerCase() === 'true';
const rate = Number.parseInt(__ENV.TOTAL_RPS || '1', 10);
const duration = __ENV.DURATION || '3m';
const timeout = __ENV.REQUEST_TIMEOUT || '15s';
const retry202Once = (__ENV.RETRY_202_ONCE || 'false').toLowerCase() === 'true';
const orderStatusPollIntervalMs = Number.parseInt(__ENV.ORDER_STATUS_POLL_INTERVAL_MS || '2000', 10);
const preAllocatedVus = Number.parseInt(__ENV.PREALLOCATED_VUS || '1', 10);
const maxVus = Number.parseInt(__ENV.MAX_VUS || '1', 10);
const accountsPerUser = Number.parseInt(__ENV.ACCOUNTS_PER_USER || (usersFilePath ? '2' : '1'), 10);
const initialCapital = Number.parseInt(__ENV.INITIAL_CAPITAL || '1000000', 10);
const orderStockCode = __ENV.ORDER_STOCK_CODE || '005930';
const orderLimitPrice = Number.parseInt(__ENV.ORDER_LIMIT_PRICE || '1', 10);
const watchlistStockCodes = [...new Set((__ENV.WATCHLIST_STOCK_CODES || '005930,000660,035420,035720,051910')
  .split(',').map((value) => value.trim()).filter(Boolean))];
const testUserIdRaw = (__ENV.TEST_USER_ID || '').trim();
const clockOffsetRaw = (__ENV.CLOCK_OFFSET_MS || '').trim();
const clockOffsetParsed = clockOffsetRaw ? Number.parseFloat(clockOffsetRaw) : NaN;
const k6ClockOffsetMs = Number.isFinite(clockOffsetParsed) ? clockOffsetParsed : null;
function jwtPayload(token) {
  try {
    const parts = token.split('.');
    if (parts.length !== 3 || parts.some((part) => !part)) return null;
    const payload = JSON.parse(encoding.b64decode(parts[1], 'rawurl', 's'));
    return payload && typeof payload === 'object' && !Array.isArray(payload) ? payload : null;
  } catch (_) {
    return null;
  }
}
function jwtSubject(token) {
  const payload = jwtPayload(token);
  return payload && /^\d+$/.test(String(payload.sub)) ? Number(payload.sub) : null;
}
const accessTokenUserId = accessToken ? jwtSubject(accessToken) : null;
const testUserId = testUserIdRaw ? (/^\d+$/.test(testUserIdRaw) ? Number(testUserIdRaw) : null) : accessTokenUserId;
// k6 init-context globals are copied into each VU. A tag generated here can differ per VU,
// so require a caller-provided stable tag shared by setup, all VUs, and the cleanup manifest.
const runTag = String(__ENV.RUN_TAG || '').toLowerCase();

const weights = {
  read: Number.parseInt(__ENV.READ_PERCENT || '20', 10),
  account_create: Number.parseInt(__ENV.ACCOUNT_CREATE_PERCENT || '3', 10),
  account_rename: Number.parseInt(__ENV.ACCOUNT_RENAME_PERCENT || '20', 10),
  competition_create: Number.parseInt(__ENV.COMPETITION_CREATE_PERCENT || '2', 10),
  watchlist_mutation: Number.parseInt(__ENV.WATCHLIST_PERCENT || '20', 10),
  order_mutation: Number.parseInt(__ENV.ORDER_PERCENT || '35', 10),
};

const readOperations = [
  'user_read',
  'account_list_read',
  'order_list_read',
  'order_request_status_read',
  'order_state_reconcile_read',
  'watchlist_list_read',
];
const operations = [
  ...readOperations,
  'account_create',
  'account_rename',
  'competition_create',
  'watchlist_add',
  'watchlist_delete',
  'order_create',
  'order_cancel',
];

// Hash non-secret test settings only. Tokens, JWT secrets, cookies, and auth
// codes are intentionally excluded from the report.
const runtimeConfiguration = {
  runTag,
  apiBaseUrl: baseUrl,
  duration,
  requestTimeout: timeout,
  targetRps: rate,
  preAllocatedVus,
  maxVus,
  accountsPerUser,
  initialCapital,
  orderStockCode,
  orderLimitPrice,
  orderStatusPollIntervalMs,
  retryOrderQueue202Once: retry202Once,
  csrfDisabled,
  clockOffsetMs: k6ClockOffsetMs,
  authMode: usersFilePath ? 'offline_signed_user_access_tokens'
    : (kakaoAuthorizationCodes.length > 0 ? 'one_time_kakao_codes' : 'existing_access_token'),
  participantCount: usersFilePath ? userTokenRecords.length
    : (kakaoAuthorizationCodes.length > 0 ? kakaoAuthorizationCodes.length : 1),
  watchlistStockCodes,
  workloadPercentages: weights,
};
const runtimeConfigurationSha256 = crypto.sha256(JSON.stringify(runtimeConfiguration), 'hex');

if (!baseUrl) throw new Error('API_BASE_URL is required');
if (requireVersionManifest) {
  const requiredVersionKeys = [
    'captured_at_utc', 'be_commit_sha', 'be_checkout_commit_sha',
    'be_checkout_matches_deployed', 'be_task_definition', 'be_image_digests',
    'be_runtime_config_sha256', 'lambda_runtime_config_sha256',
    'cloud_rehearsal_sources_clean', 'queue_snapshot_source_sha256', 'queue_snapshot_deployed_sha256',
    'switchover_source_sha256', 'switchover_deployed_sha256',
    'switchover_config_deployed_sha256',
    'lambda_function_name', 'lambda_deployed_code_sha256',
    'lambda_qualifier', 'lambda_resolved_version', 'lambda_package_matches_deployed',
    'k6_source_sha256', 'k6_deployed_sha256',
  ];
  if (!versionManifest || requiredVersionKeys.some((key) => !versionManifest[key])) {
    throw new Error('REQUIRE_VERSION_MANIFEST=true needs a complete VERSION_MANIFEST_FILE with deployed BE, switchover, Lambda, and k6 versions');
  }
  if (versionManifest.be_checkout_commit_sha !== versionManifest.be_commit_sha
      || versionManifest.be_checkout_matches_deployed !== true) {
    throw new Error('BE source checkout does not match the deployed ECS image commit');
  }
  if (versionManifest.switchover_source_sha256 !== versionManifest.switchover_deployed_sha256
      || versionManifest.queue_snapshot_source_sha256 !== versionManifest.queue_snapshot_deployed_sha256
      || versionManifest.k6_source_sha256 !== versionManifest.k6_deployed_sha256) {
    throw new Error('Deployed switchover or k6 file hash differs from the reviewed source hash');
  }
  if (versionManifest.cloud_rehearsal_sources_clean !== true) {
    throw new Error('Cloud switchover/replay source files have uncommitted changes; use the reviewed committed sources');
  }
}
if (!/^[a-z0-9]{1,6}$/.test(runTag)) throw new Error('Set RUN_TAG to a unique 1-6 character lowercase alphanumeric value');
const configuredAuthModes = [Boolean(accessToken), Boolean(authCodesFile), Boolean(usersFilePath)].filter(Boolean).length;
if (configuredAuthModes !== 1) throw new Error('Set exactly one of ACCESS_TOKEN, KAKAO_AUTH_CODES_FILE, or K6_USERS_FILE');
if (!Array.isArray(kakaoAuthorizationCodes) || kakaoAuthorizationCodes.some((code) => typeof code !== 'string' || !code.trim())) {
  throw new Error('KAKAO_AUTH_CODES_FILE must contain a JSON array of non-empty, fresh Kakao authorization-code strings');
}
if (authCodesFile && kakaoAuthorizationCodes.length !== maxVus) {
  throw new Error('Provide exactly one fresh Kakao authorization code per MAX_VUS');
}
if (usersFilePath) {
  if (!Array.isArray(userTokenRecords) || userTokenRecords.length !== maxVus) {
    throw new Error('K6_USERS_FILE must contain exactly one user token record per MAX_VUS');
  }
  const seenUserIds = new Set();
  for (let index = 0; index < userTokenRecords.length; index += 1) {
    const record = userTokenRecords[index];
    const token = String(record && record.access_token || '').replace(/^Bearer\s+/i, '');
    const userId = Number(record && record.user_id);
    if (!Number.isInteger(userId) || userId < 1 || !token) {
      throw new Error('K6_USERS_FILE record ' + (index + 1) + ' needs numeric user_id and access_token');
    }
    const claims = jwtPayload(token);
    if (jwtSubject(token) !== userId) {
      throw new Error('K6_USERS_FILE record ' + (index + 1) + ' user_id must match the token numeric sub claim');
    }
    if (claims.type !== 'access' || claims.actor !== 'USER' || claims.k6_test !== true
        || typeof claims.iss !== 'string' || !claims.iss
        || !Number.isFinite(Number(claims.exp)) || Number(claims.exp) <= Math.floor(Date.now() / 1000)) {
      throw new Error('K6_USERS_FILE record ' + (index + 1) + ' must be an unexpired K6 test access JWT with issuer, type=access, actor=USER, and k6_test=true');
    }
    if (seenUserIds.has(userId)) throw new Error('K6_USERS_FILE must contain distinct users');
    seenUserIds.add(userId);
  }
}
if (accessToken && !accessTokenUserId) throw new Error('ACCESS_TOKEN must be a JWT with a numeric sub claim for safe cleanup scoping');
if (accessToken && testUserId !== accessTokenUserId) throw new Error('TEST_USER_ID, when provided, must equal the numeric sub claim in ACCESS_TOKEN');
if (confirmWrites !== 'YES') throw new Error('This test creates persistent DB records. Set CONFIRM_WRITES=YES to continue.');
if (!Number.isInteger(rate) || rate < 1) throw new Error('TOTAL_RPS must be a positive integer');
if (!Number.isInteger(maxVus) || maxVus < 1 || !Number.isInteger(preAllocatedVus) || preAllocatedVus < 1 || preAllocatedVus > maxVus) {
  throw new Error('PREALLOCATED_VUS and MAX_VUS must be positive integers, with PREALLOCATED_VUS <= MAX_VUS');
}
if (!Number.isInteger(accountsPerUser) || accountsPerUser < 1 || accountsPerUser > 20) {
  throw new Error('ACCOUNTS_PER_USER must be an integer between 1 and 20');
}
if (!Number.isInteger(initialCapital) || initialCapital < 1000000 || initialCapital > 100000000) {
  throw new Error('INITIAL_CAPITAL must be between 1000000 and 100000000');
}
if (!/^\d{6}$/.test(orderStockCode)) throw new Error('ORDER_STOCK_CODE must be a six-digit stock code');
if (!Number.isInteger(orderLimitPrice) || orderLimitPrice < 1) throw new Error('ORDER_LIMIT_PRICE must be a positive integer');
if (!Number.isInteger(orderStatusPollIntervalMs) || orderStatusPollIntervalMs < 250) {
  throw new Error('ORDER_STATUS_POLL_INTERVAL_MS must be an integer of at least 250');
}
if (Object.values(weights).some((value) => !Number.isInteger(value) || value < 0)
    || Object.values(weights).reduce((sum, value) => sum + value, 0) !== 100) {
  throw new Error('Workload percentages must be non-negative integers whose sum is exactly 100');
}
if (weights.watchlist_mutation > 0 && watchlistStockCodes.length < 1) {
  throw new Error('Provide at least one WATCHLIST_STOCK_CODES value');
}

const authLoginAttempts = new Counter('dbmig_auth_login_attempts');
const authLoginSuccess = new Counter('dbmig_auth_login_2xx');
const authLoginFailures = new Counter('dbmig_auth_login_non_2xx_or_transport');
const authLoginLatency = new Trend('dbmig_auth_login_latency_ms', true);
const authLogoutAttempts = new Counter('dbmig_auth_logout_attempts');
const authLogoutSuccess = new Counter('dbmig_auth_logout_2xx');
const authLogoutFailures = new Counter('dbmig_auth_logout_non_2xx_or_transport');
const apiAttempts = new Counter('dbmig_api_attempts');
const apiSuccess = new Counter('dbmig_api_2xx');
const apiFailures = new Counter('dbmig_api_non_2xx_or_transport');
const readAttempts = new Counter('dbmig_read_attempts');
const readSuccess = new Counter('dbmig_read_2xx');
const readFailures = new Counter('dbmig_read_non_2xx_or_transport');
const writeAttempts = new Counter('dbmig_write_attempts');
const writeSuccess = new Counter('dbmig_write_2xx');
const writeFailures = new Counter('dbmig_write_non_2xx_or_transport');
const write4xx = new Counter('dbmig_write_http_4xx');
const writeHttp401 = new Counter('dbmig_write_http_401');
const writeHttp404 = new Counter('dbmig_write_http_404');
const writeHttp409 = new Counter('dbmig_write_http_409');
const writeOther4xx = new Counter('dbmig_write_http_other_4xx');
const write5xx = new Counter('dbmig_write_http_5xx');
const writeTransportErrors = new Counter('dbmig_write_transport_errors');
const workloadHttp401 = new Counter('dbmig_workload_http_401');
const workloadHttp404 = new Counter('dbmig_workload_http_404');
const workloadHttp409 = new Counter('dbmig_workload_http_409');
const workloadHttpOther4xx = new Counter('dbmig_workload_http_other_4xx');
const workloadHttp5xx = new Counter('dbmig_workload_http_5xx');
const workloadTransportErrors = new Counter('dbmig_workload_transport_errors');
const writeQueued202 = new Counter('dbmig_write_http_202_queued');
const databaseReplayQueued202 = new Counter('dbmig_database_replay_http_202_queued');
const writeSynchronous2xx = new Counter('dbmig_write_synchronous_http_2xx');
const writeLogicalCommands = new Counter('dbmig_write_logical_commands');
const writeRetryAttempts = new Counter('dbmig_write_retry_attempts');
const writeRetryQueued202 = new Counter('dbmig_write_retry_http_202_queued');
const writeRetry2xx = new Counter('dbmig_write_retry_http_2xx');
const orderQueueAccepted = new Counter('dbmig_order_queue_accepted');
const orderQueueProcessingPolls = new Counter('dbmig_order_queue_processing_polls');
const orderQueueCreated = new Counter('dbmig_order_queue_created');
const orderQueueRejected = new Counter('dbmig_order_queue_rejected');
const orderReplayQueued = new Counter('dbmig_order_replay_queued');
const orderReplayResolved = new Counter('dbmig_order_replay_resolved');
const orderUnknown202 = new Counter('dbmig_order_unknown_http_202');
const orderStateReconciliations = new Counter('dbmig_order_state_reconciliations');
const orderStateReconciliationFailures = new Counter('dbmig_order_state_reconciliation_failures');
const latency = new Trend('dbmig_api_latency_ms', true);
const operationMetrics = {};
for (const operation of operations) {
  operationMetrics[operation] = {
    attempts: new Counter(`dbmig_${operation}_attempts`),
    success: new Counter(`dbmig_${operation}_2xx`),
    failures: new Counter(`dbmig_${operation}_non_2xx_or_transport`),
    http401: new Counter(`dbmig_${operation}_http_401`),
    http404: new Counter(`dbmig_${operation}_http_404`),
    http409: new Counter(`dbmig_${operation}_http_409`),
    httpOther4xx: new Counter(`dbmig_${operation}_http_other_4xx`),
    http5xx: new Counter(`dbmig_${operation}_http_5xx`),
    transport: new Counter(`dbmig_${operation}_transport_errors`),
  };
}

const usePerUserPacing = usersFilePath !== '';
const workloadScenario = usePerUserPacing
  ? { executor: 'constant-vus', vus: maxVus, duration, exec: 'runWorkload' }
  : { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, preAllocatedVUs, maxVUs, exec: 'runWorkload' };

export const options = {
  discardResponseBodies: false,
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  setupTimeout: '15m',
  teardownTimeout: '5m',
  scenarios: {
    db_migration_write_heavy: workloadScenario,
  },
};

// Each VU uses disposable account(s) created for its assigned user before measured load starts.
let accountId = null;
let pendingOrderId = null;
let pendingOrderAccountId = null;
let queuedOrderRequestId = null;
let queuedOrderAccountId = null;
let orderResolutionNeeded = false;
let orderResolutionKind = null;
let orderResolutionAccountId = null;
let orderResolutionSinceMs = 0;
let orderResolutionOrderId = null;
let orderResolutionFromReplay = false;
let lastOrderStatusPollAt = 0;
let ownsWatchlistEntry = false;
let watchlistDisabled = false;

export function setup() {
  const state = { csrfToken: '', csrfCookie: '', identities: [] };
  try {
    return setupWithState(state);
  } catch (error) {
    // k6 does not call teardown when setup throws. Revoke any successful one-time logins here.
    if (kakaoAuthorizationCodes.length > 0) {
      for (const identity of state.identities) {
        if (!identity.refreshToken) continue;
        const cookieParts = [`refresh_token=${identity.refreshToken}`];
        if (state.csrfCookie) cookieParts.push(`XSRF-TOKEN=${state.csrfCookie}`);
        const headers = { Cookie: cookieParts.join('; '), Accept: 'application/json' };
        if (!csrfDisabled) headers['X-XSRF-TOKEN'] = state.csrfToken;
        try {
          const response = http.post(`${baseUrl}/api/v1/auth/logout`, null, { headers, timeout });
          console.log(JSON.stringify({ event: 'k6_setup_abort_logout_audit', participant: identity.participant, status: response.status, requestId: responseRequestId(response) }));
        } catch (_) {
          console.log(JSON.stringify({ event: 'k6_setup_abort_logout_audit', participant: identity.participant, status: 0, requestId: 'missing' }));
        }
      }
    }
    throw error;
  }
}

function setupWithState(state) {
  let csrfToken = '';
  let csrfCookie = '';
  if (!csrfDisabled) {
    // This security endpoint does not call a business API or require the database.
    const csrfResponse = http.get(`${baseUrl}/api/v1/auth/csrf`, {
      tags: { name: 'GET /api/v1/auth/csrf', kind: 'setup' },
      timeout,
    });
    if (csrfResponse.status !== 200) throw new Error(`CSRF setup failed with HTTP ${csrfResponse.status}`);

    const body = csrfResponse.json();
    const cookie = csrfResponse.cookies['XSRF-TOKEN'];
    csrfCookie = cookie && cookie.length ? cookie[0].value : '';
    if (!body || !body.token || !csrfCookie) {
      throw new Error('Could not read token and XSRF-TOKEN cookie from GET /api/v1/auth/csrf');
    }
    csrfToken = body.token;
  }
  state.csrfToken = csrfToken;
  state.csrfCookie = csrfCookie;

  const identities = state.identities;
  if (kakaoAuthorizationCodes.length > 0) {
    // One fresh, single-use Kakao authorization code per VU. Codes and tokens are never logged.
    for (let index = 0; index < kakaoAuthorizationCodes.length; index += 1) {
      const csrfCookiePart = csrfCookie ? [`XSRF-TOKEN=${csrfCookie}`] : [];
      const loginHeaders = { Accept: 'application/json', 'Content-Type': 'application/json' };
      if (csrfCookiePart.length > 0) loginHeaders.Cookie = csrfCookiePart.join('; ');
      if (!csrfDisabled) loginHeaders['X-XSRF-TOKEN'] = csrfToken;

      authLoginAttempts.add(1);
      let loginResponse = null;
      try {
        loginResponse = http.post(`${baseUrl}/api/v1/auth/login`, JSON.stringify({
          authorization_code: kakaoAuthorizationCodes[index],
        }), {
          headers: loginHeaders,
          tags: { name: 'POST /api/v1/auth/login', kind: 'setup', operation: 'auth_login' },
          timeout,
        });
      } catch (_) {
        authLoginFailures.add(1);
        console.log(JSON.stringify({ event: 'k6_auth_login_audit', participant: index + 1, status: 0, outcome: 'transport_error', requestId: 'missing' }));
        throw new Error(`Kakao login transport failed for participant ${index + 1}`);
      }
      authLoginLatency.add(loginResponse.timings.duration);

      const loginCookie = (name) => {
        const values = loginResponse.cookies && loginResponse.cookies[name];
        return values && values.length > 0 ? values[values.length - 1].value : '';
      };
      let loginBody = null;
      try { loginBody = loginResponse.json(); } catch (_) { /* Report status only; never print response body. */ }
      const token = loginCookie('access_token');
      const refreshToken = loginCookie('refresh_token');
      const userId = loginBody && Number(loginBody.user_id);
      const loginOk = loginResponse.status >= 200 && loginResponse.status < 300
        && token && refreshToken && Number.isInteger(userId) && userId > 0;
      if (token && refreshToken) {
        identities.push({ accessToken: token, refreshToken, userId, participant: index + 1 });
      }
      if (!loginOk) {
        authLoginFailures.add(1);
        console.log(JSON.stringify({ event: 'k6_auth_login_audit', participant: index + 1, status: loginResponse.status, outcome: `http_${loginResponse.status}`, requestId: responseRequestId(loginResponse) }));
        throw new Error(`Kakao login failed for participant ${index + 1} (HTTP ${loginResponse.status}); provide a fresh code`);
      }
      authLoginSuccess.add(1);
      console.log(JSON.stringify({ event: 'k6_auth_login_audit', participant: index + 1, status: loginResponse.status, outcome: 'success', requestId: responseRequestId(loginResponse) }));

      recordCleanupData('identity', { participant: index + 1, user_id: userId });
    }
  } else if (usersFilePath) {
    for (let index = 0; index < userTokenRecords.length; index += 1) {
      const record = userTokenRecords[index];
      const token = String(record.access_token).replace(/^Bearer\s+/i, '');
      const userId = Number(record.user_id);
      identities.push({ accessToken: token, refreshToken: '', userId, participant: index + 1, accountIds: [] });
      recordCleanupData('identity', { participant: index + 1, user_id: userId });
    }
  } else {
    for (let index = 0; index < maxVus; index += 1) {
      identities.push({ accessToken, refreshToken: '', userId: testUserId, participant: index + 1, accountIds: [] });
      recordCleanupData('identity', { participant: index + 1, user_id: testUserId });
    }
  }

  // Validate every user and ensure onboarding is complete before creating test data.
  for (const identity of identities) {
    const accountResponse = http.get(baseUrl + '/api/v1/users/me/accounts', {
      headers: { Cookie: 'access_token=' + identity.accessToken, Accept: 'application/json' },
      tags: { name: 'GET /api/v1/users/me/accounts', kind: 'setup' },
      timeout,
    });
    if (accountResponse.status !== 200) throw new Error('Could not load participant ' + identity.participant + ' accounts (HTTP ' + accountResponse.status + ')');
    const accounts = accountResponse.json();
    if (!Array.isArray(accounts)) throw new Error('Account list response must be a JSON array');
    if (accounts.length === 0) throw new Error('Participant ' + identity.participant + ' has not completed onboarding');
  }

  // Choose only watchlist symbols that were absent before this run. The cleanup manifest
  // records the candidate first, so an add that commits but loses its response is recoverable.
  if (weights.watchlist_mutation > 0) {
    const usedByUser = new Map();
    for (const identity of identities) {
      const response = http.get(`${baseUrl}/api/v2/users/me/watchlist`, {
        headers: { Cookie: `access_token=${identity.accessToken}`, Accept: 'application/json' },
        tags: { name: 'GET /api/v2/users/me/watchlist', kind: 'setup' },
        timeout,
      });
      if (response.status !== 200) throw new Error(`Could not inspect participant ${identity.participant}'s watchlist (HTTP ${response.status})`);
      let body = null;
      try { body = response.json(); } catch (_) { /* Validate the payload before any workload writes. */ }
      if (!body || !Array.isArray(body.watchlists)) throw new Error(`Participant ${identity.participant}'s watchlist response is invalid`);
      const existing = new Set(body.watchlists.map((item) => item && String(item.stock_code || '')).filter(Boolean));
      const used = usedByUser.get(identity.userId) || new Set();
      const stockCode = watchlistStockCodes.find((code) => !existing.has(code) && !used.has(code));
      if (!stockCode) throw new Error(`No unused WATCHLIST_STOCK_CODES remain for participant ${identity.participant}; add more symbols or reduce MAX_VUS`);
      identity.watchlistStockCode = stockCode;
      used.add(stockCode);
      usedByUser.set(identity.userId, used);
      recordCleanupData('watchlist_candidate', { user_id: identity.userId, stock_code: stockCode });
    }
  }

  // Create run-tagged disposable accounts for every participant before measured load.
  for (const identity of identities) {
    identity.accountIds = [];
    for (let accountIndex = 1; accountIndex <= accountsPerUser; accountIndex += 1) {
      const accountName = ('A' + runTag + 'S' + identity.participant.toString(36) + accountIndex.toString(36)).slice(0, 20);
      const cookieParts = ['access_token=' + identity.accessToken];
      if (csrfCookie) cookieParts.push('XSRF-TOKEN=' + csrfCookie);
      const headers = { Cookie: cookieParts.join('; '), Accept: 'application/json', 'Content-Type': 'application/json' };
      if (!csrfDisabled) headers['X-XSRF-TOKEN'] = csrfToken;
      let response = null;
      try {
        response = http.post(baseUrl + '/api/v1/users/me/accounts', JSON.stringify({
          account_name: accountName,
          initial_capital: initialCapital,
        }), {
          headers,
          tags: { name: 'POST /api/v1/users/me/accounts', kind: 'setup', operation: 'disposable_account_bootstrap' },
          timeout,
        });
      } catch (_) {
        console.log(JSON.stringify({ event: 'k6_setup_account_audit', participant: identity.participant, accountIndex, accountName, status: 0, outcome: 'transport_error', requestId: 'missing' }));
        throw new Error('Disposable account bootstrap transport failed for participant ' + identity.participant + ', account ' + accountIndex);
      }
      console.log(JSON.stringify({
        event: 'k6_setup_account_audit',
        participant: identity.participant,
        accountIndex,
        accountName,
        status: response.status,
        outcome: 'http_' + response.status,
        requestId: responseRequestId(response),
      }));
      if (response.status !== 201) throw new Error('Disposable account bootstrap failed for participant ' + identity.participant + ', account ' + accountIndex + ' (HTTP ' + response.status + ')');
      let created = null;
      try { created = response.json(); } catch (_) { /* The tagged account remains recoverable from MySQL. */ }
      const createdAccountId = created && Number(created.account_id);
      if (!Number.isInteger(createdAccountId) || createdAccountId < 1) {
        throw new Error('Disposable account response did not include account_id for participant ' + identity.participant + ', account ' + accountIndex);
      }
      identity.accountIds.push(createdAccountId);
      recordCleanupData('account', {
        user_id: identity.userId,
        account_id: createdAccountId,
        account_name: created.account_name || accountName,
      });
    }
    identity.accountId = identity.accountIds[0];
  }

  return { csrfToken, csrfCookie, identities };
}

function requestHeaders(data, isWrite, writeId, idempotencyKey) {
  const identity = data.identities[__VU - 1];
  const cookieParts = [`access_token=${identity.accessToken}`];
  if (data.csrfCookie) cookieParts.push(`XSRF-TOKEN=${data.csrfCookie}`);
  const headers = {
    Cookie: cookieParts.join('; '),
    Accept: 'application/json',
  };
  if (isWrite) {
    headers['Content-Type'] = 'application/json';
    if (!csrfDisabled) headers['X-XSRF-TOKEN'] = data.csrfToken;
    headers['X-DB-Migration-Write-Id'] = writeId;
    headers['Idempotency-Key'] = idempotencyKey;
  }
  return headers;
}

function responseRequestId(response) {
  if (!response || !response.headers) return 'missing';
  for (const key of Object.keys(response.headers)) {
    if (key.toLowerCase() === 'x-request-id') return response.headers[key];
  }
  return 'missing';
}

function recordCleanupData(type, fields) {
  console.log(`DBMIG_CLEANUP ${JSON.stringify({ version: 2, run_tag: runTag, type, ...fields })}`);
}

function responseBody(response) {
  if (!response) return null;
  try { return response.json(); } catch (_) { return null; }
}

function classify(operation, response, audit) {
  const isRead = readOperations.includes(operation);
  const status = response ? response.status : 0;
  const success = status >= 200 && status < 300;
  const elapsed = response && response.timings ? response.timings.duration : 0;
  const metric = operationMetrics[operation];

  if (status === 0) {
    workloadTransportErrors.add(1, { operation });
    metric.transport.add(1);
  } else if (status === 401) {
    workloadHttp401.add(1, { operation });
    metric.http401.add(1);
  } else if (status === 404) {
    workloadHttp404.add(1, { operation });
    metric.http404.add(1);
  } else if (status === 409) {
    workloadHttp409.add(1, { operation });
    metric.http409.add(1);
  } else if (status >= 400 && status < 500) {
    workloadHttpOther4xx.add(1, { operation });
    metric.httpOther4xx.add(1);
  } else if (status >= 500 && status < 600) {
    workloadHttp5xx.add(1, { operation });
    metric.http5xx.add(1);
  }

  apiAttempts.add(1, { operation });
  metric.attempts.add(1);
  latency.add(elapsed, { operation });
  if (success) {
    apiSuccess.add(1, { operation });
    metric.success.add(1);
    if (isRead) {
      readAttempts.add(1, { operation });
      readSuccess.add(1, { operation });
    } else {
      writeAttempts.add(1, { operation });
      writeSuccess.add(1, { operation });
      if (status === 202) {
        writeQueued202.add(1, { operation });
        if (audit.attempt > 1) writeRetryQueued202.add(1, { operation });
      } else {
        writeSynchronous2xx.add(1, { operation });
        if (audit.attempt > 1) writeRetry2xx.add(1, { operation });
      }
    }
  } else {
    apiFailures.add(1, { operation });
    metric.failures.add(1);
    if (isRead) {
      readAttempts.add(1, { operation });
      readFailures.add(1, { operation });
    } else {
      writeAttempts.add(1, { operation });
      writeFailures.add(1, { operation });
      if (status === 0) writeTransportErrors.add(1);
      else if (status >= 400 && status < 500) {
        write4xx.add(1);
        if (status === 401) writeHttp401.add(1);
        else if (status === 404) writeHttp404.add(1);
        else if (status === 409) writeHttp409.add(1);
        else writeOther4xx.add(1);
      }
      else if (status >= 500 && status < 600) write5xx.add(1);
    }
  }
  if (!isRead && audit.attempt > 1) writeRetryAttempts.add(1, { operation });

  // writeId joins this k6 event to DB transaction completion logs; requestId is server generated.
  // Never print tokens or request/response bodies.
  const body = status === 202 || status >= 400 || operation === 'order_create' ? responseBody(response) : null;
  const replayRequestId = body && (body.replayRequestId || body.replay_request_id);
  if (!isRead && status === 202 && replayRequestId) databaseReplayQueued202.add(1, { operation });
  if (operation === 'order_create') {
    if (status === 202 && body && typeof body.request_id === 'string' && body.request_id) {
      if (queuedOrderRequestId !== body.request_id) orderQueueAccepted.add(1);
      queuedOrderRequestId = body.request_id;
      queuedOrderAccountId = audit.accountId;
      orderResolutionNeeded = false;
      orderResolutionKind = null;
      orderResolutionSinceMs = Date.parse(audit.startedAt) || Date.now();
      orderResolutionAccountId = audit.accountId;
    } else if (status === 202 && body && (body.replayRequestId || body.replay_request_id)) {
      orderReplayQueued.add(1);
      orderResolutionNeeded = true;
      orderResolutionKind = 'create';
      orderResolutionAccountId = audit.accountId;
      orderResolutionSinceMs = Date.parse(audit.startedAt) || Date.now();
      orderResolutionFromReplay = true;
      orderResolutionOrderId = null;
    } else if (status === 202 || status === 0 || status >= 500) {
      if (status === 202) orderUnknown202.add(1);
      orderResolutionNeeded = true;
      orderResolutionKind = 'create';
      orderResolutionAccountId = audit.accountId;
      orderResolutionSinceMs = Date.parse(audit.startedAt) || Date.now();
      orderResolutionFromReplay = false;
      orderResolutionOrderId = null;
    }
  } else if (operation === 'order_cancel' && (status === 202 || status === 0 || status >= 500)) {
    if (status === 202 && body && (body.replayRequestId || body.replay_request_id)) {
      orderReplayQueued.add(1);
    } else if (status === 202) orderUnknown202.add(1);
    orderResolutionNeeded = true;
    orderResolutionKind = 'cancel';
    orderResolutionAccountId = audit.accountId;
    orderResolutionSinceMs = Date.parse(audit.startedAt) || Date.now();
    orderResolutionOrderId = audit.orderId || pendingOrderId;
    orderResolutionFromReplay = status === 202 && Boolean(body && (body.replayRequestId || body.replay_request_id));
  }

  if (!isRead || !success || (__ENV.AUDIT_ALL_REQUESTS || 'false').toLowerCase() === 'true') {
    console.log(JSON.stringify({
      event: 'k6_api_audit',
      timestamp: audit.completedAt || new Date().toISOString(),
      requestStartedAt: audit.startedAt || null,
      requestCompletedAt: audit.completedAt || null,
      runTag,
      operation,
      status,
      outcome: !response ? 'transport_error' : (status === 202 ? 'http_202_queued' : `http_${status}`),
      attempt: audit.attempt || 1,
      writeId: audit.writeId || undefined,
      idempotencyKey: audit.idempotencyKey || undefined,
      requestId: responseRequestId(response),
      replayRequestId: body && (body.replayRequestId || body.replay_request_id),
      orderRequestId: body && (body.request_id || undefined),
      targetAccountId: audit.accountId || undefined,
      targetOrderId: audit.orderId || undefined,
      resultOrderId: operation === 'order_create' && body ? Number(body.order_id) || undefined : undefined,
      resultOrderStatus: operation === 'order_create' && body ? body.order_status || undefined : undefined,
      errorCode: body && typeof body.code === 'string' && /^[A-Z0-9_]{1,64}$/.test(body.code) ? body.code : undefined,
    }));
  }
}

function call(data, operation, method, path, body) {
  const isWrite = !readOperations.includes(operation);
  // These identifiers are accepted by the BE validators (underscores are not).
  const logicalId = `${runTag}-v${__VU}-i${__ITER}-${operation.replace(/_/g, '-')}`;
  const idempotencyKey = isWrite ? logicalId : '';
  const orderPath = path.match(/\/api\/v1\/accounts\/(\d+)\/orders(?:\/(\d+))?/);
  const bodyText = body === null ? null : JSON.stringify(body);
  if (isWrite) writeLogicalCommands.add(1, { operation });
  for (let attempt = 1; attempt <= (retry202Once ? 2 : 1); attempt += 1) {
    const audit = {
      attempt,
      startedAt: new Date().toISOString(),
      writeId: isWrite ? `${logicalId}-a${attempt}` : '',
      idempotencyKey,
      accountId: orderPath ? Number(orderPath[1]) : null,
      orderId: orderPath && orderPath[2] ? Number(orderPath[2]) : null,
    };
    let response = null;
    try {
      response = http.request(method, `${baseUrl}${path}`, bodyText, {
        headers: requestHeaders(data, isWrite, audit.writeId, idempotencyKey),
        tags: { name: `${method} ${operation}`, operation, kind: isWrite ? 'write' : 'read' },
        timeout,
      });
      audit.completedAt = new Date().toISOString();
    } catch (_) {
      audit.completedAt = new Date().toISOString();
      classify(operation, null, audit);
      return null;
    }
    classify(operation, response, audit);
    // Retry only the typed order queue's 202, whose request_id is also the order
    // eventId and FIFO deduplication ID. Never retry generic DB-write replay 202s:
    // that consumer has no completion/status API and may receive duplicate work.
    const queuedOrderRequest = response.status === 202
      && operation === 'order_create'
      && bodyHasOrderRequestId(response);
    if (!(retry202Once && attempt === 1 && queuedOrderRequest)) return response;
  }
  return null;
}

function bodyHasOrderRequestId(response) {
  const body = responseBody(response);
  return Boolean(body && typeof body.request_id === 'string' && body.request_id);
}

function compactName(prefix) {
  return `${prefix}${runTag}${__VU.toString(36).slice(-2)}${__ITER.toString(36).slice(-5)}`.slice(0, 20);
}

function createAccount(data) {
  const name = compactName('A');
  const response = call(data, 'account_create', 'POST', '/api/v1/users/me/accounts', {
    account_name: name,
    initial_capital: initialCapital,
  });
  if (response && response.status === 201) {
    try {
      const created = response.json();
      if (created && Number.isInteger(Number(created.account_id))) {
        accountId = Number(created.account_id);
        const identity = data.identities[__VU - 1];
        identity.accountIds = identity.accountIds || [];
        identity.accountIds.push(accountId);
        identity.accountId = accountId;
        if (identity.userId) {
          recordCleanupData('account', {
            user_id: identity.userId,
            account_id: accountId,
            account_name: created.account_name || name,
          });
        }
      }
    } catch (_) {
      // The response is still counted; a later iteration can retry account bootstrap.
    }
  }
  return response;
}

function renameAccount(data) {
  if (accountId === null) return createAccount(data);
  const identity = data.identities[__VU - 1];
  const accountIds = identity.accountIds || [accountId];
  const targetAccountId = accountIds[Math.floor(Math.random() * accountIds.length)] || accountId;
  const response = call(data, 'account_rename', 'PATCH', '/api/v1/users/me/accounts/' + targetAccountId, {
    account_name: compactName('R'),
  });
  if (response && response.status >= 200 && response.status < 300) {
    try {
      identity.accountName = response.json().account_name || identity.accountName;
    } catch (_) {
      identity.accountName = compactName('R');
    }
  }
  return response;
}

function createCompetition(data) {
  const nowKst = new Date(Date.now() + 9 * 60 * 60 * 1000);
  const start = new Date(Date.UTC(nowKst.getUTCFullYear(), nowKst.getUTCMonth(), nowKst.getUTCDate()));
  const end = new Date(start.getTime());
  end.setUTCDate(end.getUTCDate() + 3);
  const endDate = end.toISOString().slice(0, 10);
  const startDate = start.toISOString().slice(0, 10);
  const competitionName = compactName('C');
  const response = call(data, 'competition_create', 'POST', '/api/v2/users/me/competitions', {
    competition_name: competitionName,
    competition_type: 'user_vs_ai',
    start_date: startDate,
    end_date: endDate,
    initial_capital: initialCapital,
    ai_participant_count: 1,
  });
  if (response && response.status === 201) {
    try {
      const created = response.json();
      const identity = data.identities[__VU - 1];
      if (identity.userId && Number.isInteger(Number(created.competition_id))) {
        recordCleanupData('competition', {
          user_id: identity.userId,
          competition_id: Number(created.competition_id),
          competition_name: competitionName,
        });
      }
    } catch (_) {
      // The response is counted; the run tag remains in the competition name for manual reconciliation.
    }
  }
  return response;
}

function mutateWatchlist(data) {
  const stockCode = data.identities[__VU - 1].watchlistStockCode;
  if (watchlistDisabled || !stockCode) return renameAccount(data);

  if (ownsWatchlistEntry) {
    const response = call(data, 'watchlist_delete', 'DELETE', `/api/v2/users/me/watchlist/${stockCode}`, null);
    if (response && response.status >= 200 && response.status < 300) ownsWatchlistEntry = false;
    return response;
  }

  const response = call(data, 'watchlist_add', 'POST', '/api/v2/users/me/watchlist', { stock_code: stockCode });
  if (response && response.status === 201) {
    ownsWatchlistEntry = true;
    const identity = data.identities[__VU - 1];
    if (identity.userId) recordCleanupData('watchlist', { user_id: identity.userId, stock_code: stockCode });
  }
  // 200 means the symbol was already registered; never delete a pre-existing user's entry.
  if (response && response.status === 200) watchlistDisabled = true;
  return response;
}

function orderPollAllowed() {
  const now = Date.now();
  if (now - lastOrderStatusPollAt < orderStatusPollIntervalMs) return false;
  lastOrderStatusPollAt = now;
  return true;
}

function saveTrackedOrder(data, order, account) {
  const id = Number(order && order.order_id);
  if (!Number.isInteger(id) || id < 1) return false;
  pendingOrderId = id;
  pendingOrderAccountId = account;
  const identity = data.identities[__VU - 1];
  if (identity.userId) {
    recordCleanupData('order', { user_id: identity.userId, account_id: account, order_id: id });
  }
  return true;
}

function clearOrderResolution() {
  orderResolutionNeeded = false;
  orderResolutionKind = null;
  orderResolutionAccountId = null;
  orderResolutionSinceMs = 0;
  orderResolutionOrderId = null;
  orderResolutionFromReplay = false;
}

function pollQueuedOrder(data) {
  if (!queuedOrderRequestId || !queuedOrderAccountId || !orderPollAllowed()) return null;
  const requestId = queuedOrderRequestId;
  const requestAccountId = queuedOrderAccountId;
  const orderSubmittedAt = orderResolutionSinceMs || Date.now();
  const response = call(data, 'order_request_status_read', 'GET',
    `/api/v1/accounts/${requestAccountId}/order-requests/${encodeURIComponent(requestId)}`, null);
  if (!response || response.status !== 200) {
    orderStateReconciliationFailures.add(1);
    console.log(JSON.stringify({ event: 'k6_order_state_audit', kind: 'order_queue', requestId, accountId: requestAccountId,
      timestamp: new Date().toISOString(), status: response ? response.status : 0, outcome: 'status_lookup_failed', httpRequestId: responseRequestId(response) }));
    return response;
  }
  const result = responseBody(response);
  const status = String(result && result.status || '').toLowerCase();
  if (status === 'created') {
    orderQueueCreated.add(1);
    queuedOrderRequestId = null;
    queuedOrderAccountId = null;
    if (!saveTrackedOrder(data, result, requestAccountId)) {
      orderStateReconciliationFailures.add(1);
      orderResolutionNeeded = true;
      orderResolutionKind = 'create';
      orderResolutionAccountId = requestAccountId;
      orderResolutionSinceMs = orderSubmittedAt;
      orderResolutionOrderId = null;
      orderResolutionFromReplay = false;
    }
    console.log(JSON.stringify({ event: 'k6_order_state_audit', kind: 'order_queue', requestId, accountId: requestAccountId,
      timestamp: new Date().toISOString(), orderId: Number(result.order_id) || null, status, httpRequestId: responseRequestId(response) }));
  } else if (status === 'rejected') {
    orderQueueRejected.add(1);
    queuedOrderRequestId = null;
    queuedOrderAccountId = null;
    console.log(JSON.stringify({ event: 'k6_order_state_audit', kind: 'order_queue', requestId, accountId: requestAccountId,
      timestamp: new Date().toISOString(), status, httpRequestId: responseRequestId(response) }));
  } else if (status === 'processing' || status === 'accepted') {
    orderQueueProcessingPolls.add(1);
  } else {
    orderStateReconciliationFailures.add(1);
  }
  return response;
}

function reconcileOrderFromHistory(data) {
  if (!orderResolutionNeeded || !orderPollAllowed()) return null;
  const requestAccountId = orderResolutionAccountId;
  const response = call(data, 'order_state_reconcile_read', 'GET',
    `/api/v1/accounts/${requestAccountId}/orders?limit=100&order_side=buy&stock_code=${orderStockCode}`, null);
  if (!response || response.status !== 200) {
    orderStateReconciliationFailures.add(1);
    return response;
  }
  const body = responseBody(response);
  if (!body || !Array.isArray(body.orders)) {
    orderStateReconciliationFailures.add(1);
    return response;
  }

  const since = orderResolutionSinceMs - 30_000;
  const candidates = body.orders.filter((order) => {
    if (orderResolutionOrderId !== null && Number(order.order_id) !== orderResolutionOrderId) return false;
    if (String(order.order_side || '').toLowerCase() !== 'buy'
        || String(order.order_type || '').toLowerCase() !== 'limit'
        || String(order.stock_code || '') !== orderStockCode
        || Number(order.quantity) !== 1
        || Number(order.limit_price) !== orderLimitPrice) return false;
    const createdAt = Date.parse(order.created_at || '');
    return Number.isFinite(createdAt) && createdAt >= since;
  }).sort((left, right) => Date.parse(right.created_at) - Date.parse(left.created_at));

  if (candidates.length === 0) {
    console.log(JSON.stringify({ event: 'k6_order_state_audit', kind: 'db_replay_history', accountId: requestAccountId,
      timestamp: new Date().toISOString(), orderId: orderResolutionOrderId, outcome: 'not_found_yet',
      httpRequestId: responseRequestId(response) }));
    return response;
  }
  const order = candidates[0];
  const status = String(order.order_status || '').toLowerCase();
  if (orderResolutionKind === 'cancel') {
    if (status === 'pending') {
      console.log(JSON.stringify({ event: 'k6_order_state_audit', kind: 'db_replay_history', accountId: requestAccountId,
        timestamp: new Date().toISOString(), orderId: Number(order.order_id), status, outcome: 'cancel_still_pending',
        httpRequestId: responseRequestId(response) }));
      return response; // Cancel outcome is still unresolved; never resend it blindly.
    }
    pendingOrderId = null;
    pendingOrderAccountId = null;
    if (orderResolutionFromReplay) orderReplayResolved.add(1);
  } else if (status === 'pending') {
    if (!saveTrackedOrder(data, order, requestAccountId)) {
      orderStateReconciliationFailures.add(1);
      return response;
    }
    if (orderResolutionFromReplay) orderReplayResolved.add(1);
  } else {
    // The order is already terminal; do not create a replacement order.
    if (orderResolutionFromReplay) orderReplayResolved.add(1);
  }
  orderStateReconciliations.add(1);
  console.log(JSON.stringify({ event: 'k6_order_state_audit', kind: 'db_replay_history', accountId: requestAccountId,
    timestamp: new Date().toISOString(), orderId: Number(order.order_id), status, httpRequestId: responseRequestId(response) }));
  clearOrderResolution();
  return response;
}

function mutateOrder(data) {
  if (queuedOrderRequestId) return pollQueuedOrder(data);
  if (orderResolutionNeeded) return reconcileOrderFromHistory(data);

  if (pendingOrderId !== null) {
    const response = call(data, 'order_cancel', 'PATCH',
      `/api/v1/accounts/${pendingOrderAccountId}/orders/${pendingOrderId}`, { status: 'cancelled' });
    if (response && (response.status === 204 || response.status === 404 || response.status === 409)) {
      pendingOrderId = null;
      pendingOrderAccountId = null;
    }
    return response;
  }

  const identity = data.identities[__VU - 1];
  const accountIds = identity.accountIds || [accountId];
  accountId = accountIds[Math.floor(Math.random() * accountIds.length)] || accountId;
  const startedAt = Date.now();
  const response = call(data, 'order_create', 'POST', '/api/v1/accounts/' + accountId + '/orders', {
    stock_code: orderStockCode,
    order_side: 'buy',
    order_type: 'limit',
    limit_price: orderLimitPrice,
    quantity: 1,
  });
  if (response && response.status === 201) {
    const created = responseBody(response);
    if (!saveTrackedOrder(data, created, accountId)) {
      orderResolutionNeeded = true;
      orderResolutionKind = 'create';
      orderResolutionAccountId = accountId;
      orderResolutionSinceMs = startedAt;
      orderResolutionFromReplay = false;
    }
  }
  return response;
}

function readRequest(data) {
  const choices = [
    ['user_read', 'GET', '/api/v1/users/me'],
    ['account_list_read', 'GET', '/api/v1/users/me/accounts'],
    ['order_list_read', 'GET', `/api/v1/accounts/${accountId}/orders?limit=10`],
    ['watchlist_list_read', 'GET', '/api/v2/users/me/watchlist'],
  ];
  const choice = choices[Math.floor(Math.random() * choices.length)];
  return call(data, choice[0], choice[1], choice[2], null);
}

function chooseWorkload() {
  const selected = Math.random() * 100;
  let boundary = weights.read;
  if (selected < boundary) return 'read';
  boundary += weights.account_create;
  if (selected < boundary) return 'account_create';
  boundary += weights.account_rename;
  if (selected < boundary) return 'account_rename';
  boundary += weights.competition_create;
  if (selected < boundary) return 'competition_create';
  boundary += weights.watchlist_mutation;
  if (selected < boundary) return 'watchlist_mutation';
  return 'order_mutation';
}

export function teardown(data) {
  if (kakaoAuthorizationCodes.length === 0) return;
  for (let index = 0; index < data.identities.length; index += 1) {
    const identity = data.identities[index];
    const cookieParts = [`refresh_token=${identity.refreshToken}`];
    if (data.csrfCookie) cookieParts.push(`XSRF-TOKEN=${data.csrfCookie}`);
    const headers = { Cookie: cookieParts.join('; '), Accept: 'application/json' };
    if (!csrfDisabled) headers['X-XSRF-TOKEN'] = data.csrfToken;
    authLogoutAttempts.add(1);
    let response = null;
    try {
      response = http.post(`${baseUrl}/api/v1/auth/logout`, null, {
        headers,
        tags: { name: 'POST /api/v1/auth/logout', kind: 'teardown', operation: 'auth_logout' },
        timeout,
      });
    } catch (_) {
      authLogoutFailures.add(1);
      console.log(JSON.stringify({ event: 'k6_auth_logout_audit', participant: index + 1, status: 0, outcome: 'transport_error', requestId: 'missing' }));
      continue;
    }
    if (response.status >= 200 && response.status < 300) authLogoutSuccess.add(1);
    else authLogoutFailures.add(1);
    console.log(JSON.stringify({ event: 'k6_auth_logout_audit', participant: index + 1, status: response.status, outcome: `http_${response.status}`, requestId: responseRequestId(response) }));
  }
}

export function runWorkload(data) {
  const identity = data.identities[__VU - 1];
  if (!identity) throw new Error('No identity assigned to VU ' + __VU);
  if (accountId === null) accountId = identity.accountId;
  if (!Number.isInteger(accountId) || accountId < 1) throw new Error('No disposable account assigned to VU ' + __VU);

  if (usePerUserPacing && __ITER === 0) sleep((__VU - 1) / rate);
  const iterationStartedAt = Date.now();
  switch (chooseWorkload()) {
    case 'read': readRequest(data); break;
    case 'account_create': createAccount(data); break;
    case 'account_rename': renameAccount(data); break;
    case 'competition_create': createCompetition(data); break;
    case 'watchlist_mutation': mutateWatchlist(data); break;
    case 'order_mutation': mutateOrder(data); break;
    default: readRequest(data);
  }
  if (usePerUserPacing) {
    const targetIntervalMs = (maxVus / rate) * 1000;
    sleep(Math.max(0, targetIntervalMs - (Date.now() - iterationStartedAt)) / 1000);
  }
}

function metricCount(data, name) {
  const metric = data.metrics[name];
  return metric && metric.values && Number.isFinite(metric.values.count) ? metric.values.count : 0;
}

function percent(part, total) {
  return total === 0 ? 0 : Number(((part / total) * 100).toFixed(2));
}

export function handleSummary(data) {
  const total = metricCount(data, 'dbmig_api_attempts');
  const reads = metricCount(data, 'dbmig_read_attempts');
  const writes = metricCount(data, 'dbmig_write_attempts');
  const uncertainWrites = metricCount(data, 'dbmig_write_http_5xx')
    + metricCount(data, 'dbmig_write_transport_errors');
  const perOperation = {};
  for (const operation of operations) {
    perOperation[operation] = {
      attempted: metricCount(data, `dbmig_${operation}_attempts`),
      http_2xx: metricCount(data, `dbmig_${operation}_2xx`),
      non_2xx_or_transport_error: metricCount(data, `dbmig_${operation}_non_2xx_or_transport`),
      http_401: metricCount(data, `dbmig_${operation}_http_401`),
      http_404: metricCount(data, `dbmig_${operation}_http_404`),
      http_409: metricCount(data, `dbmig_${operation}_http_409`),
      other_http_4xx: metricCount(data, `dbmig_${operation}_http_other_4xx`),
      http_5xx: metricCount(data, `dbmig_${operation}_http_5xx`),
      transport_errors: metricCount(data, `dbmig_${operation}_transport_errors`),
    };
  }

  const report = {
    run_tag: runTag,
    configuration: {
      duration,
      request_timeout: timeout,
      login_mode: usersFilePath ? 'offline_signed_user_access_tokens' : (kakaoAuthorizationCodes.length > 0 ? 'one_time_kakao_codes' : 'existing_access_token'),
      login_participants: kakaoAuthorizationCodes.length,
      token_file_participants: usersFilePath ? userTokenRecords.length : 0,
      accounts_per_user: accountsPerUser,
      target_api_requests_per_second: rate,
      preallocated_vus: preAllocatedVus,
      max_vus: maxVus,
      expected_read_percent: weights.read,
      expected_write_percent: 100 - weights.read,
      workload_percentages: weights,
      retry_order_queue_202_once: retry202Once,
      order_status_poll_interval_ms: orderStatusPollIntervalMs,
      order_stock_code: orderStockCode,
      order_limit_price: orderLimitPrice,
      disposable_setup_accounts: maxVus,
    },
    artifact_versions: versionManifest || { status: 'not supplied; set VERSION_MANIFEST_FILE for a reproducible rehearsal record' },
    environment_config_sha256: runtimeConfigurationSha256,
    clock_sync: {
      source: (__ENV.CLOCK_SYNC_SOURCE || '').trim() || 'not_recorded',
      ntp_offset_ms: k6ClockOffsetMs,
      synchronized: (__ENV.CLOCK_SYNCHRONIZED || '').toLowerCase() === 'true',
    },
    authentication: {
      login_attempted: metricCount(data, 'dbmig_auth_login_attempts'),
      login_2xx: metricCount(data, 'dbmig_auth_login_2xx'),
      login_failures: metricCount(data, 'dbmig_auth_login_non_2xx_or_transport'),
      logout_attempted: metricCount(data, 'dbmig_auth_logout_attempts'),
      logout_2xx: metricCount(data, 'dbmig_auth_logout_2xx'),
      logout_failures: metricCount(data, 'dbmig_auth_logout_non_2xx_or_transport'),
      login_latency_ms: data.metrics.dbmig_auth_login_latency_ms?.values || {},
    },
    measured_workload_requests: {
      attempted: total,
      includes_physical_retry_attempts: true,
      http_2xx: metricCount(data, 'dbmig_api_2xx'),
      non_2xx_or_transport_error: metricCount(data, 'dbmig_api_non_2xx_or_transport'),
      http_401: metricCount(data, 'dbmig_workload_http_401'),
      http_404: metricCount(data, 'dbmig_workload_http_404'),
      http_409: metricCount(data, 'dbmig_workload_http_409'),
      other_http_4xx: metricCount(data, 'dbmig_workload_http_other_4xx'),
      http_5xx: metricCount(data, 'dbmig_workload_http_5xx'),
      transport_errors: metricCount(data, 'dbmig_workload_transport_errors'),
      requested_rate_rps: rate,
      observed_rate_rps: data.state && Number.isFinite(data.state.testRunDurationMs)
        && data.state.testRunDurationMs > 0
        ? Number((total / (data.state.testRunDurationMs / 1000)).toFixed(3)) : null,
      response_latency_ms: data.metrics.dbmig_api_latency_ms?.values || {},
      failure_percent: percent(metricCount(data, 'dbmig_api_non_2xx_or_transport'), total),
      availability_error_count: metricCount(data, 'dbmig_workload_http_401')
        + metricCount(data, 'dbmig_workload_http_5xx')
        + metricCount(data, 'dbmig_workload_transport_errors'),
      http_401_valid_jwt_anomaly_count: metricCount(data, 'dbmig_workload_http_401'),
      http_404_409_scenario_review_count: metricCount(data, 'dbmig_workload_http_404')
        + metricCount(data, 'dbmig_workload_http_409'),
    },
    reads: {
      attempted: reads,
      http_2xx: metricCount(data, 'dbmig_read_2xx'),
      non_2xx_or_transport_error: metricCount(data, 'dbmig_read_non_2xx_or_transport'),
      share_of_measured_api_requests_percent: percent(reads, total),
    },
    writes: {
      attempted: writes,
      logical_commands_started: metricCount(data, 'dbmig_write_logical_commands'),
      http_2xx: metricCount(data, 'dbmig_write_2xx'),
      http_202_accepted_responses: metricCount(data, 'dbmig_write_http_202_queued'),
      database_replay_queue_202_responses: metricCount(data, 'dbmig_database_replay_http_202_queued'),
      synchronous_http_2xx: metricCount(data, 'dbmig_write_synchronous_http_2xx'),
      non_2xx_or_transport_error: metricCount(data, 'dbmig_write_non_2xx_or_transport'),
      http_4xx: metricCount(data, 'dbmig_write_http_4xx'),
      http_401: metricCount(data, 'dbmig_write_http_401'),
      http_404: metricCount(data, 'dbmig_write_http_404'),
      http_409: metricCount(data, 'dbmig_write_http_409'),
      other_http_4xx: metricCount(data, 'dbmig_write_http_other_4xx'),
      http_5xx: metricCount(data, 'dbmig_write_http_5xx'),
      transport_errors: metricCount(data, 'dbmig_write_transport_errors'),
      availability_error_count: metricCount(data, 'dbmig_workload_http_401')
        + metricCount(data, 'dbmig_workload_http_5xx')
        + metricCount(data, 'dbmig_workload_transport_errors'),
      http_401_valid_jwt_anomaly_count: metricCount(data, 'dbmig_write_http_401'),
      uncertain_5xx_or_transport_count: uncertainWrites,
      share_of_measured_api_requests_percent: percent(writes, total),
      uncertain_5xx_or_transport_percent: percent(uncertainWrites, writes),
      retry_attempts: metricCount(data, 'dbmig_write_retry_attempts'),
      retry_202_accepted_responses: metricCount(data, 'dbmig_write_retry_http_202_queued'),
      retry_2xx_responses: metricCount(data, 'dbmig_write_retry_http_2xx'),
    },
    order_state: {
      queue_accepted_requests: metricCount(data, 'dbmig_order_queue_accepted'),
      queue_processing_status_polls: metricCount(data, 'dbmig_order_queue_processing_polls'),
      queue_created_terminal_results: metricCount(data, 'dbmig_order_queue_created'),
      queue_rejected_terminal_results: metricCount(data, 'dbmig_order_queue_rejected'),
      db_replay_queued_order_actions: metricCount(data, 'dbmig_order_replay_queued'),
      unknown_order_202_responses: metricCount(data, 'dbmig_order_unknown_http_202'),
      db_replay_order_actions_reconciled_from_history: metricCount(data, 'dbmig_order_replay_resolved'),
      db_replay_order_actions_unresolved_or_pending_lower_bound: Math.max(0,
        metricCount(data, 'dbmig_order_replay_queued') - metricCount(data, 'dbmig_order_replay_resolved')),
      history_reconciliation_matches: metricCount(data, 'dbmig_order_state_reconciliations'),
      state_lookup_failures: metricCount(data, 'dbmig_order_state_reconciliation_failures'),
    },
    operations: perOperation,
    k6_generator: {
      http_requests_including_setup_and_auth: metricCount(data, 'http_reqs'),
      dropped_iterations_not_sent: metricCount(data, 'dropped_iterations'),
    },
    interpretation: {
      http_4xx: 'Authentication, CSRF, validation, or business rejection; not proof of DB write loss.',
      http_401: 'All measured workload requests use prevalidated authentication; a 401 is an availability/auth-path anomaly and must be matched to the BE final-response audit. It is not a business DML outcome.',
      http_404_409: 'Reported per operation for review against that API scenario; do not classify every 404/409 as either expected or unexpected without checking the endpoint contract and response body.',
      http_5xx_or_transport: 'Write outcome is unconfirmed; correlate requestId with BE/ProxySQL logs and reconcile the DB.',
      http_202: 'Queue acceptance only. It is not counted as a synchronous write commit. Order queue status is polled; DB replay 202 has no consumer completion API and is reconciled from order history when possible.',
      retry_202: 'Opt-in retry only repeats an order-queue create with the same Idempotency-Key/eventId. Generic DB replay 202 and other writes are never automatically retried.',
      db_commit: 'K6 cannot observe a MySQL commit directly. Correlate writeId + requestId with BE event=db_migration_write_transaction logs; a 202 order admission commit does not prove order creation, and a DB replay 202 does not prove replay completion.',
      order_state: 'An accepted or uncertain create/cancel is never blindly repeated. The script polls the order request status or recent history on the disposable account and leaves unresolved work visible in the summary.',
      http_201_watchlist_add: 'New watchlist row created; HTTP 200 means it already existed and is not counted as a DB insert.',
      dropped_iterations_not_sent: 'Requests k6 did not send; exclude these from BE request-loss counts.',
      order: 'Creates low-price pending limit orders and cancels them in a later order iteration; no execution API is called.',
      persistent_data: 'Run-tagged test accounts and competitions, plus this run\'s watchlist candidates, may remain after the run. Preview cleanup first; apply is blocked for accounts with holdings, orders with executions, uncancelled orders, or competitions that have started.',
    },
  };

  const output = `${JSON.stringify(report, null, 2)}\n`;
  const summaryFile = __ENV.SUMMARY_FILE || `/tmp/db-migration-write-heavy-${runTag}.json`;
  return { stdout: output, [summaryFile]: output };
}
