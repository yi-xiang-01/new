import os
import re
import uuid
import requests
from flask import Flask, jsonify, request
import firebase_admin
from firebase_admin import credentials, firestore, auth, storage
from firebase_admin import firestore as admin_firestore
from firebase_admin.exceptions import FirebaseError
import datetime
from firebase_admin import auth as admin_auth

# ===== Flask & CORS =====
try:
    from flask_cors import CORS
    _has_cors = True
except Exception:
    _has_cors = False

app = Flask(__name__)
if _has_cors:
    CORS(app)

# ===== Firebase Admin 初始化 =====
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
SERVICE_ACCOUNT_PATH = os.path.join(BASE_DIR, "serviceAccountKey.json")

FIREBASE_STORAGE_BUCKET = "wonder-map-46630.appspot.com"

if not firebase_admin._apps:
    cred = credentials.Certificate(SERVICE_ACCOUNT_PATH)
    firebase_admin.initialize_app(
        cred,
        {"storageBucket": FIREBASE_STORAGE_BUCKET}
    )

db = firestore.client()
bucket = storage.bucket()

## ===== Gemini AI 設定 =====
GEMINI_MODEL = "models/gemini-2.0-flash"  

def get_gemini_api_key() -> str:
    """每次呼叫都從環境變數讀取，避免 reloader/ngrok/子程序拿不到"""
    return (os.environ.get("GEMINI_API_KEY") or "").strip()

def call_gemini(prompt: str) -> str:
    api_key = get_gemini_api_key()
    if not api_key:
        raise RuntimeError("GEMINI_API_KEY is not set")

    url = f"https://generativelanguage.googleapis.com/v1beta/{GEMINI_MODEL}:generateContent?key={api_key}"
    payload = {
        "contents": [
            {"role": "user", "parts": [{"text": prompt}]}
        ]
    }

    r = requests.post(url, json=payload, timeout=60)

    # 印出 Google 回的錯誤 body（很重要）
    if r.status_code >= 400:
        raise RuntimeError(f"Gemini HTTP {r.status_code}: {r.text}")

    data = r.json()
    try:
        return data["candidates"][0]["content"]["parts"][0]["text"]
    except Exception:
        return "（AI 沒有回覆內容）"
    
WEEKDAY_MAP = {
    0: "週一", 1: "週二", 2: "週三",
    3: "週四", 4: "週五", 5: "週六", 6: "週日"
}

def parse_hhmm_to_minutes(hhmm: str):
    if not hhmm:
        return None
    m = re.match(r"^(\d{2}):(\d{2})$", hhmm.strip())
    if not m:
        return None
    h = int(m.group(1))
    mm = int(m.group(2))
    if h < 0 or h > 23 or mm < 0 or mm > 59:
        return None
    return h * 60 + mm

def is_likely_open(opening_hours: dict, visit_hhmm: str):
    mins = parse_hhmm_to_minutes(visit_hhmm)
    if mins is None:
        return None

    weekday = WEEKDAY_MAP[datetime.datetime.now().weekday()]
    wt = (opening_hours or {}).get("weekday_text") or []
    line = next((x for x in wt if x.startswith(weekday)), None)
    if not line:
        return None

    if "休息" in line:
        return False
    if "24 小時" in line:
        return True

    ranges = re.findall(r"(\d{1,2}):(\d{2})\s*[–-]\s*(\d{1,2}):(\d{2})", line)
    if not ranges:
        return None

    for (sh, sm, eh, em) in ranges:
        start = int(sh) * 60 + int(sm)
        end = int(eh) * 60 + int(em)
        if start <= mins <= end:
            return True

    return False

def minutes_to_hhmm(mins: int) -> str:
    mins = mins % (24 * 60)
    h = mins // 60
    m = mins % 60
    return f"{h:02d}:{m:02d}"

def haversine_meters(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    from math import radians, sin, cos, sqrt, atan2
    r = 6371000.0
    dlat = radians(lat2 - lat1)
    dlng = radians(lng2 - lng1)
    a = sin(dlat/2)**2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlng/2)**2
    c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c

def estimate_travel_minutes_by_distance(meters: float, mode: str = "drive") -> int:
    # 你可自行調：drive 平均 25~35km/h 都合理
    speed_kmh = 5.0 if mode == "walk" else (18.0 if mode == "transit" else 30.0)
    minutes = (meters / 1000.0) / speed_kmh * 60.0
    buffer_min = 3 if mode in ("walk", "transit") else 8  # 紅綠燈/找車位緩衝
    import math
    return int(math.ceil(minutes)) + buffer_min

def sort_key_for_stop(stop: dict):
    st = parse_hhmm_to_minutes((stop.get("startTime") or "").strip())
    et = parse_hhmm_to_minutes((stop.get("endTime") or "").strip())
    return (
        st if st is not None else 10**9,
        et if et is not None else 10**9,
        (stop.get("name") or "")
    )

