import base64
import hashlib
import json
import os
import random
import sqlite3
import subprocess
import time
import uuid
from datetime import datetime, timezone, date
from pathlib import Path
from typing import Optional

import requests
from dotenv import load_dotenv
from fastapi import FastAPI, HTTPException
from fastapi.responses import RedirectResponse
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from PIL import Image, ImageDraw, ImageFont

load_dotenv()
APP_DIR = Path(__file__).resolve().parent
DB_PATH = Path(os.getenv("DATABASE_PATH", str(APP_DIR / "data/songflow.sqlite3")))
MEDIA_DIR = Path(os.getenv("MEDIA_DIR", str(APP_DIR / "media")))
SECRETS_FILE = Path(os.getenv("YOUTUBE_CLIENT_SECRETS_FILE", str(APP_DIR / "secrets/client_secret.json")))
TOKEN_FILE = Path(os.getenv("YOUTUBE_TOKEN_FILE", str(APP_DIR / "data/youtube-token.json")))
DAILY_TARGET = max(1, min(int(os.getenv("DAILY_TARGET", "50")), 50))
POLL_SECONDS = max(5, int(os.getenv("WORKER_POLL_SECONDS", "20")))
MAX_RETRIES = max(0, min(int(os.getenv("MAX_RETRIES", "3")), 8))
MIN_GAP = max(0, int(os.getenv("MIN_SECONDS_BETWEEN_JOBS", "30")))
DAILY_BUDGET = max(0.0, float(os.getenv("DAILY_BUDGET_USD", "10")))
SONG_COST = max(0.0, float(os.getenv("ESTIMATED_LYRIA_COST_PER_SONG_USD", "0.08")))
PRIVACY = os.getenv("YOUTUBE_PRIVACY_STATUS", "private").lower()
if PRIVACY not in {"private", "unlisted", "public"}:
    PRIVACY = "private"
SCOPES = ["https://www.googleapis.com/auth/youtube.upload"]
LANGUAGES = ["Hebrew", "English", "Spanish", "French", "Arabic", "Japanese", "Portuguese", "Italian"]
GENRES = ["indie pop", "acoustic folk", "synth-pop", "cinematic ballad", "Afrobeat", "Latin pop", "lo-fi soul", "dance pop", "soft rock", "ambient electronic"]
THEMES = ["hope after a hard season", "a night drive through a glowing city", "choosing yourself", "a long-distance friendship", "a new beginning", "summer memories", "finding courage", "the ocean at dawn", "a joyful celebration", "a quiet love story"]

app = FastAPI(title="SongFlow AI", version="1.0.0")
worker_started = False

def db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH, timeout=30)
    con.row_factory = sqlite3.Row
    return con

def init_db():
    with db() as con:
        con.execute("""CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY, prompt TEXT NOT NULL, prompt_hash TEXT UNIQUE NOT NULL,
            language TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL, next_attempt REAL NOT NULL DEFAULT 0,
            title TEXT, description TEXT, audio_path TEXT, video_path TEXT, youtube_id TEXT,
            error TEXT, estimated_cost REAL NOT NULL DEFAULT 0
        )""")
        con.execute("CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status, next_attempt)")
        con.commit()

def now_iso():
    return datetime.now(timezone.utc).isoformat()

def today_utc():
    return date.today().isoformat()

def today_cost_and_count(con):
    row = con.execute("SELECT COUNT(*) n, COALESCE(SUM(estimated_cost),0) cost FROM jobs WHERE substr(created_at,1,10)=?", (today_utc(),)).fetchone()
    return int(row["n"]), float(row["cost"])

def make_prompt(language="mixed"):
    lang = random.choice(LANGUAGES) if language == "mixed" else language
    genre = random.choice(GENRES)
    theme = random.choice(THEMES)
    # Avoid requests to imitate any named artist, voice, or existing song.
    return (f"Create an original full-length song in {lang}, about {theme}. Genre: {genre}. "
            "Use an original melody, original lyrics, a memorable chorus, clear verse/chorus structure, "
            "polished studio arrangement, and a distinct musical identity. Do not imitate any artist, "
            "existing recording, copyrighted lyrics, or recognizable melody. Give it an original title.")

def init_oauth_flow():
    if not SECRETS_FILE.exists():
        raise HTTPException(status_code=400, detail=f"OAuth client file missing: {SECRETS_FILE}. Add it locally; do not commit it.")
    return Flow.from_client_secrets_file(str(SECRETS_FILE), scopes=SCOPES, redirect_uri=os.getenv("YOUTUBE_REDIRECT_URI", "http://localhost:8000/oauth/callback"))

