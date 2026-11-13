import test from 'node:test';
import assert from 'node:assert/strict';
import {SseParser, RecoveryLedger, RetryPolicy} from '../../main/resources/static/recovery-client.mjs';

test('SSE: UTF-8 한글을 바이트마다 나눠도 원문 복원', () => {
  const events = [];
  const parser = new SseParser(event => events.push(event));
  for (const byte of new TextEncoder().encode('event: notification\nid: 7\ndata: {"text":"초대 알림"}\n\n')) parser.push(Uint8Array.of(byte));
  assert.deepEqual(events, [{event:'notification',id:'7',data:'{"text":"초대 알림"}'}]);
});
test('SSE: CRLF가 chunk 사이에서 나뉘고 여러 이벤트가 한 chunk에 와도 분리', () => {
  const events = []; const parser = new SseParser(event => events.push(event));
  for (const text of ['event: ready\r','\ndata: {}\r','\n\r','\ndata: second\r\n\r\n']) parser.push(new TextEncoder().encode(text));
  assert.equal(events.length, 2); assert.equal(events[0].event, 'ready'); assert.equal(events[1].data, 'second');
});
test('SSE: heartbeat comment는 표시하지 않고 여러 data 줄은 개행으로 병합', () => {
  const events = []; const parser = new SseParser(event => events.push(event));
  parser.push(new TextEncoder().encode(':heartbeat\n\nevent: note\ndata: a\ndata: b\n\n'));
  assert.deepEqual(events, [{event:'note',id:'',data:'a\nb'}]);
});
test('SSE: LF·CR·BOM과 무시할 필드 처리', () => {
  const events = []; const parser = new SseParser(event => events.push(event));
  parser.push(new TextEncoder().encode('\uFEFFretry: 1000\runknown: x\rdata: value\r\r'));
  assert.equal(events[0].data, 'value'); assert.equal(events[0].event, 'message');
});
test('SSE: 종료된 chunk의 미완성 이벤트는 발행하지 않음', () => {
  const events = []; const parser = new SseParser(event => events.push(event));
  parser.push(new TextEncoder().encode('data: incomplete\n')); parser.finish(); assert.equal(events.length, 0);
});
test('SSE: id 유지·초기화 및 NUL이 들어간 id 거부', () => {
  const events = []; const parser = new SseParser(event => events.push(event));
  parser.push(new TextEncoder().encode('id: 8\ndata: a\n\nid: bad\0id\ndata: b\n\nid:\ndata: c\n\n'));
  assert.deepEqual(events.map(event => event.id), ['8','8','']);
});
test('복구: 실시간과 이력의 같은 ID를 한 번만 표시', async () => {
  const ledger = new RecoveryLedger(); ledger.receive({id:101});
  await ledger.recover(async () => ({items:[{id:101},{id:103}],hasMore:false,nextCursor:null}));
  ledger.receive({id:103}); assert.deepEqual(ledger.sorted().map(item => item.id), [101,103]);
});
test('복구: 큰 실시간 ID가 들어와도 다음 이력 페이지를 건너뛰지 않음', async () => {
  const ledger = new RecoveryLedger(); await ledger.recover(async () => ({items:[{id:101}],hasMore:false}));
  ledger.disconnected(); const cursors = [];
  await ledger.recover(async cursor => {
    cursors.push(cursor);
    if (cursor === 101) { ledger.receive({id:108}); return {items:[{id:102}],hasMore:true,nextCursor:102}; }
    return {items:[{id:103}],hasMore:false,nextCursor:null};
  });
  assert.deepEqual(cursors, [101,102]); assert.deepEqual(ledger.sorted().map(item => item.id), [101,102,103,108]);
});
test('복구: 조회 실패 중 실시간 수신도 완료 커서를 앞당기지 않음', async () => {
  const ledger = new RecoveryLedger(); await ledger.recover(async () => ({items:[{id:101}],hasMore:false}));
  ledger.disconnected(); await assert.rejects(ledger.recover(async () => { ledger.receive({id:108}); throw new Error('offline'); }));
  ledger.receive({id:109}); assert.equal(ledger.checkpoint, 101);
  const cursors = []; await ledger.recover(async cursor => { cursors.push(cursor); return {items:[{id:102},{id:103}],hasMore:false}; });
  assert.deepEqual(cursors, [101]); assert.deepEqual(ledger.sorted().map(item => item.id), [101,102,103,108,109]);
});
test('복구: 동시에 요청한 복구는 하나의 페이지 조회로 합침', async () => {
  const ledger = new RecoveryLedger(); let resolve; let calls = 0;
  const page = new Promise(done => { resolve = done; });
  const first = ledger.recover(() => { calls++; return page; }); const second = ledger.recover(() => { calls++; return page; });
  resolve({items:[],hasMore:false}); await Promise.all([first,second]); assert.equal(calls, 1);
});
test('복구: 되돌아가는 커서와 안전 범위를 벗어난 ID는 거부', async () => {
  const ledger = new RecoveryLedger(); await assert.rejects(ledger.recover(async () => ({items:[],hasMore:true,nextCursor:0})));
  assert.throws(() => ledger.receive({id:Number.MAX_SAFE_INTEGER + 1}));
});
test('재연결: 세 차례 지연 후 중단하고 성공 시 횟수 초기화', () => {
  const retry = new RetryPolicy(); assert.deepEqual([retry.nextDelay(),retry.nextDelay(),retry.nextDelay(),retry.nextDelay()], [1000,2000,4000,null]);
  retry.reset(); assert.equal(retry.nextDelay(), 1000);
});
