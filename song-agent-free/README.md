# SongFlow Free — local AI songs to YouTube

A separate local-first alternative to `song-agent/`. It does not replace or modify SafeGuard. It uses the open-source ACE-Step 1.5 music model through its local HTTP API, creates a simple MP4 cover video, and uploads through your own YouTube OAuth authorization.

## Important: what must exist before it can run

GitHub stores the code; it does not run this agent continuously. **This version needs a computer or server running ACE-Step 1.5**, plus Docker, internet access, and a Google OAuth client file. It cannot start music generation from an Android phone or from GitHub alone. Do not put OAuth files or passwords in the repository.

## Quick start (computer with Docker)

1. Install and start Docker Desktop/Engine.
2. Install ACE-Step 1.5 using its official instructions and start its API on the host at `http://localhost:8001`. Confirm `http://localhost:8001/health` responds. Model downloads and generation need suitable hardware; GPU requirements depend on the selected model.
3. In Google Cloud Console, enable **YouTube Data API v3**, configure OAuth consent, and create an OAuth client. Download its JSON and save it locally as `song-agent-free/secrets/client_secret.json`. For a Web client, configure redirect URI `http://localhost:8000/oauth/callback`; follow Google's current guidance for your OAuth client type.
4. In `song-agent-free/`, copy `.env.example` to `.env`. Keep `YOUTUBE_PRIVACY_STATUS=private` for the first test. Change the sample `APP_TOKEN` to a long random secret.
5. Run `bash start-linux-macos.sh` on macOS/Linux, or run `start-windows.bat` on Windows after reviewing `.env`. This builds and starts the API container. ACE-Step must be running separately on the same host.
6. Open [http://localhost:8000/docs](http://localhost:8000/docs), open `GET /oauth/start`, and complete Google authorization.
7. Check `GET /health`. Queue one private test song using `POST /jobs/fill-today` with JSON body `{"count":1,"language":"English"}`. If `APP_TOKEN` is set, include the `X-App-Token` header.
8. Check `GET /jobs` until the job is published. Review the private video in YouTube Studio before changing `YOUTUBE_PRIVACY_STATUS=public`.

To stop the service, run `docker compose down` from this folder. Data, tokens, and media remain in local `data/` and `media/` directories.

## Cost and limits

- The agent makes no paid music-generation API calls; the model runs on your own computer/server. Hardware, electricity, internet data, and any hosted GPU may cost money.
- Check the official project and exact model-weight license before commercial use: [ACE-Step](https://github.com/ace-step/ACE-Step) and [ACE-Step 1.5](https://github.com/ace-step/ACE-Step-1.5).
- YouTube uploads require a Google Cloud project, OAuth consent, YouTube Data API access, and available quota. API project restrictions and account standing may prevent uploads.
- The agent caps the daily queue at 50 jobs in UTC. This is a maximum target, not a guarantee of 50 completed or published songs.
- Repetitive or mass-produced content may not qualify for YouTube monetization. Make distinct original work and do not imitate named artists or copyrighted songs.
- Upload visibility defaults to **private** for testing. The video is a static cover image with audio, not moving AI footage.

## API

- `GET /health` — API status, ACE-Step reachability, queue status.
- `GET /oauth/start` and `GET /oauth/callback` — YouTube OAuth setup.
- `POST /jobs/fill-today` — queue jobs up to the daily target.
- `GET /jobs` — inspect queue and publication status.
- `POST /jobs/{job_id}/retry` — retry a failed job.

## Security

- Keep `.env`, OAuth client JSON, token JSON, database, and generated media outside Git.
- The Compose configuration binds the API to `127.0.0.1` only. Do not expose it publicly without HTTPS and strong authentication.
- Never commit API keys, Google OAuth secrets, or token files.
