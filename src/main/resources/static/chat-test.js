import {SseParser, RecoveryLedger, RetryPolicy} from '/recovery-client.mjs';

(() => {
  const $ = id => document.getElementById(id);
  let access = null, csrf = null, refreshRequest = null, session = 0, stopped = true;
  let socket = null, heartbeat = null, buffer = '', connected = false, selectedRoom = null;
  let wantWs = false, wantSse = false, sseAbort = null, wsTimer = null, sseTimer = null;
  let wsGeneration = 0, sseGeneration = 0;
  const wsRetry = new RetryPolicy(), sseRetry = new RetryPolicy();
  let roomLedgers = new Map(), notifications = new RecoveryLedger();
  const log = text => { $('log').textContent += `${new Date().toLocaleTimeString()} ${text}\n`; $('log').scrollTop = $('log').scrollHeight; };
  const status = text => { $('status').textContent = text; };
  const room = () => {
    const id = Number($('roomId').value);
    if (!Number.isSafeInteger(id) || id <= 0) throw new Error('올바른 방 ID를 입력하세요.');
    return id;
  };
  const ledger = id => { if (!roomLedgers.has(id)) roomLedgers.set(id, new RecoveryLedger()); return roomLedgers.get(id); };
  function renderMessages() {
    $('messages').replaceChildren();
    if (!selectedRoom) return;
    for (const message of ledger(selectedRoom).sorted()) {
      const item = document.createElement('li');
      item.textContent = `#${message.id} · 사용자 ${message.senderId} · ${message.content}`;
      $('messages').appendChild(item);
    }
  }
  function renderNotifications() {
    $('notifications').replaceChildren();
    for (const notification of notifications.sorted()) {
      const item = document.createElement('li');
      item.textContent = `알림 #${notification.id} · 방 ${notification.roomId} 초대`;
      $('notifications').appendChild(item);
    }
  }
  async function loadCsrf() {
    const response = await fetch('/api/auth/csrf');
    if (!response.ok) throw new Error(`CSRF ${response.status}`);
    csrf = await response.json();
  }
  async function api(path, method = 'GET', body, authenticated = true) {
    const headers = {};
    if (authenticated && access) headers.Authorization = `Bearer ${access}`;
    if (method !== 'GET') { await loadCsrf(); headers[csrf.headerName] = csrf.token; }
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(path, {method, headers, credentials:'same-origin', body:body === undefined ? undefined : JSON.stringify(body)});
    const result = response.status === 204 ? null : await response.json();
    if (!response.ok) { const error = new Error(`${response.status} ${result?.code || 'REQUEST_FAILED'}`); error.status = response.status; throw error; }
    log(`${method} ${path} → ${response.status}`);
    return result;
  }
  const credentials = () => ({email:$('email').value, password:$('password').value});
  function frame(command, headers = {}, body = '') {
    if (!socket || socket.readyState !== WebSocket.OPEN) throw new Error('먼저 연결하세요.');
    const lines = Object.entries(headers).map(([key,value]) => `${key}:${String(value)}`);
    if (body) lines.push(`content-length:${new TextEncoder().encode(body).length}`);
    socket.send([command,...lines,'',body].join('\n') + '\0');
  }
  function closeWs() {
    wsGeneration++; clearTimeout(wsTimer); clearInterval(heartbeat);
    if (selectedRoom) ledger(selectedRoom).disconnected();
    const previous = socket;
    if (previous?.readyState === WebSocket.OPEN) {
      frame('DISCONNECT', {receipt:'disconnect'});
      setTimeout(() => { if (previous.readyState === WebSocket.OPEN) previous.close(1000,'NORMAL'); },500);
    } else previous?.close();
    socket = null; connected = false;
  }
  function closeSse() {
    sseGeneration++; clearTimeout(sseTimer); notifications.disconnected();
    sseAbort?.abort(); sseAbort = null;
  }
  function stopSession() {
    stopped = true; wantWs = false; wantSse = false; session++;
    closeWs(); closeSse();
  }
  async function rotateAccess() {
    if (refreshRequest) return refreshRequest;
    const currentSession = session;
    refreshRequest = (async () => {
      try {
        const result = await api('/api/auth/refresh','POST',undefined,false);
        if (stopped || session !== currentSession) throw new Error('종료된 인증 요청입니다.');
        access = result.accessToken;
      } catch (error) {
        if (session === currentSession) { stopSession(); access = null; status('재발급 실패 · 재연결 중단'); }
        throw error;
      } finally { refreshRequest = null; }
    })();
    return refreshRequest;
  }
  async function ensureAccess() {
    const candidate = access;
    try { await api('/api/rooms?limit=1'); }
    catch (error) { if (error.status !== 401) throw error; if (access === candidate) await rotateAccess(); }
  }
  function schedule(kind) {
    if (stopped || (kind === 'ws' ? !wantWs : !wantSse)) return;
    const delay = (kind === 'ws' ? wsRetry : sseRetry).nextDelay();
    if (delay === null) { log(`${kind.toUpperCase()} 재연결 3회 실패 · 수동 연결 필요`); return; }
    const currentSession = session;
    const handle = setTimeout(async () => {
      if (stopped || session !== currentSession || (kind === 'ws' ? !wantWs : !wantSse)) return;
      try {
        await ensureAccess();
        if (stopped || session !== currentSession) return;
        if (kind === 'ws') await connectWs(); else await connectSse();
      } catch (error) { log(error.message); schedule(kind); }
    },delay);
    if (kind === 'ws') { clearTimeout(wsTimer); wsTimer = handle; }
    else { clearTimeout(sseTimer); sseTimer = handle; }
    log(`${kind.toUpperCase()} ${delay}ms 후 재연결 예정`);
  }
  async function recoverRoom(id) {
    const currentSession = session, store = ledger(id);
    await store.recover(async cursor => {
      const page = await api(`/api/rooms/${id}/messages?afterId=${cursor}&limit=20`);
      if (stopped || session !== currentSession) throw new Error('종료된 이력 요청입니다.');
      return page;
    },() => { if (id === selectedRoom) renderMessages(); });
    log(`방 ${id} 이력 복구 완료 · 마지막 ID ${store.checkpoint}`);
  }
  async function subscribeRoom(id) {
    if (!connected) throw new Error('CONNECTED를 기다리세요.');
    if (selectedRoom) frame('UNSUBSCRIBE',{id:'room'});
    selectedRoom = id; ledger(id).disconnected();
    frame('SUBSCRIBE',{id:'room',destination:`/topic/rooms/${id}`,ack:'auto'});
    log(`방 ${id} 구독 요청 전송`); renderMessages();
    // 구독 요청과 실제 브로커 등록 사이의 경계는 보장 범위 밖
    await recoverRoom(id); wsRetry.reset();
  }
  function receiveWs(data) {
    buffer += data; buffer = buffer.replace(/^\n+/,'');
    while (buffer.includes('\0')) {
      const end = buffer.indexOf('\0'), raw = buffer.slice(0,end).replace(/^\n+/,'');
      buffer = buffer.slice(end+1).replace(/^\n+/,'');
      const [head,...body] = raw.split('\n\n'), command = head.split('\n')[0].trim();
      if (command === 'CONNECTED') {
        connected = true; status('STOMP 인증 완료 · CONNECTED'); log('STOMP CONNECTED 수신');
        heartbeat = setInterval(() => { if (socket?.readyState === WebSocket.OPEN) socket.send('\n'); },10000);
        if (selectedRoom) subscribeRoom(selectedRoom).catch(error => { log(error.message); socket?.close(); });
        else wsRetry.reset();
      } else if (command === 'MESSAGE') {
        const message = JSON.parse(body.join('\n\n'));
        ledger(message.roomId).receive(message); renderMessages();
        log(`MESSAGE 수신 id=${message.id}, roomId=${message.roomId}`);
      } else if (command === 'RECEIPT') socket?.close(1000,'NORMAL');
      else if (command === 'ERROR') log('STOMP ERROR · 인증·CSRF·방 권한 확인 필요');
    }
  }
  async function connectWs() {
    if (!access || stopped) throw new Error('먼저 로그인하세요.');
    closeWs();
    const generation = wsGeneration, currentSession = session;
    await loadCsrf();
    if (stopped || currentSession !== session || generation !== wsGeneration) return;
    const protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const current = new WebSocket(`${protocol}//${location.host}/ws`,['v12.stomp']);
    socket = current; buffer = '';
    current.onopen = () => {
      if (socket !== current) return;
      log('WebSocket open · Upgrade 완료, STOMP 인증 전');
      frame('CONNECT',{'accept-version':'1.2',host:location.host,'heart-beat':'10000,10000',Authorization:`Bearer ${access}`,[csrf.headerName]:csrf.token});
    };
    current.onmessage = event => { if (socket === current) { try { receiveWs(event.data); } catch (error) { log(error.message); current.close(); } } };
    current.onclose = event => {
      if (socket !== current) return;
      connected = false; clearInterval(heartbeat);
      if (selectedRoom) ledger(selectedRoom).disconnected();
      status(`연결 종료 · ${event.code} ${event.reason}`); log(`WebSocket close code=${event.code}, reason=${event.reason || '서버 종료'}`);
      if (event.reason === 'LOGOUT') { stopSession(); access = null; return; }
      schedule('ws');
    };
    current.onerror = () => log('WebSocket 오류');
  }
  async function recoverNotifications() {
    const currentSession = session;
    await notifications.recover(async cursor => {
      const page = await api(`/api/notifications?afterId=${cursor}&limit=20`);
      if (stopped || session !== currentSession) throw new Error('종료된 알림 요청입니다.');
      return page;
    },renderNotifications);
    log(`알림 이력 복구 완료 · 마지막 ID ${notifications.checkpoint}`);
  }
  async function connectSse() {
    if (!access || stopped) throw new Error('먼저 로그인하세요.');
    closeSse();
    const generation = sseGeneration, currentSession = session, controller = new AbortController();
    sseAbort = controller;
    let response;
    try { response = await fetch('/api/notifications/stream',{headers:{Authorization:`Bearer ${access}`},signal:controller.signal}); }
    catch (error) { if (controller.signal.aborted) return; throw error; }
    if (generation !== sseGeneration || session !== currentSession || stopped) { controller.abort(); return; }
    if (!response.ok) { const error = new Error(`SSE ${response.status}`); error.status = response.status; throw error; }
    if (!response.headers.get('content-type')?.startsWith('text/event-stream')) throw new Error('SSE 응답 형식이 다릅니다.');
    $('sseStatus').textContent = 'SSE 연결됨 · ready 대기';
    const parser = new SseParser(event => {
      if (stopped || session !== currentSession || generation !== sseGeneration) return;
      if (event.event === 'ready') {
        $('sseStatus').textContent = 'SSE ready · 알림 수신 중'; log('SSE ready 수신');
        recoverNotifications().then(() => sseRetry.reset()).catch(error => { log(error.message); controller.abort(); schedule('sse'); });
      } else if (event.event === 'notification') {
        const notification = JSON.parse(event.data); notifications.receive(notification); renderNotifications();
        log(`알림 실시간 수신 id=${notification.id}, roomId=${notification.roomId}`);
      }
    });
    const reader = response.body.getReader();
    try {
      while (true) { const {value,done} = await reader.read(); if (done) { parser.finish(); break; } parser.push(value); }
    } catch (error) { if (!controller.signal.aborted) log(`SSE 오류 · ${error.message}`); }
    finally {
      await reader.cancel().catch(() => {});
      if (!stopped && session === currentSession && generation === sseGeneration && !controller.signal.aborted) {
        $('sseStatus').textContent = 'SSE 종료 · 재연결 대기'; notifications.disconnected(); schedule('sse');
      }
    }
  }
  const action = (id,handler) => { $(id).onclick = async () => { try { await handler(); } catch (error) { log(error.message); } }; };
  action('signup',async () => { const user = await api('/api/auth/signup','POST',{...credentials(),nickname:$('nickname').value},false); $('user').textContent = `가입한 사용자 ID: ${user.id}`; });
  action('login',async () => {
    stopSession(); access = null;
    const result = await api('/api/auth/login','POST',credentials(),false);
    access = result.accessToken; stopped = false; roomLedgers = new Map(); notifications = new RecoveryLedger(); selectedRoom = null;
    renderMessages(); renderNotifications(); status('로그인 완료 · 연결 전');
  });
  action('refresh',async () => {
    const reconnectWs = wantWs, reconnectSse = wantSse;
    closeWs(); closeSse(); await rotateAccess();
    if (reconnectWs) await connectWs();
    if (reconnectSse) connectSse().catch(error => { log(error.message); schedule('sse'); });
  });
  action('logout',async () => {
    stopSession();
    try { await api('/api/auth/logout','POST'); }
    finally { access = null; status('로그아웃 · 모든 자동 재연결 중단'); $('sseStatus').textContent = 'SSE 중단'; }
  });
  action('createRoom',async () => { const participantIds = $('participants').value.split(',').filter(value => value.trim()).map(Number); const result = await api('/api/rooms','POST',{name:$('roomName').value,participantIds}); $('roomId').value = result.id; });
  action('listRooms',async () => { const result = await api('/api/rooms'); $('rooms').replaceChildren(); for (const item of result.items) { const li = document.createElement('li'); li.textContent = `${item.id} · ${item.name}`; $('rooms').appendChild(li); } });
  action('connect',async () => { wantWs = true; wsRetry.reset(); await ensureAccess(); await connectWs(); });
  action('subscribe',() => subscribeRoom(room()));
  action('disconnect',() => { wantWs = false; closeWs(); status('수동 연결 종료 · 방별 커서 보관'); log('WS 수동 종료'); });
  action('send',() => { if (!connected) throw new Error('CONNECTED를 기다리세요.'); if (selectedRoom !== room()) throw new Error('현재 방을 먼저 구독하세요.'); frame('SEND',{destination:`/app/rooms/${selectedRoom}/messages`,'content-type':'application/json'},JSON.stringify({content:$('content').value})); });
  action('history',async () => { selectedRoom = room(); await recoverRoom(selectedRoom); renderMessages(); });
  action('sseConnect',() => { wantSse = true; sseRetry.reset(); connectSse().catch(error => { log(error.message); schedule('sse'); }); });
  action('sseDisconnect',() => { wantSse = false; closeSse(); $('sseStatus').textContent = 'SSE 수동 중단 · 커서 보관'; log('SSE 수동 중단'); });
  action('notificationHistory',recoverNotifications);
})();