def youtube_service():
    creds = None
    if TOKEN_FILE.exists():
        try:
            creds = Credentials.from_authorized_user_file(str(TOKEN_FILE), SCOPES)
        except Exception:
            creds = None
    if creds and creds.expired and creds.refresh_token:
        creds.refresh(GoogleRequest())
        TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        TOKEN_FILE.write_text(creds.to_json(), encoding="utf-8")
    if not creds or not creds.valid:
        raise RuntimeError("YouTube is not authorized. Open GET /oauth/start and complete Google consent.")
    return build("youtube", "v3", credentials=creds, cache_discovery=False)

def parse_interaction(data):
    title = None
    audio = None
    text_parts = []
    for step in data.get("steps", []) or []:
        if step.get("type") != "model_output":
            continue
        for item in step.get("content", []) or []:
            typ = item.get("type", "")
            if typ in ("text", "output_text"):
                value = item.get("text", "")
                if value:
                    text_parts.append(value)
            elif typ == "audio" and item.get("data"):
                audio = item["data"]
    if not audio:
        # Some response variants expose a convenience output_audio field.
        out_audio = data.get("output_audio")
        if isinstance(out_audio, dict):
            audio = out_audio.get("data")
        elif isinstance(out_audio, str):
            audio = out_audio
    combined = "\n".join(text_parts)
    for line in combined.splitlines():
        cleaned = line.strip().lstrip("#").strip()
        if cleaned.lower().startswith("title:"):
            title = cleaned.split(":", 1)[1].strip()
            break
    return title, combined, audio

def generate_song(job_id, prompt):
    key = os.getenv("GEMINI_API_KEY")
    if not key:
        raise RuntimeError("GEMINI_API_KEY is not configured.")
    url = "https://generativelanguage.googleapis.com/v1beta/interactions"
    response = requests.post(url, headers={"Content-Type":"application/json", "x-goog-api-key":key},
        json={"model":"lyria-3.5","input":prompt,"response_format":{"type":"audio"}}, timeout=(20, 900))
    if response.status_code == 429:
        raise RuntimeError("Gemini/Lyria rate limit (429). Job will be retried with backoff.")
    response.raise_for_status()
    title, lyrics_text, audio_b64 = parse_interaction(response.json())
    if not audio_b64:
        raise RuntimeError("Lyria response did not contain an audio block; check current API response schema and model access.")
    try:
        audio_bytes = base64.b64decode(audio_b64, validate=True)
    except Exception as exc:
        raise RuntimeError("Lyria audio block was not valid base64.") from exc
    MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    audio_path = MEDIA_DIR / f"{job_id}.mp3"
    audio_path.write_bytes(audio_bytes)
    if not title:
        title = "Original Song " + job_id[:8]
    return title[:95], lyrics_text, str(audio_path)

def create_cover(title, job_id):
    MEDIA_DIR.mkdir(parents=True, exist_ok=True)
    image = Image.new("RGB", (1280, 720), (20, 24, 52))
    draw = ImageDraw.Draw(image)
    # Abstract equalizer/wave bars: no external assets or unlicensed imagery.
    rng = random.Random(job_id)
    for x in range(0, 1280, 24):
        h = rng.randint(40, 280)
        y = 490 - h // 2
        draw.rounded_rectangle((x, y, x + 12, y + h), radius=5, fill=(rng.randint(70,130), rng.randint(100,210), rng.randint(160,255)))
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 52)
        small = ImageFont.truetype("DejaVuSans.ttf", 25)
    except OSError:
        font, small = ImageFont.load_default(), ImageFont.load_default()
    draw.rounded_rectangle((65, 65, 1215, 285), radius=30, fill=(10, 12, 30))
    draw.text((100, 95), title[:45], font=font, fill="white")
    draw.text((102, 205), "ORIGINAL AI-GENERATED MUSIC", font=small, fill=(170, 205, 255))
    path = MEDIA_DIR / f"{job_id}-cover.jpg"
    image.save(path, quality=90)
    return path

def render_video(job_id, title, audio_path):
    cover = create_cover(title, job_id)
    output = MEDIA_DIR / f"{job_id}.mp4"
    cmd = ["ffmpeg","-y","-loop","1","-i",str(cover),"-i",audio_path,
           "-c:v","libx264","-tune","stillimage","-c:a","aac","-b:a","192k",
           "-pix_fmt","yuv420p","-shortest","-movflags","+faststart",str(output)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=1800)
    if proc.returncode:
        raise RuntimeError("ffmpeg failed: " + proc.stderr[-1200:])
    return str(output)

