import hashlib
import hmac
import json
import os
import re
import secrets
import smtplib
import sqlite3
import threading
import time
from contextlib import contextmanager
from email.message import EmailMessage
from pathlib import Path
from urllib.parse import urlsplit

import httpx

POLICY_VERSION = "2026-11-04"
PROVIDERS = {"wechat": "微信", "qq": "QQ", "facebook": "Facebook", "telegram": "Telegram"}


class AccountError(Exception):
    def __init__(self, message, status=400):
        self.message, self.status = message, status


def identity(value):
    value = value.strip()
    if re.fullmatch(r"[^\s@]{1,64}@[^\s@.]+(?:\.[^\s@.]+)+", value) and len(value) <= 254:
        return "email", value.lower()
    phone = re.sub(r"[\s()-]", "", value)
    if re.fullmatch(r"1[3-9]\d{9}", phone):
        phone = "+86" + phone
    if re.fullmatch(r"\+[1-9]\d{7,14}", phone):
        return "phone", phone
    raise AccountError("请输入有效邮箱或带国家区号的手机号。")


def password_hash(password, salt=None):
    if not 10 <= len(password) <= 128 or not any(c.isalpha() for c in password) or not any(c.isdigit() for c in password):
        raise AccountError("密码需为 10–128 位，并包含字母和数字。")
    salt = salt or secrets.token_hex(16)
    derived = hashlib.scrypt(password.encode(), salt=bytes.fromhex(salt), n=32768, r=8, p=1, maxmem=64 * 1024 * 1024)
    return salt + ":" + derived.hex()


def password_matches(password, stored):
    try:
        return hmac.compare_digest(password_hash(password, stored.split(":")[0]), stored)
    except (AccountError, ValueError, AttributeError):
        return False


