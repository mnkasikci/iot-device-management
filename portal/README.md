# Portal

Angular app (standalone components, signals, no SSR) to see and control devices.

```bash
npm install
npm start        # ng serve on http://localhost:4200, proxies /api -> http://localhost:8080
npm test         # unit tests (Vitest)
```

The backend must be running (`cd ../backend && ./mvnw quarkus:dev`) for `/api` calls to work.

End-to-end (starts backend + portal, spawns 2 simulated devices; needs Docker, Chrome and a built `simulator.jar`):

```bash
npm run e2e
```
