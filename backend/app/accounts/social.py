import base64
import hashlib
import hmac
import json
import secrets
from urllib.parse import urlencode

import httpx
import jwt

from .service import AccountError, POLICY_VERSION, PROVIDERS


class SocialLogin:
    def __init__(self, accounts, client_factory=None):
        self.accounts = accounts
        self.client_factory = client_factory or (lambda: httpx.Client(timeout=20, follow_redirects=False))

    def config(self, provider):
        if provider not in PROVIDERS:
            raise AccountError("不支持此登录渠道。", 404)
        env = self.accounts.env
        client_id, secret = env.get(f"WAFERAI_{provider.upper()}_ID"), env.get(f"WAFERAI_{provider.upper()}_SECRET")
        if not client_id or not secret:
            raise AccountError(f"{PROVIDERS[provider]}登录尚未开通，请先配置开发者应用。", 503)
        return client_id, secret, f"{self.accounts.public_base}/auth/social/{provider}/callback"

    def start(self, provider, agreed):
        if not agreed:
            raise AccountError("请先阅读并同意使用条款和隐私政策。")
        self.config(provider)
        state, poll = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
        verifier, nonce = secrets.token_urlsafe(48), secrets.token_urlsafe(32)
        with self.accounts.transaction() as db:
            db.execute("DELETE FROM flows WHERE expires<?", (int(self.accounts.now()),))
            db.execute("INSERT INTO flows(state,poll_hash,provider,verifier,nonce,expires,user_id,claimed,status,message) VALUES(?,?,?,?,?,?,NULL,0,'pending','')", (state, self.accounts.digest(poll), provider, verifier, nonce, int(self.accounts.now()) + 600))
        return {"authorization_url": f"{self.accounts.public_base}/auth/social/launch/{state}", "poll_token": poll, "expires_in": 600}

    def flow(self, state):
        with self.accounts.lock:
            row = self.accounts.db.execute("SELECT * FROM flows WHERE state=? AND expires>? AND claimed=0 AND status='pending'", (state, int(self.accounts.now()))).fetchone()
        if not row or row["user_id"]:
            raise AccountError("授权请求已过期或已使用，请重新开始。", 401)
        return row

    def authorization_url(self, state):
        row = self.flow(state)
        provider = row["provider"]
        client_id, _, redirect = self.config(provider)
        params = {"client_id": client_id, "redirect_uri": redirect, "response_type": "code", "state": state}
        if provider == "wechat":
            params.pop("client_id")
            params.update(appid=client_id, scope="snsapi_login")
            return "https://open.weixin.qq.com/connect/qrconnect?" + urlencode(params) + "#wechat_redirect"
        if provider == "qq":
            params.update(scope="get_user_info", display="mobile")
            endpoint = "https://graph.qq.com/oauth2.0/authorize"
        elif provider == "facebook":
            params["scope"] = "public_profile"
            endpoint = "https://www.facebook.com/dialog/oauth"
        else:
            params.update(scope="openid profile", nonce=row["nonce"], code_challenge_method="S256",
                          code_challenge=base64.urlsafe_b64encode(hashlib.sha256(row["verifier"].encode()).digest()).decode().rstrip("="))
            endpoint = "https://oauth.telegram.org/auth"
        return endpoint + "?" + urlencode(params)

    def resolve(self, row, code):
        provider = row["provider"]
        client_id, secret, redirect = self.config(provider)
        with self.client_factory() as client:
            def get(url, **kwargs):
                response = client.get(url, **kwargs)
                response.raise_for_status()
                data = response.json()
                if "error" in data or data.get("errcode") or data.get("ret", 0) != 0:
                    raise AccountError("授权平台拒绝了本次登录，请重新授权。", 401)
                return data
            if provider == "wechat":
                token = get("https://api.weixin.qq.com/sns/oauth2/access_token", params={"appid": client_id, "secret": secret, "code": code, "grant_type": "authorization_code"})
                profile = get("https://api.weixin.qq.com/sns/userinfo", params={"access_token": token["access_token"], "openid": token["openid"], "lang": "zh_CN"})
                if profile["openid"] != token["openid"]:
                    raise AccountError("微信身份校验失败。", 401)
                return str(token["openid"]), profile.get("nickname", "微信用户")
            if provider == "qq":
                token = get("https://graph.qq.com/oauth2.0/token", params={"client_id": client_id, "client_secret": secret, "code": code, "redirect_uri": redirect, "grant_type": "authorization_code", "fmt": "json", "need_openid": "1"})
                subject = token["openid"]
                profile = get("https://graph.qq.com/user/get_user_info", params={"access_token": token["access_token"], "oauth_consumer_key": client_id, "openid": subject})
                return str(subject), profile.get("nickname", "QQ 用户")
            if provider == "facebook":
                token = get("https://graph.facebook.com/oauth/access_token", params={"client_id": client_id, "client_secret": secret, "code": code, "redirect_uri": redirect})
                access = token["access_token"]
                profile = get("https://graph.facebook.com/me", params={"fields": "id,name", "appsecret_proof": hmac.new(secret.encode(), access.encode(), hashlib.sha256).hexdigest()}, headers={"Authorization": f"Bearer {access}"})
                return str(profile["id"]), profile.get("name", "Facebook 用户")
            response = client.post("https://oauth.telegram.org/token", auth=(client_id, secret), data={"grant_type": "authorization_code", "code": code, "redirect_uri": redirect, "client_id": client_id, "code_verifier": row["verifier"]})
            response.raise_for_status()
            encoded = response.json()["id_token"]
            header = jwt.get_unverified_header(encoded)
            if header.get("alg") != "RS256":
                raise AccountError("Telegram 登录需在 BotFather 中使用 RS256 签名。", 401)
            keys = get("https://oauth.telegram.org/.well-known/jwks.json")["keys"]
            key = next(k for k in keys if k.get("kid") == header.get("kid") and k.get("kty") == "RSA")
            claims = jwt.decode(encoded, jwt.PyJWK.from_dict(key).key, algorithms=["RS256"], issuer="https://oauth.telegram.org", audience=client_id,
                                options={"require": ["sub", "exp", "iat", "iss", "aud", "nonce"]})
            if not hmac.compare_digest(claims["nonce"], row["nonce"]):
                raise AccountError("Telegram 授权校验失败。", 401)
            return str(claims["sub"]), claims.get("name", "Telegram 用户")

    def complete(self, provider, state, code):
        if not code or len(code) > 2048:
            raise AccountError("授权回调无效。", 401)
        with self.accounts.transaction() as db:
            row = db.execute("UPDATE flows SET status='checking' WHERE state=? AND provider=? AND expires>? AND claimed=0 AND status='pending' RETURNING *", (state, provider, int(self.accounts.now()))).fetchone()
        if not row:
            raise AccountError("授权已过期、取消或已处理。", 401)
        try:
            subject, name = self.resolve(row, code)
            if not subject or len(subject) > 256:
                raise ValueError("Invalid identity")
        except Exception as error:
            message = error.message if isinstance(error, AccountError) else "第三方身份校验失败，请检查平台配置后重新授权。"
            with self.accounts.transaction() as db:
                db.execute("UPDATE flows SET status='failed',message=? WHERE state=? AND status='checking'", (message, state))
            raise AccountError(message, 401) from None
        with self.accounts.transaction() as db:
            # Recheck while holding the transaction lock to prevent callback replay races.
            current = db.execute("SELECT 1 FROM flows WHERE state=? AND status='checking'", (state,)).fetchone()
            if not current:
                raise AccountError("本次授权已取消。", 401)
            existing = db.execute("SELECT user_id FROM identities WHERE kind=? AND subject=?", (provider, subject)).fetchone()
            user_id = existing["user_id"] if existing else secrets.token_hex(16)
            if not existing:
                db.execute("INSERT INTO users VALUES(?,?,?,?)", (user_id, str(name)[:40], int(self.accounts.now()), POLICY_VERSION))
                db.execute("INSERT INTO identities VALUES(?,?,?,NULL)", (provider, subject, user_id))
            db.execute("UPDATE flows SET user_id=?,status='complete' WHERE state=?", (user_id, state))
        return self.accounts.session(user_id)

    def poll(self, token):
        with self.accounts.transaction() as db:
            row = db.execute("SELECT * FROM flows WHERE poll_hash=? AND expires>? AND claimed=0", (self.accounts.digest(token), int(self.accounts.now()))).fetchone()
            if not row:
                raise AccountError("授权已过期或已领取，请重新开始。", 401)
            if row["status"] in {"failed", "cancelled"}:
                raise AccountError(row["message"] or "授权已取消，请重新开始。", 401)
            if not row["user_id"]:
                return {"pending": True}
            db.execute("UPDATE flows SET claimed=1,status='consumed' WHERE state=?", (row["state"],))
        return {"pending": False, **self.accounts.session(row["user_id"])}

    def cancel(self, token):
        with self.accounts.transaction() as db:
            db.execute("UPDATE flows SET status='cancelled',message='本次授权已取消。' WHERE poll_hash=? AND status IN ('pending','checking','complete')", (self.accounts.digest(token),))
        return {"message": "本次授权已取消。"}

    def fail(self, state):
        with self.accounts.transaction() as db:
            db.execute("UPDATE flows SET status='cancelled',message='已取消授权，请返回 App。' WHERE state=? AND status='pending'", (state,))
