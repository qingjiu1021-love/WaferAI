import {createHash,createHmac,randomBytes,scryptSync,timingSafeEqual,createPublicKey,verify} from "node:crypto";

export type Binding = { DB?: D1Database; WAFERAI_AUTH_SECRET?: string; WAFERAI_AUTH_PUBLIC_URL?: string; [key:string]:unknown };
type Row = Record<string,any>;
export type PlatformIdentity = {userId:string;displayName:string;email:string}|null;
const names:Record<string,string>={chatgpt:"ChatGPT",wechat:"微信",qq:"QQ",facebook:"Facebook",telegram:"Telegram"};
const policy="2026-11-04";
export class AccountError extends Error { constructor(public message:string,public status=400){super(message)} }
const now=()=>Math.floor(Date.now()/1000);
const random=()=>randomBytes(32).toString("base64url");
const sha=(value:string)=>createHash("sha256").update(value).digest("hex");
const equal=(a:string,b:string)=>a.length===b.length&&timingSafeEqual(Buffer.from(a),Buffer.from(b));
function identity(input:unknown){
  const value=String(input??"").trim();
  if(value.length<=254&&/^[^\s@]{1,64}@[^\s@.]+(?:\.[^\s@.]+)+$/.test(value))return {kind:"email",target:value.toLowerCase()};
  let phone=value.replace(/[\s()-]/g,"");if(/^1[3-9]\d{9}$/.test(phone))phone="+86"+phone;
  if(/^\+[1-9]\d{7,14}$/.test(phone))return {kind:"phone",target:phone};
  throw new AccountError("请输入有效邮箱或带国家区号的手机号。");
}
function password(value:unknown,salt=randomBytes(16).toString("hex")){
  const text=String(value??"");if(text.length<10||text.length>128||!/[a-zA-Z]/.test(text)||!/[0-9]/.test(text))throw new AccountError("密码需为 10–128 位，包含字母和数字。");
  return salt+":"+scryptSync(text,Buffer.from(salt,"hex"),64,{N:32768,r:8,p:1,maxmem:64*1024*1024}).toString("hex");
}
export class Accounts {
  db:D1Database;
  constructor(public env:Binding,public origin:string,public fetcher:typeof fetch=fetch){
    if(!env.DB)throw new AccountError("账号数据库暂时不可用。",503);
    if(typeof env.WAFERAI_AUTH_SECRET!=="string"||env.WAFERAI_AUTH_SECRET.length<32)throw new AccountError("账号服务尚未配置。",503);
    this.db=env.DB;
  }
  digest(value:string){return createHmac("sha256",String(this.env.WAFERAI_AUTH_SECRET)).update(value).digest("hex")}
  q(sql:string,...values:any[]){return this.db.prepare(sql).bind(...values)}
  async one(sql:string,...values:any[]):Promise<Row|null>{return this.q(sql,...values).first<Row>()}
  async run(sql:string,...values:any[]){return this.q(sql,...values).run()}
  async limit(key:string,maximum:number,seconds:number){
    const row=await this.one(`INSERT INTO limits(key,started,count) VALUES(?,?,1) ON CONFLICT(key) DO UPDATE SET started=CASE WHEN started+?<=? THEN ? ELSE started END,count=CASE WHEN started+?<=? THEN 1 ELSE count+1 END RETURNING count`,this.digest(key),now(),seconds,now(),now(),seconds,now());
    if(Number(row?.count)>maximum)throw new AccountError("操作过于频繁，请稍后再试。",429);
  }
  async cleanup(){await this.db.batch([this.q("DELETE FROM sessions WHERE expires<?",now()),this.q("DELETE FROM tickets WHERE expires<?",now()),this.q("DELETE FROM codes WHERE expires<?",now()),this.q("DELETE FROM flows WHERE expires<?",now()),this.q("DELETE FROM limits WHERE started<?",now()-86400)])}
  configured(provider:string){return provider==="chatgpt"||Boolean(this.env[`WAFERAI_${provider.toUpperCase()}_ID`]&&this.env[`WAFERAI_${provider.toUpperCase()}_SECRET`])}
  capabilities(){return {development:false,policy_version:policy,providers:Object.entries(names).map(([id,name])=>({id,name,available:this.configured(id)})),email:!!(this.env.WAFERAI_RESEND_KEY&&this.env.WAFERAI_EMAIL_FROM),phone:!!(this.env.WAFERAI_SMS_URL&&this.env.WAFERAI_SMS_TOKEN)}}
  async user(id:string):Promise<Row>{
    const row=await this.one("SELECT id,name,created_at FROM users WHERE id=?",id);if(!row)throw new AccountError("登录已失效，请重新登录。",401);
    const data=await this.q("SELECT kind,subject FROM identities WHERE user_id=?",id).all<Row>();
    return {...row,identities:data.results.map(r=>({kind:r.kind,label:names[r.kind]??r.subject}))};
  }
  async session(id:string){const token=random(),expiry=now()+604800;await this.run("INSERT INTO sessions VALUES(?,?,?)",this.digest(token),id,expiry);return {token,expires_at:expiry,user:await this.user(id)}}
  async authenticate(token:string){const r=await this.one("SELECT user_id FROM sessions WHERE hash=? AND expires>?",this.digest(token),now());if(!r)throw new AccountError("请登录后继续。",401);return this.user(r.user_id)}
  async send(targetInput:unknown,purpose:string,ip:string){
    if(!["register","reset"].includes(purpose))throw new AccountError("验证码用途无效。");
    return this.sendInternal(targetInput,purpose,ip);
  }
  private async sendInternal(targetInput:unknown,purpose:string,ip:string){
    const {kind,target}=identity(targetInput);
    if(!this.capabilities()[kind==="phone"?"phone":"email"])throw new AccountError(`${kind==="phone"?"短信":"邮箱"}服务尚未开通，请使用 ChatGPT 授权登录。`,503);
    await this.limit("send-ip:"+ip,15,3600);await this.limit("send-hour:"+target,5,3600);await this.limit("send-minute:"+target,1,60);
    const code=(randomBytes(4).readUInt32BE()%1000000).toString().padStart(6,"0");
    await this.run("INSERT OR REPLACE INTO codes VALUES(?,?,?,?,0)",target,purpose,this.digest(target+purpose+code),now()+600);
    try{
      if(kind==="email"){
        const result=await this.fetcher("https://api.resend.com/emails",{method:"POST",redirect:"manual",signal:AbortSignal.timeout(15000),headers:{Authorization:`Bearer ${this.env.WAFERAI_RESEND_KEY}`,"Content-Type":"application/json"},body:JSON.stringify({from:this.env.WAFERAI_EMAIL_FROM,to:[target],subject:"WaferAI 账号验证码",text:`你的验证码为 ${code}，10 分钟内有效。如非本人操作，请忽略。`})});
        if(!result.ok)throw new Error("delivery failed");
      }else{
        const endpoint=String(this.env.WAFERAI_SMS_URL);if(!endpoint.startsWith("https://"))throw new Error("invalid sms endpoint");
        const result=await this.fetcher(endpoint,{method:"POST",redirect:"manual",signal:AbortSignal.timeout(15000),headers:{Authorization:`Bearer ${this.env.WAFERAI_SMS_TOKEN}`,"Content-Type":"application/json"},body:JSON.stringify({phone:target,code,purpose:purpose.startsWith("bind:")?"bind":purpose,expires_in:600})});
        if(!result.ok||(await result.json() as Row).accepted!==true)throw new Error("delivery failed");
      }
    }catch{await this.run("DELETE FROM codes WHERE target=? AND purpose=?",target,purpose);throw new AccountError("验证码发送失败，请稍后重试。",503)}
    return {message:"验证码已发送，10 分钟内有效。",retry_after:60};
  }
  async checkCode(target:string,purpose:string,input:unknown){
    const code=String(input??"");if(!/^\d{6}$/.test(code))throw new AccountError("请输入 6 位验证码。");
    const row=await this.one("UPDATE codes SET attempts=attempts+1 WHERE target=? AND purpose=? AND expires>? AND attempts<5 RETURNING hash",target,purpose,now());
    if(!row||!equal(row.hash,this.digest(target+purpose+code)))throw new AccountError("验证码无效、已过期或尝试次数过多。");
    if(!await this.one("DELETE FROM codes WHERE target=? AND purpose=? AND hash=? RETURNING hash",target,purpose,row.hash))throw new AccountError("验证码已使用。");
  }
  async register(body:Row){
    if(body.agreed!==true)throw new AccountError("请先阅读并同意使用条款和隐私政策。");
    const {kind,target}=identity(body.target),hashed=password(body.password);await this.checkCode(target,"register",body.code);
    const id=randomBytes(16).toString("hex");
    try{await this.db.batch([this.q("INSERT INTO users VALUES(?,?,?,?)",id,String(body.name??"").trim().slice(0,40)||"WaferAI 用户",now(),policy),this.q("INSERT INTO identities VALUES(?,?,?,?)",kind,target,id,hashed)])}
    catch{throw new AccountError("此账号已注册，请登录或找回密码。",409)}
    return this.session(id);
  }
  async login(body:Row,ip:string){
    const {kind,target}=identity(body.target);await this.limit("login-ip:"+ip,60,900);await this.limit("login-target:"+target,10,900);
    const row=await this.one("SELECT user_id,password FROM identities WHERE kind=? AND subject=?",kind,target);
    const stored=row?.password??"00".repeat(16)+":"+"00".repeat(64);let valid=false;
    try{valid=equal(password(body.password,stored.split(":")[0]),stored)}catch{}
    if(!valid||!row)throw new AccountError("账号或密码不正确。",401);return this.session(row.user_id);
  }
  async reset(body:Row){
    const {kind,target}=identity(body.target),hashed=password(body.password);await this.checkCode(target,"reset",body.code);
    await this.db.batch([this.q("DELETE FROM sessions WHERE user_id IN (SELECT user_id FROM identities WHERE kind=? AND subject=?)",kind,target),this.q("UPDATE identities SET password=? WHERE kind=? AND subject=?",hashed,kind,target)]);
    return {message:"如账号已注册，密码已重置，请使用新密码登录。"};
  }
  private async bindingUser(token:string,input:unknown){
    const user=await this.authenticate(token),session=await this.one("SELECT expires FROM sessions WHERE hash=?",this.digest(token));
    if(!session||session.expires-604800+900<now())throw new AccountError("绑定联系方式前，请重新登录以确认身份。",403);
    const {kind,target}=identity(input);
    if(await this.one("SELECT 1 FROM identities WHERE user_id=? AND kind=?",user.id,kind))throw new AccountError("此账号已绑定该类联系方式，暂不支持直接替换。",409);
    if(await this.one("SELECT 1 FROM identities WHERE kind=? AND subject=?",kind,target))throw new AccountError("此联系方式已被其他账号使用。",409);
    return {user,kind,target};
  }
  async sendBindingCode(token:string,input:unknown,ip:string){const {user,target}=await this.bindingUser(token,input);return this.sendInternal(target,"bind:"+user.id,ip)}
  async bind(token:string,body:Row){
    const {user,kind,target}=await this.bindingUser(token,body.target),hashed=password(body.password);await this.checkCode(target,"bind:"+user.id,body.code);
    let result;try{result=await this.run("INSERT INTO identities SELECT ?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM identities WHERE user_id=? AND kind=?)",kind,target,user.id,hashed,user.id,kind)}catch{throw new AccountError("此联系方式已被其他账号使用。",409)}
    if(result.meta.changes!==1)throw new AccountError("此账号已绑定该类联系方式。",409);
    return {user:await this.user(user.id),message:"绑定成功，可使用此联系方式及刚设置的密码登录同一账号。"};
  }
  config(provider:string){
    if(!names[provider])throw new AccountError("不支持此登录渠道。",404);
    if(!this.configured(provider))throw new AccountError(`${names[provider]}登录尚未开通，请先配置开发者应用。`,503);
    return {id:String(this.env[`WAFERAI_${provider.toUpperCase()}_ID`]??""),secret:String(this.env[`WAFERAI_${provider.toUpperCase()}_SECRET`]??""),redirect:this.origin+`/auth/social/${provider}/callback`};
  }
  async start(provider:string,agreed:boolean){
    if(!agreed)throw new AccountError("请先阅读并同意使用条款和隐私政策。");this.config(provider);
    const state=random(),poll=random();await this.run("INSERT INTO flows VALUES(?,?,?,?,?,?,NULL,'pending','')",state,this.digest(poll),provider,random(),random(),now()+600);
    return {authorization_url:this.origin+"/auth/social/launch/"+state,poll_token:poll,expires_in:600};
  }
  async flow(state:string){const row=await this.one("SELECT * FROM flows WHERE state=? AND expires>? AND status='pending'",state,now());if(!row)throw new AccountError("授权已过期、取消或已经完成，请重新开始。",401);return row}
  async launch(state:string){
    const row=await this.flow(state),{id,redirect}=this.config(row.provider);
    if(row.provider==="chatgpt")return "/signin-with-chatgpt?return_to="+encodeURIComponent(`/auth/social/chatgpt/callback?state=${state}`);
    const params=new URLSearchParams({client_id:id,redirect_uri:redirect,response_type:"code",state});let endpoint="";
    if(row.provider==="wechat"){params.delete("client_id");params.set("appid",id);params.set("scope","snsapi_login");endpoint="https://open.weixin.qq.com/connect/qrconnect"}
    if(row.provider==="qq"){params.set("scope","get_user_info");params.set("display","mobile");endpoint="https://graph.qq.com/oauth2.0/authorize"}
    if(row.provider==="facebook"){params.set("scope","public_profile");endpoint="https://www.facebook.com/dialog/oauth"}
    if(row.provider==="telegram"){params.set("scope","openid profile");params.set("nonce",row.nonce);params.set("code_challenge_method","S256");params.set("code_challenge",createHash("sha256").update(row.verifier).digest("base64url"));endpoint="https://oauth.telegram.org/auth"}
    return endpoint+"?"+params.toString()+(row.provider==="wechat"?"#wechat_redirect":"");
  }
  async remote(url:string,options:RequestInit={}){
    const r=await this.fetcher(url,{...options,redirect:"manual",signal:AbortSignal.timeout(20000)});if(!r.ok)throw new AccountError("授权平台暂时不可用，请稍后重试。",502);
    const data=await r.json() as Row;if(data.error||data.errcode||data.ret)throw new AccountError("授权平台拒绝本次登录，请重新授权。",401);return data;
  }
  async resolve(row:Row,code:string,platform:PlatformIdentity){
    const provider=row.provider,{id,secret,redirect}=this.config(provider);
    const get=(url:string,params:Record<string,string>,headers?:HeadersInit)=>this.remote(url+"?"+new URLSearchParams(params),{headers});
    if(provider==="chatgpt"){
      if(!platform)throw new AccountError("ChatGPT 授权未完成，请重新登录。",401);
      return {subject:platform.userId,name:platform.displayName};
    }
    if(!code||code.length>2048)throw new AccountError("授权码无效。",401);
    if(provider==="wechat"){
      const t=await get("https://api.weixin.qq.com/sns/oauth2/access_token",{appid:id,secret,code,grant_type:"authorization_code"});
      const p=await get("https://api.weixin.qq.com/sns/userinfo",{access_token:t.access_token,openid:t.openid,lang:"zh_CN"});if(p.openid!==t.openid)throw new AccountError("微信身份校验失败。",401);
      return {subject:String(t.openid),name:p.nickname??"微信用户"};
    }
    if(provider==="qq"){
      const t=await get("https://graph.qq.com/oauth2.0/token",{client_id:id,client_secret:secret,code,redirect_uri:redirect,grant_type:"authorization_code",fmt:"json",need_openid:"1"});
      const p=await get("https://graph.qq.com/user/get_user_info",{access_token:t.access_token,oauth_consumer_key:id,openid:t.openid});return {subject:String(t.openid),name:p.nickname??"QQ 用户"};
    }
    if(provider==="facebook"){
      const t=await get("https://graph.facebook.com/oauth/access_token",{client_id:id,client_secret:secret,code,redirect_uri:redirect});
      const proof=createHmac("sha256",secret).update(t.access_token).digest("hex");
      const p=await get("https://graph.facebook.com/me",{fields:"id,name",appsecret_proof:proof},{Authorization:"Bearer "+t.access_token});return {subject:String(p.id),name:p.name??"Facebook 用户"};
    }
    const t=await this.remote("https://oauth.telegram.org/token",{method:"POST",headers:{Authorization:"Basic "+Buffer.from(id+":"+secret).toString("base64"),"Content-Type":"application/x-www-form-urlencoded"},body:new URLSearchParams({grant_type:"authorization_code",code,redirect_uri:redirect,client_id:id,code_verifier:row.verifier})});
    const [head,body,sig]=String(t.id_token??"").split(".");if(!head||!body||!sig)throw new AccountError("Telegram 身份令牌无效。",401);
    const header=JSON.parse(Buffer.from(head,"base64url").toString());if(header.alg!=="RS256")throw new AccountError("Telegram 需配置 RS256 签名。",401);
    const jwks=await this.remote("https://oauth.telegram.org/.well-known/jwks.json");const key=jwks.keys.find((k:Row)=>k.kty==="RSA"&&k.kid===header.kid);if(!key||!verify("RSA-SHA256",Buffer.from(head+"."+body),createPublicKey({key,format:"jwk"}),Buffer.from(sig,"base64url")))throw new AccountError("Telegram 签名校验失败。",401);
    const claims=JSON.parse(Buffer.from(body,"base64url").toString());
    if(claims.iss!=="https://oauth.telegram.org"||claims.aud!==id||!Number.isFinite(claims.exp)||claims.exp<=now()||!Number.isFinite(claims.iat)||claims.iat>now()+30||!equal(String(claims.nonce??""),row.nonce)||!claims.sub)throw new AccountError("Telegram 授权校验失败。",401);
    return {subject:String(claims.sub),name:claims.name??"Telegram 用户"};
  }
  async complete(provider:string,state:string,code:string,platform:PlatformIdentity){
    const row=await this.one("UPDATE flows SET status='checking' WHERE state=? AND provider=? AND expires>? AND status='pending' RETURNING *",state,provider,now());if(!row)throw new AccountError("授权已过期、取消或已处理。",401);
    try{
      const profile=await this.resolve(row,code,platform);if(!profile.subject||profile.subject==="undefined"||profile.subject.length>256)throw new AccountError("第三方返回了无效身份。",401);
      const id=this.digest(provider+":"+profile.subject).slice(0,32);
      await this.db.batch([
        this.q("INSERT OR IGNORE INTO users SELECT ?,?,?,? WHERE EXISTS(SELECT 1 FROM flows WHERE state=? AND status='checking')",id,String(profile.name).slice(0,40),now(),policy,state),
        this.q("INSERT OR IGNORE INTO identities SELECT ?,?,?,NULL WHERE EXISTS(SELECT 1 FROM flows WHERE state=? AND status='checking')",provider,profile.subject,id,state),
        this.q("UPDATE flows SET user_id=(SELECT user_id FROM identities WHERE kind=? AND subject=?),status='complete' WHERE state=? AND status='checking'",provider,profile.subject,state)
      ]);
      const done=await this.one("SELECT user_id FROM flows WHERE state=? AND status='complete'",state);if(!done?.user_id)throw new AccountError("本次授权已取消。",401);return this.session(done.user_id);
    }catch(error){
      const message=error instanceof AccountError?error.message:"第三方身份校验失败，请检查平台配置后重试。";
      await this.run("UPDATE flows SET status='failed',message=? WHERE state=? AND status='checking'",message,state);throw new AccountError(message,401);
    }
  }
  async cancel(poll:string){await this.run("UPDATE flows SET status='cancelled',message='本次授权已取消。' WHERE poll_hash=? AND status IN ('pending','checking','complete')",this.digest(poll));return {message:"本次授权已取消。"}}
  async fail(state:string){await this.run("UPDATE flows SET status='cancelled',message='已取消授权，请返回 App。' WHERE state=? AND status='pending'",state)}
  async poll(poll:string){
    const row=await this.one("SELECT * FROM flows WHERE poll_hash=? AND expires>?",this.digest(poll),now());if(!row)throw new AccountError("授权已过期，请重新开始。",401);
    if(["pending","checking"].includes(row.status))return {pending:true};if(row.status!=="complete")throw new AccountError(row.message||"授权已使用，请重新开始。",401);
    const claim=await this.one("UPDATE flows SET status='consumed' WHERE state=? AND status='complete' RETURNING user_id",row.state);if(!claim)throw new AccountError("授权已领取。",401);return {pending:false,...await this.session(claim.user_id)};
  }
  async handoff(token:string){const user=await this.authenticate(token),ticket=random();await this.run("INSERT INTO tickets VALUES(?,?,?)",this.digest(ticket),user.id,now()+60);return {path:"/auth/handoff?ticket="+ticket}}
  async consume(ticket:string){const row=await this.one("DELETE FROM tickets WHERE hash=? AND expires>? RETURNING user_id",this.digest(ticket),now());if(!row)throw new AccountError("网页登录链接已过期，请回 App 重新打开。",401);return this.session(row.user_id)}
  async logout(token:string){await this.run("DELETE FROM sessions WHERE hash=?",this.digest(token));return {message:"已退出登录。"}}
  async remove(token:string){const user=await this.authenticate(token);await this.db.batch([this.q("DELETE FROM codes WHERE target IN(SELECT subject FROM identities WHERE user_id=? AND kind IN('email','phone'))",user.id),this.q("DELETE FROM users WHERE id=?",user.id)]);return {message:"账号及账号服务中的身份、登录会话已删除。本机数据保留。"}}
}
