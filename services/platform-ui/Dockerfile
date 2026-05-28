# ---- Build stage: compile the Vite/React app to a static bundle ----
FROM node:22-alpine AS build

WORKDIR /app

COPY services/platform-ui/package*.json ./
RUN npm ci

COPY services/platform-ui/ ./

# VITE_* vars are inlined at build time (import.meta.env). The browser runs on
# the host, so the gateway is reachable at localhost:8080 by default.
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ---- Serve stage: static files behind nginx ----
FROM nginx:1.27-alpine AS serve

COPY services/platform-ui/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