def upload_video(title, lyrics_text, video_path, language):
    youtube = youtube_service()
    desc = (
        f"{title}\n\nAn original AI-generated music track created for this channel.\n"
        "Music/audio generated with an AI music model. Artwork and video assembled automatically.\n\n"
        "Lyrics / generation notes:\n" + (lyrics_text[:3500] if lyrics_text else "Original AI-generated composition.") +
        "\n\n#OriginalMusic #AIMusic"
    )
    body = {
        "snippet": {"title": title[:100], "description": desc[:4900],
                    "tags": ["original music", "AI music", "original song", language.lower()],
                    "categoryId": os.getenv("YOUTUBE_CATEGORY_ID", "10"),
                    "defaultLanguage": os.getenv("YOUTUBE_DEFAULT_LANGUAGE", "en")},
        "status": {"privacyStatus": PRIVACY, "selfDeclaredMadeForKids": False,
                   "containsSyntheticMedia": os.getenv("YOUTUBE_CONTAINS_SYNTHETIC_MEDIA", "true").lower() == "true"}
    }
    media = MediaFileUpload(video_path, mimetype="video/mp4", resumable=True, chunksize=8 * 1024 * 1024)
    request = youtube.videos().insert(part="snippet,status", body=body, media_body=media, notifySubscribers=os.getenv("YOUTUBE_NOTIFY_SUBSCRIBERS","false").lower()=="true")
    response = None
    while response is None:
        status, response = request.next_chunk()
        if status:
            print(f"Upload progress: {int(status.progress() * 100)}%")
    return response["id"]

def process_job(job):
    job_id = job["id"]
    try:
        with db() as con:
            con.execute("UPDATE jobs SET status='generating', attempts=attempts+1, updated_at=?, error=NULL WHERE id=?", (now_iso(), job_id))
            con.commit()
        title, lyrics_text, audio_path = generate_song(job_id, job["prompt"])
        video_path = render_video(job_id, title, audio_path)
        with db() as con:
            con.execute("UPDATE jobs SET status='uploading', title=?, description=?, audio_path=?, video_path=?, updated_at=? WHERE id=?",
                        (title, lyrics_text[:5000], audio_path, video_path, now_iso(), job_id))
            con.commit()
        yt_id = upload_video(title, lyrics_text, video_path, job["language"])
        with db() as con:
            con.execute("UPDATE jobs SET status='published', youtube_id=?, updated_at=?, estimated_cost=? WHERE id=?",
                        (yt_id, now_iso(), SONG_COST, job_id))
            con.commit()
    except Exception as exc:
        with db() as con:
            current = con.execute("SELECT attempts FROM jobs WHERE id=?", (job_id,)).fetchone()
            attempts = int(current["attempts"]) if current else 1
            if attempts <= MAX_RETRIES:
                delay = min(3600, 60 * (2 ** max(0, attempts - 1)))
                con.execute("UPDATE jobs SET status='retry', error=?, next_attempt=?, updated_at=? WHERE id=?",
                            (str(exc)[:2000], time.time()+delay, now_iso(), job_id))
            else:
                con.execute("UPDATE jobs SET status='failed', error=?, updated_at=? WHERE id=?",
                            (str(exc)[:2000], now_iso(), job_id))
            con.commit()
        print(f"Job {job_id} failed: {exc}")

def worker_loop():
    while True:
        try:
            with db() as con:
                row = con.execute("SELECT * FROM jobs WHERE status IN ('queued','retry') AND next_attempt<=? ORDER BY created_at LIMIT 1", (time.time(),)).fetchone()
                if row:
                    count, cost = today_cost_and_count(con)
                    if cost + SONG_COST > DAILY_BUDGET:
                        con.execute("UPDATE jobs SET status='budget_paused', error='Daily budget limit reached', updated_at=? WHERE id=?", (now_iso(), row["id"]))
                        con.commit()
                        time.sleep(POLL_SECONDS)
                        continue
                    job = dict(row)
                else:
                    job = None
            if job:
                process_job(job)
                time.sleep(MIN_GAP)
            else:
                time.sleep(POLL_SECONDS)
        except Exception as exc:
            print(f"Worker loop error: {exc}")
            time.sleep(POLL_SECONDS)

@app.on_event("startup")
def startup():
    global worker_started
    init_db()
    if not worker_started:
        import threading
        threading.Thread(target=worker_loop, daemon=True, name="songflow-worker").start()
        worker_started = True

