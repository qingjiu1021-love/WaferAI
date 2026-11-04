import {test} from "node:test";
import assert from "node:assert/strict";
import {DatabaseSync} from "node:sqlite";
import {readFileSync,readdirSync} from "node:fs";
import {Accounts,AccountError} from "../lib/account-service";

function fixture(env:Record<string,unknown>={}){
 const sqlite=new DatabaseSync(":memory:");sqlite.exec("PRAGMA foreign_keys=ON");
 for(const file of readdirSync("drizzle").filter(f=>f.endsWith(".sql")).sort())sqlite.exec(readFileSync("drizzle/"+file,"utf8"));
 const db:any={prepare(sql:string){return {bind(...values:any[]){return {
   async first(){return sqlite.prepare(sql).get(...values)??null},
   async all(){return {results:sqlite.prepare(sql).all(...values)}},
   async run(){const result=sqlite.prepare(sql).run(...values);return {meta:{changes:Number(result.changes)}}}
 }}}},async batch(statements:any[]){sqlite.exec("BEGIN");try{const results=[];for(const statement of statements)results.push(await statement.run());sqlite.exec("COMMIT");return results}catch(error){sqlite.exec("ROLLBACK");throw error}}};
 const account=new Accounts({DB:db,WAFERAI_AUTH_SECRET:"qa-secret-".repeat(8),...env},"https://account.example.invalid");
 return {account,sqlite,db};
}

test("only ChatGPT is available before platform credentials are installed",()=>{
 const {account,sqlite}=fixture();const c=account.capabilities();assert.equal(c.development,false);assert.equal(c.email,false);assert.equal(c.phone,false);assert.equal(c.providers.filter(p=>p.available).map(p=>p.id).join(),"chatgpt");sqlite.close();
});

test("verified contacts bind to the signed-in identity, never another account",async()=>{
 const {account,sqlite}=fixture({WAFERAI_RESEND_KEY:"qa",WAFERAI_EMAIL_FROM:"test@example.invalid",WAFERAI_SMS_URL:"https://sms.example.invalid",WAFERAI_SMS_TOKEN:"qa"});let code="";
 account.fetcher=(async(_url:any,options:any)=>{const body=JSON.parse(options.body);code=body.code??body.text.match(/\d{6}/)[0];return new Response('{"accepted":true}')}) as typeof fetch;
 async function social(id:string){const flow=await account.start("chatgpt",true);return account.complete("chatgpt",flow.authorization_url.split("/").at(-1)!,"",{userId:id,displayName:id,email:id+"@example.invalid"})}
 const first=await social("first"),second=await social("second");
 await account.sendBindingCode(first.token,"reader@example.invalid","qa");
 await assert.rejects(()=>account.bind(second.token,{target:"reader@example.invalid",password:"EmailPassword123",code}),AccountError);
 const bound=await account.bind(first.token,{target:"reader@example.invalid",password:"EmailPassword123",code});assert.equal(bound.user.identities.length,2);
 assert.equal((await account.login({target:"reader@example.invalid",password:"EmailPassword123"},"qa")).user.id,first.user.id);
 await assert.rejects(()=>account.sendBindingCode(second.token,"reader@example.invalid","qa"),AccountError);
 await account.sendBindingCode(first.token,"13800000000","qa");await account.bind(first.token,{target:"13800000000",password:"PhonePassword123",code});
 assert.equal((await account.login({target:"13800000000",password:"PhonePassword123"},"qa")).user.id,first.user.id);
 assert.equal((await account.authenticate(first.token)).identities.length,3);sqlite.close();
});

