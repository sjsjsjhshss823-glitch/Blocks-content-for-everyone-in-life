import hashlib, json, os, random, sqlite3, subprocess, threading, time, uuid
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urljoin

import requests
from dotenv import load_dotenv
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import RedirectResponse
from google.auth.transport.requests import Request as GoogleRequest
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload
from PIL import Image, ImageDraw, ImageFont

load_dotenv()
ROOT = Path(__file__).resolve().parent
DB_PATH = Path(os.getenv("DATABASE_PATH", str(ROOT / "data/jobs.sqlite3")))
MEDIA_DIR = Path(os.getenv("MEDIA_DIR", str(ROOT / "media")))
CLIENT_FILE = Path(os.getenv("YOUTUBE_CLIENT_SECRETS_FILE", str(ROOT / "secrets/client_secret.json")))
TOKEN_FILE = Path(os.getenv("YOUTUBE_TOKEN_FILE", str(ROOT / "data/youtube-token.json")))
ACE_URL = os.getenv("ACE_STEP_API_URL", "http://127.0.0.1:8001").rstrip("/")
ACE_KEY = os.getenv("ACE_STEP_API_KEY", "")
APP_TOKEN = os.getenv("APP_TOKEN", "")
DAILY_TARGET = max(1, min(int(os.getenv("DAILY_TARGET", "50")), 50))
POLL_SECONDS = max(5, int(os.getenv("WORKER_POLL_SECONDS", "15")))
MIN_GAP = max(0, int(os.getenv("MIN_SECONDS_BETWEEN_JOBS", "20")))
MAX_RETRIES = max(0, min(int(os.getenv("MAX_RETRIES", "3")), 8))
SONG_SECONDS = max(30, min(int(os.getenv("SONG_DURATION_SECONDS", "180")), 600))
PRIVACY = os.getenv("YOUTUBE_PRIVACY_STATUS", "private").lower()
if PRIVACY not in {"private", "unlisted", "public"}:
    PRIVACY = "private"
SCOPES = ["https://www.googleapis.com/auth/youtube.upload"]
LANGUAGES = [("Hebrew","he"),("English","en"),("Spanish","es"),("French","fr"),("Arabic","ar"),("Japanese","ja"),("Portuguese","pt"),("Italian","it")]
STYLES = ["indie pop with warm piano and uplifting chorus","acoustic folk with fingerpicked guitar","cinematic pop ballad with strings","synth-pop with bright analog synths","Afrobeat-inspired pop with original rhythm","Latin pop with acoustic guitar","lo-fi soul with mellow keys","dance pop with energetic drums","soft rock with live-band feel"]
THEMES = ["hope after a difficult season","a night drive through a glowing city","choosing a new beginning","a friendship across distance","summer memories","finding courage","the ocean at dawn","celebrating a small victory","a quiet love story","believing in yourself"]
app = FastAPI(title="SongFlow Free — local AI music to YouTube", version="1.0.0")
_worker_started = False

def utc_now():
    return datetime.now(timezone.utc)

def utc_iso():
    return utc_now().isoformat()

def utc_day():
    return utc_now().date().isoformat()

def db():
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    con = sqlite3.connect(DB_PATH, timeout=30)
    con.row_factory = sqlite3.Row
    return con

def init_db():
    with db() as con:
        con.execute("""CREATE TABLE IF NOT EXISTS jobs (
            id TEXT PRIMARY KEY, prompt TEXT NOT NULL, prompt_hash TEXT UNIQUE NOT NULL,
            language TEXT NOT NULL, language_code TEXT NOT NULL, style TEXT NOT NULL,
            theme TEXT NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL, updated_at TEXT NOT NULL, next_attempt REAL NOT NULL DEFAULT 0,
            title TEXT, lyrics TEXT, audio_path TEXT, video_path TEXT, youtube_id TEXT, error TEXT
        )""")
        con.execute("CREATE INDEX IF NOT EXISTS idx_jobs_status ON jobs(status,next_attempt)")
        con.commit()

def check_token(x_app_token):
    if APP_TOKEN and x_app_token != APP_TOKEN:
        raise HTTPException(status_code=401, detail="Missing or invalid X-App-Token")

