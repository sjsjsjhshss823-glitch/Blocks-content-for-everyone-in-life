# SongFlow Free — local AI songs to YouTube

This is a separate local-first alternative to the existing song-agent folder. It uses the open-source ACE-Step 1.5 music model through its local HTTP API, then creates a simple MP4 cover video and uploads it to YouTube using your OAuth authorization.

## Cost and limits

- The agent makes no paid music-generation API calls. The model runs on your own computer.
- Free means no per-song music API charge, not zero total cost: model downloads use internet data and generation uses your computer's hardware and electricity. A hosted GPU may cost money.
- Check the official project and model license and the terms for the exact model weights before commercial use: https://github.com/ace-step/ACE-Step and https://github.com/ace-step/ACE-Step-1.5
- YouTube uploads require a Google Cloud project, OAuth consent, and YouTube Data API access. Quotas, project restrictions, account standing, and monetization eligibility still apply.
- The agent caps the daily queue at 50 jobs in UTC. That is a maximum target, not a guarantee of 50 completed or published songs.
- Repetitive, mass-produced content may not qualify for YouTube monetization. Create genuinely distinct work and do not imitate named artists or copyrighted songs.
- Upload visibility defaults to private for a first test. After checking a test upload, set YOUTUBE_PRIVACY_STATUS=public for automatic public publishing without per-song approval.
- The video is a generated cover image with audio, not moving AI footage.

## 1. Start ACE-Step locally

Follow the official ACE-Step 1.5 installation instructions for your operating system and hardware. Its API documentation describes a local server on port 8001 and endpoints /release_task, /query_result, and /v1/audio.

- Project: https://github.com/ace-step/ACE-Step
- API documentation: https://github.com/ace-step/ACE-Step-1.5/blob/main/docs/en/API.md

Start the ACE-Step API server so it is reachable at http://127.0.0.1:8001. Downloading model weights and generating music may require a capable GPU; check the current requirements for your computer.

## 2. Configure YouTube

1. Install Python 3.12+ and FFmpeg.
2. In Google Cloud Console, create/select a project and enable YouTube Data API v3.
3. Configure OAuth consent and create an OAuth client. Download the client JSON to song-agent-free/secrets/client_secret.json.
4. Configure the redirect URI http://localhost:8000/oauth/callback for a Web OAuth client. Use the client type and redirect setup recommended by Google's current documentation.
5. Copy .env.example to .env and adjust paths for your environment. Never commit .env, client JSON, or token JSON.
6. Keep YOUTUBE_PRIVACY_STATUS=private for the first test. Change to public only after checking that the test upload works and looks correct.

## 3. Run the agent

Start ACE-Step separately. Then open a terminal in this folder and run:

    python -m venv .venv
    # Windows: .venv\\Scripts\\activate
    # macOS/Linux:
    source .venv/bin/activate
    pip install -r requirements.txt
    cp .env.example .env
    uvicorn main:app --host 127.0.0.1 --port 8000

Open http://127.0.0.1:8000/docs and visit GET /oauth/start to authorize YouTube. The token is stored locally.

For a first test, queue one song using the API docs: POST /jobs/fill-today with JSON body {"count":1,"language":"English"}. If APP_TOKEN is set, include it in the X-App-Token header. Then check GET /health and GET /jobs.

To queue up to 50 mixed-language songs for the day, send {"count":50,"language":"mixed"}. Supported language labels include Hebrew, English, Spanish, French, Arabic, Japanese, Portuguese, and Italian. Actual vocal quality and language support depend on the installed model.

## API

- GET /health — local generator reachability and queue status.
- GET /oauth/start and GET /oauth/callback — YouTube OAuth setup.
- POST /jobs/fill-today — queue jobs up to the daily target.
- GET /jobs — inspect queue and publication status.
- POST /jobs/{job_id}/retry — retry a failed job.

## Security

This starter is intended for local use. Keep the app bound to localhost; do not expose it publicly without HTTPS and proper authentication. Protect .env, OAuth client secrets, access tokens, database, and generated media. Never put keys in GitHub commits or issues.
