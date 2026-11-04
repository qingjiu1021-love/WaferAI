import hmac
from pathlib import Path

from fastapi import APIRouter, FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from pydantic import BaseModel, Field

from .service import AccountError, Accounts
from .social import SocialLogin

WEB = Path(__file__).parent / "web"
router = APIRouter()


class CodeInput(BaseModel):
    target: str = Field(max_length=254)
    purpose: str = Field(pattern="^(register|reset)$")


class BindingCodeInput(BaseModel):
    target: str = Field(max_length=254)


class PasswordInput(BaseModel):
    target: str = Field(max_length=254)
    password: str = Field(min_length=1, max_length=128)


class RegisterInput(PasswordInput):
    code: str = Field(pattern=r"^\d{6}$")
    name: str = Field(default="", max_length=40)
    agreed: bool


class ResetInput(PasswordInput):
    code: str = Field(pattern=r"^\d{6}$")


class SocialInput(BaseModel):
    provider: str = Field(max_length=20)
    agreed: bool


class PollInput(BaseModel):
    poll_token: str = Field(min_length=20, max_length=100)


def service(request):
    return request.app.state.accounts


def token(request):
    authorization = request.headers.get("authorization", "")
    if authorization.startswith("Bearer "):
        return authorization[7:]
    return request.cookies.get("wafer_session", "")


def cookie(response, request, session):
    response.set_cookie("wafer_session", session["token"], max_age=7 * 86400, httponly=True,
                        secure=service(request).public_base.startswith("https://"), samesite="lax", path="/")
    return response


@router.get("/api/v1/auth/capabilities")
def capabilities(request: Request):
    return service(request).capabilities()


@router.post("/api/v1/auth/codes")
def code(body: CodeInput, request: Request):
    return service(request).send_code(body.target, body.purpose, request.client.host if request.client else "unknown")


@router.post("/api/v1/auth/register")
def register(body: RegisterInput, request: Request):
    result = service(request).register(body.target, body.password, body.code, body.name, body.agreed)
    return cookie(JSONResponse(result), request, result)


@router.post("/api/v1/auth/login")
def login(body: PasswordInput, request: Request):
    result = service(request).login(body.target, body.password, request.client.host if request.client else "unknown")
    return cookie(JSONResponse(result), request, result)


@router.post("/api/v1/auth/reset-password")
def reset(body: ResetInput, request: Request):
    return service(request).reset(body.target, body.password, body.code)


@router.post("/api/v1/auth/bind/codes")
def binding_code(body: BindingCodeInput, request: Request):
    return service(request).send_binding_code(token(request), body.target, request.client.host if request.client else "unknown")


@router.post("/api/v1/auth/bind")
def bind(body: ResetInput, request: Request):
    return service(request).bind(token(request), body.target, body.password, body.code)


@router.get("/api/v1/auth/me")
def me(request: Request):
    return service(request).authenticate(token(request))


@router.post("/api/v1/auth/logout")
def logout(request: Request):
    service(request).logout(token(request))
    response = JSONResponse({"message": "已退出登录。"})
    response.delete_cookie("wafer_session", path="/")
    return response


@router.delete("/api/v1/auth/account")
def delete(request: Request):
    result = service(request).delete(token(request))
    response = JSONResponse(result)
    response.delete_cookie("wafer_session", path="/")
    return response


@router.post("/api/v1/auth/web-session")
def handoff(request: Request):
    ticket = service(request).handoff(token(request))
    # The app uses its configured origin, so emulator/USB host aliases remain valid.
    return {"path": "/auth/handoff?ticket=" + ticket}


@router.get("/auth/handoff")
def consume(request: Request, ticket: str = ""):
    if not 20 <= len(ticket) <= 100:
        raise AccountError("网页登录链接无效。", 401)
    result = service(request).consume_ticket(ticket)
    return cookie(RedirectResponse("/help", status_code=303), request, result)


@router.post("/api/v1/auth/social/start")
def social_start(body: SocialInput, request: Request):
    service(request).throttle("social-start:" + (request.client.host if request.client else "unknown"), 30, 3600)
    return request.app.state.social.start(body.provider, body.agreed)