def make_job(language="mixed"):
    lang_name, lang_code = random.choice(LANGUAGES) if language == "mixed" else next(x for x in LANGUAGES if x[0] == language)
    style, theme = random.choice(STYLES), random.choice(THEMES)
    prompt = (f"Create a fully original song in {lang_name}. Style: {style}. Theme: {theme}. "
              "Use original lyrics and melody, clear verse and chorus structure, expressive lead vocals, "
              "a memorable original chorus, polished arrangement, and natural ending. Do not imitate any named artist, existing song, recording, or recognizable melody.")
    return prompt, lang_name, lang_code, style, theme

def oauth_flow():
    if not CLIENT_FILE.exists():
        raise RuntimeError(f"YouTube OAuth client JSON missing at {CLIENT_FILE}. Add it locally; never commit it.")
    return Flow.from_client_secrets_file(str(CLIENT_FILE), scopes=SCOPES,
        redirect_uri=os.getenv("YOUTUBE_REDIRECT_URI","http://localhost:8000/oauth/callback"))

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
        try: TOKEN_FILE.chmod(0o600)
        except OSError: pass
    if not creds or not creds.valid:
        raise RuntimeError("YouTube is not authorized. Open /oauth/start and complete Google consent.")
    return build("youtube","v3",credentials=creds,cache_discovery=False)

def ace_headers():
    h = {"Content-Type":"application/json"}
    if ACE_KEY: h["Authorization"] = f"Bearer {ACE_KEY}"
    return h

def ace_generate(job):
    payload = {
        "prompt": job["style"], "sample_mode": True,
        "sample_query": f"Original {job['language']} vocal song about {job['theme']}; style: {job['style']}. Do not imitate existing artists or songs.",
        "thinking": True, "vocal_language": job["language_code"], "audio_format": "mp3",
        "audio_duration": SONG_SECONDS, "batch_size": 1
    }
    response = requests.post(f"{ACE_URL}/release_task", json=payload, headers=ace_headers(), timeout=60)
    response.raise_for_status()
    envelope = response.json()
    task_id = envelope.get("data", envelope).get("task_id")
    if not task_id: raise RuntimeError(f"ACE-Step did not return task_id: {str(envelope)[:700]}")
    deadline = time.time() + max(600, SONG_SECONDS * 15)
    while time.time() < deadline:
        time.sleep(5)
        check = requests.post(f"{ACE_URL}/query_result", json={"task_id_list":[task_id]}, headers=ace_headers(), timeout=60)
        check.raise_for_status()
        result_data = check.json().get("data", [])
        if isinstance(result_data, dict): result_data = [result_data]
        task = next((x for x in result_data if x.get("task_id") == task_id), result_data[0] if result_data else {})
        status = str(task.get("status",0)).lower()
        if status in {"2","failed","error"}: raise RuntimeError(f"ACE-Step generation failed: {str(task)[:900]}")
        if status in {"1","succeeded","success"}:
            raw = task.get("result", [])
            if isinstance(raw,str):
                try: raw = json.loads(raw)
                except json.JSONDecodeError: raw = []
            if isinstance(raw,dict): raw = [raw]
            item = next((x for x in raw if x.get("file")),None)
            if not item: raise RuntimeError(f"ACE-Step completed without an audio file: {str(task)[:900]}")
            audio_url = urljoin(ACE_URL + "/", item["file"].lstrip("/"))
            audio_response = requests.get(audio_url, headers=ace_headers(), timeout=300)
            audio_response.raise_for_status()
            if not audio_response.content: raise RuntimeError("ACE-Step returned an empty audio file.")
            MEDIA_DIR.mkdir(parents=True,exist_ok=True)
            audio_path = MEDIA_DIR / f"{job['id']}.mp3"
            audio_path.write_bytes(audio_response.content)
            return str(audio_path), item.get("lyrics") or task.get("lyrics") or ""
    raise TimeoutError(f"ACE-Step task {task_id} timed out.")

def make_cover(title, job_id):
    MEDIA_DIR.mkdir(parents=True,exist_ok=True)
    image = Image.new("RGB",(1280,720),(19,23,48)); draw = ImageDraw.Draw(image); rng = random.Random(job_id)
    for x in range(0,1280,22):
        h = rng.randint(35,300); y = 500-h//2
        draw.rounded_rectangle((x,y,x+12,y+h),radius=5,fill=(rng.randint(65,145),rng.randint(100,210),rng.randint(155,255)))
    try:
        font = ImageFont.truetype("DejaVuSans.ttf",48); small = ImageFont.truetype("DejaVuSans.ttf",24)
    except OSError: font = small = ImageFont.load_default()
    draw.rounded_rectangle((55,55,1225,260),radius=28,fill=(8,10,26))
    draw.text((90,90),title[:42],font=font,fill="white")
    draw.text((92,185),"ORIGINAL AI-GENERATED MUSIC",font=small,fill=(170,205,255))
    path = MEDIA_DIR / f"{job_id}-cover.jpg"; image.save(path,quality=90); return path

