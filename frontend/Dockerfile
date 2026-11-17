FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
ENV NEXT_PUBLIC_TRACKING_URL=http://localhost:8086
RUN npm run build
USER node
CMD ["npm","start"]
