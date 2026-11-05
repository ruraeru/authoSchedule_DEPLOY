# 오토스케줄 (autoSchedule)

카페·매장 사장님용 근무표 자동 생성 서비스. 직원(유동 출퇴근)과 알바생(파트 단위)을 한 달 단위로 배치한다.
기획: `오토스케줄_기획서.md`, 요구사항·API 초안: `PRD.md`. 사용자와는 한국어로 대화한다.

## 구조
- `web/` — React 18 + Vite 6 + TypeScript + Tailwind v4 + react-query + zustand + @dnd-kit, 테스트는 vitest.
- 백엔드: 옆 폴더 `../autoSchedule_BE`(Spring Boot). `VITE_USE_MOCK=false`(`web/.env.local`)면 vite가 `/api`를 localhost:8080으로 프록시하고 백엔드로 요청한다(`src/api/index.ts`, `src/api/auth.ts`). 값이 없거나 true면 브라우저 mock DB로 동작한다(Vercel 배포는 지금 mock 모드).
  - 로컬 연동: 백엔드를 먼저 띄우고(`../autoSchedule_BE/CLAUDE.md`) `npm run dev`. env를 바꾸면 dev 서버를 다시 띄운다.
  - 인증: 백엔드가 HttpOnly 쿠키(access 30분, refresh 14일)를 준다. `http()`는 401이면 `/api/auth/refresh`를 한 번(동시 요청끼리 공유) 부르고 재시도, 실패하면 로그아웃시킨다. 로그아웃 버튼은 `/api/auth/logout`을 부르고 react-query 캐시를 비운다. 로그인·가입 성공 때도 캐시를 비운다.
  - Docker 배포: `web/Dockerfile`(백엔드 모드로 빌드 → nginx, `/api`는 `BACKEND_URL`로 프록시, `web/nginx.conf.template`). 두 레포 옆의 `../docker-compose.yml`이 백엔드 compose를 include하고 `web`(포트 3002)을 더한다. 백엔드는 8082.
  - 백엔드는 프론트 타입(`types.ts`) 그대로 주고받는다. 매장의 `sizeMode`·파트별 `required`도 저장한다.

## 명령 (web/ 에서)
- `npm run dev` / `npm run build` (`tsc -b && vite build`) / `npm test` (vitest)
- 검증 순서: `npx tsc -b` → `npx vitest run` → `npx vite build`

## 데이터
- mock DB: `src/api/mock.ts`, localStorage 키 `autoschedule-mock-v6`. 로드 시 `upgrade()`가 예전 형식을 고친다:
  - `availableParts`가 없는 데이터를 채운다.
  - 예전 시간대별 필요 인원(`demand`)을 지우고 파트별 `required`(기본 1)를 채운다.
  - 잠깐 썼던 평일·주말 분리의 주말 복사본(`id_WE`)은 원래 파트로 합치고 `days`를 지운다.
  - `workHours {start,end,days}` 형식을 요일별 배열로 바꾼다.
  - `sizeMode`가 없으면 `AUTO`로 채우고 `size`를 다시 정한다.
- 사업장 규모: `store.sizeMode`(`AUTO`|`UNDER_5`|`OVER_5`)가 설정값이고, `store.size`는 `sizeOf`(`lib/rules.ts`)로 구한 실제 적용값이다. `AUTO`면 등록 구성원 수로 정한다.
- 예시 데이터는 앱에 없다. 테스트 전용 `src/lib/__tests__/fixtures.ts`(카페 커푸치노 성수점, 2026-11-02 주).
- 매장 기본값: `src/api/defaults.ts`.
- 구성원:
  - 근무 파트: 평일·주말 구분이 없다. 시작 시각 순으로 정렬한다(`sortParts`): mock 로드·저장, 백엔드 모드 `api.getStore`, AI 프롬프트.
  - 생성된 근무(고정 근무, 자동 채우기, AI 생성)는 날짜 → 시작 시각 순이다(`sortShifts`).
  - 알바생: `availableParts[요일][]`(0=월)는 고정 근무 파트다. 고른 칸에는 반드시 배치한다.
  - 직원: `workHours`는 요일별 `{start,end}|null` 배열(고정 근무)이다.
- 근무표는 달력 기준 한 달(1일~말일)이다. 화면은 주(월~일) 탭으로 나눈다(`weeksOf`).

