import fs from "node:fs";
import path from "node:path";
import {spawnSync} from "node:child_process";
import ts from "typescript";
const directory=path.resolve(".sites-runtime/account-tests");
fs.mkdirSync(directory,{recursive:true});
for(const [input,output]of [["lib/account-service.ts","account-service.mjs"],["tests/accounts.test.ts","accounts.test.mjs"]]){
 let code=fs.readFileSync(input,"utf8").replace('../lib/account-service','./account-service.mjs');
 const result=ts.transpileModule(code,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}});
 fs.writeFileSync(path.join(directory,output),result.outputText);
}
const result=spawnSync(process.execPath,["--test",path.join(directory,"accounts.test.mjs")],{stdio:"inherit"});
process.exitCode=result.status??1;
