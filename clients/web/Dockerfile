# Build context: clients/web

FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine
# Size nginx worker processes from the container's CPU limit rather than the
# node's core count, to stay well inside the 64Mi memory limit.
ENV NGINX_ENTRYPOINT_WORKER_PROCESSES_AUTOTUNE=1
# Drop the base image's default pages (index.html, 50x.html) so only our build
# is served.
USER root
RUN rm -rf /usr/share/nginx/html/*
USER 101
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