def get_next_stop_info(trip_id: str, day: int, stop_id: str):
    """
    回傳：(next_stop_dict_or_none, dist_m, travel_min, late_flag, travel_hint_text)
    late_flag: True / False / None(資料不足)
    """
    try:
        col = db.collection("trips").document(trip_id).collection("days").document(str(day)).collection("stops")
        docs = col.get()

        stops = []
        for d in docs:
            s = d.to_dict() or {}
            s["_id"] = d.id
            stops.append(s)

        stops.sort(key=sort_key_for_stop)

        idx = next((i for i, s in enumerate(stops) if s.get("_id") == stop_id), -1)
        if idx < 0 or idx >= len(stops) - 1:
            return None, None, None, None, "【移動/遲到判斷】這是當天最後一站或找不到下一站，無法計算下一段移動時間。"

        cur = stops[idx]
        nxt = stops[idx + 1]

        cur_lat = float(cur.get("lat") or 0.0)
        cur_lng = float(cur.get("lng") or 0.0)
        nxt_lat = float(nxt.get("lat") or 0.0)
        nxt_lng = float(nxt.get("lng") or 0.0)

        if (cur_lat == 0.0 and cur_lng == 0.0) or (nxt_lat == 0.0 and nxt_lng == 0.0):
            return nxt, None, None, None, "【移動/遲到判斷】缺少座標，無法計算兩站距離與移動時間。"

        dist_m = haversine_meters(cur_lat, cur_lng, nxt_lat, nxt_lng)
        travel_min = estimate_travel_minutes_by_distance(dist_m, mode="drive")

        # 出發時間：優先用本站 endTime，沒有就用 startTime
        depart_src = (cur.get("endTime") or cur.get("startTime") or "").strip()
        next_start_src = (nxt.get("startTime") or "").strip()
        depart_min = parse_hhmm_to_minutes(depart_src) if depart_src else None
        next_start_min = parse_hhmm_to_minutes(next_start_src) if next_start_src else None

        km = dist_m / 1000.0

        if depart_min is None or next_start_min is None:
            return nxt, dist_m, travel_min, None, (
                f"【移動/遲到判斷】下一站「{(nxt.get('name') or '').strip() or '未命名地點'}」距離約 {km:.1f} km，"
                f"預估移動 {travel_min} 分鐘（含緩衝）。但缺少完整時間（本站出發或下一站開始時間），無法判斷是否會遲到。"
            )

        arrive_min = depart_min + travel_min
        late = arrive_min > next_start_min
        next_name = (nxt.get("name") or "").strip() or "下一站"
        base = f"【下一站】{next_name}｜約 {km:.1f} km｜車程約 {travel_min} 分（含緩衝）"

        

        if late:
            hint = (
                f"{base} ⚠️ 可能趕不上："
                f"{minutes_to_hhmm(depart_min)} 出發約 {minutes_to_hhmm(arrive_min)} 抵達，"
                f"但行程安排為 {next_start_src}。"
                )
        else:
            hint = (
                f"{base} ✅ 時間可行："
                f"{minutes_to_hhmm(depart_min)} 出發約 {minutes_to_hhmm(arrive_min)} 抵達（{next_start_src}）。"
            )

        return nxt, dist_m, travel_min, late, hint

    except Exception as e:
        return None, None, None, None, f"【移動/遲到判斷】計算失敗：{e}"
    

PLACES_KEY = os.environ.get("GOOGLE_PLACES_API_KEY")

def fetch_place_details(place_id: str):
    if not PLACES_KEY or not place_id:
        return None

    url = "https://maps.googleapis.com/maps/api/place/details/json"
    params = {
        "place_id": place_id,
        "fields": "opening_hours,current_opening_hours,name",
        "language": "zh-TW",
        "key": PLACES_KEY
    }

    r = requests.get(url, params=params, timeout=10)
    if r.status_code != 200:
        return None

    data = r.json()
    return data.get("result")


print("GEMINI_API_KEY len =", len(get_gemini_api_key()))
print("GEMINI_MODEL =", PLACES_KEY)


# ===== 測試 API =====
@app.get("/api/hello")
def hello():
    return jsonify(message="Hello from Flask!")


# ===== Profile APIs =====

@app.get("/me/profile")
def get_profile():
    email = request.args.get("email")
    if not email:
        return jsonify(error="email is required"), 400

    doc = db.collection("users").document(email).get()
    if not doc.exists:
        return jsonify(
            email=email,
            userName="",
            userLabel="",
            introduction="",
            photoUrl=None,
            firstLogin=True
        )

    d = doc.to_dict() or {}
    return jsonify(
        email=email,
        userName=d.get("userName", ""),
        userLabel=d.get("userLabel", ""),
        introduction=d.get("introduction", ""),
        photoUrl=d.get("photoUrl"),
        firstLogin=d.get("firstLogin", True)
    )


@app.put("/me/profile")
def update_profile():
    data = request.get_json(force=True) or {}
    email = data.get("email")
    if not email:
        return jsonify(error="email is required"), 400

    updates = {
        "email": email,
        "userName": data.get("userName", ""),
        "userLabel": data.get("userLabel", ""),
        "introduction": data.get("introduction", ""),
        "firstLogin": data.get("firstLogin", True),
    }

    if data.get("photoUrl"):
        updates["photoUrl"] = data["photoUrl"]

    db.collection("users").document(email).set(updates, merge=True)
    return jsonify(ok=True)


@app.post("/me/profile/photo")
def upload_profile_photo():
    email = request.form.get("email")
    photo = request.files.get("photo")
    if not email or not photo:
        return jsonify(error="email and photo required"), 400

    safe_email = email.replace("/", "_")
    filename = f"profile_{uuid.uuid4().hex}.jpg"
    path = f"users/{safe_email}/{filename}"

    blob = bucket.blob(path)
    blob.upload_from_file(photo, content_type="image/jpeg")
    url = blob.generate_signed_url(expiration=60 * 60 * 24 * 7)

    db.collection("users").document(email).set(
        {"photoUrl": url},
        merge=True
    )
    return jsonify(photoUrl=url)


# ===== Favorites =====
@app.get("/me/favorites")
def get_favorites():
    email = request.args.get("email")
    if not email:
        return jsonify([])

    user = db.collection("users").document(email).get()
    if not user.exists:
        return jsonify([])

    fav_ids = (user.to_dict() or {}).get("favorites", [])
    results = []

    for pid in fav_ids:
        pdoc = db.collection("posts").document(pid).get()
        if pdoc.exists:
            p = pdoc.to_dict() or {}
            results.append({
                "id": pid,
                "mapName": p.get("mapName", ""),
                "mapType": p.get("mapType", ""),
                "ownerEmail": p.get("ownerEmail", "")
            })
    return jsonify(results)


