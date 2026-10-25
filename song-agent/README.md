# SongFlow AI — automated song-to-YouTube agent

A separate service under `song-agent/`; it does not replace the existing SafeGuard Android app. It queues up to 50 song jobs per local day, generates audio with Google's Lyria 3.5 through the Gemini Interactions API, creates a simple visualizer-style MP4 with a generated cover, then uploads through YouTube Data API OAuth.

## Important current limits

- Target: 50 songs/day; this is a target, not a guarantee. Provider quotas, outages, billing, YouTube project restrictions, and storage can stop jobs.
- Google's published Lyria 3.5 pricing is **$0.08 per full song** (verify current pricing before running). At 50 songs, estimated music-generation cost is **$4/day**, about **$120 per 30-day month**, excluding text generation, hosting/storage, and other services. No free-tier availability is listed for Lyria 3.5 in the published pricing page.
- YouTube's default quota documentation currently lists 100 `videos.insert` uploads/day. 50 uploads leaves limited room for retries/other uploads. Check your own Google Cloud Console quota. Some API projects may have upload/publishing restrictions until audited.
- Uploads default to public only when configured that way. This app defaults to `public` because the requested mode is fully automatic. Use `private` for a safe first test.
- Automated publishing does not guarantee YouTube monetization. Repetitive, mass-produced, or low-value content can face platform-policy and monetization restrictions. Upload only original content you have rights to use; never imitate a named artist's voice or use copyrighted lyrics.
- Lyria-generated audio includes SynthID watermarking. Check current model terms and rights for your use case.
- The video is a static generated cover with audio (not AI-generated moving footage).

## Run with Docker

1. Install Docker Engine + Compose.
2. Copy `.env.example` to `.env`, then set `GEMINI_API_KEY`, `YOUTUBE_CLIENT_SECRETS_FILE`, and the other settings.
3. In Google Cloud Console, enable **YouTube Data API v3**, configure OAuth consent, create an OAuth Desktop/Web client as appropriate, download the client JSON to `./secrets/client_secret.json`, and add the redirect URI `http://localhost:8000/oauth/callback` if using a web OAuth client.
4. Put that JSON at `./secrets/client_secret.json`. Do not commit it.
5. Start: `docker compose up --build -d`
6. Open `http://localhost:8000/docs`, call `GET /oauth/start`, complete Google consent, then call `POST /jobs/fill-today` to queue jobs up to the daily target.
7. Check `GET /jobs` and `GET /health`.

For a first test, set `YOUTUBE_PRIVACY_STATUS=private` and `DAILY_TARGET=1`. Only switch to public after confirming the output and account settings.

## API

- `GET /health` — readiness/configuration status (never returns secrets).
- `GET /oauth/start` — begins Google OAuth.
- `GET /oauth/callback` — OAuth callback.
- `POST /jobs/fill-today` — queues jobs up to the daily target; optional JSON `{"count": 50, "language": "mixed"}`.
- `GET /jobs` — recent job states.
- `POST /jobs/{job_id}/retry` — manually requeue a failed job.

The worker polls the SQLite queue and applies a conservative one-at-a-time generation/upload workflow. Retries use bounded exponential delay. Duplicate prompts are prevented by a database uniqueness hash. Daily job count and spend estimate are tracked locally.

## Secrets and deployment

- Keep `.env`, OAuth client JSON, token JSON, and generated media outside Git.
- Use a private server and HTTPS/reverse proxy for remote access. Do not expose the unauthenticated API publicly; this starter is intended for localhost/trusted deployment. Add authentication before public deployment.
- Use GitHub Actions/Secrets or a secret manager only for deployment secrets; never paste keys into source files or issues.
- Back up the SQLite database and media directory securely.
