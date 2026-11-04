import json
import tempfile
import unittest
import time
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from unittest.mock import patch
import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.accounts.routes import create_service, install_accounts
from app.accounts.service import AccountError, Accounts, identity
from app.accounts.social import SocialLogin


class AccountTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.sent = []
        self.clock = [1000]
        self.service = Accounts(Path(self.temp.name), env={}, sender=lambda *args: self.sent.append(args), now=lambda: self.clock[0])

    def tearDown(self):
        self.service.close()
        self.temp.cleanup()

    def register(self, target="test@example.invalid"):
        self.service.send_code(target, "register", "local")
        return self.service.register(target, "ExamplePassword123", self.sent[-1][-1], "测试用户", True)

    def test_normalizes_verified_identities(self):
        self.assertEqual(identity(" Test@Example.invalid "), ("email", "test@example.invalid"))
        self.assertEqual(identity("13800000000"), ("phone", "+8613800000000"))
        for bad in ["invalid", "x\r\nBcc:z@example.invalid", "+00", "1@x"]:
            with self.assertRaises(AccountError): identity(bad)

    def test_verified_binding_adds_login_without_creating_another_account(self):
        result = self.register()
        self.service.send_binding_code(result["token"], "13800000000", "local")
        code = self.sent[-1][-1]
        bound = self.service.bind(result["token"], "13800000000", "PhonePassword123", code)
        self.assertEqual(len(bound["user"]["identities"]), 2)
        self.assertEqual(self.service.login("13800000000", "PhonePassword123", "local")["user"]["id"], result["user"]["id"])
        self.assertEqual(self.service.login("test@example.invalid", "ExamplePassword123", "local")["user"]["id"], result["user"]["id"])
        with self.assertRaises(AccountError): self.service.bind(result["token"], "+8613800000001", "PhonePassword123", code)

    def test_binding_cannot_use_another_users_code_or_merge_registered_accounts(self):
        first = self.register()
        second = self.register("second@example.invalid")
        self.service.send_binding_code(first["token"], "13800000000", "local")
        code = self.sent[-1][-1]
        with self.assertRaises(AccountError): self.service.bind(second["token"], "13800000000", "PhonePassword123", code)
        self.assertEqual(len(self.service.authenticate(second["token"])["identities"]), 1)
        self.service.bind(first["token"], "13800000000", "PhonePassword123", code)
        with self.assertRaises(AccountError) as conflict: self.service.send_binding_code(second["token"], "13800000000", "local")
        self.assertEqual(conflict.exception.status, 409)

    def test_binding_requires_valid_recent_session_and_cannot_reuse_registration_code(self):
        result = self.register()
        self.service.send_code("13800000000", "register", "local")
        with self.assertRaises(AccountError): self.service.bind(result["token"], "13800000000", "PhonePassword123", self.sent[-1][-1])
        with self.assertRaises(AccountError): self.service.send_binding_code("invalid", "13800000000", "local")
        self.clock[0] += 901
        with self.assertRaises(AccountError) as stale: self.service.send_binding_code(result["token"], "13800000000", "local")
        self.assertEqual(stale.exception.status, 403)
        with self.assertRaises(AccountError): self.service.send_code("other@example.invalid", "bind:"+result["user"]["id"], "local")

    def test_password_is_salted_and_sessions_survive_service_restart(self):
        result = self.register()
        row = self.service.db.execute("SELECT password FROM identities").fetchone()
        self.assertNotIn("ExamplePassword123", row[0])
        self.assertEqual(self.service.authenticate(result["token"])["name"], "测试用户")
        self.service.close()
        self.service = Accounts(Path(self.temp.name), env={}, now=lambda: self.clock[0])
        self.assertEqual(self.service.authenticate(result["token"])["id"], result["user"]["id"])

    def test_wrong_password_and_code_reuse_are_rejected(self):
        self.register()
        with self.assertRaises(AccountError): self.service.login("test@example.invalid", "WrongPassword123", "local")
        with self.assertRaises(AccountError): self.service.register("test@example.invalid", "ExamplePassword123", self.sent[-1][-1], "", True)

    def test_code_attempts_cannot_be_rolled_back(self):
        self.service.send_code("a@example.invalid", "register", "local")
        code = self.sent[-1][-1]
        wrong = "000000" if code != "000000" else "111111"
        for _ in range(5):
            with self.assertRaises(AccountError): self.service.verify_code("a@example.invalid", "register", wrong)
        with self.assertRaises(AccountError): self.service.verify_code("a@example.invalid", "register", code)

    def test_code_expiry_purpose_and_target_binding(self):
        self.service.send_code("a@example.invalid", "register", "local")
        code = self.sent[-1][-1]
        with self.assertRaises(AccountError): self.service.verify_code("b@example.invalid", "register", code)
        with self.assertRaises(AccountError): self.service.verify_code("a@example.invalid", "reset", code)
        self.clock[0] += 601
        with self.assertRaises(AccountError): self.service.verify_code("a@example.invalid", "register", code)

    def test_phone_registration_and_password_recovery_revoke_old_sessions(self):
        result = self.register("13800000000")
        self.clock[0] += 61
        self.service.send_code("+8613800000000", "reset", "local")
        self.service.reset("13800000000", "NewPassword123", self.sent[-1][-1])
        with self.assertRaises(AccountError): self.service.authenticate(result["token"])
        with self.assertRaises(AccountError): self.service.login("13800000000", "ExamplePassword123", "local")
        self.assertEqual(self.service.login("13800000000", "NewPassword123", "local")["user"]["id"], result["user"]["id"])

    def test_handoff_can_be_used_only_once_and_deletion_cascades(self):
        result = self.register()
        ticket = self.service.handoff(result["token"])
        self.assertEqual(self.service.consume_ticket(ticket)["user"]["id"], result["user"]["id"])
        with self.assertRaises(AccountError): self.service.consume_ticket(ticket)
        self.service.delete(result["token"])
        for table in ["users", "identities", "sessions", "tickets"]:
            self.assertEqual(self.service.db.execute(f"SELECT count(*) FROM {table}").fetchone()[0], 0)

    def test_consent_and_send_frequency_enforced(self):
        with self.assertRaises(AccountError): self.service.register("a@example.invalid", "ExamplePassword123", "123456", "", False)
        self.service.send_code("a@example.invalid", "register", "local")
        with self.assertRaises(AccountError) as error: self.service.send_code("a@example.invalid", "register", "local")
        self.assertEqual(error.exception.status, 429)

    def test_failed_delivery_invalidates_code(self):
        self.service.sender = lambda *args: (_ for _ in ()).throw(RuntimeError("provider credential must never leak"))
        with self.assertRaises(AccountError) as error: self.service.send_code("a@example.invalid", "register", "local")
        self.assertNotIn("credential", error.exception.message)
        self.assertEqual(self.service.db.execute("SELECT count(*) FROM codes").fetchone()[0], 0)

    def test_expired_sessions_and_handoff_links_cannot_be_used(self):
        result = self.register()
        ticket = self.service.handoff(result["token"])
        self.clock[0] += 61
        with self.assertRaises(AccountError): self.service.consume_ticket(ticket)
        self.clock[0] += 7 * 86400
        with self.assertRaises(AccountError): self.service.authenticate(result["token"])

    def test_platform_adapters_verify_identity_server_side(self):
        for provider in ["wechat", "qq", "facebook"]:
            with self.subTest(provider=provider):
                self.service.env.update({f"WAFERAI_{provider.upper()}_ID": "app-id", f"WAFERAI_{provider.upper()}_SECRET": "app-secret"})
                def transport(request):
                    if request.url.path.endswith(("/access_token", "/token")):
                        return httpx.Response(200, json={"access_token": "platform-access-token", "openid": "subject-id"})
                    if request.url.host == "graph.facebook.com":
                        self.assertEqual(request.headers["authorization"], "Bearer platform-access-token")
                        self.assertIn("appsecret_proof", request.url.params)
                        return httpx.Response(200, json={"id": "subject-id", "name": "Reader"})
                    return httpx.Response(200, json={"openid": "subject-id", "nickname": "Reader", "ret": 0})
                social = SocialLogin(self.service, lambda: httpx.Client(transport=httpx.MockTransport(transport)))
                flow = social.start(provider, True)
                state = flow["authorization_url"].split("/")[-1]
                result = social.complete(provider, state, "one-time-code")
                self.assertEqual(result["user"]["identities"][0]["kind"], provider)

    def test_telegram_signature_audience_and_nonce_are_validated(self):
        self.service.env.update(WAFERAI_TELEGRAM_ID="1234", WAFERAI_TELEGRAM_SECRET="test-secret")
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        public = jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key(), as_dict=True)
        public["kid"] = "qa-key"
        claim_override = {}
        row = None
        def transport(request):
            if request.url.path.endswith("/token"):
                self.assertTrue(request.headers["authorization"].startswith("Basic "))
                self.assertIn(b"code_verifier=", request.content)
                claims = {"sub": "telegram-subject", "aud": "1234", "iss": "https://oauth.telegram.org", "iat": int(time.time()), "exp": int(time.time()) + 300, "nonce": row["nonce"], "name": "Reader", **claim_override}
                return httpx.Response(200, json={"id_token": jwt.encode(claims, key, algorithm="RS256", headers={"kid": "qa-key"})})
            return httpx.Response(200, json={"keys": [public]})
        social = SocialLogin(self.service, lambda: httpx.Client(transport=httpx.MockTransport(transport)))
        for overrides in [{"nonce": "wrong-nonce"}, {"aud": "wrong-audience"}, {}]:
            claim_override = overrides
            flow = social.start("telegram", True)
            state = flow["authorization_url"].split("/")[-1]
            row = social.flow(state)
            if overrides:
                with self.assertRaises(AccountError): social.complete("telegram", state, "code")
            else:
                self.assertEqual(social.complete("telegram", state, "code")["user"]["name"], "Reader")


class AccountRoutesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.sent = []
        self.app = FastAPI()
        install_accounts(self.app)
        create_service(self.app, Path(self.temp.name), env={"WAFERAI_AUTH_PUBLIC_URL": "http://127.0.0.1:8010"}, sender=lambda *args: self.sent.append(args))
        self.client = TestClient(self.app)

    def tearDown(self):
        self.client.close()
        self.app.state.accounts.close()
        self.temp.cleanup()

    def register(self):
        self.client.post("/api/v1/auth/codes", json={"target": "hello@example.invalid", "purpose": "register"})
        return self.client.post("/api/v1/auth/register", json={"target": "hello@example.invalid", "password": "ExamplePassword123", "code": self.sent[-1][-1], "name": "Reader", "agreed": True})

    def test_help_requires_login_but_policy_is_public(self):
        response = self.client.get("/help", follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["location"], "/account")
        self.assertNotIn("良率如何", response.text)
        self.assertEqual(self.client.get("/legal/privacy").status_code, 200)
        self.assertEqual(self.client.get("/legal/terms").status_code, 200)
        self.assertEqual(self.client.get("/api/v1/auth/me").status_code, 401)

    def test_browser_registration_login_help_logout_and_session_expiry(self):
        response = self.register()
        self.assertEqual(response.status_code, 200)
        self.assertIn("httponly", response.headers["set-cookie"].lower())
        self.assertIn("良率如何", self.client.get("/help").text)
        self.assertEqual(self.client.get("/help").headers["cache-control"], "no-store")
        self.client.post("/api/v1/auth/logout", json={})
        self.assertEqual(self.client.get("/api/v1/auth/me").status_code, 401)

    def test_unauthorized_web_handoff_and_cross_origin_requests_fail(self):
        self.assertEqual(self.client.post("/api/v1/auth/web-session").status_code, 401)
        response = self.client.post("/api/v1/auth/login", headers={"Origin": "https://attacker.invalid"}, json={"target": "hello@example.invalid", "password": "ExamplePassword123"})
        self.assertEqual(response.status_code, 403)

    def test_unconfigured_channels_have_honest_status(self):
        status = self.client.get("/api/v1/auth/capabilities").json()
        self.assertFalse(status["email"])
        self.assertFalse(status["phone"])
        self.assertEqual(len(status["providers"]), 4)
        self.assertFalse(any(p["available"] for p in status["providers"]))
        for provider in ["wechat", "qq", "facebook", "telegram"]:
            response = self.client.post("/api/v1/auth/social/start", json={"provider": provider, "agreed": True})
            self.assertEqual(response.status_code, 503)
            self.assertNotIn("token", response.json())

    def test_social_flow_state_cookie_poll_binding_and_callback_replay(self):
        accounts = self.app.state.accounts
        accounts.env.update(WAFERAI_QQ_ID="test-app", WAFERAI_QQ_SECRET="test-secret")
        flow = self.client.post("/api/v1/auth/social/start", json={"provider": "qq", "agreed": True}).json()
        state = flow["authorization_url"].split("/")[-1]
        self.assertEqual(self.client.get(f"/auth/social/qq/callback?state={state}&code=test").status_code, 401)
        launch = self.client.get(f"/auth/social/launch/{state}", follow_redirects=False)
        query = parse_qs(urlparse(launch.headers["location"]).query)
        self.assertEqual(query["state"], [state])
        pending = self.client.post("/api/v1/auth/social/poll", json={"poll_token": flow["poll_token"]}).json()
        self.assertTrue(pending["pending"])
        with patch.object(self.app.state.social, "resolve", return_value=("verified-qq-user", "QQ Reader")):
            self.assertEqual(self.client.get(f"/auth/social/qq/callback?state={state}&code=test").status_code, 200)
            self.assertEqual(self.client.get(f"/auth/social/qq/callback?state={state}&code=test").status_code, 401)
        session = self.client.post("/api/v1/auth/social/poll", json={"poll_token": flow["poll_token"]}).json()
        self.assertFalse(session["pending"])
        self.assertEqual(session["user"]["name"], "QQ Reader")
        self.assertEqual(self.client.post("/api/v1/auth/social/poll", json={"poll_token": flow["poll_token"]}).status_code, 401)

    def test_app_session_opens_help_using_one_time_link(self):
        session = self.register().json()
        self.client.cookies.clear()
        path = self.client.post("/api/v1/auth/web-session", headers={"Authorization": "Bearer " + session["token"]}).json()["path"]
        response = self.client.get(path, follow_redirects=False)
        self.assertEqual(response.status_code, 303)
        self.assertEqual(response.headers["location"], "/help")
        self.assertEqual(self.client.get("/help").status_code, 200)
        self.assertEqual(self.client.get(path).status_code, 401)


if __name__ == "__main__":
    unittest.main()