# ===== Following =====
@app.get("/me/following")
def get_following():
    email = request.args.get("email")
    if not email:
        return jsonify([])

    me = db.collection("users").document(email).get()
    if not me.exists:
        return jsonify([])

    following = (me.to_dict() or {}).get("following", [])
    out = []

    for fe in following:
        u = db.collection("users").document(fe).get()
        if u.exists:
            d = u.to_dict() or {}
            out.append({
                "email": fe,
                "userName": d.get("userName", ""),
                "introduction": d.get("introduction", ""),
                "photoUrl": d.get("photoUrl")
            })
    return jsonify(out)


# ===== My Posts =====
@app.get("/me/posts")
def get_my_posts():
    email = request.args.get("email")
    if not email:
        return jsonify([])

    q = db.collection("posts").where("ownerEmail", "==", email)
    snap = q.get()

    out = []
    for doc in snap:
        p = doc.to_dict() or {}
        ts = p.get("createdAt")
        ms = int(ts.timestamp() * 1000) if ts else 0

        out.append({
            "id": doc.id,
            "mapName": p.get("mapName", ""),
            "mapType": p.get("mapType", ""),
            "createdAtMillis": ms,
            "isRecommended": bool(p.get("isRecommended", False))
        })

    out.sort(key=lambda x: x["createdAtMillis"], reverse=True)
    return jsonify(out)


@app.delete("/me/posts/<post_id>")
def delete_my_post_api(post_id):
    email = request.args.get("email")
    if not email:
        return jsonify(error="email is required"), 400

    ref = db.collection("posts").document(post_id)
    doc = ref.get()
    if not doc.exists:
        return jsonify(error="post not found"), 404

    if (doc.to_dict() or {}).get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    try:
        spots = ref.collection("spots").get()
        for s in spots:
            ref.collection("spots").document(s.id).delete()
    except Exception:
        pass

    ref.delete()
    return jsonify(ok=True)


# ===== AI API =====
@app.post("/ai/ask")
def ai_ask():
    data = request.get_json(force=True) or {}
    prompt = (data.get("prompt") or "").strip()
    if not prompt:
        return jsonify(error="prompt is required"), 400

    try:
        text = call_gemini(prompt)
        return jsonify(text=text)
    except Exception as e:
        return jsonify(error=str(e)), 500
    
from firebase_admin import firestore as admin_firestore

@app.post("/ai/voice")
def ai_voice():
    data = request.get_json(force=True) or {}

    user_text = (data.get("text") or "").strip()
    spot_name = (data.get("spotName") or "").strip() or "未命名地點"
    desc = (data.get("desc") or "").strip() or "無"
    start_time = (data.get("startTime") or "").strip()
    end_time = (data.get("endTime") or "").strip()
    lat = data.get("lat")
    lng = data.get("lng")

    if not user_text:
        return jsonify(error="text is required"), 400

    time_part = f"{start_time} - {end_time}" if start_time and end_time else "未指定"
    coord_part = f"({lat}, {lng})" if lat is not None and lng is not None else "未知"

    prompt = f"""
你是一位旅遊 APP 內的智慧導遊，請用「繁體中文」回答，內容短而實用（2~5句），不要說你不能上網。
【地點】{spot_name}
【時間】{time_part}
【座標】{coord_part}
【使用者描述】{desc}

使用者問題：{user_text}
""".strip()

    try:
        text = call_gemini(prompt).strip() or "（AI 沒有回覆內容）"
        return jsonify(text=text)
    except Exception as e:
        return jsonify(error=str(e)), 500

# ========= Posts =========

# POST /me/posts
@app.post("/me/posts")
def create_my_post():
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    map_name = (data.get("mapName") or "").strip()
    map_type = (data.get("mapType") or "").strip()
    is_rec = bool(data.get("isRecommended", False))

    if not email or not map_name or not map_type:
        return jsonify(error="email, mapName, mapType are required"), 400

    doc = {
        "ownerEmail": email,
        "mapName": map_name,
        "mapType": map_type,
        "isRecommended": is_rec,
        "createdAt": admin_firestore.SERVER_TIMESTAMP,
        "updatedAt": admin_firestore.SERVER_TIMESTAMP
    }
    ref = db.collection("posts").document()
    ref.set(doc)
    return jsonify(id=ref.id)


# GET /posts/<post_id>
@app.get("/posts/<post_id>")
def get_post_detail(post_id: str):
    doc = db.collection("posts").document(post_id).get()
    if not doc.exists:
        return jsonify(error="post not found"), 404

    p = doc.to_dict() or {}
    return jsonify(
        id=doc.id,
        ownerEmail=p.get("ownerEmail", ""),
        mapName=p.get("mapName", ""),
        mapType=p.get("mapType", ""),
        isRecommended=bool(p.get("isRecommended", False)),
    )


# PUT /me/posts/<post_id>
@app.put("/me/posts/<post_id>")
def update_my_post(post_id: str):
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    map_name = (data.get("mapName") or "").strip()
    map_type = (data.get("mapType") or "").strip()

    if not email or not map_name or not map_type:
        return jsonify(error="email, mapName, mapType are required"), 400

    ref = db.collection("posts").document(post_id)
    doc = ref.get()
    if not doc.exists:
        return jsonify(error="post not found"), 404

    cur = doc.to_dict() or {}
    if cur.get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    ref.update({
        "mapName": map_name,
        "mapType": map_type,
        "updatedAt": admin_firestore.SERVER_TIMESTAMP
    })
    return jsonify(ok=True)


# GET /me/posts/recommended?email=xxx
@app.get("/me/posts/recommended")
def get_my_recommended_post():
    email = request.args.get("email")
    if not email:
        return jsonify(id=None, mapName=None, mapType=None)

    snap = db.collection("posts") \
        .where("ownerEmail", "==", email) \
        .where("isRecommended", "==", True) \
        .limit(1).get()

    if not snap:
        return jsonify(id=None, mapName=None, mapType=None)

    doc = snap[0]
    p = doc.to_dict() or {}
    return jsonify(id=doc.id, mapName=p.get("mapName"), mapType=p.get("mapType"))


