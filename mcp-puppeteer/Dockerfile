FROM ghcr.io/puppeteer/puppeteer:latest

USER root
# Ensure there is a directory for cache
RUN mkdir -p /home/pptruser/.cache && chown -R pptruser:pptruser /home/pptruser/.cache

USER pptruser
WORKDIR /home/pptruser

# Pre-install the browser binary needed by Puppeteer
RUN npx puppeteer browsers install chrome
