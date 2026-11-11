(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  let access = null, socket = null, csrf = null, heartbeat = null, refreshRequest = null, buffer = '';
  let connected = false;
  const seen = new Set();
  const log = text => { $('log').textContent += `${new Date().toLocaleTimeString()} ${text}\n`; $('log').scrollTop = $('log').scrollHeight; };
  const status = text => { $('status').textContent = text; };
  async function loadCsrf() {
    const response = await fetch('/api/auth/csrf');
    if (!response.ok) throw new Error(`CSRF ${response.status}`);
    csrf = await response.json();
  }
  async function api(path, method = 'GET', body, authenticated = true) {
    const headers = {};
    if (authenticated && access) headers.Authorization = `Bearer ${access}`;
    if (method !== 'GET') {
      if (!csrf) await loadCsrf();
      headers[csrf.headerName] = csrf.token;
    }
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body), credentials: 'same-origin'});
    const result = response.status === 204 ? null : await response.json();
    if (!response.ok) throw new Error(`${response.status} ${result?.code || 'REQUEST_FAILED'}`);
    log(`${method} ${path} → ${response.status}`);
    return result;
  }
  const credentials = () => ({email: $('email').value, password: $('password').value});
  function frame(command, headers = {}, body = '') {
    if (!socket || socket.readyState !== WebSocket.OPEN) throw new Error('먼저 연결하세요.');
    const lines = Object.entries(headers).map(([key, value]) => `${key}:${String(value)}`);
    if (body) lines.push(`content-length:${new TextEncoder().encode(body).length}`);
    socket.send([command, ...lines, '', body].join('\n') + '\0');
  }
  function show(message) {
    if (seen.has(message.id)) return;
    seen.add(message.id);
    const item = document.createElement('li');
    item.textContent = `#${message.id} · 사용자 ${message.senderId} · ${message.content}`;
    $('messages').appendChild(item);
  }
  function receive(data) {
    buffer += data;
    buffer = buffer.replace(/^\n+/, '');
    while (buffer.includes('\0')) {
      const end = buffer.indexOf('\0');
      const raw = buffer.slice(0, end).replace(/^\n+/, '');
      buffer = buffer.slice(end + 1).replace(/^\n+/, '');
      const [head, ...bodyParts] = raw.split('\n\n');
      const command = head.split('\n')[0].trim();
      if (command === 'CONNECTED') {
        connected = true;
        status('STOMP 인증 완료 · CONNECTED');
        log('STOMP CONNECTED 수신');
        heartbeat = setInterval(() => { if (socket?.readyState === WebSocket.OPEN) socket.send('\n'); }, 10000);
      } else if (command === 'MESSAGE') {
        const message = JSON.parse(bodyParts.join('\n\n'));
        show(message);
        log(`MESSAGE 수신 id=${message.id}, roomId=${message.roomId}`);
      } else if (command === 'RECEIPT') {
        socket?.close(1000, 'NORMAL');
      } else if (command === 'ERROR') {
        log('STOMP ERROR · 인증·CSRF·방 권한·목적지를 확인하세요.');
      }
    }
  }
  function disconnect() {
    const previous = socket;
    if (previous?.readyState === WebSocket.OPEN) {
      frame('DISCONNECT', {receipt: 'disconnect'});
      setTimeout(() => { if (previous.readyState === WebSocket.OPEN) previous.close(1000, 'NORMAL'); }, 1000);
    } else previous?.close(1000, 'NORMAL');
    connected = false;
    clearInterval(heartbeat);
  }
  async function connect() {
    if (!access) throw new Error('먼저 로그인하세요.');
    disconnect();
    await loadCsrf();
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const current = new WebSocket(`${protocol}//${location.host}/ws`, ['v12.stomp']);
    socket = current;
    buffer = '';
    current.onopen = () => {
      if (socket !== current) return;
      log('WebSocket open · HTTP Upgrade 완료, STOMP 인증 전');
      frame('CONNECT', {'accept-version': '1.2', host: location.host, 'heart-beat': '10000,10000', Authorization: `Bearer ${access}`, [csrf.headerName]: csrf.token});
    };
    current.onmessage = event => { if (socket === current) receive(event.data); };
    current.onclose = event => {
      if (socket !== current) return;
      connected = false; clearInterval(heartbeat);
      status(`연결 종료 · ${event.code} ${event.reason}`);
      log(`WebSocket close code=${event.code}, reason=${event.reason || '서버 종료'}`);
    };
    current.onerror = () => log('WebSocket 오류');
  }
  async function refresh() {
    if (refreshRequest) return refreshRequest;
    refreshRequest = (async () => {
      try {
        const result = await api('/api/auth/refresh', 'POST', undefined, false);
        access = result.accessToken;
        await connect();
      } catch (error) { access = null; disconnect(); throw error; }
      finally { refreshRequest = null; }
    })();
    return refreshRequest;
  }
  const action = (id, handler) => { $(id).onclick = async () => { try { await handler(); } catch (error) { log(error.message); } }; };
  action('signup', async () => {
    const user = await api('/api/auth/signup', 'POST', {...credentials(), nickname: $('nickname').value}, false);
    $('user').textContent = `가입한 사용자 ID: ${user.id}`;
  });
  action('login', async () => {
    disconnect();
    const result = await api('/api/auth/login', 'POST', credentials(), false);
    access = result.accessToken;
    status('로그인 완료 · WS 연결 전');
  });
  action('refresh', refresh);
  action('logout', async () => { await api('/api/auth/logout', 'POST'); access = null; status('로그아웃 완료 · 서버 연결 종료 확인'); });
  action('createRoom', async () => {
    const participantIds = $('participants').value.split(',').filter(value => value.trim()).map(Number);
    const room = await api('/api/rooms', 'POST', {name: $('roomName').value, participantIds});
    $('roomId').value = room.id;
  });
  action('listRooms', async () => {
    const result = await api('/api/rooms');
    $('rooms').replaceChildren();
    for (const room of result.items) { const item = document.createElement('li'); item.textContent = `${room.id} · ${room.name}`; $('rooms').appendChild(item); }
  });
  action('connect', connect);
  action('subscribe', () => { if (!connected) throw new Error('CONNECTED를 기다리세요.'); seen.clear(); $('messages').replaceChildren(); frame('SUBSCRIBE', {id: 'room', destination: `/topic/rooms/${$('roomId').value}`, ack: 'auto'}); log('방 구독 요청 전송'); });
  action('disconnect', disconnect);
  action('send', () => { if (!connected) throw new Error('CONNECTED를 기다리세요.'); frame('SEND', {destination: `/app/rooms/${$('roomId').value}/messages`, 'content-type': 'application/json'}, JSON.stringify({content: $('content').value})); });
  action('history', async () => { const result = await api(`/api/rooms/${$('roomId').value}/messages`); for (const message of [...result.items].reverse()) show(message); });
})();
