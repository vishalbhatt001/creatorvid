# Frontend (Next.js) image. Build context must be the repo root.
FROM node:22-slim AS build
WORKDIR /app

COPY apps/frontend-next/package*.json apps/frontend-next/
RUN cd apps/frontend-next && npm install

COPY apps/frontend-next apps/frontend-next

# NEXT_PUBLIC_API_URL is baked in at build time (Next.js inlines NEXT_PUBLIC_* at build).
ARG NEXT_PUBLIC_API_URL=http://localhost:4001
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}
RUN cd apps/frontend-next && npm run build

# --- Run the Next.js standalone server ---
FROM node:22-slim AS runtime
WORKDIR /app
COPY --from=build /app/apps/frontend-next/.next/standalone ./
COPY --from=build /app/apps/frontend-next/.next/static ./apps/frontend-next/.next/static
COPY --from=build /app/apps/frontend-next/public ./apps/frontend-next/public
EXPOSE 3001
ENV PORT=3001
CMD ["node", "apps/frontend-next/server.js"]
