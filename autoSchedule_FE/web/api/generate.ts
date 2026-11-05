/**
 * AI 근무표 생성 서버 코드. Vercel 함수(POST /api/generate, vercel.json이 /ai/generate를 여기로 보냄)이고,
 * 로컬에서는 vite 서버 미들웨어(vite.config.ts)가 같은 handleGenerate를 쓴다. 브라우저 코드에서 import하지 않는다.
 * Ollama 호환 서버(LLM_BASE_URL)의 /api/chat을 부른다. LLM_API_KEY가 있으면 Bearer 헤더를 붙인다.
 * Vercel이 이 파일만 따로 컴파일하므로 다른 파일을 import하지 않는다.
 */

// @types/node 없이 쓰는 최소 타입
declare const process: { env: Record<string, string | undefined> };

/** Ollama format (JSON Schema) */
export const RESPONSE_SCHEMA = {
  type: 'object',
  properties: {
    shifts: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          staffId: { type: 'integer' },
          date: { type: 'string', description: 'YYYY-MM-DD' },
          start: { type: 'string', description: 'HH:MM, 15분 단위' },
          end: { type: 'string', description: 'HH:MM, 15분 단위' },
        },
        required: ['staffId', 'date', 'start', 'end'],
      },
    },
    comment: { type: 'string', description: '사장님께 드리는 2~3문장 배치 요약' },
  },
  required: ['shifts', 'comment'],
};


export interface LlmConfig {
  baseUrl: string;
  model: string;
  apiKey?: string;
}

/** 호출자에게 돌려줄 HTTP 상태를 담은 오류 */
export class LlmError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

/** loadEnv 결과에서 설정을 만든다 */
export function llmConfig(env: Record<string, string | undefined>): LlmConfig {
  return {
    baseUrl: (env.LLM_BASE_URL || 'https://ai.ruraeru.xyz').replace(/\/+$/, ''),
    model: env.LLM_MODEL || 'qwen3.5:9b',
    // 대시보드에 붙여넣을 때 섞이는 공백·따옴표·"Bearer "를 걷어낸다
    apiKey: env.LLM_API_KEY?.trim().replace(/^["']|["']$/g, '').replace(/^Bearer\s+/i, '') || undefined,
  };
}

/** prompt를 보내고 schema에 맞춘 JSON 응답을 파싱해 돌려준다. 실패하면 LlmError. 제한 시간은 없다 */
export async function generateJson(cfg: LlmConfig, prompt: string, schema: object): Promise<unknown> {
  const r = await fetch(`${cfg.baseUrl}/api/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(cfg.apiKey && { Authorization: `Bearer ${cfg.apiKey}` }) },
    body: JSON.stringify({
      model: cfg.model,
      messages: [{ role: 'user', content: prompt }],
      format: schema,
      think: false,
      // 스트리밍: 안 하면 node fetch가 300초 안에 응답 헤더를 못 받아 끊는다
      stream: true,
      options: { num_ctx: 8192 },
      keep_alive: '30m',
    }),
  }).catch((e: Error) => e);
  if (r instanceof Error) {
    throw new LlmError(503, `AI 서버(${cfg.baseUrl})에 연결하지 못했습니다: ${r.cause instanceof Error ? r.cause.message : r.name}`);
  }
  if (!r.ok) {
    const data = await r.json().catch(() => null);
    console.error(`[llm] ${r.status}`, data);
    if (r.status === 401 || r.status === 403)
      throw new LlmError(502, `AI 서버가 인증을 거절했습니다(${r.status}). LLM_API_KEY ${cfg.apiKey ? `값을 확인하세요(길이 ${cfg.apiKey.length})` : '환경변수가 없습니다. 넣은 뒤 다시 배포하세요'}`);
    throw new LlmError(502, `AI 오류: ${data?.error ?? r.status}`);
  }
  // 한 줄에 JSON 하나(NDJSON). message.content 조각을 이어 붙인다
  let content = '';
  let reason = '';
  for (const line of (await r.text()).split('\n')) {
    if (!line.trim()) continue;
    const chunk = JSON.parse(line);
    if (chunk.error) throw new LlmError(502, `AI 오류: ${chunk.error}`);
    content += chunk.message?.content ?? '';
    if (chunk.done) reason = chunk.done_reason;
  }
  try {
    return JSON.parse(content);
  } catch {
    console.error('[llm] JSON이 아닌 응답', reason, content.slice(-300));
    throw new LlmError(502, `AI 응답이 잘렸거나 JSON이 아닙니다 (done_reason ${reason || '없음'})`);
  }
}

/** 요청 본문(JSON { prompt })을 받아 { status, body }를 돌려준다 */
export async function handleGenerate(raw: string, env: Record<string, string | undefined>): Promise<{ status: number; body: unknown }> {
  let prompt: unknown;
  try {
    prompt = JSON.parse(raw)?.prompt;
  } catch {
    // 아래에서 400
  }
  if (typeof prompt !== 'string' || !prompt || prompt.length > 200_000) return { status: 400, body: { error: 'prompt가 없거나 너무 깁니다' } };
  try {
    return { status: 200, body: await generateJson(llmConfig(env), prompt, RESPONSE_SCHEMA) };
  } catch (e) {
    if (e instanceof LlmError) return { status: e.status, body: { error: e.message } };
    console.error('[llm]', e);
    return { status: 502, body: { error: `생성 실패: ${(e as Error).message}` } };
  }
}

// shortcut: 인증 없음. 배포 주소를 아는 누구나 AI 토큰으로 요청할 수 있다. 실제 로그인(백엔드)이 생기면 세션을 확인한다
/** Vercel 함수 */
export async function POST(request: Request): Promise<Response> {
  const { status, body } = await handleGenerate(await request.text(), process.env);
  return Response.json(body, { status });
}
