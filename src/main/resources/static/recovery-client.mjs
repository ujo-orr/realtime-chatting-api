// fetch 바이트 스트림의 UTF-8·개행 경계 처리
export class SseParser {
  constructor(onEvent) {
    this.onEvent = onEvent;
    this.decoder = new TextDecoder();
    this.line = '';
    this.skipLf = false;
    this.data = [];
    this.event = '';
    this.id = '';
  }
  push(bytes) { this.text(this.decoder.decode(bytes, {stream: true})); }
  finish() { this.text(this.decoder.decode()); }
  text(text) {
    for (const character of text) {
      if (character === '\n' && this.skipLf) { this.skipLf = false; continue; }
      this.skipLf = false;
      if (character === '\r' || character === '\n') {
        this.processLine(this.line);
        this.line = '';
        this.skipLf = character === '\r';
      } else this.line += character;
    }
  }
  processLine(line) {
    if (line === '') {
      if (this.data.length) this.onEvent({event: this.event || 'message', id: this.id, data: this.data.join('\n')});
      this.data = [];
      this.event = '';
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    let value = colon < 0 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'data') this.data.push(value);
    else if (field === 'event') this.event = value;
    else if (field === 'id' && !value.includes('\0')) this.id = value;
  }
}

// 실시간 ID와 이력 페이지 커서의 분리
export class RecoveryLedger {
  constructor() {
    this.items = new Map();
    this.checkpoint = 0;
    this.needsRecovery = true;
    this.inFlight = null;
  }
  disconnected() { this.needsRecovery = true; }
  receive(item) {
    if (!Number.isSafeInteger(item.id) || item.id <= 0) throw new Error('안전한 범위의 서버 ID가 필요합니다.');
    this.items.set(item.id, item);
    if (!this.needsRecovery) this.checkpoint = Math.max(this.checkpoint, item.id);
  }
  sorted() { return [...this.items.values()].sort((a, b) => a.id - b.id); }
  recover(fetchPage, onPage = () => {}) {
    if (this.inFlight) return this.inFlight;
    this.needsRecovery = true;
    this.inFlight = (async () => {
      let cursor = this.checkpoint;
      while (true) {
        const page = await fetchPage(cursor);
        for (const item of page.items) this.receive(item);
        onPage();
        if (!page.hasMore) break;
        if (!Number.isSafeInteger(page.nextCursor) || page.nextCursor <= cursor) throw new Error('잘못된 이력 커서입니다.');
        cursor = page.nextCursor;
      }
      for (const id of this.items.keys()) this.checkpoint = Math.max(this.checkpoint, id);
      this.needsRecovery = false;
    })().finally(() => { this.inFlight = null; });
    return this.inFlight;
  }
}

export class RetryPolicy {
  constructor(maxAttempts = 3, baseDelay = 1000) { this.maxAttempts = maxAttempts; this.baseDelay = baseDelay; this.attempts = 0; }
  nextDelay() { return this.attempts < this.maxAttempts ? this.baseDelay * 2 ** this.attempts++ : null; }
  reset() { this.attempts = 0; }
}
