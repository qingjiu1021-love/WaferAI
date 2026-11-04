import {Accounts,AccountError,type Binding,type PlatformIdentity} from "./account-service";
import {web} from "./web";
const baseHeaders={"Cache-Control":"no-store","Referrer-Policy":"no-referrer","X-Content-Type-Options":"nosniff","X-Frame-Options":"DENY","Content-Security-Policy":"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"};
const json=(value:unknown,status=200)=>new Response(JSON.stringify(value),{status,headers:{"Content-Type":"application/json; charset=utf-8"}});
const page=(value:string,status=200)=>new Response(value,{status,headers:{"Content-Type":"text/html; charset=utf-8"}});
const redirect=(path:string)=>new Response(null,{status:303,headers:{Location:path}});
function cookies(request:Request){return Object.fromEntries((request.headers.get("Cookie")??"").split(";").filter(x=>x.includes("=")).map(x=>{const i=x.indexOf("=");return [x.slice(0,i).trim(),x.slice(i+1)]}))}
function token(request:Request){return request.headers.get("Authorization")?.replace(/^Bearer /,"")??cookies(request).wafer_session??""}
function sessionCookie(response:Response,token:string,origin:string){response.headers.append("Set-Cookie",`wafer_session=${token}; Path=/; Max-Age=604800; HttpOnly; SameSite=Lax${origin.startsWith("https://")?"; Secure":""}`);return response}
const completion=(message:string)=>`<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width,initial-scale=1"><title>授权结果 · WaferAI</title><link rel="stylesheet" href="/account-assets/style.css"><main class="shell"><section class="panel"><span class="eyebrow">WAFERAI / ACCOUNT</span><h1>${message}</h1><p>手机 App 正在等待结果，可以返回继续操作。</p><div class="actions"><a class="button" href="waferai://auth/complete">返回 WaferAI</a><a class="button secondary" href="/help">进入帮助中心</a></div></section></main></html>`;
export async function handleAccount(request:Request,env:Binding,platform:PlatformIdentity=null){
 const url=new URL(request.url),path=url.pathname;let response:Response;
 try{
  const origin=String(env.WAFERAI_AUTH_PUBLIC_URL??url.origin).replace(/\/$/,"");
  if(path.startsWith("/account-assets/")){
   const filename=path.slice(16),data=web[filename];if(!data)throw new AccountError("资源不存在。",404);
   response=new Response(data,{headers:{"Content-Type":filename.endsWith("css")?"text/css; charset=utf-8":"text/javascript; charset=utf-8"}});
  }else if(path.startsWith("/legal/")){
   const filename=path.split("/").pop()+".html";if(!["terms.html","privacy.html"].includes(filename))throw new AccountError("页面不存在。",404);response=page(web[filename]);
  }else if(path==="/account"||path==="/")response=page(web["account.html"]);
  else{
   const account=new Accounts(env,origin);
   if(["POST","DELETE"].includes(request.method)){
    const incoming=request.headers.get("Origin");if(incoming&&incoming!==origin&&incoming!==url.origin)throw new AccountError("请求来源无效。",403);
   }
   if(path==="/help"){
    try{await account.authenticate(token(request));response=page(web["help.html"])}catch(error){if(error instanceof AccountError&&error.status===401)response=redirect("/account");else throw error}
   }else if(path==="/api/v1/health")response=json({status:"ok",service:"WaferAI Account",version:"4.3.0"});
   else if(path==="/api/v1/auth/capabilities")response=json(account.capabilities());
   else if(path==="/api/v1/auth/me")response=json(await account.authenticate(token(request)));
   else if(path==="/auth/handoff")response=sessionCookie(redirect("/help"),(await account.consume(url.searchParams.get("ticket")??"")).token,origin);
   else if(path.startsWith("/auth/social/launch/")){
    const state=path.slice(20);if(state.length>100)throw new AccountError("授权请求无效。",401);
    response=redirect(await account.launch(state));response.headers.append("Set-Cookie",`wafer_oauth=${account.digest(state)}; Path=/auth/social/; Max-Age=600; HttpOnly; SameSite=Lax${origin.startsWith("https://")?"; Secure":""}`);
   }else if(/^\/auth\/social\/[^/]+\/callback$/.test(path)){
    const provider=path.split("/")[3],state=url.searchParams.get("state")??"";
    if(!state||state.length>100||cookies(request).wafer_oauth!==account.digest(state))throw new AccountError("授权状态校验失败，请从 App 重新发起。",401);
    if(url.searchParams.has("error")){await account.fail(state);response=page(completion("本次授权已取消"))}
    else{const session=await account.complete(provider,state,url.searchParams.get("code")??"",platform);response=sessionCookie(page(completion("登录完成")),session.token,origin)}
    response.headers.append("Set-Cookie","wafer_oauth=; Path=/auth/social/; Max-Age=0; HttpOnly; SameSite=Lax");
   }else if(request.method==="DELETE"&&path==="/api/v1/auth/account"){
    response=json(await account.remove(token(request)));response.headers.append("Set-Cookie","wafer_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
   }else if(request.method==="POST"&&path.startsWith("/api/v1/auth/")){
    const raw=await request.text();if(raw.length>12000)throw new AccountError("请求内容过大。",413);
    let body:Record<string,any>;try{body=raw?JSON.parse(raw):{}}catch{throw new AccountError("请求格式无效。")}
    const ip=request.headers.get("CF-Connecting-IP")??"unknown";
    switch(path){
     case "/api/v1/auth/codes":await account.cleanup();response=json(await account.send(body.target,body.purpose,ip));break;
     case "/api/v1/auth/register":{const session=await account.register(body);response=sessionCookie(json(session),session.token,origin);break}
     case "/api/v1/auth/login":{await account.cleanup();const session=await account.login(body,ip);response=sessionCookie(json(session),session.token,origin);break}
     case "/api/v1/auth/reset-password":response=json(await account.reset(body));break;
     case "/api/v1/auth/bind/codes":response=json(await account.sendBindingCode(token(request),body.target,ip));break;
     case "/api/v1/auth/bind":response=json(await account.bind(token(request),body));break;
     case "/api/v1/auth/logout":response=json(await account.logout(token(request)));response.headers.append("Set-Cookie","wafer_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");break;
     case "/api/v1/auth/web-session":response=json(await account.handoff(token(request)));break;
     case "/api/v1/auth/social/start":await account.cleanup();await account.limit("social-start:"+ip,30,3600);response=json(await account.start(String(body.provider??""),body.agreed===true));break;
     case "/api/v1/auth/social/poll":if(typeof body.poll_token!=="string"||body.poll_token.length>100)throw new AccountError("授权凭据无效。");await account.limit("poll:"+body.poll_token,350,660);response=json(await account.poll(body.poll_token));break;
     case "/api/v1/auth/social/cancel":if(typeof body.poll_token!=="string"||body.poll_token.length>100)throw new AccountError("授权凭据无效。");response=json(await account.cancel(body.poll_token));break;
     default:throw new AccountError("接口不存在。",404);
    }
   }else throw new AccountError("页面不存在。",404);
  }
 }catch(error){
  const known=error instanceof AccountError;
  if(path.startsWith("/auth/"))response=page(`<!doctype html><html lang="zh-CN"><meta name="viewport" content="width=device-width,initial-scale=1"><title>授权未完成 · WaferAI</title><link rel="stylesheet" href="/account-assets/style.css"><main class="shell"><section class="panel"><h1>授权未完成</h1><p>${known?error.message.replace(/[&<>"']/g,""):"暂时无法完成登录，请稍后重试。"}</p><a class="button" href="/account">重新登录</a><a class="button secondary" href="waferai://auth/complete">返回 App</a></section></main></html>`,known?error.status:503);
  else response=json({message:known?error.message:"账号服务暂时不可用，请稍后再试。"},known?error.status:503);
 }
 for(const [key,value]of Object.entries(baseHeaders))response.headers.set(key,value);return response;
}