def render_video(job_id,title,audio_path):
    cover = make_cover(title,job_id); output = MEDIA_DIR / f"{job_id}.mp4"
    cmd = ["ffmpeg","-y","-loop","1","-i",str(cover),"-i",audio_path,"-c:v","libx264","-tune","stillimage",
           "-c:a","aac","-b:a","192k","-pix_fmt","yuv420p","-shortest","-movflags","+faststart",str(output)]
    proc = subprocess.run(cmd,capture_output=True,text=True,timeout=1800)
    if proc.returncode: raise RuntimeError("ffmpeg failed: "+proc.stderr[-1200:])
    return str(output)

def upload_video(title,lyrics,video_path,language):
    yt = youtube_service()
    description = f"{title}\n\nOriginal AI-generated music. Video artwork assembled automatically.\n\n"
    if lyrics: description += f"Lyrics:\n{lyrics[:3000]}\n\n"
    description += "#OriginalMusic #AIMusic"
    body = {"snippet":{"title":title[:100],"description":description[:4900],
        "tags":["original music","AI music","original song",language.lower()],"categoryId":os.getenv("YOUTUBE_CATEGORY_ID","10"),"defaultLanguage":"en"},
        "status":{"privacyStatus":PRIVACY,"selfDeclaredMadeForKids":False}}
    media = MediaFileUpload(video_path,mimetype="video/mp4",resumable=True,chunksize=8*1024*1024)
    request = yt.videos().insert(part="snippet,status",body=body,media_body=media)
    response = None
    while response is None: _, response = request.next_chunk()
    return response["id"]

def process_job(job):
    jid = job["id"]
    try:
        with db() as con:
            con.execute("UPDATE jobs SET status='generating',attempts=attempts+1,updated_at=?,error=NULL WHERE id=?",(utc_iso(),jid)); con.commit()
        audio_path, lyrics = ace_generate(job)
        title = f"{job['theme'].capitalize()} — {job['language']} Original Song"
        video_path = render_video(jid,title,audio_path)
        with db() as con:
            con.execute("UPDATE jobs SET status='uploading',title=?,lyrics=?,audio_path=?,video_path=?,updated_at=? WHERE id=?",
                        (title,lyrics[:5000],audio_path,video_path,utc_iso(),jid)); con.commit()
        video_id = upload_video(title,lyrics,video_path,job["language"])
        with db() as con:
            con.execute("UPDATE jobs SET status='published',youtube_id=?,updated_at=? WHERE id=?",(video_id,utc_iso(),jid)); con.commit()
    except Exception as exc:
        with db() as con:
            row = con.execute("SELECT attempts FROM jobs WHERE id=?",(jid,)).fetchone()
            attempts = int(row["attempts"]) if row else 1
            if attempts <= MAX_RETRIES:
                delay = min(3600,60*(2**max(0,attempts-1)))
                con.execute("UPDATE jobs SET status='retry',error=?,next_attempt=?,updated_at=? WHERE id=?",(str(exc)[:2000],time.time()+delay,utc_iso(),jid))
            else:
                con.execute("UPDATE jobs SET status='failed',error=?,updated_at=? WHERE id=?",(str(exc)[:2000],utc_iso(),jid))
            con.commit()
        print(f"Job {jid} failed: {exc}")

def worker_loop():
    while True:
        try:
            with db() as con:
                row = con.execute("SELECT * FROM jobs WHERE status IN ('queued','retry') AND next_attempt<=? ORDER BY created_at LIMIT 1",(time.time(),)).fetchone()
            if row: process_job(dict(row)); time.sleep(MIN_GAP)
            else: time.sleep(POLL_SECONDS)
        except Exception as exc:
            print(f"Worker error: {exc}"); time.sleep(POLL_SECONDS)

@app.on_event("startup")
def startup():
    global _worker_started
    init_db()
    if not _worker_started:
        threading.Thread(target=worker_loop,daemon=True,name="songflow-free-worker").start()
        _worker_started = True