# ========= Spots =========

# GET /posts/<post_id>/spots
@app.get("/posts/<post_id>/spots")
def get_spots(post_id: str):
    post_ref = db.collection("posts").document(post_id)
    if not post_ref.get().exists:
        return jsonify([])

    snap = post_ref.collection("spots").order_by("createdAt").get()
    out = []
    for d in snap:
        s = d.to_dict() or {}
        out.append({
            "id": d.id,
            "name": s.get("name", ""),
            "description": s.get("description", ""),
            "lat": float(s.get("lat", 0.0)),
            "lng": float(s.get("lng", 0.0)),
            "photoUrl": s.get("photoUrl"),
        })
    return jsonify(out)


# POST /posts/<post_id>/spots
@app.post("/posts/<post_id>/spots")
def create_spot(post_id: str):
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    name = (data.get("name") or "").strip()
    description = (data.get("description") or "").strip()
    lat = data.get("lat")
    lng = data.get("lng")

    if not email:
        return jsonify(error="email is required"), 400
    if lat is None or lng is None:
        return jsonify(error="lat and lng are required"), 400

    post_ref = db.collection("posts").document(post_id)
    post_doc = post_ref.get()
    if not post_doc.exists:
        return jsonify(error="post not found"), 404

    if (post_doc.to_dict() or {}).get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    spot = {
        "name": name,
        "description": description,
        "lat": float(lat),
        "lng": float(lng),
        "photoUrl": None,
        "createdAt": admin_firestore.SERVER_TIMESTAMP,
        "updatedAt": admin_firestore.SERVER_TIMESTAMP,
    }
    ref = post_ref.collection("spots").document()
    ref.set(spot)
    return jsonify(id=ref.id)


# PUT /posts/<post_id>/spots/<spot_id>
@app.put("/posts/<post_id>/spots/<spot_id>")
def update_spot(post_id: str, spot_id: str):
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    name = (data.get("name") or "").strip()
    description = (data.get("description") or "").strip()

    if not email:
        return jsonify(error="email is required"), 400

    post_ref = db.collection("posts").document(post_id)
    post_doc = post_ref.get()
    if not post_doc.exists:
        return jsonify(error="post not found"), 404

    if (post_doc.to_dict() or {}).get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    spot_ref = post_ref.collection("spots").document(spot_id)
    if not spot_ref.get().exists:
        return jsonify(error="spot not found"), 404

    spot_ref.update({
        "name": name,
        "description": description,
        "updatedAt": admin_firestore.SERVER_TIMESTAMP
    })
    return jsonify(ok=True)


# DELETE /posts/<post_id>/spots/<spot_id>?email=xxx
@app.delete("/posts/<post_id>/spots/<spot_id>")
def delete_spot(post_id: str, spot_id: str):
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify(error="email is required"), 400

    post_ref = db.collection("posts").document(post_id)
    post_doc = post_ref.get()
    if not post_doc.exists:
        return jsonify(error="post not found"), 404

    if (post_doc.to_dict() or {}).get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    spot_ref = post_ref.collection("spots").document(spot_id)
    if not spot_ref.get().exists:
        return jsonify(error="spot not found"), 404

    spot_ref.delete()
    return jsonify(ok=True)


# POST /posts/<post_id>/spots/<spot_id>/photo  (multipart: email + photo)
@app.post("/posts/<post_id>/spots/<spot_id>/photo")
def upload_spot_photo(post_id: str, spot_id: str):
    email = (request.form.get("email") or "").strip()
    photo = request.files.get("photo")

    if not email or not photo:
        return jsonify(error="email and photo are required"), 400

    post_ref = db.collection("posts").document(post_id)
    post_doc = post_ref.get()
    if not post_doc.exists:
        return jsonify(error="post not found"), 404

    if (post_doc.to_dict() or {}).get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    spot_ref = post_ref.collection("spots").document(spot_id)
    if not spot_ref.get().exists:
        return jsonify(error="spot not found"), 404

    filename = f"spot_{uuid.uuid4().hex}.jpg"
    storage_path = f"posts/{post_id}/spots/{spot_id}/{filename}"

    blob = bucket.blob(storage_path)
    blob.upload_from_file(photo, content_type="image/jpeg")

    # 7天簽名網址（你目前的做法）
    url = blob.generate_signed_url(expiration=60 * 60 * 24 * 7)

    spot_ref.update({"photoUrl": url, "updatedAt": admin_firestore.SERVER_TIMESTAMP})
    return jsonify(ok=True)

# ========= Trips API（PathActivity 用） =========

def _trip_doc_to_res(doc):
    d = doc.to_dict() or {}
    def ts_to_ms(ts):
        try:
            return int(ts.timestamp() * 1000) if ts else None
        except Exception:
            return None

    return {
        "id": doc.id,
        "ownerEmail": d.get("ownerEmail", ""),
        "title": d.get("title", ""),
        "collaborators": d.get("collaborators", []) or [],
        "createdAtMillis": ts_to_ms(d.get("createdAt")),
        "startDateMillis": ts_to_ms(d.get("startDate")),
        "endDateMillis": ts_to_ms(d.get("endDate")),
        "days": int(d.get("days", 7) or 7),
    }

@app.post("/me/trips/<trip_id>/collaborators")
def add_trip_collaborator(trip_id):
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    collab = (data.get("collaboratorEmail") or "").strip()

    if not email or not collab:
        return jsonify(error="email and collaboratorEmail are required"), 400
    if email == collab:
        return jsonify(error="cannot add yourself"), 400

    ref = db.collection("trips").document(trip_id)
    snap = ref.get()
    if not snap.exists:
        return jsonify(error="trip not found"), 404

    trip = snap.to_dict() or {}
    owner = (trip.get("ownerEmail") or "").strip()


    if email != owner:
        return jsonify(error="only owner can add collaborators"), 403


    ref.update({
        "collaborators": admin_firestore.ArrayUnion([collab])
    })

    return jsonify(ok=True)