class Accounts:
    def __init__(self, root: Path, env=None, sender=None, now=time.time):
        self.env = dict(os.environ if env is None else env)
        self.root = root
        root.mkdir(parents=True, exist_ok=True)
        self.development = self.env.get("WAFERAI_AUTH_DEV_OUTBOX") == "1"
        self.public_base = self.env.get("WAFERAI_AUTH_PUBLIC_URL", "http://127.0.0.1:8010").rstrip("/")
        parsed = urlsplit(self.public_base)
        if parsed.scheme != "https" and not (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "10.0.2.2"}):
            raise ValueError("Account public URL must use HTTPS, except local testing.")
        if parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
            raise ValueError("Account public URL must be an origin.")
        if self.development and parsed.scheme != "http":
            raise ValueError("Development outbox is restricted to the local test service.")
        secret_file = root / "account-secret.key"
        if not secret_file.exists():
            try:
                fd = os.open(secret_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "w") as output:
                    output.write(secrets.token_hex(32))
            except FileExistsError:
                pass
        self.secret = secret_file.read_bytes()
        if len(self.secret) != 64:
            raise ValueError("Account signing secret is incomplete; check the local service storage.")
        self.now, self.sender = now, sender or self.deliver
        self.lock = threading.RLock()
        self.db = sqlite3.connect(root / "accounts.sqlite3", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA foreign_keys=ON")
        self.db.executescript("""
          CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, policy TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS identities(kind TEXT NOT NULL, subject TEXT NOT NULL, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, password TEXT, PRIMARY KEY(kind,subject));
          CREATE INDEX IF NOT EXISTS identity_user ON identities(user_id);
          CREATE TABLE IF NOT EXISTS sessions(hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires INTEGER NOT NULL);
          CREATE INDEX IF NOT EXISTS session_expiry ON sessions(expires);
          CREATE TABLE IF NOT EXISTS codes(target TEXT NOT NULL, purpose TEXT NOT NULL, hash TEXT NOT NULL, expires INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(target,purpose));
          CREATE TABLE IF NOT EXISTS limits(key TEXT PRIMARY KEY, started INTEGER NOT NULL, count INTEGER NOT NULL);
          CREATE TABLE IF NOT EXISTS flows(state TEXT PRIMARY KEY, poll_hash TEXT UNIQUE NOT NULL, provider TEXT NOT NULL, verifier TEXT NOT NULL, nonce TEXT NOT NULL, expires INTEGER NOT NULL, user_id TEXT REFERENCES users(id) ON DELETE CASCADE, claimed INTEGER NOT NULL DEFAULT 0);
          CREATE TABLE IF NOT EXISTS tickets(hash TEXT PRIMARY KEY, user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, expires INTEGER NOT NULL);
        """)
        flow_columns = {row[1] for row in self.db.execute("PRAGMA table_info(flows)")}
        if "status" not in flow_columns:
            self.db.execute("ALTER TABLE flows ADD COLUMN status TEXT NOT NULL DEFAULT 'pending'")
            self.db.execute("ALTER TABLE flows ADD COLUMN message TEXT NOT NULL DEFAULT ''")
            self.db.execute("UPDATE flows SET status=CASE WHEN claimed=1 THEN 'consumed' WHEN user_id IS NOT NULL THEN 'complete' ELSE 'pending' END")
            self.db.commit()

    @contextmanager
    def transaction(self):
        with self.lock, self.db:
            yield self.db

    def close(self):
        self.db.close()

    def digest(self, text):
        return hmac.new(self.secret, text.encode(), hashlib.sha256).hexdigest()

    def throttle(self, key, limit, seconds):
        key = self.digest(key)
        with self.transaction() as db:
            row = db.execute("SELECT * FROM limits WHERE key=?", (key,)).fetchone()
            now = int(self.now())
            if not row or row["started"] + seconds <= now:
                db.execute("INSERT OR REPLACE INTO limits VALUES(?,?,1)", (key, now))
            elif row["count"] >= limit:
                raise AccountError("操作过于频繁，请稍后再试。", 429)
            else:
                db.execute("UPDATE limits SET count=count+1 WHERE key=?", (key,))
            db.execute("DELETE FROM limits WHERE started<?", (now - 86400,))

    def capabilities(self):
        return {"development": self.development, "policy_version": POLICY_VERSION,
                "providers": [{"id": key, "name": name, "available": bool(self.env.get(f"WAFERAI_{key.upper()}_ID") and self.env.get(f"WAFERAI_{key.upper()}_SECRET"))} for key, name in PROVIDERS.items()],
                "email": self.development or bool(self.env.get("WAFERAI_SMTP_HOST") and self.env.get("WAFERAI_SMTP_FROM")),
                "phone": self.development or bool(self.env.get("WAFERAI_SMS_URL") and self.env.get("WAFERAI_SMS_TOKEN"))}

    def deliver(self, kind, target, purpose, code):
        if self.development:
            # Explicit local-only transport, never included in an API response or application logs.
            path = self.root / "development-outbox.jsonl"
            fd = os.open(path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
            with os.fdopen(fd, "a", encoding="utf-8") as output:
                output.write(json.dumps({"target": target, "purpose": purpose, "code": code, "expires_in": 600}) + "\n")
            return
        if kind == "email":
            host, source = self.env.get("WAFERAI_SMTP_HOST"), self.env.get("WAFERAI_SMTP_FROM")
            if not host or not source:
                raise AccountError("邮箱发送服务尚未配置，请联系应用维护者。", 503)
            msg = EmailMessage()
            msg["Subject"], msg["From"], msg["To"] = "WaferAI 账号验证码", source, target
            msg.set_content(f"你的 WaferAI 验证码为 {code}，10 分钟内有效。如非本人操作，请忽略。")
            port = int(self.env.get("WAFERAI_SMTP_PORT", "465"))
            import ssl
            connection = smtplib.SMTP_SSL(host, port, timeout=15, context=ssl.create_default_context()) if port == 465 else smtplib.SMTP(host, port, timeout=15)
            with connection:
                if port != 465:
                    connection.starttls(context=ssl.create_default_context())
                if self.env.get("WAFERAI_SMTP_USER"):
                    connection.login(self.env["WAFERAI_SMTP_USER"], self.env["WAFERAI_SMTP_PASSWORD"])
                connection.send_message(msg)
        else:
            endpoint, token = self.env.get("WAFERAI_SMS_URL", ""), self.env.get("WAFERAI_SMS_TOKEN")
            if not endpoint.startswith("https://") or not token:
                raise AccountError("短信发送服务尚未配置，请联系应用维护者。", 503)
            with httpx.Client(timeout=15, follow_redirects=False) as client:
                response = client.post(endpoint, headers={"Authorization": f"Bearer {token}"}, json={"phone": target, "code": code, "purpose": purpose, "expires_in": 600})
                response.raise_for_status()
                if response.json().get("accepted") is not True:
                    raise AccountError("短信发送失败，请稍后重试。", 503)

    def send_code(self, target, purpose, ip):
        if purpose not in {"register", "reset"}:
            raise AccountError("验证码用途无效。")
        return self._send_code(target, purpose, ip)

    def _send_code(self, target, purpose, ip):
        kind, target = identity(target)
        self.throttle("send-ip:" + ip, 15, 3600)
        self.throttle("send-target-hour:" + target, 5, 3600)
        self.throttle("send-target-minute:" + target, 1, 60)
        code = f"{secrets.randbelow(1000000):06d}"
        with self.transaction() as db:
            db.execute("DELETE FROM codes WHERE expires<?", (int(self.now()),))
            db.execute("INSERT OR REPLACE INTO codes VALUES(?,?,?,?,0)", (target, purpose, self.digest(target + purpose + code), int(self.now()) + 600))
        try:
            self.sender(kind, target, "bind" if purpose.startswith("bind:") else purpose, code)
        except Exception as error:
            with self.transaction() as db:
                db.execute("DELETE FROM codes WHERE target=? AND purpose=?", (target, purpose))
            if isinstance(error, AccountError):
                raise
            raise AccountError("验证码发送失败，请检查发送服务后重试。", 503) from None
        return {"message": "验证码已发送，10 分钟内有效。" if not self.development else "测试验证码已写入本机开发收件箱，请由开发者查看。", "retry_after": 60}

    def verify_code(self, target, purpose, code):
        # Consume/update in its own transaction so a bad code cannot roll back its attempt counter.
        with self.transaction() as db:
            row = db.execute("SELECT * FROM codes WHERE target=? AND purpose=?", (target, purpose)).fetchone()
            valid = row and row["expires"] > self.now() and row["attempts"] < 5 and hmac.compare_digest(row["hash"], self.digest(target + purpose + code))
            if valid:
                db.execute("DELETE FROM codes WHERE target=? AND purpose=?", (target, purpose))
            elif row:
                db.execute("UPDATE codes SET attempts=attempts+1 WHERE target=? AND purpose=?", (target, purpose))
        if not valid:
            raise AccountError("验证码无效、已过期或尝试次数过多。")

    def register(self, target, password, code, name, agreed):
        if not agreed:
            raise AccountError("请先阅读并同意使用条款和隐私政策。")
        kind, target = identity(target)
        hashed = password_hash(password)
        self.verify_code(target, "register", code)
        user_id = secrets.token_hex(16)
        try:
            with self.transaction() as db:
                db.execute("INSERT INTO users VALUES(?,?,?,?)", (user_id, name.strip()[:40] or "WaferAI 用户", int(self.now()), POLICY_VERSION))
                db.execute("INSERT INTO identities VALUES(?,?,?,?)", (kind, target, user_id, hashed))
        except sqlite3.IntegrityError:
            raise AccountError("此账号已注册，请登录或找回密码。", 409) from None
        return self.session(user_id)

    def login(self, target, password, ip):
        kind, target = identity(target)
        self.throttle("login-ip:" + ip, 60, 900)
        self.throttle("login-target:" + target, 10, 900)
        with self.lock:
            row = self.db.execute("SELECT * FROM identities WHERE kind=? AND subject=?", (kind, target)).fetchone()
        # Also run scrypt for missing accounts, avoiding an obvious timing oracle.
        stored = row["password"] if row and row["password"] else "00" * 16 + ":" + "00" * 64
        if not password_matches(password, stored):
            raise AccountError("账号或密码不正确。", 401)
        return self.session(row["user_id"])

    def reset(self, target, password, code):
        kind, target = identity(target)
        hashed = password_hash(password)
        self.verify_code(target, "reset", code)
        with self.transaction() as db:
            row = db.execute("SELECT user_id FROM identities WHERE kind=? AND subject=?", (kind, target)).fetchone()
            if row:
                db.execute("UPDATE identities SET password=? WHERE kind=? AND subject=?", (hashed, kind, target))
                db.execute("DELETE FROM sessions WHERE user_id=?", (row["user_id"],))
        return {"message": "如账号已注册，密码已重置，请使用新密码登录。"}

    def binding_user(self, token, target):
        user = self.authenticate(token)
        with self.lock:
            session = self.db.execute("SELECT expires FROM sessions WHERE hash=?", (self.digest(token),)).fetchone()
            if not session or session["expires"] - 7 * 86400 + 900 < self.now():
                raise AccountError("绑定联系方式前，请重新登录以确认身份。", 403)
            kind, target = identity(target)
            if self.db.execute("SELECT 1 FROM identities WHERE user_id=? AND kind=?", (user["id"], kind)).fetchone():
                raise AccountError("此账号已绑定该类联系方式，暂不支持直接替换。", 409)
            if self.db.execute("SELECT 1 FROM identities WHERE kind=? AND subject=?", (kind, target)).fetchone():
                raise AccountError("此联系方式已被其他账号使用。", 409)
        return user, kind, target

    def send_binding_code(self, token, target, ip):
        user, _, target = self.binding_user(token, target)
        return self._send_code(target, "bind:" + user["id"], ip)

    def bind(self, token, target, password, code):
        user, kind, target = self.binding_user(token, target)
        hashed = password_hash(password)
        self.verify_code(target, "bind:" + user["id"], code)
        try:
            with self.transaction() as db:
                changed = db.execute("INSERT INTO identities SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM identities WHERE user_id=? AND kind=?)",
                                     (kind, target, user["id"], hashed, user["id"], kind)).rowcount
                if changed != 1:
                    raise AccountError("此账号已绑定该类联系方式。", 409)
        except sqlite3.IntegrityError:
            raise AccountError("此联系方式已被其他账号使用。", 409) from None
        return {"user": self.user(user["id"]), "message": "绑定成功，可使用此联系方式及刚设置的密码登录同一账号。"}

    def user(self, user_id):
        with self.lock:
            row = self.db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
            sources = self.db.execute("SELECT kind,subject FROM identities WHERE user_id=?", (user_id,)).fetchall()
        if not row:
            raise AccountError("登录已失效，请重新登录。", 401)
        return {"id": row["id"], "name": row["name"], "created_at": row["created_at"],
                "identities": [{"kind": r["kind"], "label": r["subject"] if r["kind"] in {"email", "phone"} else PROVIDERS[r["kind"]]} for r in sources]}

    def session(self, user_id):
        token, expiry = secrets.token_urlsafe(32), int(self.now()) + 7 * 86400
        with self.transaction() as db:
            db.execute("DELETE FROM sessions WHERE expires<?", (int(self.now()),))
            db.execute("INSERT INTO sessions VALUES(?,?,?)", (self.digest(token), user_id, expiry))
        return {"token": token, "expires_at": expiry, "user": self.user(user_id)}

    def authenticate(self, token):
        with self.lock:
            row = self.db.execute("SELECT user_id FROM sessions WHERE hash=? AND expires>?", (self.digest(token or ""), int(self.now()))).fetchone()
        if not row:
            raise AccountError("请登录后继续。", 401)
        return self.user(row["user_id"])

    def logout(self, token):
        with self.transaction() as db:
            db.execute("DELETE FROM sessions WHERE hash=?", (self.digest(token),))

    def delete(self, token):
        user = self.authenticate(token)
        with self.transaction() as db:
            db.execute("DELETE FROM codes WHERE target IN (SELECT subject FROM identities WHERE user_id=? AND kind IN ('email','phone'))", (user["id"],))
            db.execute("DELETE FROM users WHERE id=?", (user["id"],))
        return {"message": "账号及账号服务中的身份、登录会话已删除。本机晶圆与聊天记录保留。"}

    def handoff(self, token):
        user = self.authenticate(token)
        ticket = secrets.token_urlsafe(32)
        with self.transaction() as db:
            db.execute("DELETE FROM tickets WHERE expires<?", (int(self.now()),))
            db.execute("INSERT INTO tickets VALUES(?,?,?)", (self.digest(ticket), user["id"], int(self.now()) + 60))
        return ticket

    def consume_ticket(self, ticket):
        with self.transaction() as db:
            row = db.execute("SELECT * FROM tickets WHERE hash=? AND expires>?", (self.digest(ticket), int(self.now()))).fetchone()
            if not row:
                raise AccountError("网页登录链接已过期，请回到 App 重新打开。", 401)
            db.execute("DELETE FROM tickets WHERE hash=?", (self.digest(ticket),))
        return self.session(row["user_id"])