@app.get("/health")
def health():
    init_db()
    try: ace_reachable = requests.get(f"{ACE_URL}/",timeout=3).status_code < 500
    except requests.RequestException: ace_reachable = False
    with db() as con:
        count = con.execute("SELECT COUNT(*) n FROM jobs WHERE substr(created_at,1,10)=?",(utc_day(),)).fetchone()["n"]
        statuses = {r["status"]:r["n"] for r in con.execute("SELECT status,COUNT(*) n FROM jobs WHERE substr(created_at,1,10)=? GROUP BY status",(utc_day(),)).fetchall()}
    return {"ok":True,"generator":"local ACE-Step","ace_step_reachable":ace_reachable,"daily_target":DAILY_TARGET,
            "jobs_created_today_utc":int(count),"youtube_token_exists":TOKEN_FILE.exists(),"youtube_privacy_status":PRIVACY,
            "states_today":statuses,"estimated_music_api_cost_usd":0}

@app.get("/oauth/start")
def oauth_start():
    flow = oauth_flow(); url,state = flow.authorization_url(access_type="offline",include_granted_scopes="true",prompt="consent")
    TOKEN_FILE.parent.mkdir(parents=True,exist_ok=True)
    (TOKEN_FILE.parent/"oauth-state.txt").write_text(state,encoding="utf-8")
    return RedirectResponse(url)

@app.get("/oauth/callback")
def oauth_callback(code:str,state:str):
    state_file = TOKEN_FILE.parent/"oauth-state.txt"
    if not state_file.exists() or state_file.read_text(encoding="utf-8").strip()!=state: raise HTTPException(status_code=400,detail="OAuth state mismatch.")
    flow = oauth_flow(); flow.fetch_token(code=code)
    TOKEN_FILE.parent.mkdir(parents=True,exist_ok=True); TOKEN_FILE.write_text(flow.credentials.to_json(),encoding="utf-8")
    try: TOKEN_FILE.chmod(0o600)
    except OSError: pass
    state_file.unlink(missing_ok=True)
    return {"authorized":True,"message":"YouTube authorization saved locally."}

@app.post("/jobs/fill-today")
def fill_today(payload:dict|None=None,x_app_token:str|None=Header(default=None)):
    check_token(x_app_token); init_db(); payload = payload or {}
    requested = int(payload.get("count",DAILY_TARGET)); amount = max(1,min(requested,DAILY_TARGET))
    language = str(payload.get("language","mixed"))
    if language not in {"mixed",*(name for name,_ in LANGUAGES)}: raise HTTPException(status_code=400,detail="Unsupported language.")
    inserted = 0
    with db() as con:
        today_count = con.execute("SELECT COUNT(*) n FROM jobs WHERE substr(created_at,1,10)=?",(utc_day(),)).fetchone()["n"]
        remaining = min(amount,max(0,DAILY_TARGET-int(today_count)))
        for _ in range(remaining):
            prompt,lang,code,style,theme = make_job(language)
            digest = hashlib.sha256(f"{prompt}|{uuid.uuid4()}".encode()).hexdigest()
            try:
                con.execute("INSERT INTO jobs(id,prompt,prompt_hash,language,language_code,style,theme,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    (str(uuid.uuid4()),prompt,digest,lang,code,style,theme,"queued",utc_iso(),utc_iso()))
                inserted += 1
            except sqlite3.IntegrityError: pass
        con.commit()
    return {"queued":inserted,"daily_target":DAILY_TARGET,"language":language,
            "note":"Audio generation runs locally; hardware, power, and YouTube quotas still apply."}

@app.get("/jobs")
def list_jobs(limit:int=50,x_app_token:str|None=Header(default=None)):
    check_token(x_app_token); init_db(); limit=max(1,min(limit,200))
    with db() as con:
        rows = con.execute("SELECT id,status,attempts,created_at,updated_at,title,language,youtube_id,error FROM jobs ORDER BY created_at DESC LIMIT ?",(limit,)).fetchall()
    return [dict(r) for r in rows]

@app.post("/jobs/{job_id}/retry")
def retry_job(job_id:str,x_app_token:str|None=Header(default=None)):
    check_token(x_app_token)
    with db() as con:
        row = con.execute("SELECT id,status FROM jobs WHERE id=?",(job_id,)).fetchone()
        if not row: raise HTTPException(status_code=404,detail="Job not found.")
        if row["status"] not in {"failed","retry"}: raise HTTPException(status_code=409,detail=f"Cannot retry job in state {row['status']}.")
        con.execute("UPDATE jobs SET status='queued',attempts=0,next_attempt=0,error=NULL,updated_at=? WHERE id=?",(utc_iso(),job_id)); con.commit()
    return {"queued":True,"job_id":job_id}