# GET /me/trips?email=xxx  （我擁有 + 我是協作者）
@app.get("/me/trips")
def get_my_trips():
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify([])

    merged = {}

    mine = db.collection("trips").where("ownerEmail", "==", email).get()
    for doc in mine:
        merged[doc.id] = _trip_doc_to_res(doc)

    shared = db.collection("trips").where("collaborators", "array_contains", email).get()
    for doc in shared:
        merged[doc.id] = _trip_doc_to_res(doc)

    # createdAtMillis desc
    out = list(merged.values())
    out.sort(key=lambda x: x.get("createdAtMillis") or 0, reverse=True)
    return jsonify(out)

# POST /me/trips  body: {email,title,startMillis,endMillis}
@app.post("/me/trips")
def create_trip():
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    title = (data.get("title") or "").strip() or "我的行程"
    start_ms = data.get("startMillis")
    end_ms = data.get("endMillis")

    if not email:
        return jsonify(error="email is required"), 400
    if start_ms is None or end_ms is None:
        return jsonify(error="startMillis and endMillis are required"), 400

    # 最多 7 天
    start_ms = int(start_ms)
    end_ms = int(end_ms)
    max_end = start_ms + 6 * 86_400_000
    if end_ms > max_end:
        end_ms = max_end

    days = int((end_ms - start_ms) / 86_400_000) + 1
    days = max(1, min(7, days))

    doc = {
    "ownerEmail": email,
    "title": title,
    "days": days,
    "collaborators": [],
    "createdAt": admin_firestore.SERVER_TIMESTAMP,
    "startDate": datetime.datetime.fromtimestamp(start_ms / 1000),
    "endDate": datetime.datetime.fromtimestamp(end_ms / 1000),
    }

    ref = db.collection("trips").document()
    ref.set(doc)

    # 回傳 id
    return jsonify(id=ref.id)

# PUT /me/trips/<trip_id>/title  body:{email,title}
@app.put("/me/trips/<trip_id>/title")
def rename_trip(trip_id: str):
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    title = (data.get("title") or "").strip()

    if not email:
        return jsonify(error="email is required"), 400
    if not title:
        return jsonify(error="title is required"), 400

    ref = db.collection("trips").document(trip_id)
    doc = ref.get()
    if not doc.exists:
        return jsonify(error="trip not found"), 404

    cur = doc.to_dict() or {}
    if cur.get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    ref.update({"title": title})
    return jsonify(ok=True)

# PUT /me/trips/<trip_id>/dates  body:{email,startMillis,endMillis}
@app.put("/me/trips/<trip_id>/dates")
def change_trip_dates(trip_id: str):
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    start_ms = data.get("startMillis")
    end_ms = data.get("endMillis")

    if not email:
        return jsonify(error="email is required"), 400
    if start_ms is None or end_ms is None:
        return jsonify(error="startMillis and endMillis are required"), 400

    ref = db.collection("trips").document(trip_id)
    doc = ref.get()
    if not doc.exists:
        return jsonify(error="trip not found"), 404

    cur = doc.to_dict() or {}
    if cur.get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    start_ms = int(start_ms)
    end_ms = int(end_ms)
    max_end = start_ms + 6 * 86_400_000
    if end_ms > max_end:
        end_ms = max_end

    days = int((end_ms - start_ms) / 86_400_000) + 1
    days = max(1, min(7, days))

    ref.update({
        "startDate": admin_firestore.Timestamp.from_millis(start_ms),
        "endDate": admin_firestore.Timestamp.from_millis(end_ms),
        "days": days
    })
    return jsonify(ok=True)

# DELETE /me/trips/<trip_id>?email=xxx
@app.delete("/me/trips/<trip_id>")
def delete_trip(trip_id: str):
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify(error="email is required"), 400

    ref = db.collection("trips").document(trip_id)
    doc = ref.get()
    if not doc.exists:
        return jsonify(error="trip not found"), 404

    cur = doc.to_dict() or {}
    if cur.get("ownerEmail") != email:
        return jsonify(error="permission denied"), 403

    ref.delete()
    return jsonify(ok=True)

# ========= Public Posts API（給 RecommendActivity 用） =========
# GET /posts/public?limit=300
@app.get("/posts/public")
def get_public_posts():
    try:
        limit = int(request.args.get("limit", "300"))
    except Exception:
        limit = 300
    limit = max(1, min(limit, 500))

    q = db.collection("posts")
    try:
        q = q.order_by("createdAt", direction=firestore.Query.DESCENDING)
    except Exception:
        pass

    snap = q.limit(limit).get()

    results = []
    for doc in snap:
        p = doc.to_dict() or {}

        ts = p.get("createdAt")
        if ts:
            try:
                created_ms = int(ts.timestamp() * 1000)
            except Exception:
                created_ms = 0
        else:
            created_ms = 0

        results.append({
            "id": doc.id,
            "ownerEmail": p.get("ownerEmail", ""),
            "mapName": p.get("mapName", ""),
            "mapType": p.get("mapType", ""),
            "createdAtMillis": created_ms,
            "likes": int(p.get("likes", 0) or 0),
            "isRecommended": bool(p.get("isRecommended", False))
        })

    # 保險排序
    results.sort(key=lambda x: x.get("createdAtMillis", 0), reverse=True)
    return jsonify(results)

# ========= Auth APIs =========
# POST /auth/register
# body: { "email": "...", "password": "..." }