@router.post("/api/v1/auth/social/poll")
def social_poll(body: PollInput, request: Request):
    service(request).throttle("social-poll:" + body.poll_token, 320, 660)
    return request.app.state.social.poll(body.poll_token)


@router.post("/api/v1/auth/social/cancel")
def social_cancel(body: PollInput, request: Request):
    return request.app.state.social.cancel(body.poll_token)


@router.get("/auth/social/launch/{state}")
def launch(state: str, request: Request):
    response = RedirectResponse(request.app.state.social.authorization_url(state), status_code=303)
    response.set_cookie("wafer_oauth", service(request).digest(state), httponly=True, secure=service(request).public_base.startswith("https://"), samesite="lax", max_age=600, path="/auth/social/")
    return response


@router.get("/auth/social/{provider}/callback")
def callback(provider: str, request: Request, state: str = "", code: str = "", error: str = ""):
    if not state or len(state) > 100 or not hmac.compare_digest(request.cookies.get("wafer_oauth", ""), service(request).digest(state)):
        raise AccountError("授权状态校验失败，请从 App 重新发起登录。", 401)
    if error:
        request.app.state.social.fail(state)
        raise AccountError("本次授权已取消，请返回 App 重试。", 401)
    result = request.app.state.social.complete(provider, state, code)
    response = HTMLResponse('<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width,initial-scale=1"><link rel="stylesheet" href="/account-assets/style.css"><title>登录完成 · WaferAI</title><main class="shell"><section class="panel"><span class="eyebrow">WAFERAI / ACCOUNT</span><h1>登录完成</h1><p>请返回 WaferAI，App 会继续完成登录。</p><a class="button" href="waferai://auth/complete">返回 WaferAI</a><a class="button secondary" href="/help">进入帮助中心</a></section></main></html>')
    response.delete_cookie("wafer_oauth", path="/auth/social/")
    return cookie(response, request, result)


@router.get("/help")
def help_center(request: Request):
    try:
        service(request).authenticate(token(request))
    except AccountError:
        return RedirectResponse("/account", status_code=303)
    return HTMLResponse((WEB / "help.html").read_text(encoding="utf-8"))


@router.get("/account")
def account_page():
    return HTMLResponse((WEB / "account.html").read_text(encoding="utf-8"))


@router.get("/legal/{document}")
def legal(document: str):
    if document not in {"terms", "privacy"}:
        raise AccountError("页面不存在。", 404)
    return HTMLResponse((WEB / f"{document}.html").read_text(encoding="utf-8"))


@router.get("/account-assets/{filename}")
def asset(filename: str):
    if filename not in {"style.css", "account.js", "help.js"}:
        raise AccountError("资源不存在。", 404)
    return Response((WEB / filename).read_text(encoding="utf-8"), media_type="text/css" if filename.endswith("css") else "text/javascript")


def install_accounts(application: FastAPI):
    application.include_router(router)

    @application.exception_handler(AccountError)
    async def account_error(_, error):
        return JSONResponse({"message": error.message}, status_code=error.status, headers={"Cache-Control": "no-store"})

    @application.middleware("http")
    async def account_headers(request: Request, call_next):
        relevant = request.url.path.startswith(("/api/v1/auth", "/auth/", "/account", "/help", "/legal/"))
        if relevant and request.method in {"POST", "DELETE", "PATCH"}:
            origin = request.headers.get("origin")
            allowed = {str(request.base_url).rstrip("/"), service(request).public_base}
            if origin and origin not in allowed:
                return JSONResponse({"message": "请求来源无效。"}, status_code=403)
        response = await call_next(request)
        if relevant:
            response.headers.update({"Cache-Control": "no-store", "Referrer-Policy": "no-referrer", "X-Content-Type-Options": "nosniff", "X-Frame-Options": "DENY", "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"})
        return response


def create_service(application: FastAPI, root=None, env=None, sender=None):
    application.state.accounts = Accounts(root or Path(__file__).resolve().parents[3] / "storage" / "accounts", env, sender)
    application.state.social = SocialLogin(application.state.accounts)