test("binding requires recent login and cannot accept public-purpose verification codes",async()=>{
 const {account,sqlite}=fixture({WAFERAI_RESEND_KEY:"qa",WAFERAI_EMAIL_FROM:"test@example.invalid"});let code="";
 account.fetcher=(async(_url:any,options:any)=>{code=JSON.parse(options.body).text.match(/\d{6}/)[0];return new Response('{}')})as typeof fetch;
 const flow=await account.start("chatgpt",true),session=await account.complete("chatgpt",flow.authorization_url.split("/").at(-1)!,"",{userId:"qa",displayName:"QA",email:"qa@example.invalid"});
 await account.send("reader@example.invalid","register","qa");await assert.rejects(()=>account.bind(session.token,{target:"reader@example.invalid",password:"ExamplePassword123",code}),AccountError);
 await assert.rejects(()=>account.send("reader@example.invalid","bind:"+session.user.id,"qa"),AccountError);
 await assert.rejects(()=>account.sendBindingCode("bad","reader@example.invalid","qa"),AccountError);
 sqlite.prepare("UPDATE sessions SET expires=expires-901").run();await assert.rejects(()=>account.sendBindingCode(session.token,"reader@example.invalid","qa"),(e:any)=>e.status===403);sqlite.close();
});
test("ChatGPT uses dispatch-owned login and verifies an actual platform identity",async()=>{
 const {account,sqlite}=fixture();const flow=await account.start("chatgpt",true);const state=flow.authorization_url.split("/").at(-1)!;
 const launch=await account.launch(state);assert.ok(launch.startsWith("/signin-with-chatgpt?return_to="));assert.equal((await account.poll(flow.poll_token)).pending,true);
 await assert.rejects(()=>account.complete("chatgpt",state,"",null),AccountError);
 await assert.rejects(()=>account.poll(flow.poll_token),AccountError);sqlite.close();
});
test("successful authorization can be claimed once and restores the same account",async()=>{
 const {account,sqlite}=fixture();const who={userId:"platform-qa",email:"qa@example.invalid",displayName:"Reader"};
 async function authorize(){const flow=await account.start("chatgpt",true);const state=flow.authorization_url.split("/").at(-1)!;const browser=await account.complete("chatgpt",state,"",who);const app=await account.poll(flow.poll_token);assert.ok("user" in app);assert.equal(app.user.id,browser.user.id);await assert.rejects(()=>account.poll(flow.poll_token),AccountError);await assert.rejects(()=>account.complete("chatgpt",state,"",who),AccountError);return app}
 const first=await authorize(),second=await authorize();assert.equal(first.user.id,second.user.id);assert.equal((await account.authenticate(first.token)).name,"Reader");assert.equal(sqlite.prepare("SELECT count(*) n FROM users").get()!.n,1);sqlite.close();
});
test("cancelled and expired authorizations cannot create a session",async()=>{
 const {account,sqlite}=fixture();const flow=await account.start("chatgpt",true);const state=flow.authorization_url.split("/").at(-1)!;
 await account.cancel(flow.poll_token);await assert.rejects(()=>account.poll(flow.poll_token),AccountError);await assert.rejects(()=>account.complete("chatgpt",state,"",{userId:"qa",email:"qa@example.invalid",displayName:"Reader"}),AccountError);
 const expired=await account.start("chatgpt",true);sqlite.prepare("UPDATE flows SET expires=0").run();await assert.rejects(()=>account.poll(expired.poll_token),AccountError);sqlite.close();
});
test("one-time web login and account deletion protect existing sessions",async()=>{
 const {account,sqlite}=fixture();const flow=await account.start("chatgpt",true);const state=flow.authorization_url.split("/").at(-1)!;
 const session=await account.complete("chatgpt",state,"",{userId:"qa",email:"qa@example.invalid",displayName:"Reader"});
 const handoff=await account.handoff(session.token),ticket=handoff.path.split("ticket=")[1];const browser=await account.consume(ticket);assert.equal(browser.user.id,session.user.id);await assert.rejects(()=>account.consume(ticket),AccountError);
 await account.remove(session.token);await assert.rejects(()=>account.authenticate(browser.token),AccountError);assert.equal(sqlite.prepare("SELECT count(*) n FROM identities").get()!.n,0);sqlite.close();
});
test("unconfigured platforms and missing consent never succeed",async()=>{
 const {account,sqlite}=fixture();await assert.rejects(()=>account.start("chatgpt",false),AccountError);for(const provider of ["wechat","qq","facebook","telegram"])await assert.rejects(()=>account.start(provider,true),AccountError);sqlite.close();
});
test("verified email registration stores a salted password and revokes sessions on recovery",async()=>{
 const {account,sqlite}=fixture({WAFERAI_RESEND_KEY:"qa-delivery",WAFERAI_EMAIL_FROM:"test@example.invalid"});let code="";
 account.fetcher=(async(_url:any,options:any)=>{code=JSON.parse(options.body).text.match(/\d{6}/)[0];return new Response('{}',{status:200})}) as typeof fetch;
 await account.send("Reader@Example.invalid","register","qa");const session=await account.register({target:"reader@example.invalid",password:"ExamplePassword123",code,agreed:true,name:"Reader"});
 const hash=sqlite.prepare("SELECT password FROM identities").get()!.password as string;assert.ok(!hash.includes("ExamplePassword"));assert.equal((await account.login({target:"reader@example.invalid",password:"ExamplePassword123"},"qa")).user.id,session.user.id);
 sqlite.prepare("DELETE FROM limits").run();await account.send("reader@example.invalid","reset","qa");await account.reset({target:"reader@example.invalid",password:"NewPassword123",code});await assert.rejects(()=>account.authenticate(session.token),AccountError);assert.ok((await account.login({target:"reader@example.invalid",password:"NewPassword123"},"qa")).token);sqlite.close();
});
test("verification attempts, cooldown and purpose binding are enforced",async()=>{
 const {account,sqlite}=fixture({WAFERAI_RESEND_KEY:"qa",WAFERAI_EMAIL_FROM:"test@example.invalid"});let code="";account.fetcher=(async(_url:any,options:any)=>{code=JSON.parse(options.body).text.match(/\d{6}/)[0];return new Response('{}')})as typeof fetch;
 await account.send("qa@example.invalid","register","qa");await assert.rejects(()=>account.send("qa@example.invalid","register","qa"),AccountError);await assert.rejects(()=>account.checkCode("qa@example.invalid","reset",code),AccountError);
 const wrong=code==="000000"?"111111":"000000";for(let i=0;i<5;i++)await assert.rejects(()=>account.checkCode("qa@example.invalid","register",wrong),AccountError);await assert.rejects(()=>account.checkCode("qa@example.invalid","register",code),AccountError);sqlite.close();
});