@app.post("/auth/register")
def register():
    data = request.get_json(force=True) or {}
    email = (data.get("email") or "").strip()
    password = data.get("password") or ""

    if not email or not password:
        return jsonify(error="email and password are required"), 400

    if len(password) < 6:
        return jsonify(error="password must be at least 6 characters"), 400

    try:
        # 1️⃣ 建立 Firebase Auth 使用者
        user = admin_auth.create_user(
            email=email,
            password=password
        )

        # 2️⃣ 建立 Firestore 個人資料（用 email 當 docId）
        profile = {
            "uid": user.uid,
            "email": email,
            "userName": "使用者姓名",
            "userLabel": "個人化標籤",
            "introduction": "個人簡介",
            "photoUrl": None,
            "createdAt": admin_firestore.SERVER_TIMESTAMP,
            "following": [],
            "favorites": [],
            "firstLogin": True
        }

        db.collection("users").document(email).set(profile, merge=True)

        return jsonify(ok=True)

    except admin_auth.EmailAlreadyExistsError:
        return jsonify(error="email already exists"), 409
    except Exception as e:
        return jsonify(error=str(e)), 500
    
    # ===== Search Posts API（給 SearchActivity 用）=====
# GET /posts/search?q=xxx&limit=300

@app.get("/posts/search")
def search_posts():
    q = (request.args.get("q") or "").strip()
    limit = int(request.args.get("limit") or 300)

    if not q:
        return jsonify([])

    # 先抓近 N 筆（跟你 Android 原本一致：抓 300 再做加權）
    ref = db.collection("posts")
    try:
        snap = ref.order_by("createdAt", direction=firestore.Query.DESCENDING).limit(limit).get()
    except Exception:
        snap = ref.limit(limit).get()

    rows = []
    for doc in snap:
        p = doc.to_dict() or {}
        ts = p.get("createdAt")
        if ts:
            try:
                created_ms = int(ts.timestamp() * 1000)
            except Exception:
                created_ms = 0
        else:
            created_ms = 0

        rows.append({
            "id": doc.id,
            "ownerEmail": p.get("ownerEmail", ""),
            "mapName": p.get("mapName", ""),
            "mapType": p.get("mapType", ""),
            "createdAtMillis": created_ms
        })

    # 保險排序（新→舊）
    rows.sort(key=lambda x: x.get("createdAtMillis", 0), reverse=True)
    return jsonify(rows)

# ========= Trips Stops APIs =========

def _ms_from_ts(ts):
    if not ts:
        return 0
    try:
        return int(ts.timestamp() * 1000)
    except Exception:
        return 0

# DELETE /trips/<tripId>/days/<day>/stops/<stopId>?email=xxx
@app.delete("/trips/<trip_id>/days/<int:day>/stops/<stop_id>")
def delete_trip_day_stop(trip_id: str, day: int, stop_id: str):
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify(error="email is required"), 400

    day = max(1, min(day, 7))

    # 1️⃣ 取得行程
    trip_ref = db.collection("trips").document(trip_id)
    trip_doc = trip_ref.get()
    if not trip_doc.exists:
        return jsonify(error="trip not found"), 404

    trip = trip_doc.to_dict() or {}
    owner = trip.get("ownerEmail")
    collaborators = trip.get("collaborators", []) or []

    # 2️⃣ 權限檢查
    if email != owner and email not in collaborators:
        return jsonify(error="permission denied"), 403

    # 3️⃣ 取得 stop
    stop_ref = (
        trip_ref
        .collection("days").document(str(day))
        .collection("stops").document(stop_id)
    )

    if not stop_ref.get().exists:
        return jsonify(error="stop not found"), 404

    # 4️⃣ 刪除
    stop_ref.delete()

    return jsonify(ok=True)

# GET /me/trips/<tripId>/days/<day>/stops?email=xxx
@app.get("/me/trips/<trip_id>/days/<int:day>/stops")
def get_trip_day_stops(trip_id: str, day: int):
    email = request.args.get("email")
    if not email:
        return jsonify(error="email is required"), 400
    day = max(1, min(day, 7))

    # （簡化版權限）只要是 owner 或 collaborators 才能讀
    trip_doc = db.collection("trips").document(trip_id).get()
    if not trip_doc.exists:
        return jsonify([])

    trip = trip_doc.to_dict() or {}
    owner = trip.get("ownerEmail")
    collaborators = trip.get("collaborators", []) or []
    if email != owner and email not in collaborators:
        return jsonify(error="permission denied"), 403

    ref = db.collection("trips").document(trip_id)\
        .collection("days").document(str(day))\
        .collection("stops")

    # createdAt 若沒索引就不用 order_by，先保險 client 排序
    try:
        snap = ref.order_by("createdAt", direction=firestore.Query.ASCENDING).get()
    except Exception:
        snap = ref.get()

    out = []
    for d in snap:
        s = d.to_dict() or {}
        out.append({
            "id": d.id,
            "name": s.get("name", ""),
            "description": s.get("description", ""),
            "lat": float(s.get("lat") or 0.0),
            "lng": float(s.get("lng") or 0.0),
            "photoUrl": s.get("photoUrl"),
            "startTime": s.get("startTime", ""),
            "endTime": s.get("endTime", ""),
            "aiSuggestion": s.get("aiSuggestion", ""),
            "category": s.get("category", "景點"),
            "createdAtMillis": _ms_from_ts(s.get("createdAt"))
        })

    out.sort(key=lambda x: x.get("createdAtMillis", 0))
    return jsonify(out)

