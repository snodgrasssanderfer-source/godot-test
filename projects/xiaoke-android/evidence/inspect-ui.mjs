import fs from 'node:fs/promises';
const targets=await (await fetch('http://127.0.0.1:9222/json')).json();
const ws=new WebSocket(targets.find(x=>x.type==='page').webSocketDebuggerUrl);await new Promise(r=>ws.addEventListener('open',r,{once:true}));
let next=0;const pending=new Map();const logs=[];
ws.addEventListener('message',e=>{const v=JSON.parse(e.data);if(v.id){const p=pending.get(v.id);pending.delete(v.id);v.error?p.reject(v.error):p.resolve(v.result)}else if(v.method==='Runtime.exceptionThrown')logs.push(v.params.exceptionDetails.text)});
const call=(method,params={})=>new Promise((resolve,reject)=>{const id=++next;pending.set(id,{resolve,reject});ws.send(JSON.stringify({id,method,params}))});
await call('Runtime.enable');await call('Page.enable');await call('Emulation.setDeviceMetricsOverride',{width:412,height:915,deviceScaleFactor:1,mobile:true});await call('Page.navigate',{url:'http://127.0.0.1:5173'});
for(let i=0;i<80;i++){const v=await call('Runtime.evaluate',{expression:'!!document.querySelector(".companion-app")',returnByValue:true});if(v.result.value)break;await new Promise(r=>setTimeout(r,100));}
await new Promise(r=>setTimeout(r,400));
const result=await call('Runtime.evaluate',{expression:'JSON.stringify({title:document.title,text:document.body.innerText,width:innerWidth,scrollWidth:document.documentElement.scrollWidth})',returnByValue:true});
console.log(result.result.value);console.log('EXCEPTIONS',JSON.stringify(logs));
const shot=await call('Page.captureScreenshot',{format:'png'});await fs.writeFile('<EVIDENCE_ROOT>/companion-chat-mobile.png',Buffer.from(shot.data,'base64'));ws.close();