@app.get("/health")
def health():
    init_db()
    with db() as con:
        count, cost = today_cost_and_count(con)
        states = {row["status"]: row["n"] for row in con.execute("SELECT status,COUNT(*) n FROM jobs WHERE substr(created_at,1,10)=? GROUP BY status", (today_utc(),)).fetchall()}
    return {"ok": True, "daily_target": DAILY_TARGET, "jobs_created_today": count, "estimated_spend_today_usd": round(cost, 2),
            "daily_budget_usd": DAILY_BUDGET, "youtube_privacy_status": PRIVACY, "states_today": states,
            "gemini_key_configured": bool(os.getenv("GEMINI_API_KEY")), "youtube_token_exists": TOKEN_FILE.exists()}

@app.get("/oauth/start")
def oauth_start():
    flow = init_oauth_flow()
    authorization_url, state = flow.authorization_url(access_type="offline", include_granted_scopes="true", prompt="consent")
    (TOKEN_FILE.parent / "oauth-state.txt").write_text(state, encoding="utf-8")
    return RedirectResponse(authorization_url)

@app.get("/oauth/callback")
def oauth_callback(code: str, state: str):
    state_path = TOKEN_FILE.parent / "oauth-state.txt"
    if not state_path.exists() or state_path.read_text(encoding="utf-8").strip() != state:
        raise HTTPException(status_code=400, detail="OAuth state mismatch.")
    flow = init_oauth_flow()
    flow.fetch_token(code=code)
    creds = flow.credentials
    TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
    TOKEN_FILE.write_text(creds.to_json(), encoding="utf-8")
    try:
        TOKEN_FILE.chmod(0o600)
    except OSError:
        pass
    state_path.unlink(missing_ok=True)
    return {"authorized": True, "message": "YouTube OAuth saved locally. You can now queue jobs."}

@app.post("/jobs/fill-today")
def fill_today(payload: Optional[dict] = None):
    init_db()
    payload = payload or {}
    requested = int(payload.get("count", DAILY_TARGET))
    count_to_add = max(1, min(requested, DAILY_TARGET))
    language = str(payload.get("language", "mixed"))
    if language not in {"mixed", *LANGUAGES}:
        raise HTTPException(status_code=400, detail=f"language must be mixed or one of: {', '.join(LANGUAGES)}")
    inserted = 0
    with db() as con:
        count, cost = today_cost_and_count(con)
        remaining = min(count_to_add, max(0, DAILY_TARGET - count))
        if cost + (remaining * SONG_COST) > DAILY_BUDGET:
            remaining = max(0, int((DAILY_BUDGET - cost) // SONG_COST)) if SONG_COST else remaining
        for _ in range(remaining):
            prompt = make_prompt(language)
            prompt_hash = hashlib.sha256(prompt.encode("utf-8")).hexdigest()
            try:
                con.execute("INSERT INTO jobs(id,prompt,prompt_hash,language,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?)",
                            (str(uuid.uuid4()), prompt, prompt_hash, language, "queued", now_iso(), now_iso()))
                inserted += 1
            except sqlite3.IntegrityError:
                continue
        con.commit()
    return {"queued": inserted, "target": DAILY_TARGET, "language": language,
            "budget_usd": DAILY_BUDGET, "estimated_cost_for_queued_songs_usd": round(inserted * SONG_COST, 2),
            "note": "Queueing is limited by the configured daily target and estimated music-generation budget. API quotas and provider availability may reduce actual output."}

@app.get("/jobs")
def list_jobs(limit: int = 50):
    init_db()
    limit = max(1, min(limit, 200))
    with db() as con:
        rows = con.execute("SELECT id,status,attempts,created_at,updated_at,title,language,youtube_id,error,estimated_cost FROM jobs ORDER BY created_at DESC LIMIT ?", (limit,)).fetchall()
    return [dict(r) for r in rows]

@app.post("/jobs/{job_id}/retry")
def retry_job(job_id: str):
    with db() as con:
        row = con.execute("SELECT id,status FROM jobs WHERE id=?", (job_id,)).fetchone()
        if not row:
            raise HTTPException(status_code=404, detail="Job not found")
        if row["status"] not in {"failed", "budget_paused"}:
            raise HTTPException(status_code=409, detail=f"Cannot retry job in state {row['status']}")
        con.execute("UPDATE jobs SET status='queued', attempts=0, next_attempt=0, error=NULL, updated_at=? WHERE id=?", (now_iso(), job_id))
        con.commit()
    return {"queued": True, "job_id": job_id}