# POST /me/trips/<tripId>/days/<day>/stops
# 新增一個行程點（給 PickLocationActivity 用）
@app.post("/me/trips/<trip_id>/days/<int:day>/stops")
def add_trip_day_stop(trip_id: str, day: int):
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify(error="email is required"), 400

    day = max(1, min(day, 7))

    data = request.get_json(force=True) or {}
    name = (data.get("name") or "新景點").strip()
    description = (data.get("description") or "").strip()
    lat = data.get("lat")
    lng = data.get("lng")
    category = (data.get("category") or "景點").strip()

    # ✅ 新增：讀時間（Android 會用 HH:mm）
    start_time = (data.get("startTime") or "").strip()
    end_time = (data.get("endTime") or "").strip()
    place_id = data.get("placeId")

    if lat is None or lng is None:
        return jsonify(error="lat and lng are required"), 400

    # （可選）時間格式檢查：允許空值或 HH:mm
    hhmm = re.compile(r"^\d{2}:\d{2}$")
    if start_time and not hhmm.match(start_time):
        return jsonify(error="startTime format must be HH:mm"), 400
    if end_time and not hhmm.match(end_time):
        return jsonify(error="endTime format must be HH:mm"), 400

    # （可選）若兩個時間都有，檢查 start <= end（字串 HH:mm 直接比可用）
    if start_time and end_time and start_time > end_time:
        return jsonify(error="startTime must be <= endTime"), 400

    # 權限檢查（跟 GET 一致）
    trip_ref = db.collection("trips").document(trip_id)
    trip_doc = trip_ref.get()
    if not trip_doc.exists:
        return jsonify(error="trip not found"), 404

    trip = trip_doc.to_dict() or {}
    owner = trip.get("ownerEmail")
    collaborators = trip.get("collaborators", []) or []

    if email != owner and email not in collaborators:
        return jsonify(error="permission denied"), 403

    # ✅ 新增 stop（把時間存進去）
    stop = {
        "name": name,
        "description": description,
        "lat": float(lat),
        "lng": float(lng),
        "photoUrl": None,
        "startTime": start_time,   # ✅ 改這裡
        "endTime": end_time,       # ✅ 改這裡
        "aiSuggestion": "",
        "category": category,
        "createdAt": admin_firestore.SERVER_TIMESTAMP,
        "updatedAt": admin_firestore.SERVER_TIMESTAMP,
    }

    ref = trip_ref \
        .collection("days").document(str(day)) \
        .collection("stops").document()

    ref.set(stop)

    return jsonify(id=ref.id), 200

@app.post("/trips/<trip_id>/days/<int:day>/stops/<stop_id>/photo")
def upload_trip_stop_photo(trip_id: str, day: int, stop_id: str):
    email = (request.form.get("email") or "").strip()
    photo = request.files.get("photo")

    if not email or not photo:
        return jsonify(error="email and photo are required"), 400

    day = max(1, min(day, 7))

    # 權限檢查：owner 或 collaborator 才能上傳
    trip_ref = db.collection("trips").document(trip_id)
    trip_doc = trip_ref.get()
    if not trip_doc.exists:
        return jsonify(error="trip not found"), 404

    trip = trip_doc.to_dict() or {}
    owner = trip.get("ownerEmail")
    collaborators = trip.get("collaborators", []) or []

    if email != owner and email not in collaborators:
        return jsonify(error="permission denied"), 403

    # stop 是否存在
    stop_ref = trip_ref \
        .collection("days").document(str(day)) \
        .collection("stops").document(stop_id)

    stop_doc = stop_ref.get()
    if not stop_doc.exists:
        return jsonify(error="stop not found"), 404

    # 上傳到 Storage
    filename = f"stop_{stop_id}_{uuid.uuid4().hex}.jpg"
    storage_path = f"trips/{trip_id}/days/{day}/stops/{stop_id}/{filename}"

    blob = bucket.blob(storage_path)
    blob.upload_from_file(photo, content_type="image/jpeg")

    # 簽名網址（7天）—測試很方便
    url = blob.generate_signed_url(expiration=60 * 60 * 24 * 7)

    # 寫回 Firestore
    stop_ref.set(
        {"photoUrl": url, "updatedAt": admin_firestore.SERVER_TIMESTAMP},
        merge=True
    )

    return jsonify(photoUrl=url), 200

# POST /me/trips/<tripId>/days/<day>/stops/<stopId>/ai?email=xxx
# body: { "prompt": "....(optional)" }
AI_AFFECT_FIELDS = {
    "name",
    "description",
    "category",
    "startTime",
    "endTime",
    "lat",
    "lng",
    "openingHours",
}

def build_stop_ai_prompt(trip_id: str, day: int, stop_id: str) -> tuple[str, any]:
    """
    讀取 stop + 組合 prompt
    回傳：(prompt, stop_ref)
    """
    day = max(1, min(day, 7))

    stop_ref = (
        db.collection("trips").document(trip_id)
        .collection("days").document(str(day))
        .collection("stops").document(stop_id)
    )
    stop_doc = stop_ref.get()
    if not stop_doc.exists:
        raise RuntimeError("stop not found")

    s = stop_doc.to_dict() or {}

    

    place_id = s.get("placeId")
    opening_hours = s.get("openingHours")

    if not opening_hours and place_id:
        opening_hours = fetch_place_opening_hours(place_id)

        stop_ref.set(
        {
            "openingHoursSource": "google_places",
            "openingHoursFetchedAt": admin_firestore.SERVER_TIMESTAMP,
            "openingHoursFetchOk": bool(opening_hours),
        },
        merge=True
    )

        if opening_hours:
            stop_ref.set(
                {
                    "openingHours": opening_hours,
                    },
                    merge=True
                    )
            s["openingHours"] = opening_hours


    

    # ===== 組內容 =====
    name = (s.get("name") or "").strip() or "未命名地點"
    category = (s.get("category") or "景點").strip() or "景點"
    start_time = (s.get("startTime") or "").strip()
    end_time = (s.get("endTime") or "").strip()
    time_part = f"{start_time} - {end_time}" if start_time and end_time else "未指定時間"
    lat = s.get("lat") or 0.0
    lng = s.get("lng") or 0.0
    desc = (s.get("description") or "").strip() or "無"

    # ===== 營業時間判斷 =====
    opening_hours = s.get("openingHours")
    open_state = None
    if opening_hours and start_time:
        open_state = is_likely_open(opening_hours, start_time)

    if open_state is True:
        open_hint = "【營業狀態】你安排的時間可能在營業時間內，請正常給建議。"
    elif open_state is False:
        open_hint = "【營業狀態】你安排的時間可能不在營業時間內，請明確提醒「可能不在營業時間」，並給替代方案（改時間或附近備案）。"
    else:
        open_hint = "【營業狀態】查不到確切營業時間，請不要亂猜，改成提醒使用者自行確認營業時間。"

    # ===== 下一站距離/遲到風險判斷 =====
    next_stop, dist_m, travel_min, late_flag, travel_hint = get_next_stop_info(trip_id, day, stop_id)

    if late_flag is True:
        late_rule = "【遲到規則】你必須明確提醒「可能遲到」，並提供至少 2 個可行解法（例如：縮短本站停留、調整下一站開始時間、改變交通方式、或把下一站改成備案）。"
    elif late_flag is False:
        late_rule = "【遲到規則】時間看起來可行，但仍可給 1 句提醒（例如：預留緩衝、注意路況）。"
    else:
        late_rule = "【遲到規則】資料不足無法判斷遲到，請用保守語氣提醒使用者自行確認路況與時間。"

    prompt = f"""
你是一位旅遊行程規劃助理，請用「繁體中文」為下面這個行程點產生一段「很像旅遊 APP 卡片內的建議文字」。

{open_hint}

{travel_hint}
{late_rule}

【地點】{name}
【類型】{category}
【時間】{time_part}
【座標】({lat}, {lng})
【使用者描述】{desc}

輸出規則：
1) 1~2 句即可，不要條列
2) 不要太長（約 35-70 字；若要提醒遲到可略長）
3) 不要出現「我無法查詢網路」之類字句
4) 若可能不在營業時間內，必須出現「可能不在營業時間」的提醒
5) 若可能遲到，必須明確提醒並給至少 2 個解法
""".strip()

    return prompt, stop_ref


