FROM node:22-bookworm-slim AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --prefer-offline --no-audit --no-fund
COPY frontend/ ./
RUN npm test && npm run build

FROM nginx:stable-alpine
COPY deploy/linux/nginx.conf /etc/nginx/nginx.conf
COPY frontend/nginx/ai-proxy.conf /etc/nginx/ai-proxy.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
