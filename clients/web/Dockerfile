# Build context: clients/web

FROM node:24-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine
# Runs with a read-only root filesystem; /tmp is the only writable path (an
# emptyDir in k8s). See nginx-main.conf.
USER root
# Drop the base image's default pages (index.html, 50x.html) so only our build
# is served.
RUN rm -rf /usr/share/nginx/html/*
# Empty /docker-entrypoint.d so the entrypoint runs no configuration scripts
# (it logs "No files found ... skipping configuration" and execs nginx). None
# of them is needed, and three of them write under /etc/nginx:
# - 10-listen-on-ipv6-by-default.sh would add `listen [::]:8080` to
#   conf.d/default.conf. It already skipped ours (the COPY'd file is
#   root-owned, so uid 101 "can not modify" it; its checksum doesn't match the
#   packaged one either), so we listen on IPv4 8080 only, unchanged.
# - 15-local-resolvers.envsh only exports NGINX_LOCAL_RESOLVERS for templates.
# - 20-envsubst-on-templates.sh renders /etc/nginx/templates; we have none.
# - 30-tune-worker-processes.sh rewrites worker_processes in nginx.conf; we
#   set it statically instead.
# The base image's ENTRYPOINT/CMD stay as they are.
RUN rm -f /docker-entrypoint.d/*
USER 101
COPY nginx-main.conf /etc/nginx/nginx.conf
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