def build_and_generate_ai_for_stop(trip_id: str, day: int, stop_id: str) -> str:
    """
    ✅ 共用：產生 AI 建議 + 寫回 Firestore，回傳文字
    """
    prompt, stop_ref = build_stop_ai_prompt(trip_id, day, stop_id)

    text = call_gemini(prompt).strip()
    if not text:
        text = "（暫時無法產生建議，請稍後再試）"

    stop_ref.set(
        {
            "aiSuggestion": text,
            "aiUpdatedAt": admin_firestore.SERVER_TIMESTAMP,
            "updatedAt": admin_firestore.SERVER_TIMESTAMP,  # 可選：同步更新
        },
        merge=True
    )
    return text


# ==========================================
# 1) 手動生成 AI（POST）
# ==========================================
@app.post("/me/trips/<trip_id>/days/<int:day>/stops/<stop_id>/ai")
def generate_stop_ai_and_save(trip_id: str, day: int, stop_id: str):
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify(error="email is required"), 400

    day = max(1, min(day, 7))

    # ===== 權限檢查（owner 或 collaborators）=====
    trip_doc = db.collection("trips").document(trip_id).get()
    if not trip_doc.exists:
        return jsonify(error="trip not found"), 404

    trip = trip_doc.to_dict() or {}
    owner = (trip.get("ownerEmail") or "").strip()
    collaborators = trip.get("collaborators", []) or []
    if email != owner and email not in collaborators:
        return jsonify(error="permission denied"), 403

    # ===== 直接用共用方法生成 =====
    try:
        text = build_and_generate_ai_for_stop(trip_id, day, stop_id)
        return jsonify(text=text)
    except RuntimeError as e:
        msg = str(e)
        if "stop not found" in msg:
            return jsonify(error="stop not found"), 404
        return jsonify(error=msg), 400
    except Exception as e:
        return jsonify(error=str(e)), 500


# ==========================================
# 2) 更新 stop（PUT）— 有改到影響 AI 的欄位就自動刷新
# ==========================================
@app.put("/me/trips/<trip_id>/days/<int:day>/stops/<stop_id>")
def update_trip_day_stop(trip_id: str, day: int, stop_id: str):
    email = (request.args.get("email") or "").strip()
    if not email:
        return jsonify(error="email is required"), 400
    day = max(1, min(day, 7))

    # ===== 權限檢查 =====
    trip_doc = db.collection("trips").document(trip_id).get()
    if not trip_doc.exists:
        return jsonify(error="trip not found"), 404

    trip = trip_doc.to_dict() or {}
    owner = (trip.get("ownerEmail") or "").strip()
    collaborators = trip.get("collaborators", []) or []
    if email != owner and email not in collaborators:
        return jsonify(error="permission denied"), 403

    stop_ref = (
        db.collection("trips").document(trip_id)
        .collection("days").document(str(day))
        .collection("stops").document(stop_id)
    )
    stop_doc = stop_ref.get()
    if not stop_doc.exists:
        return jsonify(error="stop not found"), 404

    data = request.get_json(force=True) or {}

    # ===== 收集更新欄位 =====
    updates = {}

    # 文字欄位
    for k in ["name", "description", "category", "startTime", "endTime"]:
        if k in data:
            updates[k] = (data.get(k) or "").strip()

    # 數字欄位
    if "lat" in data:
        updates["lat"] = float(data.get("lat") or 0.0)
    if "lng" in data:
        updates["lng"] = float(data.get("lng") or 0.0)

    # 物件欄位
    if "openingHours" in data:
        updates["openingHours"] = data.get("openingHours")

    if not updates:
        return jsonify(ok=True, refreshed=False)

    # ===== 寫入 Firestore =====
    updates["updatedAt"] = admin_firestore.SERVER_TIMESTAMP
    stop_ref.set(updates, merge=True)

    # ===== 判斷是否需要刷新 AI =====
    need_refresh = any(k in AI_AFFECT_FIELDS for k in updates.keys())
    if not need_refresh:
        return jsonify(ok=True, refreshed=False)

    # ===== 自動刷新 AI =====
    try:
        text = build_and_generate_ai_for_stop(trip_id, day, stop_id)
        return jsonify(ok=True, refreshed=True, aiSuggestion=text)
    except Exception as e:
        # 更新成功，但 AI 刷新失敗
        return jsonify(ok=True, refreshed=False, aiError=str(e))



# ===== 啟動 =====
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True, use_reloader=False)

