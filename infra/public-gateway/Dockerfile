FROM nginx:1.27.5-alpine

# Only substitute deployment settings; nginx runtime variables must remain intact.
ENV NGINX_ENVSUBST_FILTER="^(BACKEND_UPSTREAM|PORT)$"
COPY default.conf.template /etc/nginx/templates/default.conf.template
