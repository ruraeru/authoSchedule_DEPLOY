# 오토스케줄 웹 (React)

직원(유동 근무)과 알바생(파트 근무)이 섞인 매장의 근무표 웹 프론트엔드. PRD v0.3 기준.

## 실행

```bash
npm install
npm run dev        # http://localhost:5173 — 백엔드 없이 예시 데이터(mock)로 동작
npm test           # 근로 규칙 엔진·휴게 조정 제안 테스트
npm run build
```

Spring Boot 서버와 연동할 때는 `VITE_USE_MOCK=false npm run dev`. `/api` 요청은 `localhost:8080`으로 프록시된다.

## 구조

| 경로 | 내용 |
| --- | --- |
| `src/types.ts` | API 데이터 형태 (Staff, Shift, Schedule …) |
| `src/api/` | REST 클라이언트, mock DB(localStorage), 예시 데이터 |
| `src/lib/rules.ts` | 규칙 엔진 미리보기 (R-01~06, S-01~07, T-01), 커버리지, 인건비 |
| `src/lib/suggest.ts` | 휴게 위치만 옮겨 커버리지 부족을 메우는 제안 |
| `src/store/editor.ts` | 에디터 상태 + 실행 취소 (Zustand) |
| `src/pages/schedule/` | 근무표 에디터: 파트별 / 인원별 / 일간 타임라인 |

규칙 검증은 화면 미리보기용이다. 확정 시 서버가 같은 규칙으로 다시 검증해야 한다.
