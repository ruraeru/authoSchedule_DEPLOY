import { afterEach, describe, expect, it, vi } from 'vitest';
import { generateJson, handleGenerate, llmConfig } from '../../../api/generate';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status });

afterEach(() => vi.unstubAllGlobals());

describe('generateJson', () => {
  it('/api/chat에 Bearer·think·num_ctx를 넣고 스트림 content를 이어 붙여 파싱한다', async () => {
    const fetch = vi.fn().mockResolvedValue(new Response('{"message":{"content":"{\\"ok\\""}}\n{"message":{"content":":1}"},"done":true}\n'));
    vi.stubGlobal('fetch', fetch);
    const cfg = llmConfig({ LLM_BASE_URL: 'https://ai.example/', LLM_API_KEY: 'k' });
    expect(await generateJson(cfg, 'p', {})).toEqual({ ok: 1 });
    const [url, init] = fetch.mock.calls[0];
    expect(url).toBe('https://ai.example/api/chat');
    expect(init.headers.Authorization).toBe('Bearer k');
    expect(JSON.parse(init.body)).toMatchObject({ think: false, stream: true, options: { num_ctx: 8192 } });
    expect(init.signal).toBeUndefined();
  });

  it('키가 없으면 Authorization을 보내지 않는다', async () => {
    const fetch = vi.fn().mockResolvedValue(json(200, { message: { content: '{}' } }));
    vi.stubGlobal('fetch', fetch);
    await generateJson(llmConfig({}), 'p', {});
    expect(fetch.mock.calls[0][1].headers.Authorization).toBeUndefined();
  });
});

describe('handleGenerate', () => {
  it('prompt가 없으면 400', async () => {
    expect((await handleGenerate('{}', {})).status).toBe(400);
    expect((await handleGenerate('not json', {})).status).toBe(400);
  });

  it('prompt를 AI에 보내고 JSON을 돌려준다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('{"message":{"content":"{\\"shifts\\":[]}"},"done":true}\n')));
    expect(await handleGenerate('{"prompt":"p"}', {})).toEqual({ status: 200, body: { shifts: [] } });
  });
});

describe('llmConfig', () => {
  it('키의 공백·따옴표·Bearer 접두사를 걷어낸다', () => {
    expect(llmConfig({ LLM_API_KEY: ' "Bearer abc"\n' }).apiKey).toBe('abc');
  });

  it('401이면 키 유무를 알려준다', async () => {
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async () => new Response('{}', { status: 401 })));
    expect((await handleGenerate('{"prompt":"p"}', {})).body).toEqual({ error: expect.stringContaining('환경변수가 없습니다') });
    expect((await handleGenerate('{"prompt":"p"}', { LLM_API_KEY: 'abc' })).body).toEqual({ error: expect.stringContaining('길이 3') });
  });
});
