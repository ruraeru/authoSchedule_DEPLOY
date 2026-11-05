import { defineConfig, loadEnv, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { handleGenerate } from './api/generate';

/**
 * POST /ai/generate → api/generate.ts. 배포(Vercel)에서는 같은 파일이 함수로 돈다. AI 주소·키는 서버에만 있다.
 */
// @types/node 없이 쓰는 최소 타입
interface Req {
  method?: string;
  on(event: 'data' | 'end', cb: (chunk?: Uint8Array) => void): void;
  destroy(): void;
}
interface Res {
  statusCode: number;
  setHeader(name: string, value: string): void;
  end(body: string): void;
}

function aiGenerate(env: Record<string, string | undefined>): Plugin {
  const handle = (req: Req, res: Res) => {
    const send = (status: number, body: unknown) => {
      res.statusCode = status;
      res.setHeader('Content-Type', 'application/json; charset=utf-8');
      res.end(JSON.stringify(body));
    };
    if (req.method !== 'POST') return send(405, { error: 'POST만 됩니다' });
    let raw = '';
    const dec = new TextDecoder();
    req.on('data', (c) => {
      raw += dec.decode(c, { stream: true });
      if (raw.length > 1_000_000) req.destroy();
    });
    req.on('end', async () => {
      const { status, body } = await handleGenerate(raw, env);
      send(status, body);
    });
  };
  return {
    name: 'ai-generate',
    // 위 최소 타입과 node 타입을 맞추지 않으려고 never로 넘긴다
    configureServer: (s) => void s.middlewares.use('/ai/generate', handle as never),
    configurePreviewServer: (s) => void s.middlewares.use('/ai/generate', handle as never),
  };
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', 'LLM_');
  return {
    plugins: [react(), tailwindcss(), aiGenerate(env)],
    server: {
      // 백엔드(Spring Boot) 연동 시 VITE_USE_MOCK=false 로 실행
      proxy: { '/api': 'http://localhost:8080' },
    },
  };
});
