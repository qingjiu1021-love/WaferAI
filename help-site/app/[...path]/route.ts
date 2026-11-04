import {env} from "cloudflare:workers";
import {handleAccount} from "../../lib/account-handler";
import {getChatGPTUser} from "../chatgpt-auth";
export const dynamic="force-dynamic";
async function handler(request:Request){
 const platform=new URL(request.url).pathname==="/auth/social/chatgpt/callback"?await getChatGPTUser():null;
 return handleAccount(request,env,platform);
}
export const GET=handler;
export const POST=handler;
export const DELETE=handler;
