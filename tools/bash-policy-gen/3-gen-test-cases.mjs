import fs from 'fs';
const cases = new Set();
const cmds = ['ls','cat','head','tail','grep','wc','echo','pwd','which','stat','id','uname','find','git','sed','sort','uniq','cut','diff','file','du','df','tree','jq','xargs','ps','lsof','ss','test','man','date','printf','fd','rg'];
const args = ['', ' -l', ' -la', ' -n 5', ' -n5', ' f.txt', ' -r foo .', ' --help', ' --version', ' -x', ' --badflag', ' -'];
for (const c of cmds) for (const a of args) cases.add(c + a);
// git 组合
for (const sub of ['status','log','diff','show','branch','tag','remote','stash','rev-parse','ls-files','blame','reflog','cat-file','describe','shortlog','grep','rev-list','merge-base','config','worktree','ls-remote','for-each-ref'])
  for (const a of ['', ' -v', ' --oneline', ' -5', ' --stat', ' -a', ' --list', ' x', ' --badflag'])
    cases.add('git ' + sub + a);
// 重定向
for (const r of ['> out', '>> out', '2> err', '2>&1', '< in', '<<< here', '> /dev/null', '2>/dev/null', '&> all', '>&2', '3>&1'])
  for (const c of ['ls', 'cat f.txt', 'grep foo f.txt']) cases.add(c + ' ' + r);
// 动态/引号
for (const d of ['echo $HOME', 'echo ${HOME}', 'echo $1', 'echo $@', 'echo $(date)', 'echo `date`', 'echo $((1+2))', 'echo {a,b}', "echo 'a b'", 'echo "a b"', 'ls *.txt', "ls '*.txt'", 'ls "*.txt"', 'ls ?.txt', 'ls [ab].txt', 'ls ~/x', 'cd ~', 'A=1 ls', 'A=1 B=2 ls', 'FOO=1 ls', 'LD_PRELOAD=x ls', 'x=1'])
  cases.add(d);
// 串联
for (const s of ['ls && pwd','ls || pwd','ls; pwd','ls | grep x','ls & pwd','(ls)','{ ls; }','if true; then ls; fi','for i in a; do echo $i; done','while true; do ls; done','f() { ls; }','! ls','time ls','ls && rm x','ls; rm x','ls | xargs rm'])
  cases.add(s);
// 包装
for (const w of ['command ls','builtin echo x','noglob ls','command -p ls','command -- ls','env ls','sudo ls','nice ls','nohup ls'])
  cases.add(w);
// 绕过手法
for (const b of ['/bin/rm x','/usr/bin/rm -rf /tmp','bash -c "rm x"','sh -c \'rm x\'','find . -delete','find . -exec rm {} ;','sed -i s/a/b/ f','sed --in-place s/a/b/ f','rm -rf /','dd if=/dev/zero of=x','tee f','chmod 777 x','mkdir x','touch x','mv a b','cp a b','python3 x.py','node x.js','npm install','pip install x','curl http://x','wget http://x','git commit -m x','git push','git add .','git reset --hard','git checkout x','git branch newb','git tag v1','git remote add o u','git config user.name x','git stash','git stash list','git stash show'])
  cases.add(b);
fs.writeFileSync('/data/data/com.termux/files/home/kt-verify/cases.txt', [...cases].join('\n'));
console.log('生成用例:', cases.size);
