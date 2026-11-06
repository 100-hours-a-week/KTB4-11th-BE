async function (request) {
  const target = new URL(request.url, window.location.href);
  // Never send credentials or fetch a token for an external server URL.
  if (target.origin !== window.location.origin) {
    throw new Error('Swagger 요청은 현재 서버에서만 실행할 수 있습니다.');
  }
  request.credentials = 'same-origin';
  const marker = window.location.pathname.indexOf('/swagger-ui');
  const contextPath = marker < 0 ? '' : window.location.pathname.slice(0, marker);
  const apiPath = target.pathname.slice(contextPath.length);
  if (apiPath.startsWith('/api/') &&
      !['GET', 'HEAD', 'OPTIONS'].includes((request.method || 'GET').toUpperCase())) {
    const response = await fetch(contextPath + '/api/v1/auth/csrf', {
      credentials: 'same-origin', cache: 'no-store'
    });
    if (!response.ok) throw new Error('CSRF 토큰 발급에 실패했습니다.');
    const csrf = await response.json();
    if (!csrf.token || csrf.header_name !== 'X-XSRF-TOKEN') {
      throw new Error('CSRF 응답 형식을 확인해주세요.');
    }
    request.headers = request.headers || {};
    request.headers[csrf.header_name] = csrf.token;
  }
  return request;
}
