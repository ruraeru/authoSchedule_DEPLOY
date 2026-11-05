# ---- build ----
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 백엔드 모드로 빌드한다(mock DB 아님). VITE_* 는 빌드 때 번들에 들어간다
ENV VITE_USE_MOCK=false
RUN npm run build

# ---- run ----
# nginx가 정적 파일을 내주고 /api 는 백엔드로 프록시한다(같은 출처라 로그인 쿠키가 그대로 동작)
FROM nginx:1.27-alpine
# 백엔드 주소. compose에서는 서비스 이름(app)으로 찾는다
ENV BACKEND_URL=http://app:8080
COPY nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
