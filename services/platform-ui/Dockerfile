FROM node:22-alpine

WORKDIR /app

COPY services/platform-ui/package*.json ./
RUN npm ci

COPY services/platform-ui/ ./

EXPOSE 5173

CMD ["npm", "run", "dev"]
