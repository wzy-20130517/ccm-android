import fs from 'fs';
const kt = fs.readFileSync('/data/data/com.termux/files/home/kt-verify/kt-out.txt','utf8').split('\n').filter(Boolean);
const cli = fs.readFileSync('/data/data/com.termux/files/home/kt-verify/cli-out.txt','utf8').split('\n').filter(Boolean);
console.log('KT:', kt.length, ' CLI:', cli.length);
let same=0; const diff=[];
const km = new Map(kt.map(l => { const i=l.lastIndexOf('\u0001'); return [l.slice(0,i), l.slice(i+1)]; }));
const cm = new Map(cli.map(l => { const i=l.lastIndexOf('\u0001'); return [l.slice(0,i), l.slice(i+1)]; }));
for (const [cmd, kv] of km) {
  const cv = cm.get(cmd);
  if (cv === undefined) { diff.push([cmd, kv, '(CLI无)']); continue; }
  if (kv === cv) same++; else diff.push([cmd, kv, cv]);
}
console.log(`一致: ${same} / ${km.size}`);
if (diff.length) {
  console.log(`\n差异 ${diff.length} 条:`);
  for (const [c,k,v] of diff.slice(0,60)) console.log(`  ${k==='true'?'KT只读':'KT非读'} vs ${v==='true'?'CLI只读':'CLI非读'}  ${JSON.stringify(c)}`);
  if (diff.length>60) console.log(`  ...还有 ${diff.length-60} 条`);
}
