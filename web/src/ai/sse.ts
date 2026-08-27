export type ServerSentEvent = {
  event: string;
  data: string;
  id?: string;
};

export function findPendingAssistant<T extends { role: string; status: string }>(messages: T[]): T | undefined {
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index];
    if (message.role.toUpperCase() === 'ASSISTANT' && message.status.toUpperCase() === 'PENDING') return message;
  }
  return undefined;
}

export function revealStepSize(backlog: number, ticksRemaining?: number): number {
  if (backlog <= 0) return 0;
  if (ticksRemaining !== undefined) return Math.min(backlog, Math.max(1, Math.ceil(backlog / Math.max(1, ticksRemaining))));
  if (backlog > 192) return 16;
  if (backlog > 96) return 8;
  if (backlog > 48) return 4;
  if (backlog > 16) return 2;
  return 1;
}

export async function* parseEventStream(stream: ReadableStream<Uint8Array>): AsyncGenerator<ServerSentEvent> {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventName = '';
  let eventId: string | undefined;
  let dataLines: string[] = [];
  let hasData = false;

  const consumeLine = (line: string): ServerSentEvent | null => {
    if (line === '') {
      if (!hasData) {
        eventName = '';
        return null;
      }
      const frame: ServerSentEvent = {
        event: eventName || 'message',
        data: dataLines.join('\n'),
        ...(eventId === undefined ? {} : { id: eventId }),
      };
      eventName = '';
      dataLines = [];
      hasData = false;
      return frame;
    }
    if (line.startsWith(':')) return null;

    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    let value = separator < 0 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') eventName = value;
    if (field === 'data') {
      dataLines.push(value);
      hasData = true;
    }
    if (field === 'id' && !value.includes('\0')) eventId = value;
    return null;
  };

  const drainCompleteLines = function* (): Generator<ServerSentEvent> {
    let newline = buffer.indexOf('\n');
    while (newline >= 0) {
      let line = buffer.slice(0, newline);
      buffer = buffer.slice(newline + 1);
      if (line.endsWith('\r')) line = line.slice(0, -1);
      const frame = consumeLine(line);
      if (frame) yield frame;
      newline = buffer.indexOf('\n');
    }
  };

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      yield* drainCompleteLines();
    }
    buffer += decoder.decode();
    yield* drainCompleteLines();
    if (buffer.length) {
      const frame = consumeLine(buffer.endsWith('\r') ? buffer.slice(0, -1) : buffer);
      if (frame) yield frame;
    }
    const finalFrame = consumeLine('');
    if (finalFrame) yield finalFrame;
  } finally {
    reader.releaseLock();
  }
}