## 근무표 생성
- **AI 생성(백엔드 모드):** `POST /api/schedules/generate`. 백엔드 파이프라인이 프롬프트·검증·저장을 하고 Ollama 호환 서버(qwen)를 부른다(`../autoSchedule_BE`의 `OllamaClient`). 알바생도 AI가 배치하고, 규칙 위반 줄은 백엔드가 버린다. 아래 `/ai/generate` 경로는 mock 모드 전용이다.
- **AI 생성(mock 모드):** 브라우저가 `buildPrompt`로 프롬프트를 만들어 `POST /ai/generate`에 `{ prompt }`로 보낸다. 서버 코드는 `web/api/generate.ts`(`handleGenerate`) 하나다.
  - 배포(Vercel, 루트 `web/`): `api/generate.ts`가 함수로 돈다. `web/vercel.json`이 `/ai/generate`를 `/api/generate`로 보내고 `maxDuration` 300초를 준다. 나머지 경로(`/api/` 제외)는 `index.html`로 보내 새로고침·직접 접속을 살린다. Vercel 환경변수에 `LLM_*`를 넣어야 한다(바꾸면 재배포해야 반영).
  - 배포 주소: https://auto-schedule-one.vercel.app . main에 머지하면 자동 배포된다. 확인은 `curl`로 `/staff`(200)와 `POST /ai/generate`(`{"prompt":"..."}` → 200)를 부른다.
  - 로컬: vite dev/preview 서버 미들웨어(`vite.config.ts`)가 같은 `handleGenerate`를 쓴다.
  - `api/generate.ts`는 Vercel이 따로 컴파일하므로 다른 파일을 import하지 않는다. 브라우저 코드에서도 import하지 않는다.
  - 환경변수는 `web/.env.example`을 `web/.env.local`로 복사해서 정한다.
  - `LLM_BASE_URL`(기본 `https://ai.ruraeru.xyz`, Ollama 호환)의 `/api/chat`, `LLM_MODEL`(기본 `qwen3.5:9b`). 이 서버는 Bearer 토큰이 필요하다: `LLM_API_KEY`. `think: false`, `num_ctx: 8192`. 제한 시간은 없다(`stream: true`로 node fetch 300초 헤더 제한 회피).
  - 연결 확인: `/api/tags`(모델 목록)에 같은 Bearer 헤더로 요청한다.
  - 응답은 JSON Schema(`RESPONSE_SCHEMA`)로 강제한다. 응답에 `partId`는 받지 않는다(AI는 직원만 정함).
- **프롬프트·검증:** `src/lib/ai.ts`.
  - `buildPrompt`는 구성원 이름·연락처를 빼고 `#id`로만 보낸다.
  - `toShifts`는 AI 응답을 믿지 않고 규칙에 맞지 않는 줄을 버린다.
- **규칙 기반 생성:**
  - `fixedShifts`(`src/lib/rules.ts`): 고정 근무(직원 요일별 시각, 알바생 고른 파트)를 깐다. 직접 만들기 미리 채우기, 자동 채우기, AI 생성(알바생 몫)이 모두 쓴다.
  - `autoFill`(`src/lib/autofill.ts`): 에디터의 "자동 채우기". 기존 근무는 유지하고 고정 근무만 더한다. 필요 인원으로 사람을 더 넣지 않는다.
  - AI는 직원 근무만 정한다. 필요 인원은 파트별 `required`이고, 겹치는 파트는 더한다(`requiredAt`).
- 규칙 엔진: `src/lib/rules.ts`(`validate`, `canWork`, `hoursOn`, 휴게 계산). 근무표 평가 UI(부족/충족, 규칙 위반 표시)는 의도적으로 뺐다.

## 화면 (라우트 → 파일)
- 로그인·회원가입: `/login`·`/signup` → `pages/Auth.tsx`. mock 계정은 localStorage에 있다. 로그인 가드는 `components/Layout.tsx`.
- 대시보드: `/` → `pages/Dashboard.tsx`. 오늘 근무 띠(`components/TodayStrip.tsx`), 통계 띠, 직원 근무 현황.
- 근무표 목록: `/schedules` → `pages/Schedules.tsx`. 행마다 삭제 버튼(확인 창, `api.deleteSchedule`).
- AI 생성: `/schedules/new` → `Generate.tsx`. 진행률 막대는 걸린 시간으로 추정한다.
- 직접 만들기: `/schedules/manual` → `CreateSchedule.tsx`.
- 에디터: `/schedules/:id` → `pages/schedule/ScheduleEditor.tsx`.
  - 보기: 한 달(`MonthView`), 파트별, 인원별, 일간(06–24시, 익일 마감·자정 넘는 근무가 있으면 그 시각까지 늘림). 인원별 보기의 알바생은 고정 근무 파트 순서로 나열한다(`sortByPart`).
  - 기능: 전월·익월 이동, 자동 채우기, 근무 추가·수정 창(`ShiftDialog`), 근무표 삭제(오른쪽 패널, 확인 후 목록으로).
