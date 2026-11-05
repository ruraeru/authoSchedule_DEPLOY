# 오토스케줄 (autoSchedule) 배포

카페·매장용 근무표 자동 생성 서비스. 프론트(React)·백엔드(Spring Boot)·MySQL을 Docker Compose 하나로 띄운다.

```
.
├── docker-compose.yml     # 전체 스택: 백엔드 compose를 include하고 web을 더한다
├── autoSchedule_BE/       # Spring Boot 4 (Java 17) + MySQL 8.4, Dockerfile·docker-compose.yml
└── autoSchedule_FE/web/   # React + Vite, Dockerfile(nginx)
```

| 서비스 | 내용 | 호스트 포트 |
|---|---|---|
| `web` | nginx가 프론트 정적 파일을 내주고 `/api`를 `app`으로 프록시 | **3002** (`WEB_PORT`) |
| `app` | Spring Boot API (`/api/*`, `/actuator/health`) | **8082** (`APP_PORT`, 컨테이너 안은 8080) |
| `db` | MySQL 8.4, 데이터는 볼륨 `db-data` | 열지 않음 |

브라우저는 `web`(3002)만 쓰면 된다. 프론트와 API가 같은 출처라 로그인 쿠키가 그대로 동작한다.

## 준비물

- Docker, Docker Compose **v2.20 이상** (`include` 사용). `docker compose version`으로 확인.
- AI 근무표 생성용 Ollama 호환 서버의 토큰(`LLM_API_KEY`). 없으면 AI 생성만 안 되고 나머지는 동작한다.

## 배포

```bash
git clone https://github.com/ruraeru/authoSchedule_DEPLOY.git
cd authoSchedule_DEPLOY

# 1. 환경변수 파일 만들기 (백엔드 폴더 안에 둔다. 루트 compose가 이 파일을 읽는다)
cp autoSchedule_BE/.env.example autoSchedule_BE/.env

# 2. autoSchedule_BE/.env 를 편집해 값 채우기 (아래 표)
#    JWT_SECRET 만들기:
openssl rand -base64 48

# 3. 빌드하고 띄우기
docker compose up -d --build

# 4. 확인
docker compose ps                                  # app·db가 healthy인지
curl http://localhost:8082/actuator/health         # {"status":"UP"}
# 브라우저: http://<서버 주소>:3002 → 회원가입 후 사용
```

첫 빌드는 Gradle·npm 의존성을 받느라 몇 분 걸린다. `web`은 `app`이 healthy가 된 뒤에 뜬다.

### 환경변수 (`autoSchedule_BE/.env`)

| 이름 | 필수 | 설명 |
|---|---|---|
| `DB_ROOT_PASSWORD` | ✅ | MySQL root 비밀번호(앱은 쓰지 않음) |
| `DB_PASSWORD` | ✅ | 앱 DB 계정 `autoschedule`의 비밀번호 |
| `JWT_SECRET` | ✅ | 32바이트 이상 임의 문자열. 바꾸면 모든 로그인이 풀린다 |
| `AI_PROVIDER` | | `ollama`(기본) 또는 `gemini` |
| `LLM_BASE_URL` | | Ollama 호환 서버 주소. 기본 `https://ai.ruraeru.xyz` |
| `LLM_MODEL` | | 기본 `qwen3.5:9b` |
| `LLM_API_KEY` | | Ollama 서버 Bearer 토큰 |
| `GEMINI_API_KEY` | | `AI_PROVIDER=gemini`일 때만 |
| `COOKIE_SECURE` | | HTTPS로 서비스할 때 `true`. http면 `false`(기본) |
| `CORS_ALLOWED_ORIGINS` | | 프론트를 다른 출처에서 띄울 때만. 이 구성(nginx 프록시)에서는 비워 둔다 |
| `APP_PORT` | | 백엔드 호스트 포트. 기본 `8082` |

프론트 포트는 루트에서 바꾼다: `WEB_PORT=8000 docker compose up -d` 또는 루트에 `.env` 파일(`WEB_PORT=8000`).

`.env`는 git에 올라가지 않는다(`.gitignore`). 키를 커밋하지 않는다.

## 운영

```bash
docker compose logs -f app web     # 로그
docker compose restart app         # 재시작
docker compose down                # 중지 (DB 데이터는 볼륨에 남는다)

# 업데이트
git pull
docker compose up -d --build

# DB 백업
docker compose exec db sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" autoschedule' > backup.sql
```

> ⚠️ `docker compose down -v`는 DB 볼륨까지 지운다. 모든 데이터가 사라지니 백업 후에만 쓴다.

- 테이블은 백엔드가 처음 뜰 때 엔티티로 자동 생성·갱신한다(`ddl-auto=update`). 따로 마이그레이션할 것은 없다.
- DB 계정(`autoschedule`)과 비밀번호는 **볼륨이 비어 있을 때 처음 한 번만** 만들어진다. 나중에 `.env`의 `DB_PASSWORD`만 바꾸면 접속이 실패하니, MySQL에서 비밀번호를 같이 바꾼다.

## HTTPS

공개 서비스는 앞에 리버스 프록시(Caddy, nginx, Cloudflare 등)를 두고 `3002`로 넘긴다.
- `COOKIE_SECURE=true`로 바꾸고 `docker compose up -d`로 다시 띄운다.
- 프록시의 응답 대기 시간을 **5분 이상**으로 둔다. AI 근무표 생성이 3~4분 걸린다(내장 nginx는 600초).
- `8082`(백엔드 직접 접근)는 방화벽으로 막아도 된다. 브라우저는 3002만 쓴다.

## 문제 해결

| 증상 | 확인 |
|---|---|
| `app`이 계속 재시작 | `docker compose logs app`. `JWT_SECRET`이 비었거나 32바이트 미만이면 뜨지 않는다 |
| 로그인 후 바로 로그아웃됨 | http인데 `COOKIE_SECURE=true`이면 쿠키가 저장되지 않는다 |
| AI 생성 실패 "인증을 거절" | `LLM_API_KEY` 확인 |
| AI 생성 실패 "연결하지 못했거나 시간 초과" | `LLM_BASE_URL` 접속 가능 여부: `curl -H "Authorization: Bearer $LLM_API_KEY" $LLM_BASE_URL/api/tags` |
| 화면 새로고침 시 404 | `web` 이미지가 최신인지 (`docker compose up -d --build web`) |

## 로컬 개발

```bash
# 백엔드 (MySQL과 autoschedule DB가 있어야 함)
cd autoSchedule_BE
JWT_SECRET=$(openssl rand -base64 48) SPRING_DATASOURCE_PASSWORD=<mysql 비밀번호> LLM_API_KEY=<토큰> ./gradlew bootRun   # :8080

# 프론트
cd autoSchedule_FE/web
npm install
echo VITE_USE_MOCK=false >> .env.local   # 없으면 브라우저 mock DB로 동작
npm run dev                               # :5173, /api → localhost:8080 프록시
```

자세한 구조와 규칙은 `autoSchedule_BE/CLAUDE.md`, `autoSchedule_FE/CLAUDE.md`.
