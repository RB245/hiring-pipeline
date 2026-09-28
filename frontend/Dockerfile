# Dependencies in their own layer, so editing a component does not reinstall the world.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules node_modules
COPY . .
# The board page is force-dynamic and the BFF reads the key at request time, so nothing
# here needs a running backend or a real key to compile. The placeholder exists only
# because the module that reads it is imported during the build.
ENV API_KEY=build-time-placeholder
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
# output: "standalone" traces exactly the files the server needs, which is a few megabytes
# against the couple of hundred a full node_modules would copy.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
# Not root. The container serves untrusted input and holds the API key in its environment.
USER node
EXPOSE 3000
CMD ["node", "server.js"]