- 직원·알바생 관리: `/staff` → `pages/Staff.tsx`. 알바생은 근무 파트 표(행=파트, 열=요일), 직원은 요일별 고정 근무 시각.
- 템플릿: `/templates`.
- 매장 설정: `/settings` → `pages/Settings.tsx`. 영업시간은 평일과 주말·공휴일을 따로 정하고, "익일" 버튼으로 다음 날 새벽 마감을 고른다(종료를 24시 넘겨 저장: `26:00` = 익일 02:00, 최대 24시간). 근무 파트는 개수 제한 없이 추가할 수 있다. 사업장 규모는 자동/5인 미만/5인 이상 중에서 고르고, 수동으로 바꿀 때는 확인 창을 띄운다.

## 디자인 시스템 (파스텔)
- Figma 파일 `H3pigmb77AZ88qmzl6PW5n`:
  - "Design System" 페이지: 변수 `DS Primitives`·`DS Semantic`, 글자 스타일 `DS/*`, 컴포넌트, Foundations 안내.
  - "디자인 시안" 페이지의 섹션 "DS 적용 화면 (파스텔)": 18개 화면의 기준 디자인.
  - 원본 페이지 "오토스케줄 Web": 예전 디자인을 웹 코드 기준으로 옮긴 화면.
- 코드 토큰: `web/src/index.css` `@theme`, Figma 변수와 1:1로 대응한다.
- 원칙:
  - 메뉴·카드·버튼 같은 틀은 무채색이다. `brand`는 검정이고, 선택 표시는 회색 알약(`brand-subtle`)이다.
  - 색은 근무 데이터에만 쓴다. 오픈=버터, 미들=민트, 마감=스카이, 직원=피치. 파스텔 바탕(`*-bg`)에 같은 계열 진한 글자를 쓴다. 미정은 점선, 연차는 회색, 경고는 연빨강이다.
  - 검정(primary) 버튼은 화면당 하나다. 버튼은 알약 모양, 카드는 둥글기 20에 1px 테두리이고 그림자가 없다. 그림자는 모달에만 쓴다.
  - 통계는 아이콘 없는 띠(`StatStrip`)로 보여준다. 아이콘 칩, 그라데이션, 보라·인디고 계열은 쓰지 않는다.
  - 화면 틀: 회색 사이드바 위에 흰 시트 본문을 둔다(8px 인셋, 둥글기 16).
- 공통 UI: `components/ui.tsx`, 아이콘·마크: `components/Icon.tsx`(lucide 경로를 코드에 직접 넣음, 의존성 없음).
- Tailwind 주의: `w-full`이 다른 너비 클래스를 이긴다. 너비를 따로 줄 때는 `inputBase`(w-full 없음)를 쓴다.

## 알려진 단순화 (`shortcut:` 주석)
- 공휴일은 2026년만 하드코딩돼 있다(`lib/time.ts`).
- 익일 마감은 영업시간만 된다. 파트·직원 고정 근무 시각은 24:00까지만 고른다. 백엔드도 `26:00` 표기를 그대로 받는다(`util/TimeCodec`).
- `/ai/generate`(mock 모드)에는 인증이 없다. 배포 주소를 아는 누구나 AI 서버 토큰으로 요청할 수 있다(`api/generate.ts`).
- 상시 근로자 수를 등록 구성원 수로 본다. 법 기준은 직전 1개월 평균 인원이다(`lib/rules.ts` `sizeOf`).
- 생성 진행률은 실제 진행이 아니라 시간 기반 추정이다(`Generate.tsx`).
- mock 계정은 브라우저에만 있고, 계정마다 데이터가 나뉘지 않는다(`api/auth.ts`).
- `web/README.md`는 예전 내용이 남아 있어 최신이 아니다.

## 작업 방식
- git: 보통 기능 브랜치(`feat/...`)에서 커밋·푸시한다. 머지를 요청받으면 `gh pr create`로 PR을 올리고 `gh pr merge --rebase --delete-branch`로 머지한다. main에 직접 머지하거나 푸시하지 않는다. PR을 만든 직후에는 GitHub가 머지 가능 여부를 아직 계산하지 못해 `gh pr merge`가 실패할 수 있으니 몇 초 뒤 다시 시도한다.
- "커밋푸시해"는 기능 브랜치에 커밋·푸시까지, "머지해"는 PR 생성과 머지까지 한다. 머지 뒤 로컬은 main으로 돌아온다. 커밋 메시지는 한국어이고, 끝에 `Co-Authored-By` 줄을 붙인다.
- 커밋 전에 `.env*`가 포함되지 않았는지, 키 문자열이 없는지 확인한다.
- 이 환경에는 브라우저 도구가 없다. UI 변경은 타입 검사·테스트·빌드로만 검증한 것이며, 화면을 직접 보지 못했다고 사용자에게 알린다.
