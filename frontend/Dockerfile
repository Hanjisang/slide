FROM 10.25.13.206:5000/devops/node:24-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build && mkdir -p dist/openseadragon/images && cp -r node_modules/openseadragon/build/openseadragon/images/* dist/openseadragon/images/

FROM 10.25.13.206:5000/devops/nginx:1.30.1-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80

